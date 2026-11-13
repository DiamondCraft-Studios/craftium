package dev.craftium.core;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Moves selected blocks, and the BuildObjects among them, with the world winning:
 * <ul>
 * <li>A block of an object that arrives where a solid world block is does not overwrite it. The object keeps the block as <em>dormant</em>.</li>
 * <li>Where it arrives on air, a fluid or something replaceable, it is written, and what it replaced is kept to give back when the object leaves.</li>
 * <li>Where it arrives on a block of another object, the one later in the list wins (a child comes after its parent). The loser keeps its block as dormant,
 *     and gets the cell back when the winner leaves.</li>
 * <li>A selected block that belongs to no object is an ordinary edit: it overwrites whatever is there, and an object that held the cell loses it, as before.</li>
 * <li>An object that moves whole (all the blocks it holds in the world are selected) takes its dormant blocks along as if they were selected too, so they
 *     are shifted, turned, scaled or dropped like any other block, and each result is written where the world is free or kept dormant again.</li>
 * </ul>
 * The move is described by where each landed cell came from, so a shift, a turn, a mirror and a resampling (scale, any angle) are all the same to it.
 * Nothing here touches the world: it returns the blocks to write and what each object holds afterwards. A block is its state as text and the text of its block entity.
 */
public final class ObjectMove {
    private ObjectMove(){}

    public static final String AIR="minecraft:air";

    /** A block: its state as text, and the data of its block entity as text (null for none). */
    public record Block(String state,String data){
        /** A block with no data, or null for no block at all. */
        public static Block of(String state){return state==null?null:new Block(state,null);}
        public boolean isAir(){return ObjectMove.isAir(state);}
    }

    /** A BuildObject as the move sees it: the cells it holds in the world, and what it keeps outside it. */
    public record Obj(int id,Set<Point> own,PackedCells dormant,PackedCells replaced){}

    /** The blocks to write (a cell to its block; air clears it) and every object that changed, as it is afterwards. */
    public record Result(Map<Point,Block> writes,Map<Integer,Obj> objects){}

    /** Whether an object moves whole for this selection, and so takes its dormant blocks along. */
    public static boolean moves(Obj o,Set<Point> source){return !o.own().isEmpty()&&source.containsAll(o.own());}

    /** A move by a one-to-one map of cells (a shift, a quarter turn, a mirror). */
    public static Result shift(List<Obj> ordered,Set<Point> source,Function<Point,Point> forward,Function<Point,Block> worldState,Predicate<String> solid,boolean pasteAir){
        var origin=new LinkedHashMap<Point,Point>();for(var c:source)origin.put(forward.apply(c),c);
        var dormantOrigin=new HashMap<Integer,Map<Point,Point>>();
        for(var o:ordered)if(moves(o,source)){var m=new LinkedHashMap<Point,Point>();for(var c:o.dormant().points())m.put(forward.apply(c),c);dormantOrigin.put(o.id(),m);}
        return move(ordered,source,origin,dormantOrigin,worldState,solid,pasteAir);
    }

