package dev.craftium.core;

import java.util.*;

/**
 * The flow of Fluid Ball and Fluid Block, kept apart from the world so the server builds from it and the hover preview shows exactly what it
 * would build. The world is reached only through {@link Cells}; every block the flow places is reported back through it, in order, and a placed
 * block that is solid counts as solid for what the flow looks at next.
 */
public final class FluidFlow {
    private FluidFlow(){}

    public interface Cells {
        boolean solid(Point at);
        int minY();
        /** Water or lava at {@code at}, with the flow level 0 (a source) to 7. */
        void fluid(Point at,int level);
        /** A snow layer pile of {@code layers} (1 to 8) at {@code at}. */
        void snow(Point at,int layers);
        /** One of Fluid Block's blocks at {@code cell}; the world picks it from the palette. */
        void pour(Point cell);
    }

    private static final Point[] NEIGHBOURS={new Point(1,0,0),new Point(-1,0,0),new Point(0,1,0),new Point(0,-1,0),new Point(0,0,1),new Point(0,0,-1)};
    private record Flow(Point point,int cost,int distance){}

    private static PriorityQueue<Flow> queue(){return new PriorityQueue<>(Comparator.comparingInt(Flow::cost).thenComparingInt(Flow::distance));}

    /** Fluid Ball: water, lava or snow poured at {@code origin}, the block in front of the face that was clicked. */
    public static void ball(Cells c,Options o,Point origin){
        var queue=queue();var visited=new HashMap<Point,Integer>();
        queue.add(new Flow(origin,0,0));int maxDistance=o.radius+o.flowLength;
        while(!queue.isEmpty()){
            var f=queue.remove();Point at=f.point;if(f.distance>maxDistance||at.y()>origin.y()+o.radius||at.y()<Math.max(c.minY(),origin.y()-maxDistance*2))continue;
            if(visited.getOrDefault(at,Integer.MAX_VALUE)<=f.cost)continue;visited.put(at,f.cost);if(visited.size()>Selection.LIMIT)throw new IllegalArgumentException("Fluid simulation exceeds limit");
            if(c.solid(at))continue;boolean supported=c.solid(at.add(0,-1,0));if(o.requireSupport&&!supported){queue.add(new Flow(at.add(0,-1,0),0,f.distance+1));continue;}
            if(o.fluidType==Options.FluidType.SNOW){if(supported)c.snow(at,Math.clamp(8-f.cost,1,8));}
            else {int level=o.infiniteSources&&at.distanceSquared(origin)<=o.radius*o.radius?0:Math.clamp(f.cost,0,7);if(o.removeFluidsWithoutSources&&f.distance>o.flowLength)continue;c.fluid(at,level);}
            if(!supported&&at.y()>c.minY())queue.add(new Flow(at.add(0,-1,0),0,f.distance+1));
            if(supported||o.fillEdges)for(Point n:NEIGHBOURS)if(n.y()==0&&f.cost<Math.min(7,o.flowLength))queue.add(new Flow(at.add(n.x(),0,n.z()),f.cost+1,f.distance+1));
            if(o.fluidQuality>1&&o.fillEdges&&f.cost<6)for(int x:new int[]{-1,1})for(int z:new int[]{-1,1})queue.add(new Flow(at.add(x,0,z),f.cost+2,f.distance+1));
        }
    }

    /** Fluid Block: Fluid Ball's flow, but pouring blocks. They fall until something holds them, then spread sideways, piled up to three high where they land and a single layer at the edge. */
    public static void powder(Cells c,Options o,Point origin){
        var queue=queue();var visited=new HashMap<Point,Integer>();
        queue.add(new Flow(origin,0,0));int maxDistance=o.radius+o.flowLength,maxCost=Math.max(1,Math.min(7,o.flowLength));
        while(!queue.isEmpty()){
            var f=queue.remove();Point at=f.point;if(f.distance>maxDistance||at.y()>origin.y()+o.radius||at.y()<Math.max(c.minY(),origin.y()-maxDistance*2))continue;
            if(visited.getOrDefault(at,Integer.MAX_VALUE)<=f.cost)continue;visited.put(at,f.cost);if(visited.size()>Selection.LIMIT)throw new IllegalArgumentException("Fluid block pour exceeds limit");
            if(c.solid(at))continue;
            if(!c.solid(at.add(0,-1,0))){queue.add(new Flow(at.add(0,-1,0),0,f.distance+1));continue;} // nothing under it yet: keep falling
            int height=1+(maxCost-Math.min(maxCost,f.cost))*2/maxCost;
            for(int i=0;i<height;i++){Point cell=at.add(0,i,0);if(c.solid(cell))break;c.pour(cell);}
            if(f.cost<Math.min(7,o.flowLength))for(Point n:NEIGHBOURS)if(n.y()==0)queue.add(new Flow(at.add(n.x(),0,n.z()),f.cost+1,f.distance+1));
        }
    }
}
