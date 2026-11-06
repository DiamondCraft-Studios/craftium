package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.*;
import dev.craftium.client.compat.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The single outline of a set of blocks, ready to draw: one line along each edge of the outer surface and a fill on
 * each exterior face. Every preview that outlines blocks draws through this, so they all look like the selection.
 */
final class OutlineShape {
    static final OutlineShape EMPTY=new OutlineShape(SelectionOutline.Result.EMPTY,null);
    /**
     * Beyond these the outline is too detailed to draw every frame, so the shape falls back to its bounding box. Measured with the
     * selection draw-cost game test: a 100,000-block selection with about 25,000 edges and 18,000 faces still draws at 70 frames a
     * second or more. A thousand edges cost about a tenth of a millisecond and a thousand filled faces about a quarter, so a shape
     * at both caps still runs at about 50 frames a second. Past the face cap only the fill is dropped; the box is for selections
     * that are far busier than anything a person selects on purpose.
     */
    static final int DEFAULT_MAX_EDGES=40_000,DEFAULT_MAX_FACES=30_000;
    static int maxEdges=DEFAULT_MAX_EDGES,maxFaces=DEFAULT_MAX_FACES; // not final only so the draw-cost game test can lift them; nothing else changes them

    private final SelectionOutline.Result outline;
    private final AABB bounds;
    // The outline's corners as ready-made points, made on first use. Building a few of them per edge and face every frame was a large part of the
    // cost of a big shape, and a shape that is drawn where it is never changes. A shape drawn moved by something still works them out each time.
    private Vec3[] edgeEnds,creaseEnds;
    private Vec3[][] quads;

    private OutlineShape(SelectionOutline.Result outline,AABB bounds){this.outline=outline;this.bounds=bounds;}

    static OutlineShape of(Collection<Point> points){
        if(points.isEmpty())return EMPTY;
        AABB bounds=null;
        for(Point p:points){var cell=new AABB(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1);bounds=bounds==null?cell:bounds.minmax(cell);}
        return new OutlineShape(SelectionOutline.of(points),bounds);
    }

