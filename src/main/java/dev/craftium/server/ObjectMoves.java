package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Moves BuildObjects with the world winning (see {@link ObjectMove}): this is where the live world is turned into block text for it, and what it decides is
 * written into an edit and into the BuildObjects. Runs on the server thread.
 */
final class ObjectMoves {
    private ObjectMoves(){}

    /** What a move did to the BuildObjects: their own blocks, and what they keep outside the world, before and after, for undo and for cancelling a staged edit. */
    record Applied(Map<Integer,List<Point>> ownBefore,Map<Integer,List<Point>> ownAfter,Map<Integer,SavedSelections.Held> heldBefore,Map<Integer,SavedSelections.Held> heldAfter){
        Session.SelectionChange change(){
            return Session.SelectionChange.restoring(Map.of(),Map.of()).withObjects(ownBefore,ownAfter).withHeld(heldBefore,heldAfter);
        }
        /** Puts the BuildObjects back as they were, for a move that failed afterwards. */
        void undo(Session s){s.saved.setPoints(ownBefore);s.saved.restoreHeld(heldBefore);}
    }

    /** Works out a move from the BuildObjects, the world and the rule for what beats an object. */
    interface Mover{ObjectMove.Result move(List<ObjectMove.Obj> ordered,Function<Point,ObjectMove.Block> world,Predicate<String> solid);}

    /** Whether any BuildObject holds a selected block, so the move has objects to look after. */
    static boolean involves(Session s){
        for(var e:s.saved.entries())for(var p:e.points())if(s.selection.contains(p))return true;
        return false;
    }

    /** The dormant blocks of the BuildObjects that move whole with this selection: where they land has to be loaded, though they are not selected. */
    static List<Point> hidden(Session s){
        var out=new ArrayList<Point>();
        for(var e:s.saved.entries()){
            if(e.dormant().isEmpty()||e.points().isEmpty())continue;
            boolean whole=true;for(var p:e.points())if(!s.selection.contains(p)){whole=false;break;}
            if(whole)out.addAll(e.dormant().points());
        }
        return out;
    }

    /**
     * Runs the move, writes what it decides into {@code plan}, and sets the BuildObjects as they are afterwards. Nothing is left changed if it throws.
     * Returns what changed, or null when no BuildObject was involved.
     */
    static Applied apply(ServerLevel level,Session s,EditPlanner plan,Mover mover){
        var ordered=new ArrayList<ObjectMove.Obj>();
        for(var e:s.saved.entries())ordered.add(new ObjectMove.Obj(e.id(),new HashSet<>(e.points()),e.dormant(),e.replaced()));
        var ownBefore=s.saved.snapshot();var heldBefore=s.saved.heldSnapshot();
        var solidCache=new HashMap<String,Boolean>();
        Predicate<String> solid=text->solidCache.computeIfAbsent(text,t->beats(plan.parse(t)));
        Function<Point,ObjectMove.Block> world=p->block(BlockSnapshot.capture(level,EditPlanner.pos(p)));
        var result=mover.move(ordered,world,solid);
        if(result.objects().isEmpty()&&result.writes().isEmpty())return null;
        return commit(s,plan,result,ownBefore,heldBefore);
    }

    /** Queues what the move decided and sets the BuildObjects as they are afterwards; {@code ownStart} and {@code heldStart} are what undo goes back to. Nothing is left changed if it throws. */
    private static Applied commit(Session s,EditPlanner plan,ObjectMove.Result result,Map<Integer,List<Point>> ownStart,Map<Integer,SavedSelections.Held> heldStart){
        // A block that can hold water is waterlogged where it covers water, as it is when built there: the water it replaced is given back when it leaves.
        var wet=new HashSet<Point>();
        for(var o:result.objects().values())for(var c:o.own())if(ObjectMove.isWater(o.replaced().get(c)))wet.add(c);
        for(var e:result.writes().entrySet()){
            plan.validate(e.getKey());
            var written=snapshot(plan,e.getValue());
            var state=written.state();
            if(wet.contains(e.getKey())&&state.hasProperty(BlockStateProperties.WATERLOGGED)&&!state.getValue(BlockStateProperties.WATERLOGGED))
                written=new BlockSnapshot(state.setValue(BlockStateProperties.WATERLOGGED,true),written.data());
            plan.put(e.getKey(),written);
        }
        var ownAfter=new LinkedHashMap<Integer,List<Point>>();
        for(var o:result.objects().values())ownAfter.put(o.id(),List.copyOf(o.own()));
        s.saved.setPoints(ownAfter);
        try{for(var o:result.objects().values())s.saved.setHeld(o.id(),o.dormant(),o.replaced());}
        catch(RuntimeException ex){s.saved.setPoints(ownStart);s.saved.restoreHeld(heldStart);throw ex;}
        return new Applied(ownStart,ownAfter,heldStart,s.saved.heldSnapshot());
    }