    /**
     * @param ordered every BuildObject, lowest priority first
     * @param source the selected cells, which are vacated
     * @param origin for each cell that was landed in, the selected cell its block came from
     * @param dormantOrigin for each object that {@linkplain #moves moves}, the same for its dormant blocks: landed cell to the dormant cell it came from.
     *        Sampled with the same box and steps as the selection, so they line up with it. An object that moves and is missing here has its dormant blocks dropped.
     * @param worldState the block at a cell now, or null for air
     * @param solid whether a block state text beats an object: not air, a fluid or replaceable
     * @param pasteAir whether air in the selection is moved too (it clears its place either way)
     */
    public static Result move(List<Obj> ordered,Set<Point> source,Map<Point,Point> origin,Map<Integer,Map<Point,Point>> dormantOrigin,
                              Function<Point,Block> worldState,Predicate<String> solid,boolean pasteAir){
        var work=new LinkedHashMap<Integer,Work>();var priority=new HashMap<Integer,Integer>();
        for(int i=0;i<ordered.size();i++){var o=ordered.get(i);work.put(o.id(),new Work(o));priority.put(o.id(),i);}
        var owner=new HashMap<Point,Work>();
        for(var w:work.values())for(var p:w.own)owner.put(p,w);

        // What every selected block is, and whose it was, before anything is vacated.
        var blocks=new HashMap<Point,Block>();var ownerBefore=new HashMap<Point,Work>();
        for(var c:source){blocks.put(c,orAir(worldState.apply(c)));var w=owner.get(c);if(w!=null)ownerBefore.put(c,w);}
        var whole=new HashSet<Work>();
        for(var o:ordered)if(moves(o,source))whole.add(work.get(o.id()));

        var current=new HashMap<Point,Block>(); // what the world holds where this move has written
        Function<Point,Block> at=p->{var b=current.get(p);return b!=null?b:orAir(worldState.apply(p));};

        // 1. Vacate every selected cell: another object's hidden block comes back, else what the mover replaced, else air.
        for(var c:source){
            var mover=ownerBefore.get(c);
            if(mover!=null){mover.own.remove(c);owner.remove(c);mover.touched=true;}
            Work back=null;
            for(int i=ordered.size()-1;i>=0&&back==null;i--){var p=work.get(ordered.get(i).id());if(!whole.contains(p)&&p!=mover&&p.dormant.has(c))back=p;}
            if(back!=null){current.put(c,new Block(back.dormant.get(c),back.dormant.data(c)));back.dormant.remove(c);back.own.add(c);owner.put(c,back);back.touched=true;}
            else if(mover!=null&&mover.replaced.has(c)){current.put(c,new Block(mover.replaced.get(c),mover.replaced.data(c)));mover.replaced.remove(c);}
            else current.put(c,new Block(AIR,null));
        }

        // 2. Arrivals, lowest priority first so a later object can take a cell from an earlier one that just landed there.
        record Arrival(Work who,Point to,Block block,int rank){}
        var arrivals=new ArrayList<Arrival>();
        for(var e:origin.entrySet()){
            var b=blocks.get(e.getValue());if(b==null||(b.isAir()&&!pasteAir))continue;
            var w=ownerBefore.get(e.getValue());arrivals.add(new Arrival(w,e.getKey(),b,w==null?-1:priority.get(w.id)));
        }
        for(var w:whole){
            var from=dormantOrigin.getOrDefault(w.id,Map.of());
            var held=w.dormant;w.dormant=new PackedCells();w.touched=true; // what is not landed anywhere is sampled away
            for(var e:from.entrySet()){var s=held.get(e.getValue());if(s==null)continue;arrivals.add(new Arrival(w,e.getKey(),new Block(s,held.data(e.getValue())),priority.get(w.id)));}
        }
        arrivals.sort(Comparator.comparingInt(Arrival::rank));
        for(var a:arrivals){
            var d=a.to();var here=at.apply(d);var holder=owner.get(d);
            if(a.who()==null){ // an ordinary block: it wins, and an object that held the cell loses it
                if(holder!=null){holder.own.remove(d);holder.touched=true;owner.remove(d);}
                current.put(d,a.block());continue;
            }
            var me=a.who();me.touched=true;
            if(solid.test(here.state())){
                if(holder!=null&&holder!=me&&priority.get(me.id)>priority.get(holder.id)){
                    holder.own.remove(d);holder.dormant.put(d,here.state(),here.data());holder.touched=true;
                    owner.put(d,me);me.own.add(d);current.put(d,a.block());
                }else if(holder==me){current.put(d,a.block());}
                else me.dormant.put(d,a.block().state(),a.block().data());
            }else{
                if(!isAir(here.state()))me.replaced.put(d,here.state(),here.data());
                owner.put(d,me);me.own.add(d);current.put(d,a.block());
            }
        }

        var writes=new LinkedHashMap<Point,Block>();
        for(var e:current.entrySet())if(!e.getValue().equals(orAir(worldState.apply(e.getKey()))))writes.put(e.getKey(),e.getValue());
        var changed=new LinkedHashMap<Integer,Obj>();
        for(var w:work.values())if(w.touched)changed.put(w.id,new Obj(w.id,w.own,w.dormant,w.replaced));
        return new Result(writes,changed);
    }

    /** Air in any of its three kinds, or nothing. */
    public static boolean isAir(String state){
        return state==null||state.equals(AIR)||state.startsWith("minecraft:air[")||state.equals("minecraft:cave_air")||state.equals("minecraft:void_air");
    }
    private static Block orAir(Block b){return b==null?new Block(AIR,null):b;}

    private static final class Work{
        final int id;final Set<Point> own;PackedCells dormant,replaced;boolean touched;
        Work(Obj o){id=o.id();own=new HashSet<>(o.own());dormant=o.dormant().copy();replaced=o.replaced().copy();}
    }
}
