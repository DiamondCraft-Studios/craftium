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

    /**
     * A BuildObject as the move sees it: the cells it holds in the world, and what it keeps outside it. {@code carried} is set for one that holds nothing in the
     * world (everything it has is dormant) but is shown with the selection: it moves with it, so its hidden blocks go through the same steps.
     */
    public record Obj(int id,Set<Point> own,PackedCells dormant,PackedCells replaced,boolean carried){
        public Obj(int id,Set<Point> own,PackedCells dormant,PackedCells replaced){this(id,own,dormant,replaced,false);}
    }

    /** A block that landed in a cell (as it lands: a transform may have turned it), and the cell it came from. */
    public record Landing(Point from,Block block){}

    /**
     * The blocks to write (a cell to its block; air clears it), every object that changed as it is afterwards, and the cells where a moved block landed
     * (not those where it stayed dormant): the selection after the move.
     */
    public record Result(Map<Point,Block> writes,Map<Integer,Obj> objects,Set<Point> landed){}

    /** Whether an object moves whole for this selection, and so takes its dormant blocks along: all it holds in the world is selected, or it holds nothing there and is carried. */
    public static boolean moves(Obj o,Set<Point> source){return o.carried()?o.own().isEmpty()||source.containsAll(o.own()):!o.own().isEmpty()&&source.containsAll(o.own());}

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
     *        Sampled with the same steps as the selection, so they line up with it. An object that moves and is missing here has its dormant blocks dropped.
     * @param worldState the block at a cell now, or null for air
     * @param solid whether a block state text beats an object: not air, a fluid or replaceable
     * @param pasteAir whether air in the selection is moved too (it clears its place either way)
     */
    public static Result move(List<Obj> ordered,Set<Point> source,Map<Point,Point> origin,Map<Integer,Map<Point,Point>> dormantOrigin,
                              Function<Point,Block> worldState,Predicate<String> solid,boolean pasteAir){
        var landed=new LinkedHashMap<Point,Landing>();
        for(var e:origin.entrySet())landed.put(e.getKey(),new Landing(e.getValue(),orAir(worldState.apply(e.getValue()))));
        var byId=new HashMap<Integer,Obj>();for(var o:ordered)byId.put(o.id(),o);
        var dormantLanded=new HashMap<Integer,Map<Point,Landing>>();
        for(var e:dormantOrigin.entrySet()){
            var o=byId.get(e.getKey());if(o==null)continue;
            var m=new LinkedHashMap<Point,Landing>();
            for(var d:e.getValue().entrySet()){var state=o.dormant().get(d.getValue());if(state!=null)m.put(d.getKey(),new Landing(d.getValue(),new Block(state,o.dormant().data(d.getValue()))));}
            dormantLanded.put(e.getKey(),m);
        }
        return moveLanded(ordered,source,landed,dormantLanded,worldState,solid,pasteAir);
    }

    /**
     * As above, with each landed block given as it lands and the cell it came from.
     * @param landed for each cell a selected block landed in, that block and the selected cell it came from
     * @param dormantLanded for each object that {@linkplain #moves moves}, the same for its dormant blocks, by object id
     */
    public static Result moveLanded(List<Obj> ordered,Set<Point> source,Map<Point,Landing> landed,Map<Integer,Map<Point,Landing>> dormantLanded,
                              Function<Point,Block> worldState,Predicate<String> solid,boolean pasteAir){
        var work=new LinkedHashMap<Integer,Work>();var priority=new HashMap<Integer,Integer>();
        for(int i=0;i<ordered.size();i++){var o=ordered.get(i);work.put(o.id(),new Work(o));priority.put(o.id(),i);}
        var owner=new HashMap<Point,Work>();
        for(var w:work.values())for(var p:w.own)owner.put(p,w);

        // Whose every selected block was, before anything is vacated.
        var ownerBefore=new HashMap<Point,Work>();
        for(var c:source){var w=owner.get(c);if(w!=null)ownerBefore.put(c,w);}
        var whole=new HashSet<Work>();
        for(var o:ordered)if(moves(o,source))whole.add(work.get(o.id()));

        var current=new HashMap<Point,Block>(); // what the world holds where this move has written
        Function<Point,Block> at=p->{var b=current.get(p);return b!=null?b:orAir(worldState.apply(p));};
        var dried=new HashSet<Point>(); // cells where the mover stood in water: a block that was waterlogged there is not any more where it goes

        // 1. Vacate every selected cell: another object's hidden block comes back, else what the mover replaced, else air.
        for(var c:source){
            var mover=ownerBefore.get(c);
            if(mover!=null){mover.own.remove(c);owner.remove(c);mover.touched=true;}
            Work back=null;
            for(int i=ordered.size()-1;i>=0&&back==null;i--){var p=work.get(ordered.get(i).id());if(!whole.contains(p)&&p!=mover&&p.dormant.has(c))back=p;}
            if(back!=null){current.put(c,new Block(back.dormant.get(c),back.dormant.data(c)));back.dormant.remove(c);back.own.add(c);owner.put(c,back);back.touched=true;}
            else if(mover!=null&&mover.replaced.has(c)){if(isWater(mover.replaced.get(c)))dried.add(c);current.put(c,new Block(mover.replaced.get(c),mover.replaced.data(c)));mover.replaced.remove(c);}
            else current.put(c,new Block(AIR,null));
        }

        // 2. Arrivals, lowest priority first so a later object can take a cell from an earlier one that just landed there.
        var arrivals=new ArrayList<Arrival>();
        for(var e:landed.entrySet()){
            var b=e.getValue().block();if(b==null||(b.isAir()&&!pasteAir))continue;
            if(dried.contains(e.getValue().from())&&b.state().contains("waterlogged=true"))b=new Block(b.state().replace("waterlogged=true","waterlogged=false"),b.data());
            var w=ownerBefore.get(e.getValue().from());arrivals.add(new Arrival(w,e.getKey(),b,w==null?-1:priority.get(w.id)));
        }
        for(var w:whole){
            w.dormant=new PackedCells();w.touched=true; // what is not landed anywhere is sampled away
            for(var e:dormantLanded.getOrDefault(w.id,Map.of()).entrySet())arrivals.add(new Arrival(w,e.getKey(),e.getValue().block(),priority.get(w.id)));
        }
        var landedCells=settle(arrivals,owner,current,priority,solid,at);

        var writes=new LinkedHashMap<Point,Block>();
        for(var e:current.entrySet())if(!e.getValue().equals(orAir(worldState.apply(e.getKey()))))writes.put(e.getKey(),e.getValue());
        var changed=new LinkedHashMap<Integer,Obj>();
        for(var w:work.values())if(w.touched)changed.put(w.id,new Obj(w.id,w.own,w.dormant,w.replaced,false));
        return new Result(writes,changed,landedCells);
    }

    private record Arrival(Work who,Point to,Block block,int rank){}

    /** Lets each arrival take its cell, lowest priority first, by the rules above. Returns the cells where a block landed. */
    private static Set<Point> settle(List<Arrival> arrivals,Map<Point,Work> owner,Map<Point,Block> current,Map<Integer,Integer> priority,Predicate<String> solid,Function<Point,Block> at){
        arrivals.sort(Comparator.comparingInt(Arrival::rank));
        var landedCells=new LinkedHashSet<Point>();
        for(var a:arrivals){
            var d=a.to();var here=at.apply(d);var holder=owner.get(d);
            if(a.who()==null){ // an ordinary block: it wins, and an object that held the cell loses it
                if(holder!=null){holder.own.remove(d);holder.touched=true;owner.remove(d);}
                current.put(d,a.block());landedCells.add(d);continue;
            }
            var me=a.who();me.touched=true;
            if(solid.test(here.state())){
                if(holder!=null&&holder!=me&&priority.get(me.id)>priority.get(holder.id)){
                    holder.own.remove(d);holder.dormant.put(d,here.state(),here.data());holder.touched=true;
                    owner.put(d,me);me.own.add(d);current.put(d,a.block());landedCells.add(d);
                }else if(holder==me){current.put(d,a.block());landedCells.add(d);}
                else if(!a.block().isAir())me.dormant.put(d,a.block().state(),a.block().data()); // an air block that loses a cell has nothing to keep
            }else{
                if(holder!=null&&holder!=me){holder.own.remove(d);holder.touched=true;} // what it held here is not there, or is replaced when built on: it does not keep the cell
                if(!isAir(here.state()))me.replaced.put(d,here.state(),here.data());
                owner.put(d,me);me.own.add(d);current.put(d,a.block());landedCells.add(d);
            }
        }
        return landedCells;
    }

    /** A block an object places: it is the object's block that would go in this cell. */
    public record Placement(int objectId,Point cell,Block block){}

    /**
     * Places blocks that come from outside the world (a paste, a duplicate) for BuildObjects, by the same rules as a move but with nothing vacated: a solid world
     * block wins and the object keeps its block as dormant, what a block replaces is kept, and between objects the later one in the list wins.
     * The objects the blocks are for should hold none of those cells in {@code ordered}.
     * @param ordered every BuildObject, lowest priority first
     */
    public static Result place(List<Obj> ordered,List<Placement> placements,Function<Point,Block> worldState,Predicate<String> solid){
        var work=new LinkedHashMap<Integer,Work>();var priority=new HashMap<Integer,Integer>();
        for(int i=0;i<ordered.size();i++){var o=ordered.get(i);work.put(o.id(),new Work(o));priority.put(o.id(),i);}
        var owner=new HashMap<Point,Work>();
        for(var w:work.values())for(var p:w.own)owner.put(p,w);
        var current=new HashMap<Point,Block>();
        Function<Point,Block> at=p->{var b=current.get(p);return b!=null?b:orAir(worldState.apply(p));};
        var arrivals=new ArrayList<Arrival>();
        for(var pl:placements){var w=work.get(pl.objectId());if(w==null)throw new IllegalArgumentException("No such BuildObject");arrivals.add(new Arrival(w,pl.cell(),pl.block(),priority.get(pl.objectId())));}
        var landedCells=settle(arrivals,owner,current,priority,solid,at);
        var writes=new LinkedHashMap<Point,Block>();
        for(var e:current.entrySet())if(!e.getValue().equals(orAir(worldState.apply(e.getKey()))))writes.put(e.getKey(),e.getValue());
        var changed=new LinkedHashMap<Integer,Obj>();
        for(var w:work.values())if(w.touched)changed.put(w.id,new Obj(w.id,w.own,w.dormant,w.replaced,false));
        return new Result(writes,changed,landedCells);
    }

    /**
     * Gives each cell to the BuildObject that now outranks the one holding it: where a BuildObject keeps a block hidden under the block of one earlier in the list, which
     * is lower priority, the block of the later one is written and the earlier one keeps its own as dormant. For after the order of the Hierarchy has changed. Cells that
     * are only hidden, with nothing of another BuildObject on them, stay hidden (that is Place here).
     */
    public static Result reorder(List<Obj> ordered,Function<Point,Block> worldState,Predicate<String> solid){
        var placements=outranked(ordered);
        if(placements.isEmpty())return new Result(Map.of(),Map.of(),Set.of());
        var taken=new HashMap<Integer,Set<Point>>();
        for(var pl:placements)taken.computeIfAbsent(pl.objectId(),k->new HashSet<>()).add(pl.cell());
        var adjusted=new ArrayList<Obj>();
        for(var o:ordered){
            var cut=taken.get(o.id());
            if(cut==null){adjusted.add(o);continue;}
            var rest=o.dormant().copy();for(var c:cut)rest.remove(c); // these are placed below, not kept twice
            adjusted.add(new Obj(o.id(),o.own(),rest,o.replaced(),o.carried()));
        }
        return place(adjusted,placements,worldState,solid);
    }

    /** The hidden blocks that outrank the BuildObject standing in their cell, as the placements that {@link #reorder} makes: what a caller needs to know which chunks to load. */
    public static List<Placement> outranked(List<Obj> ordered){
        var priority=new HashMap<Integer,Integer>();for(int i=0;i<ordered.size();i++)priority.put(ordered.get(i).id(),i);
        var hiddenAt=new HashSet<Point>();for(var o:ordered)hiddenAt.addAll(o.dormant().points());
        if(hiddenAt.isEmpty())return List.of();
        var holder=new HashMap<Point,Obj>();
        for(var o:ordered)for(var p:o.own())if(hiddenAt.contains(p))holder.put(p,o);
        var placements=new ArrayList<Placement>();
        for(var o:ordered)for(var c:o.dormant().points()){
            var held=holder.get(c);
            if(held!=null&&held!=o&&priority.get(o.id())>priority.get(held.id()))placements.add(new Placement(o.id(),c,new Block(o.dormant().get(c),o.dormant().data(c))));
        }
        return placements;
    }

    /** Water, as a block state text (not a block that only starts with the word, such as a water cauldron). */
    public static boolean isWater(String state){return state!=null&&(state.equals("minecraft:water")||state.startsWith("minecraft:water["));}

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
