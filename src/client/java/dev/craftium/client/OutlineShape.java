package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The single outline of a set of blocks, ready to draw: one line along each edge of the outer surface and a fill on
 * each exterior face. Every preview that outlines blocks draws through this, so they all look like the selection.
 */
final class OutlineShape {
    static final OutlineShape EMPTY=new OutlineShape(SelectionOutline.Result.EMPTY,null);
    /** Beyond these the outline is too detailed to draw every tick, so the shape falls back to its bounding box. */
    private static final int MAX_EDGES=8192,MAX_FACES=4096;

    private final SelectionOutline.Result outline;
    private final AABB bounds;

    private OutlineShape(SelectionOutline.Result outline,AABB bounds){this.outline=outline;this.bounds=bounds;}

    static OutlineShape of(Collection<Point> points){
        if(points.isEmpty())return EMPTY;
        AABB bounds=null;
        for(Point p:points){var cell=new AABB(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1);bounds=bounds==null?cell:bounds.minmax(cell);}
        return new OutlineShape(SelectionOutline.of(points),bounds);
    }

    /** The box around every block, or null when empty. */
    AABB bounds(){return bounds;}
    boolean isEmpty(){return bounds==null;}

    /** Receives a line, after any transform the caller wants applied. */
    interface Lines{void line(Vec3 from,Vec3 to);}

    /**
     * Draws the shape moved by {@code at}. Each outline edge goes to {@code lines}, so the caller picks the colour and
     * any transform. With {@code withFill} the exterior faces are filled with {@code fill} too, unless there are too
     * many to draw every frame. When the outline is too detailed, one box around everything is drawn instead.
     */
    void draw(Vec3 at,Lines lines,int stroke,int fill,boolean withFill){
        if(bounds==null)return;
        if(outline.edges().size()>MAX_EDGES){
            AABB box=bounds.move(at);
            if(withFill)Overlay.box(box,stroke,1,fill);else boxLines(box,lines);
            return;
        }
        for(var s:outline.edges())lines.line(new Vec3(s.x0(),s.y0(),s.z0()).add(at),new Vec3(s.x1(),s.y1(),s.z1()).add(at));
        if(withFill&&outline.faces().size()<=MAX_FACES)for(var f:outline.faces())face(f,at,fill);
    }

    private static void boxLines(AABB box,Lines lines){
        for(int i=0;i<8;i++)for(int bit:new int[]{1,2,4})if((i&bit)==0)lines.line(corner(box,i),corner(box,i|bit));
    }
    private static Vec3 corner(AABB box,int i){return new Vec3((i&1)==0?box.minX:box.maxX,(i&2)==0?box.minY:box.maxY,(i&4)==0?box.minZ:box.maxZ);}

    /** Sends one exterior rectangle to the overlay, wound so it faces outward. */
    private static void face(SelectionOutline.Face f,Vec3 at,int color){
        int[] lo={f.x0(),f.y0(),f.z0()},hi={f.x1(),f.y1(),f.z1()};
        int axis=f.axis(),u=(axis+1)%3,v=(axis+2)%3;
        // (axis, u, v) is a right-handed cycle, so this ring runs counter-clockwise seen from the +axis side.
        int[][] ring={{lo[u],lo[v]},{hi[u],lo[v]},{hi[u],hi[v]},{lo[u],hi[v]}};
        Vec3[] c=new Vec3[4];
        for(int i=0;i<4;i++){
            double[] p=new double[3];p[axis]=lo[axis];p[u]=ring[i][0];p[v]=ring[i][1];
            c[i]=new Vec3(p[0],p[1],p[2]).add(at);
        }
        if(f.sign()>0)Overlay.face(c[0],c[1],c[2],c[3],color);else Overlay.face(c[3],c[2],c[1],c[0],color);
    }
}
