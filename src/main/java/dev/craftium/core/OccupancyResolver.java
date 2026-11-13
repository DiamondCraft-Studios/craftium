package dev.craftium.core;

import java.util.*;
import java.util.function.Predicate;

/**
 * Decides which block owns each cell when BuildObjects are placed over the world or over one another, so a cell has one occupant
 * as in vanilla. A solid world block always wins; between objects the later one in the list wins (the hierarchy puts a child after
 * its parent). The cells that lose are dormant: the object keeps their blocks but does not write them to the world. Nothing is
 * deleted, so moving the object elsewhere brings them back.
 */
public final class OccupancyResolver{
    /** An object as the resolver sees it: its blocks, by world cell. */
    public record Placed(String id,ObjectVolume volume){}

    private final Predicate<Point> worldSolid;

    /** @param worldSolid whether the world cell holds a block that wins over objects: not air, a fluid or replaceable. */
    public OccupancyResolver(Predicate<Point> worldSolid){this.worldSolid=worldSolid;}

    /**
     * The dormant cells of every object, by object id. {@code ordered} runs from lowest priority to highest; the last object
     * with a block in a cell owns it, unless a solid world block is there.
     */
    public Map<String,Set<Point>> dormant(List<Placed> ordered){
        var owner=new HashMap<Point,Placed>();
        var out=new LinkedHashMap<String,Set<Point>>();
        for(Placed o:ordered)out.put(o.id(),new HashSet<>());
        for(Placed o:ordered)for(Point cell:o.volume().points()){
            if(worldSolid.test(cell)){out.get(o.id()).add(cell);continue;}
            Placed previous=owner.put(cell,o);
            if(previous!=null)out.get(previous.id()).add(cell);
        }
        return out;
    }
}