    /** A solid box from the block corner {@code lo} up to, not including, {@code hi}, drawn in the selection's style like any other shape. */
    static OutlineShape box(Point lo,Point hi){return new OutlineShape(SelectionOutline.box(lo,hi),new AABB(lo.x(),lo.y(),lo.z(),hi.x(),hi.y(),hi.z()));}
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
    void draw(Vec3 at,Lines lines,int stroke,int fill,boolean withFill){draw(at,lines,stroke,fill,withFill,ClientState.options.selectionStyle);}
    /**
     * The selection's style as it is: the silhouette (or the outline) at the plain line width, with no extra edges. All edges is always
     * depth-tested. With {@code scene} the other styles' lines are hidden by blocks in front of them too.
     */
    void drawPlain(Vec3 at,int colour,float width,int fill,boolean withFill,boolean scene){drawPlain(at,colour,width,fill,fill,withFill,scene);}
    /** As above, with the faces that point away from the camera filled in {@code backFill}: with the camera inside the shape, those are the walls around it. */
    void drawPlain(Vec3 at,int colour,float width,int fill,int backFill,boolean withFill,boolean scene){
        var style=ClientState.options.selectionStyle;
        Lines lines=scene||style==Options.SelectionStyle.ALL_EDGES?(a,b)->sceneLine(a,b,colour,width):(a,b)->Overlay.line(a,b,colour,width);
        draw(at,lines,colour,fill,backFill,withFill,style);
    }
    /** Only the fill of the exterior faces, with no outline: how blocks about to be removed are shown. */
    void drawFill(Vec3 at,int fill){draw(at,(a,b)->{},0,fill,true,ClientState.options.selectionStyle);}
    /** A depth-tested line, drawn a little toward the camera: the outline lies in the surface of the blocks, so exactly there it would be cut away by them. */
    private static void sceneLine(Vec3 a,Vec3 b,int colour,float width){
        Vec3 eye=ClientCompat.camera(Minecraft.getInstance()).position(),mid=a.add(b).scale(.5),toEye=eye.subtract(mid);
        double distance=toEye.length();
        Vec3 nudge=distance<1e-6?Vec3.ZERO:toEye.scale((.004+.0006*distance)/distance);
        Overlay.sceneLine(a.add(nudge),b.add(nudge),colour,width);
    }
    /** As {@link #draw(Vec3,Lines,int,int,boolean)}, with the outline style chosen by the caller instead of the selection's. */
    void draw(Vec3 at,Lines lines,int stroke,int fill,boolean withFill,Options.SelectionStyle style){draw(at,lines,stroke,fill,fill,withFill,style);}
    private void draw(Vec3 at,Lines lines,int stroke,int fill,int backFill,boolean withFill,Options.SelectionStyle style){
        if(bounds==null)return;
        if(outline.edges().size()>maxEdges){
            AABB box=bounds.move(at);
            if(withFill)Overlay.box(box,stroke,1,fill);else boxLines(box,lines);
            return;
        }
        // Only the outline as the camera sees it, unless every edge was asked for or the camera is inside the shape.
        if(style==Options.SelectionStyle.ALL_EDGES||!(style==Options.SelectionStyle.SILHOUETTE&&contour(lines,at))&&!silhouette(lines,at))
            allEdges(lines,at);
        if(withFill&&outline.faces().size()<=maxFaces){
            // Only a camera inside the shape sees its walls from behind; from outside, every face keeps the one fill.
            Vec3 eye=backFill==fill?null:ClientCompat.camera(Minecraft.getInstance()).position().subtract(at);
            boolean inside=eye!=null&&inside(eye);
            Vec3[][] ready=at.equals(Vec3.ZERO)?quads():null;int next=0;
            for(var f:outline.faces()){
                int colour=fill;
                if(inside){double plane=switch(f.axis()){case 0->f.x0();case 1->f.y0();default->f.z0();},from=switch(f.axis()){case 0->eye.x;case 1->eye.y;default->eye.z;};
                    if(f.sign()*(from-plane)<=0)colour=backFill;}
                if(ready!=null){Vec3[] q=ready[next++];Overlay.face(q[0],q[1],q[2],q[3],colour);}else face(f,at,colour);
            }
        }
    }
    private void allEdges(Lines lines,Vec3 at){
        if(at.equals(Vec3.ZERO)){var ends=edgeEnds;if(ends==null)edgeEnds=ends=ends(outline.edges());for(int i=0;i<ends.length;i+=2)lines.line(ends[i],ends[i+1]);return;}
        for(var s:outline.edges())lines.line(new Vec3(s.x0(),s.y0(),s.z0()).add(at),new Vec3(s.x1(),s.y1(),s.z1()).add(at));
    }
    private static Vec3[] ends(List<SelectionOutline.Segment> segments){
        var ends=new Vec3[segments.size()*2];int i=0;
        for(var s:segments){ends[i++]=new Vec3(s.x0(),s.y0(),s.z0());ends[i++]=new Vec3(s.x1(),s.y1(),s.z1());}
        return ends;
    }
    /** Every exterior rectangle as four corners, already in the order the overlay wants them. */
    private Vec3[][] quads(){
        var made=quads;
        if(made==null){
            made=new Vec3[outline.faces().size()][];int i=0;
            for(var f:outline.faces()){
                int[] lo={f.x0(),f.y0(),f.z0()},hi={f.x1(),f.y1(),f.z1()};
                int axis=f.axis(),u=(axis+1)%3,v=(axis+2)%3;
                int[][] ring={{lo[u],lo[v]},{hi[u],lo[v]},{hi[u],hi[v]},{lo[u],hi[v]}};
                Vec3[] c=new Vec3[4];
                for(int k=0;k<4;k++){double[] p=new double[3];p[axis]=lo[axis];p[u]=ring[k][0];p[v]=ring[k][1];c[k]=new Vec3(p[0],p[1],p[2]);}
                made[i++]=f.sign()>0?c:new Vec3[]{c[3],c[2],c[1],c[0]};
            }
            quads=made;
        }
        return made;
    }
    /** Whether the camera is inside the shape. Shapes with too many faces to be filled are not worked out, which keeps the frame cheap. */
    boolean cameraInside(){
        if(bounds==null||outline.faces().size()>maxFaces)return false;
        return inside(ClientCompat.camera(Minecraft.getInstance()).position());
    }
    /** Whether {@code eye}, relative to the shape, is inside it: a ray along +x crosses an odd number of the closed surface's faces. */
    private boolean inside(Vec3 eye){
        if(bounds==null||!bounds.contains(eye))return false;
        int crossed=0;
        for(var f:outline.faces())if(f.axis()==0&&f.x0()>eye.x&&eye.y>=f.y0()&&eye.y<f.y1()&&eye.z>=f.z0()&&eye.z<f.z1())crossed++;
        return (crossed&1)==1;
    }

