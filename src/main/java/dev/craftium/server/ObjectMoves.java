package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
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
        Function<Point,ObjectMove.Block> world=p->{
            var snapshot=BlockSnapshot.capture(level,EditPlanner.pos(p));
            return new ObjectMove.Block(BlockStateParser.serialize(snapshot.state()),snapshot.data()==null?null:snapshot.data().toString());
        };
        var result=mover.move(ordered,world,solid);
        if(result.objects().isEmpty()&&result.writes().isEmpty())return null;

        for(var e:result.writes().entrySet()){
            plan.validate(e.getKey());
            plan.put(e.getKey(),snapshot(plan,e.getValue()));
        }
        var ownAfter=new LinkedHashMap<Integer,List<Point>>();
        for(var o:result.objects().values())ownAfter.put(o.id(),List.copyOf(o.own()));
        s.saved.setPoints(ownAfter);
        try{for(var o:result.objects().values())s.saved.setHeld(o.id(),o.dormant(),o.replaced());}
        catch(RuntimeException ex){s.saved.setPoints(ownBefore);s.saved.restoreHeld(heldBefore);throw ex;}
        return new Applied(ownBefore,ownAfter,heldBefore,s.saved.heldSnapshot());
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
