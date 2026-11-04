package dev.craftium.client;

import dev.craftium.core.Numbers;
import dev.craftium.core.Point;

/**
 * One measurement: a block at each end, drawn with a knob on each. The ends can be picked up and moved, and what is drawn and
 * measured follows them as they move. The second end is empty until it is placed.
 */
public final class Ruler {
    public Point a,b;
    public Ruler(Point a){this.a=a;}
    /** True once both ends are placed, which is when there is something to measure. */
    public boolean complete(){return b!=null;}
    /** The straight distance between the two blocks. */
    public double length(){return b==null?0:Math.sqrt(a.distanceSquared(b));}
    /** The distance along the three axes added up. */
    public int manhattan(){return b==null?0:Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z());}
    /** The length as shown: whole numbers whole, otherwise at most two decimals. */
    public static String show(double length){return Numbers.trim(length);}
}