    /** Whether any selected cell belongs to one of these BuildObjects. */
    static boolean touches(Map<Integer,List<Point>> own,Set<Point> source){
        for(var cells:own.values())for(var p:cells)if(source.contains(p))return true;
        return false;
    }

    /** A transform that worked out where the selection lands, run for a selection that holds BuildObject blocks. {@code moved} are the cells where a block landed. */
    record Transformed(Applied applied,Set<Point> moved){}

    /**
     * Places a computed transform with the world winning (see {@link ObjectMove}). The BuildObjects are first put back to {@code ownStart} and {@code heldStart}, as they were
     * before a run of staged transforms, because every transform of a run works from the original blocks. The dormant blocks of a BuildObject that moves whole go through the
     * same steps as the selection. Nothing is left changed if it throws.
     */
    static Transformed transform(ServerLevel level,Session s,EditPlanner plan,ModelTransform.Computed c,Map<Integer,List<Point>> ownStart,Map<Integer,SavedSelections.Held> heldStart){
        s.saved.setPoints(ownStart);s.saved.restoreHeld(heldStart);
        try{
            var ordered=new ArrayList<ObjectMove.Obj>();
            for(var e:s.saved.entries())ordered.add(new ObjectMove.Obj(e.id(),new HashSet<>(e.points()),e.dormant(),e.replaced()));
            var source=c.source().keySet();
            var solidCache=new HashMap<String,Boolean>();
            Predicate<String> solid=text->solidCache.computeIfAbsent(text,t->beats(plan.parse(t)));
            Function<Point,ObjectMove.Block> world=p->{ // what is staged but not built counts as what is there
                var staged=plan.changes.get(p);return block(staged!=null?staged.after():BlockSnapshot.capture(level,EditPlanner.pos(p)));
            };
            var landed=new LinkedHashMap<Point,ObjectMove.Landing>();
            for(var e:c.landed().entrySet())landed.put(e.getKey(),new ObjectMove.Landing(c.origin().get(e.getKey()),block(e.getValue())));
            var dormantLanded=new HashMap<Integer,Map<Point,ObjectMove.Landing>>();
            for(var o:ordered){
                if(o.dormant().isEmpty()||!ObjectMove.moves(o,source))continue;
                var hidden=new LinkedHashMap<Point,BlockSnapshot>();
                o.dormant().forEach((p,state)->hidden.put(p,snapshot(plan,new ObjectMove.Block(state,o.dormant().data(p)))));
                var origin=new LinkedHashMap<Point,Point>();
                var lands=ModelTransform.landOutside(hidden,c.steps(),origin);
                var m=new LinkedHashMap<Point,ObjectMove.Landing>();
                for(var e:lands.entrySet())m.put(e.getKey(),new ObjectMove.Landing(origin.get(e.getKey()),block(e.getValue())));
                dormantLanded.put(o.id(),m);
            }
            var result=ObjectMove.moveLanded(ordered,source,landed,dormantLanded,world,solid,true);
            return new Transformed(commit(s,plan,result,ownStart,heldStart),result.landed());
        }catch(RuntimeException ex){s.saved.setPoints(ownStart);s.saved.restoreHeld(heldStart);throw ex;}
    }

