package dev.craftium.core;

import java.util.*;
import java.util.function.Predicate;

/**
 * Decides which block owns each cell when lifted objects overlap the world or one another, so a cell has one occupant as in
 * vanilla. A solid world block always wins; between objects the later one in the list wins (the hierarchy puts a child after
 * its parent). The cells that lose are suppressed: not drawn, not collided with. Nothing is deleted, and the result is derived
 * from its inputs every time, so it is never saved and cannot drift.
 */
public final class OccupancyResolver{
    /** An object as the resolver sees it: its blocks and where it sits, with the transform already composed with its parents'. */
    public record Placed(String id,ObjectVolume volume,GridTransform transform){}

    /** Who owns a cell: an object id, or null for the world or empty. */
    public record Occupant(String objectId,String state){}

    private final Predicate<Point> worldSolid;

    /** @param worldSolid whether the world cell holds a block that wins over objects: not air, a fluid or replaceable. */
    public OccupancyResolver(Predicate<Point> worldSolid){this.worldSolid=worldSolid;}

    /**
     * The suppressed cells of every object, local to it, by object id. {@code ordered} runs from lowest priority to highest;
     * the last object with a block in a cell owns it.
     */
    public Map<String,Set<Point>> suppressed(List<Placed> ordered){
        var owner=new HashMap<Point,Placed>();
        var out=new LinkedHashMap<String,Set<Point>>();
        for(Placed o:ordered)out.put(o.id(),new HashSet<>());
        for(Placed o:ordered)for(Point local:o.volume().points()){
            Point world=o.transform().apply(local);
            if(worldSolid.test(world)){out.get(o.id()).add(local);continue;}
            Placed previous=owner.put(world,o);
            if(previous!=null)out.get(previous.id()).add(previous.transform().invert(world));
        }
        return out;
    }

    /** What stands at a world cell: the winning object's block, or an empty occupant when it is the world's (or nothing). */
    public Occupant occupant(List<Placed> ordered,Point world){
        if(worldSolid.test(world))return new Occupant(null,null);
        for(int i=ordered.size()-1;i>=0;i--){
            Placed o=ordered.get(i);
            String state=o.volume().get(o.transform().invert(world));
            if(state!=null)return new Occupant(o.id(),state);
        }
        return new Occupant(null,null);
    }
}
