package dev.craftium.core;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Moves selected blocks, and the BuildObjects among them, by a one-to-one map of cells (a shift, a quarter turn, a mirror), with the world winning:
 * <ul>
 * <li>A block of an object that arrives where a solid world block is does not overwrite it. The object keeps the block as <em>dormant</em>.</li>
 * <li>Where it arrives on air, a fluid or something replaceable, it is written, and what it replaced is kept to give back when the object leaves.</li>
 * <li>Where it arrives on a block of another object, the one later in the list wins (a child comes after its parent). The loser keeps its block as dormant,
 *     and gets the cell back when the winner leaves.</li>
 * <li>A selected block that belongs to no object is an ordinary edit: it overwrites whatever is there, and an object that held the cell loses it, as before.</li>
 * <li>An object that moves whole takes its dormant blocks along by the same map, so they come back wherever the world is free.</li>
 * </ul>
 * Nothing here touches the world: it returns the blocks to write and what each object holds afterwards. Block states are text, as in {@link PackedCells}.
 */
public final class ObjectMove {
    private ObjectMove(){}

    public static final String AIR="minecraft:air";

    /** A BuildObject as the move sees it: the cells it holds in the world, and what it keeps outside it. */
    public record Obj(int id,Set<Point> own,PackedCells dormant,PackedCells replaced){}

    /** The blocks to write (a cell to its state; {@link #AIR} clears it) and every object that changed, as it is afterwards. */
    public record Result(Map<Point,String> writes,Map<Integer,Obj> objects){}

    /**
     * @param ordered every BuildObject, lowest priority first
     * @param source the selected cells; each moves to {@code forward(cell)}
     * @param forward a one-to-one map of cells
     * @param worldState the block state at a cell now, or null for air
     * @param solid whether a block state text beats an object: not air, a fluid or replaceable
     * @param pasteAir whether air in the selection is moved too (it clears its place either way)
     */
    public static Result move(List<Obj> ordered,Set<Point> source,Function<Point,Point> forward,Function<Point,String> worldState,Predicate<String> solid,boolean pasteAir){
        var work=new LinkedHashMap<Integer,Work>();var priority=new HashMap<Integer,Integer>();
        for(int i=0;i<ordered.size();i++){var o=ordered.get(i);work.put(o.id(),new Work(o));priority.put(o.id(),i);}
        var owner=new HashMap<Point,Work>();
        for(var w:work.values())for(var p:w.own)owner.put(p,w);

        // What every selected block is, and whose it was, before anything is vacated.
        var states=new HashMap<Point,String>();var ownerBefore=new HashMap<Point,Work>();
        for(var c:source){states.put(c,orAir(worldState.apply(c)));var w=owner.get(c);if(w!=null)ownerBefore.put(c,w);}
        // An object moves whole when every block it holds in the world is selected; then its dormant blocks go with it.
        var whole=new HashSet<Work>();
        for(var w:work.values())if(!w.own.isEmpty()&&source.containsAll(w.own))whole.add(w);

        var current=new HashMap<Point,String>(); // what the world holds where this move has written
        Function<Point,String> at=p->{var s=current.get(p);return s!=null?s:orAir(worldState.apply(p));};

        // 1. Vacate every selected cell: another object's hidden block comes back, else what the mover replaced, else air.
        for(var c:source){
            var mover=ownerBefore.get(c);
            if(mover!=null){mover.own.remove(c);owner.remove(c);mover.touched=true;}
            Work back=null;
            for(int i=ordered.size()-1;i>=0&&back==null;i--){var p=work.get(ordered.get(i).id());if(!whole.contains(p)&&p!=mover&&p.dormant.has(c))back=p;}
            if(back!=null){current.put(c,back.dormant.get(c));back.dormant.remove(c);back.own.add(c);owner.put(c,back);back.touched=true;}
            else if(mover!=null&&mover.replaced.has(c)){current.put(c,mover.replaced.get(c));mover.replaced.remove(c);}
            else current.put(c,AIR);
        }

        // 2. Arrivals, lowest priority first so a later object can take a cell from an earlier one that just landed there.
        record Arrival(Work who,Point to,String state,int rank){}
        var arrivals=new ArrayList<Arrival>();
        for(var c:source){
            var st=states.get(c);if(isAir(st)&&!pasteAir)continue;
            var w=ownerBefore.get(c);arrivals.add(new Arrival(w,forward.apply(c),st,w==null?-1:priority.get(w.id)));
        }
        for(var w:whole){
            var hidden=new ArrayList<Point>();var kept=w.dormant;
            kept.forEach((p,s)->hidden.add(p));
            var states2=new HashMap<Point,String>();for(var p:hidden)states2.put(p,kept.get(p));
            w.dormant=new PackedCells();w.touched=true;
            for(var p:hidden)arrivals.add(new Arrival(w,forward.apply(p),states2.get(p),priority.get(w.id)));
        }
        arrivals.sort(Comparator.comparingInt(Arrival::rank));
        for(var a:arrivals){
            var d=a.to();var here=at.apply(d);var holder=owner.get(d);
            if(a.who()==null){ // an ordinary block: it wins, and an object that held the cell loses it
                if(holder!=null){holder.own.remove(d);holder.touched=true;owner.remove(d);}
                current.put(d,a.state());continue;
            }
            var me=a.who();me.touched=true;
            if(solid.test(here)){
                if(holder!=null&&holder!=me&&priority.get(me.id)>priority.get(holder.id)){
                    holder.own.remove(d);holder.dormant.put(d,here);holder.touched=true;
                    owner.put(d,me);me.own.add(d);current.put(d,a.state());
                }else if(holder==me){current.put(d,a.state());}
                else me.dormant.put(d,a.state());
            }else{
                if(!isAir(here))me.replaced.put(d,here);
                owner.put(d,me);me.own.add(d);current.put(d,a.state());
            }
        }

        var writes=new LinkedHashMap<Point,String>();
        for(var e:current.entrySet())if(!e.getValue().equals(orAir(worldState.apply(e.getKey()))))writes.put(e.getKey(),e.getValue());
        var changed=new LinkedHashMap<Integer,Obj>();
        for(var w:work.values())if(w.touched)changed.put(w.id,new Obj(w.id,w.own,w.dormant,w.replaced));
        return new Result(writes,changed);
    }

    /** Air in any of its three kinds, or nothing. */
    public static boolean isAir(String state){
        return state==null||state.equals(AIR)||state.startsWith("minecraft:air[")||state.equals("minecraft:cave_air")||state.equals("minecraft:void_air");
    }
    private static String orAir(String state){return state==null?AIR:state;}

    private static final class Work{
        final int id;final Set<Point> own;PackedCells dormant,replaced;boolean touched;
        Work(Obj o){id=o.id();own=new HashSet<>(o.own());dormant=o.dormant().copy();replaced=o.replaced().copy();}
    }
}
