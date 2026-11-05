package dev.craftium.client;

import dev.craftium.core.Point;

/**
 * One measurement: a block at each end, drawn with a knob on each. The ends can be picked up and moved, and what is drawn and
 * measured follows them as they move. The second end is empty until it is placed.
 */
public final class Ruler {
    public Point a,b;
    /** Whether this ruler is just a line or also draws a circle round its first end. */
    public dev.craftium.core.Options.RulerMode mode=dev.craftium.core.Options.RulerMode.LINE;
    public Ruler(Point a){this.a=a;}
    /** True once both ends are placed, which is when there is something to measure. */
    public boolean complete(){return b!=null;}
    /** The straight distance between the two blocks. */
    public double length(){return b==null?0:Math.sqrt(a.distanceSquared(b));}
    /** The distance along the three axes added up. */
    public int manhattan(){return b==null?0:Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z());}
    /**
     * How many blocks the ruler covers along each axis, counting both end blocks: two blocks side by side span 2 x 1 x 1. This is what a
     * ruler measures, rather than the distance between the centres of its end blocks, which is one less along each axis.
     */
    public int[] span(){return b==null?new int[]{1,1,1}:new int[]{Math.abs(a.x()-b.x())+1,Math.abs(a.y()-b.y())+1,Math.abs(a.z()-b.z())+1};}
    public String showSpan(){int[] s=span();return showSpan(s[0],s[1],s[2]);}
    /**
     * A span as shown: the axes it covers more than one block along, in the order X, Y, Z, joined with x. A straight run of 6 blocks is "6",
     * and 4 blocks by 5 by 1 is "4 x 5". One block is "1".
     */
    public static String showSpan(int x,int y,int z){
        var parts=new java.util.ArrayList<String>();
        for(int size:new int[]{x,y,z})if(size>1)parts.add(String.valueOf(size));
        return parts.isEmpty()?"1":String.join(" × ",parts);
    }
}
