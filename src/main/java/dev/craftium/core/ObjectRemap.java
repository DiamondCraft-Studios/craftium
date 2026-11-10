package dev.craftium.core;

import java.util.*;

/**
 * Where a BuildObject's own blocks are after the selected blocks were moved, turned or scaled. The transform looks back from every cell it covers to
 * the cell it took its block from, so a block of an object that is now at a cell is that cell's: ownership follows the block, never the place it
 * left. A cell the shape grew into inherits from the cell it was sampled from (so scaling up keeps an object whole), and a cell that was sampled
 * away is gone (so scaling down loses what it loses).
 */
public final class ObjectRemap {
    private ObjectRemap(){}

    /**
     * @param own the object's blocks before
     * @param source every cell the transform took its blocks from (the selection)
     * @param origin for each cell the transform covers, the cell its block came from
     * @return the object's blocks after: those outside {@code source} where they were, then the cells that came from one of its blocks
     */
    public static List<Point> apply(Collection<Point> own,Set<Point> source,Map<Point,Point> origin){
        var mine=own instanceof Set<?>?(Set<Point>)own:new HashSet<>(own);
        var out=new LinkedHashSet<Point>();
        for(var p:own)if(!source.contains(p))out.add(p);
        for(var e:origin.entrySet())if(mine.contains(e.getValue()))out.add(e.getKey());
        return List.copyOf(out);
    }
}
