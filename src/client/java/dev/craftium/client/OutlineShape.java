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
    void draw(Vec3 at,Lines lines,int stroke,int fill,boolean withFill){draw(at,lines,stroke,fill,withFill,ClientState.options.selectionStyle);}
    /** As {@link #draw(Vec3,Lines,int,int,boolean)}, with the outline style chosen by the caller instead of the selection's. */
    void draw(Vec3 at,Lines lines,int stroke,int fill,boolean withFill,Options.SelectionStyle style){draw(at,lines,null,stroke,fill,withFill,style);}
    /**
     * The selection's look, shared by whatever else wants it: the silhouette twice as wide and fully opaque over the shape's edges, which stay in view
     * but thinner and fainter. With {@code scene} the lines are depth-tested, so blocks in front hide them. The selection's style decides what is shown.
     */
    void drawEmphasised(Vec3 at,int colour,float width,int fill,boolean withFill,boolean scene){draw(at,colour,width,fill,withFill,scene,true);}
    /**
     * The selection's style with nothing added: the silhouette (or the outline) at the plain line width and no extra edges. All edges is still
     * depth-tested. With {@code scene} the lines are hidden by blocks in front of them.
     */
    void drawPlain(Vec3 at,int colour,float width,int fill,boolean withFill,boolean scene){draw(at,colour,width,fill,withFill,scene,false);}
    private void draw(Vec3 at,int colour,float width,int fill,boolean withFill,boolean scene,boolean emphasise){
        var style=ClientState.options.selectionStyle;
        if(style==Options.SelectionStyle.ALL_EDGES){
            // Every edge, tested against depth so the ones facing you are drawn in full; the ones behind blocks show through at a quarter of the opacity.
            int back=(colour&0xffffff)|((colour>>>24)/4)<<24;
            draw(at,(a,b)->sceneLine(a,b,colour,width),(a,b)->Overlay.line(a,b,back,width),colour,fill,withFill,style);
            return;
        }
        if(!emphasise){
            draw(at,scene?(a,b)->sceneLine(a,b,colour,width):(a,b)->Overlay.line(a,b,colour,width),colour,fill,withFill,style);
            return;
        }
        int faint=(colour&0xffffff)|((colour>>>24)*2/5)<<24;float thin=Math.max(.5f,width*.6f);
        Lines strong=scene?(a,b)->sceneLine(a,b,colour,width*2):(a,b)->Overlay.line(a,b,colour,width*2);
        Lines soft=scene?(a,b)->sceneLine(a,b,faint,thin):(a,b)->Overlay.line(a,b,faint,thin);
        draw(at,strong,soft,colour,fill,withFill,style);
    }
    /** A depth-tested line, drawn a little toward the camera: the outline lies in the surface of the blocks, so exactly there it would be cut away by them. */
    private static void sceneLine(Vec3 a,Vec3 b,int colour,float width){
        Vec3 eye=ClientCompat.camera(Minecraft.getInstance()).position(),mid=a.add(b).scale(.5),toEye=eye.subtract(mid);
        double distance=toEye.length();
        Vec3 nudge=distance<1e-6?Vec3.ZERO:toEye.scale((.004+.0006*distance)/distance);
        Overlay.sceneLine(a.add(nudge),b.add(nudge),colour,width);
    }
    /**
     * As above, and with the Silhouette style every edge of the outline also goes to {@code edges} (when given), beneath the contour, so the
     * edges stay in view while the silhouette, drawn through {@code lines}, is the stronger of the two.
     */
    void draw(Vec3 at,Lines lines,Lines edges,int stroke,int fill,boolean withFill,Options.SelectionStyle style){
        if(bounds==null)return;
        if(outline.edges().size()>MAX_EDGES){
            AABB box=bounds.move(at);
            if(withFill)Overlay.box(box,stroke,1,fill);else boxLines(box,lines);
            return;
        }
        // Only the outline as the camera sees it, unless every edge was asked for or the camera is inside the shape.
        if(edges!=null&&style!=Options.SelectionStyle.OUTLINE)for(var s:outline.edges())edges.line(new Vec3(s.x0(),s.y0(),s.z0()).add(at),new Vec3(s.x1(),s.y1(),s.z1()).add(at));
        if(style==Options.SelectionStyle.ALL_EDGES||!(style==Options.SelectionStyle.SILHOUETTE&&contour(lines,at))&&!silhouette(lines,at))
            for(var s:outline.edges())lines.line(new Vec3(s.x0(),s.y0(),s.z0()).add(at),new Vec3(s.x1(),s.y1(),s.z1()).add(at));
        if(withFill&&outline.faces().size()<=MAX_FACES)for(var f:outline.faces())face(f,at,fill);
    }

    private Vec3 contourEye,contourAt;
    private List<ScreenSilhouette.Line> contourLines;
    /**
     * Draws only the contour of the shape on the screen. Worked out again only when the camera or the shape moves.
     * Returns false when there is none to draw, so the caller falls back to the creases.
     */
    private boolean contour(Lines lines,Vec3 at){
        Vec3 eye=ClientCompat.camera(Minecraft.getInstance()).position();
        if(contourEye==null||contourEye.distanceToSqr(eye)>1e-8||!at.equals(contourAt)){
            contourEye=eye;contourAt=at;
            contourLines=ScreenSilhouette.of(outline,eye.x-at.x,eye.y-at.y,eye.z-at.z);
        }
        if(contourLines==null||contourLines.isEmpty())return false;
        for(var l:contourLines)lines.line(new Vec3(l.x0(),l.y0(),l.z0()).add(at),new Vec3(l.x1(),l.y1(),l.z1()).add(at));
        return true;
    }

    /**
     * Draws the creases where one of the two faces points toward the camera and the other away, which is the outline of
     * the shape on the screen. Returns false when there are none, as when the camera is inside the shape.
     */
    private boolean silhouette(Lines lines,Vec3 at){
        Vec3 eye=ClientCompat.camera(Minecraft.getInstance()).position();
        int drawn=0;
        for(var crease:outline.creases()){
            var s=crease.segment();
            double mx=(s.x0()+s.x1())/2d+at.x,my=(s.y0()+s.y1())/2d+at.y,mz=(s.z0()+s.z1())/2d+at.z;
            if(towards(crease.normalA(),eye,mx,my,mz)!=towards(crease.normalB(),eye,mx,my,mz)){
                lines.line(new Vec3(s.x0(),s.y0(),s.z0()).add(at),new Vec3(s.x1(),s.y1(),s.z1()).add(at));drawn++;
            }
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