    private Vec3 contourEye,contourAt;
    private List<ScreenSilhouette.Line> contourLines;
    private Vec3[] contourEnds;
    /**
     * Draws only the contour of the shape on the screen. Worked out again only when the camera or the shape moves.
     * Returns false when there is none to draw, so the caller falls back to the creases.
     */
    private boolean contour(Lines lines,Vec3 at){
        Vec3 eye=ClientCompat.camera(Minecraft.getInstance()).position();
        if(contourEye==null||contourEye.distanceToSqr(eye)>1e-8||!at.equals(contourAt)){
            contourEye=eye;contourAt=at;
            contourLines=ScreenSilhouette.of(outline,eye.x-at.x,eye.y-at.y,eye.z-at.z);
            if(contourLines==null)contourEnds=null;
            else{contourEnds=new Vec3[contourLines.size()*2];int i=0;for(var l:contourLines){contourEnds[i++]=new Vec3(l.x0(),l.y0(),l.z0()).add(at);contourEnds[i++]=new Vec3(l.x1(),l.y1(),l.z1()).add(at);}}
        }
        if(contourEnds==null||contourEnds.length==0)return false;
        for(int i=0;i<contourEnds.length;i+=2)lines.line(contourEnds[i],contourEnds[i+1]);
        return true;
    }

    /**
     * Draws the creases where one of the two faces points toward the camera and the other away, which is the outline of
     * the shape on the screen. Returns false when there are none, as when the camera is inside the shape.
     */
    private boolean silhouette(Lines lines,Vec3 at){
        Vec3 eye=ClientCompat.camera(Minecraft.getInstance()).position();
        int drawn=0;
        boolean here=at.equals(Vec3.ZERO);
        Vec3[] ready=null;
        if(here){ready=creaseEnds;if(ready==null)creaseEnds=ready=ends(outline.creases().stream().map(SelectionOutline.Crease::segment).toList());}
        int index=0;
        for(var crease:outline.creases()){
            var s=crease.segment();
            double mx=(s.x0()+s.x1())/2d+at.x,my=(s.y0()+s.y1())/2d+at.y,mz=(s.z0()+s.z1())/2d+at.z;
            if(towards(crease.normalA(),eye,mx,my,mz)!=towards(crease.normalB(),eye,mx,my,mz)){
                if(here)lines.line(ready[index*2],ready[index*2+1]);
                else lines.line(new Vec3(s.x0(),s.y0(),s.z0()).add(at),new Vec3(s.x1(),s.y1(),s.z1()).add(at));
                drawn++;
            }
            index++;
        }
        return drawn>0;
    }
    /** True when the face with this direction, on a plane through the point, faces the eye. */
    private static boolean towards(int direction,Vec3 eye,double x,double y,double z){
        int[] n=SelectionOutline.normal(direction);
        return n[0]*(eye.x-x)+n[1]*(eye.y-y)+n[2]*(eye.z-z)>0;
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
