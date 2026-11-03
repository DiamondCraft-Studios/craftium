package dev.craftium.core;

import java.util.*;

/**
 * The Magic Wand's flood fill, free of any Minecraft types so the server (which applies a click) and the
 * client (which previews it on hover) run the very same search.
 */
public final class MagicWand {
    /** What the search needs to know about the world. */
    public interface World {
        boolean loaded(Point p);
        boolean air(Point p);
        /** True when the block at {@code p} is the kind of block the click started on. */
        boolean matches(Point p);
        boolean surface(Point p);
    }

    public record Params(int limit,int range,boolean corners,boolean up,boolean down,boolean horizontal,boolean surfaceOnly,int radius,int visitedCap){}

    /** {@code truncated} is true when the search stopped at the limit or the visit cap, so there may be more than {@code found}. */
    public record Result(LinkedHashSet<Point> found,boolean truncated){}

    private MagicWand(){}

    public static Result select(Point start,World world,Params params){
        var found=new LinkedHashSet<Point>();var visited=new HashSet<Point>();var queue=new ArrayDeque<Point>();
        queue.add(start);visited.add(start);boolean capped=false;
        while(!queue.isEmpty()&&found.size()<params.limit()){
            Point current=queue.removeFirst();
            if(current.distanceSquared(start)>(long)params.radius()*params.radius()||!world.loaded(current))continue;
            if(!world.matches(current)||world.air(current))continue;
            if((!params.up()&&current.y()>start.y())||(!params.down()&&current.y()<start.y())||(!params.horizontal()&&(current.x()!=start.x()||current.z()!=start.z())))continue;
            if(!params.surfaceOnly()||world.surface(current))found.add(current);
            int range=params.range();
            for(int dx=-range;dx<=range;dx++)for(int dy=-range;dy<=range;dy++)for(int dz=-range;dz<=range;dz++){
                int distance=Math.abs(dx)+Math.abs(dy)+Math.abs(dz);
                if(distance==0||(!params.corners()&&distance>range)||Math.max(Math.abs(dx),Math.max(Math.abs(dy),Math.abs(dz)))>range)continue;
                if(visited.size()>=params.visitedCap()){capped=true;break;}
                var next=current.add(dx,dy,dz);
                if(visited.add(next))queue.add(next);
            }
            if(capped)break;
        }
        return new Result(found,capped||found.size()>=params.limit()&&!queue.isEmpty());
    }
}
