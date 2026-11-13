package dev.craftium.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;

/**
 * Picks which blocks of a big set to draw as models when a frame cannot draw them all: the ones with a face in the open come
 * first, so a cut set still shows its whole outer surface as far as it reaches, and buried blocks only fill what is left over.
 */
public final class Exposed {
    private Exposed(){}

    /**
     * Up to {@code limit} of the cells, in their own order, the exposed ones first. A set that already fits is returned as it is, so
     * the common case costs nothing. A cell is exposed when at least one of its six neighbours is not in the set.
     */
    public static List<Point> first(Collection<Point> cells,int limit){
        if(cells.size()<=limit)return cells instanceof List<Point> list?list:new ArrayList<>(cells);
        if(limit<=0)return List.of();
        var present=new LongOpenHashSet(cells.size()*2);
        for(Point p:cells)present.add(key(p.x(),p.y(),p.z()));
        var out=new ArrayList<Point>(limit);
        for(Point p:cells)if(!buried(present,p)){out.add(p);if(out.size()==limit)return out;}
        for(Point p:cells)if(buried(present,p)){out.add(p);if(out.size()==limit)break;}
        return out;
    }

    private static boolean buried(LongOpenHashSet present,Point p){
        int x=p.x(),y=p.y(),z=p.z();
        return present.contains(key(x+1,y,z))&&present.contains(key(x-1,y,z))&&present.contains(key(x,y+1,z))
            &&present.contains(key(x,y-1,z))&&present.contains(key(x,y,z+1))&&present.contains(key(x,y,z-1));
    }

    /** The same packing as a block position's long: 26 bits each for x and z, 12 for y. */
    private static long key(int x,int y,int z){return ((long)x&0x3FFFFFFL)<<38|((long)z&0x3FFFFFFL)<<12|(long)y&0xFFFL;}
}
