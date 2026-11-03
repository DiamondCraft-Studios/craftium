package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.client.compat.Overlay;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class SelectionOverlay {
    private static final int STROKE=0xffffd66d,FILL=0x225d91ff;
    /** Beyond these the outline is too detailed to draw every tick, so the selection falls back to its bounding box. */
    private static final int MAX_EDGES=8192,MAX_FACES=4096;
    private static String encoded="";
    private static SelectionOutline.Result outline=SelectionOutline.Result.EMPTY;
    private static AABB bounds;
    private SelectionOverlay(){}
    static void update(String data){
        if(Objects.equals(encoded,data))return;encoded=data;
        var points=SelectionCodec.decode(data);
        outline=SelectionOutline.of(points);
        bounds=null;
        for(Point p:points){var cell=new AABB(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1);bounds=bounds==null?cell:bounds.minmax(cell);}
    }
    /**
     * Draws the selection as one outline around its outer surface, with a fill on each exterior face. While the
     * transform gizmo is being dragged only the outline is drawn, moved by the transform being applied.
     */
    static void draw(boolean transforming){
        if(bounds==null)return;
        if(outline.edges().size()>MAX_EDGES){
            if(transforming)TransformGizmo.box(bounds);else Overlay.box(bounds,STROKE,1,FILL);
            return;
        }
        for(var s:outline.edges()){
            Vec3 a=new Vec3(s.x0(),s.y0(),s.z0()),b=new Vec3(s.x1(),s.y1(),s.z1());
            if(transforming)TransformGizmo.line(a,b);else Overlay.line(a,b,STROKE,1);
        }
        if(transforming||outline.faces().size()>MAX_FACES)return;
        for(var f:outline.faces())face(f);
    }
    /** Sends one exterior rectangle to the overlay, wound so it faces outward. */
    private static void face(SelectionOutline.Face f){
        int[] lo={f.x0(),f.y0(),f.z0()},hi={f.x1(),f.y1(),f.z1()};
        int axis=f.axis(),u=(axis+1)%3,v=(axis+2)%3;
        // (axis, u, v) is a right-handed cycle, so this ring runs counter-clockwise seen from the +axis side.
        int[][] ring={{lo[u],lo[v]},{hi[u],lo[v]},{hi[u],hi[v]},{lo[u],hi[v]}};
        Vec3[] c=new Vec3[4];
        for(int i=0;i<4;i++){
            double[] p=new double[3];p[axis]=lo[axis];p[u]=ring[i][0];p[v]=ring[i][1];
            c[i]=new Vec3(p[0],p[1],p[2]);
        }
        if(f.sign()>0)Overlay.face(c[0],c[1],c[2],c[3],FILL);else Overlay.face(c[3],c[2],c[1],c[0],FILL);
    }
    static List<AABB> merge(Collection<Point> points){
        var remaining=new HashSet<>(points);var ordered=new ArrayList<>(points);
        ordered.sort(Comparator.comparingInt(Point::y).thenComparingInt(Point::z).thenComparingInt(Point::x));var result=new ArrayList<AABB>();
        for(Point p:ordered){if(!remaining.contains(p))continue;int x=p.x(),z=p.z(),y=p.y();while(remaining.contains(new Point(x+1,p.y(),p.z())))x++;
            boolean extend=true;while(extend){for(int ix=p.x();ix<=x;ix++)if(!remaining.contains(new Point(ix,p.y(),z+1))){extend=false;break;}if(extend)z++;}
            extend=true;while(extend){for(int ix=p.x();ix<=x&&extend;ix++)for(int iz=p.z();iz<=z;iz++)if(!remaining.contains(new Point(ix,y+1,iz))){extend=false;break;}if(extend)y++;}
            for(int ix=p.x();ix<=x;ix++)for(int iy=p.y();iy<=y;iy++)for(int iz=p.z();iz<=z;iz++)remaining.remove(new Point(ix,iy,iz));
            result.add(new AABB(p.x(),p.y(),p.z(),x+1,y+1,z+1));
        }return List.copyOf(result);
    }
}