    /**
     * Places the blocks of a paste for the BuildObjects it just made, with the world winning (see {@link ObjectMove#place}). Making them claimed their cells from whatever held
     * them; {@code ownBefore} and {@code heldBefore} are what the BuildObjects that were already there held before the paste, which they get back so that a block that has to give
     * way is kept by its object, not lost. Nothing is left changed if it throws, except that the new BuildObjects still exist: the caller takes those back.
     * {@code blocks} is each pasted cell's block.
     */
    static Transformed settlePaste(ServerLevel level,Session s,EditPlanner plan,Map<Point,BlockSnapshot> blocks,Map<Integer,List<Point>> ownBefore,Map<Integer,SavedSelections.Held> heldBefore){
        var fresh=new LinkedHashMap<Integer,List<Point>>();
        for(var e:s.saved.entries())if(!ownBefore.containsKey(e.id()))fresh.put(e.id(),e.points());
        s.saved.setPoints(ownBefore);
        var ordered=new ArrayList<ObjectMove.Obj>();
        for(var e:s.saved.entries())ordered.add(new ObjectMove.Obj(e.id(),fresh.containsKey(e.id())?new HashSet<>():new HashSet<>(e.points()),e.dormant(),e.replaced()));
        var placements=new ArrayList<ObjectMove.Placement>();
        for(var e:fresh.entrySet())for(var p:e.getValue()){var b=blocks.get(p);if(b!=null)placements.add(new ObjectMove.Placement(e.getKey(),p,block(b)));}
        var solidCache=new HashMap<String,Boolean>();
        Predicate<String> solid=text->solidCache.computeIfAbsent(text,t->beats(plan.parse(t)));
        Function<Point,ObjectMove.Block> world=p->{var staged=plan.changes.get(p);return block(staged!=null?staged.after():BlockSnapshot.capture(level,EditPlanner.pos(p)));};
        var result=ObjectMove.place(ordered,placements,world,solid);
        return new Transformed(commit(s,plan,result,ownBefore,heldBefore),result.landed());
    }

    /**
     * Places the dormant blocks of these BuildObjects where they stand, with the world winning: each is written where the world is free now, and kept dormant where it is
     * not. A block that was hidden because of a block that has gone, or because of the order of the Hierarchy, comes back. Returns null when none of them keeps anything.
     */
    static Transformed placeHere(ServerLevel level,Session s,EditPlanner plan,Set<Integer> ids){
        var ownBefore=s.saved.snapshot();var heldBefore=s.saved.heldSnapshot();
        var ordered=new ArrayList<ObjectMove.Obj>();var placements=new ArrayList<ObjectMove.Placement>();
        for(var e:s.saved.entries()){
            boolean here=ids.contains(e.id());
            ordered.add(new ObjectMove.Obj(e.id(),new HashSet<>(e.points()),here?new PackedCells():e.dormant(),e.replaced()));
            if(here)e.dormant().forEach((p,state)->placements.add(new ObjectMove.Placement(e.id(),p,new ObjectMove.Block(state,e.dormant().data(p)))));
        }
        if(placements.isEmpty())return null;
        var solidCache=new HashMap<String,Boolean>();
        Predicate<String> solid=text->solidCache.computeIfAbsent(text,t->beats(plan.parse(t)));
        Function<Point,ObjectMove.Block> world=p->block(BlockSnapshot.capture(level,EditPlanner.pos(p)));
        var result=ObjectMove.place(ordered,placements,world,solid);
        return new Transformed(commit(s,plan,result,ownBefore,heldBefore),result.landed());
    }

    private static ObjectMove.Block block(BlockSnapshot snapshot){
        return new ObjectMove.Block(BlockStateParser.serialize(snapshot.state()),snapshot.data()==null?null:snapshot.data().toString());
    }

    /** A block text and its block entity text as something that can be placed. */
    private static BlockSnapshot snapshot(EditPlanner plan,ObjectMove.Block block){
        var state=plan.parse(block.state());
        if(block.data()==null)return new BlockSnapshot(state);
        try{return new BlockSnapshot(state,TagParser.parseCompoundFully(block.data()));}
        catch(Exception e){throw new IllegalArgumentException("A block's data could not be read back: "+block.state());}
    }

    /**
     * Whether a block beats a BuildObject's block: anything but air or something that is replaced when built on. Water, lava, grass and snow layers are replaceable;
     * a waterlogged slab is not, so it wins like any other block.
     */
    static boolean beats(BlockState state){return !state.isAir()&&!state.canBeReplaced();}
}
