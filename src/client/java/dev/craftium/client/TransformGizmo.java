package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Shared editor handles: world/local axes, centre and plane moves, rotation rings and scale nodes. */
final class TransformGizmo {
    private enum Kind { PLACEMENT,NODE,ENTITY,SELECTION }
    private record Target(Kind kind,Vec3 anchor,Entity entity){}
    private static final int[] COLORS={0xffff7777,0xff8bef9a,0xff78b9ff};
    private static Target dragging;
    private static int handle=-1;
    private static double startX,startY,startAngle;
    private static Vec3 delta=Vec3.ZERO,angles=Vec3.ZERO,size=new Vec3(1,1,1);
    private static Vec3 normalization=Vec3.ZERO;
    private static boolean normalizationDirty=true;
    private static Options.GizmoMode mode;
    private static Vec3[] basis;
    private TransformGizmo(){}
    static boolean available(EditorScreen screen){return target(screen)!=null;}
    static Entity selectedEntity(EditorScreen screen){Target t=target(screen);return t!=null&&t.kind==Kind.ENTITY?t.entity:null;}
    static boolean nudge(EditorScreen screen,Point step){
        if(ClientState.status.busy())return true;Target target=target(screen);if(target==null)return false;
        if(target.kind==Kind.PLACEMENT){PlacementPreview.origin=PlacementPreview.origin.add(step.x(),step.y(),step.z());return true;}
        if(target.kind==Kind.NODE){screen.moveHandle(screen.handlePoint().add(step.x(),step.y(),step.z()));return true;}
        Options original=ClientState.options,request=Packets.JSON.fromJson(Packets.JSON.toJson(original),Options.class);
        try{ClientState.options=request;request.yaw=request.pitch=request.roll=0;request.sizeX=request.sizeY=request.sizeZ=1;request.offsetX=step.x();request.offsetY=step.y();request.offsetZ=step.z();
            if(target.kind==Kind.ENTITY){double size=Minecraft.getInstance().hasShiftDown()?.0001:Minecraft.getInstance().hasControlDown()?1:1d/16;request.entityX=step.x()*size;request.entityY=step.y()*size;request.entityZ=step.z()*size;request.entityScaleX=request.entityScaleY=request.entityScaleZ=1;CraftiumClient.send("entity_gizmo",CraftiumClient.point(target.entity.blockPosition()),null);}
            else CraftiumClient.send("selection_gizmo",null,null);
        }finally{ClientState.options=original;}return true;
    }
    private static Target target(EditorScreen screen){
        if(PlacementPreview.active()){AABB bounds=bounds();Point p=PlacementPreview.origin;return new Target(Kind.PLACEMENT,new Vec3(p.x(),p.y(),p.z()).add(bounds.getCenter()),null);}
        Point node=screen.handlePoint();if(node!=null)return new Target(Kind.NODE,new Vec3(node.x(),node.y(),node.z()),null);
        var status=ClientState.status;var o=ClientState.options;if(o.gizmoTarget!=Options.GizmoTarget.ENTITY&&(o.tool==Tool.BOX_SELECT||o.gizmoTarget==Options.GizmoTarget.SELECTION)&&status.selectionSize()>0){Point min=status.min();return new Target(Kind.SELECTION,new Vec3(min.x(),min.y(),min.z()),null);}
        var mc=Minecraft.getInstance();if(mc.level!=null&&!ClientState.options.entityUuid.isBlank())for(var e:mc.level.entitiesForRendering())if(e.getUUID().toString().equals(ClientState.options.entityUuid)&&(e instanceof Display||e instanceof Marker))return new Target(Kind.ENTITY,e.position(),e);
        return null;
    }
    private static AABB bounds(){AABB bounds=PlacementPreview.shape.bounds();return bounds==null?new AABB(0,0,0,1,1,1):bounds;}
    private static Vec3[] axes(){var o=ClientState.options;Vec3[] result={new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)};if(o.gizmoLocal)for(int i=0;i<3;i++){var v=Geometry.rotate(new Geometry.Vector(result[i].x,result[i].y,result[i].z),o.gizmoYaw,o.gizmoPitch,o.gizmoRoll);result[i]=new Vec3(v.x(),v.y(),v.z());}return result;}
    private static double[] project(Vec3 at){var mc=Minecraft.getInstance();var camera=ClientCompat.camera(mc);Vec3 v=at.subtract(camera.position()),plane=ClientCompat.nearPlane(camera).getPointOnPlane(0,0);var near=ClientCompat.nearPlane(camera);Vec3 right=near.getPointOnPlane(1,0).subtract(plane).normalize(),up=near.getPointOnPlane(0,1).subtract(plane).normalize();double depth=v.dot(plane.normalize());if(depth<=.05)return null;double focal=mc.getWindow().getGuiScaledHeight()/(2*Math.tan(Math.toRadians(ClientCompat.fov(mc,camera))/2));return new double[]{mc.getWindow().getGuiScaledWidth()/2d+v.dot(right)*focal/depth,mc.getWindow().getGuiScaledHeight()/2d-v.dot(up)*focal/depth};}
    private static double distance(double x,double y,double[] a,double[] b){if(a==null||b==null)return Double.POSITIVE_INFINITY;double dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy,t=length<.001?0:Math.clamp(((x-a[0])*dx+(y-a[1])*dy)/length,0,1);return Math.pow(x-a[0]-dx*t,2)+Math.pow(y-a[1]-dy*t,2);}
    static boolean click(EditorScreen screen,double x,double y){
        if(ClientState.status.busy())return false;Target t=target(screen);if(t==null)return selectEntity(x,y);
        mode=t.kind==Kind.NODE?Options.GizmoMode.MOVE:ClientState.options.gizmoMode;basis=axes();pickScale=screenScale(t.anchor);double best=100;int chosen=-1;double[] center=project(t.anchor);
        if(mode!=Options.GizmoMode.ROTATE&&center!=null&&Math.pow(x-center[0],2)+Math.pow(y-center[1],2)<81)chosen=3;
        if(chosen<0)for(int i=0;i<3;i++){
            if(mode==Options.GizmoMode.ROTATE){Vec3 a=basis[(i+1)%3],b=basis[(i+2)%3];for(int j=0;j<96;j++){double v=j*Math.PI/48,w=(j+1)*Math.PI/48;double d=distance(x,y,project(t.anchor.add(a.scale(Math.cos(v)*3.5*pickScale)).add(b.scale(Math.sin(v)*3.5*pickScale))),project(t.anchor.add(a.scale(Math.cos(w)*3.5*pickScale)).add(b.scale(Math.sin(w)*3.5*pickScale))));if(d<best){best=d;chosen=i;}}}
            else{double d=distance(x,y,project(t.anchor.add(basis[i].scale((mode==Options.GizmoMode.SCALE?4.7:.5)*pickScale))),project(t.anchor.add(basis[i].scale(5*pickScale))));if(d<best){best=d;chosen=i;}}
        }
        if(mode==Options.GizmoMode.MOVE&&chosen<0)for(int i=0;i<3;i++){Vec3 p=t.anchor.add(basis[(i+1)%3].scale(1.4*pickScale)).add(basis[(i+2)%3].scale(1.4*pickScale));var pixel=project(p);if(pixel!=null&&Math.pow(x-pixel[0],2)+Math.pow(y-pixel[1],2)<144){chosen=4+i;break;}}
        if(chosen<0)return selectEntity(x,y);dragging=t;handle=chosen;startX=x;startY=y;delta=angles=Vec3.ZERO;size=new Vec3(1,1,1);startAngle=mode==Options.GizmoMode.ROTATE?angle(x,y,t.anchor,handle):0;return true;
    }
    private static boolean selectEntity(double x,double y){var mc=Minecraft.getInstance();if(mc.level==null)return false;Entity closest=null;double best=100;for(var e:mc.level.entitiesForRendering()){if(!(e instanceof Display)&&!(e instanceof Marker)||e.distanceToSqr(mc.player)>128*128)continue;if(e instanceof Display&&!ClientState.options.showDisplayGizmos||e instanceof Marker&&!ClientState.options.showMarkerGizmos)continue;var pixel=project(e.position());if(pixel==null)continue;double d=Math.pow(x-pixel[0],2)+Math.pow(y-pixel[1],2);if(d<best){best=d;closest=e;}}if(closest==null)return false;ClientState.options.entityUuid=closest.getUUID().toString();ClientState.options.gizmoTarget=Options.GizmoTarget.ENTITY;CraftiumClient.send("entity_select_uuid",CraftiumClient.point(closest.blockPosition()),null);return true;}
    private static double angle(double x,double y,Vec3 center,int axis){var mc=Minecraft.getInstance();var camera=ClientCompat.camera(mc);Vec3 ray=ClientCompat.nearPlane(camera).getPointOnPlane((float)(x/mc.getWindow().getGuiScaledWidth()*2-1),(float)(1-y/mc.getWindow().getGuiScaledHeight()*2)).normalize();double divisor=ray.dot(basis[axis]);if(Math.abs(divisor)<1e-5){var pixel=project(center);return pixel==null?0:Math.atan2(y-pixel[1],x-pixel[0]);}double distance=center.subtract(camera.position()).dot(basis[axis])/divisor;Vec3 v=camera.position().add(ray.scale(distance)).subtract(center);return Math.atan2(v.dot(basis[(axis+2)%3]),v.dot(basis[(axis+1)%3]));}
    private static double snap(double value,double increment){return Math.rint(value/increment)*increment;}
    static boolean drag(double x,double y){
        if(dragging==null)return false;normalizationDirty=true;var mc=Minecraft.getInstance();boolean entity=dragging.kind==Kind.ENTITY;double increment=entity?(mc.hasShiftDown()?.0001:mc.hasControlDown()?1:1d/16):1;
        if(mode==Options.GizmoMode.ROTATE){double degrees=Math.toDegrees(angle(x,y,dragging.anchor,handle)-startAngle);degrees=Math.IEEEremainder(degrees,360);degrees=snap(degrees,mc.hasShiftDown()?1:mc.hasControlDown()?15:entity||ClientState.options.unlockRotation?5:90);Vec3 vector=basis[handle].scale(degrees);angles=new Vec3(vector.x,vector.y,vector.z);return true;}
        var center=project(dragging.anchor);if(center==null)return true;
        if(handle==3&&mode==Options.GizmoMode.MOVE){var camera=ClientCompat.camera(mc);var near=ClientCompat.nearPlane(camera);double depth=dragging.anchor.distanceTo(camera.position());Vec3 from=near.getPointOnPlane((float)(startX/mc.getWindow().getGuiScaledWidth()*2-1),(float)(1-startY/mc.getWindow().getGuiScaledHeight()*2)).normalize(),to=near.getPointOnPlane((float)(x/mc.getWindow().getGuiScaledWidth()*2-1),(float)(1-y/mc.getWindow().getGuiScaledHeight()*2)).normalize();Vec3 move=to.subtract(from).scale(depth);delta=new Vec3(snap(move.x,increment),snap(move.y,increment),snap(move.z,increment));return true;}
        if(handle>=4){int a=(handle-4+1)%3,b=(handle-4+2)%3;double[] pa=project(dragging.anchor.add(basis[a])),pb=project(dragging.anchor.add(basis[b]));if(pa==null||pb==null)return true;double ax=pa[0]-center[0],ay=pa[1]-center[1],bx=pb[0]-center[0],by=pb[1]-center[1],det=ax*by-ay*bx;if(Math.abs(det)<.001)return true;double dx=x-startX,dy=y-startY;delta=basis[a].scale(snap((dx*by-dy*bx)/det,increment)).add(basis[b].scale(snap((dy*ax-dx*ay)/det,increment)));return true;}
        int axis=handle==3?1:handle;var end=project(dragging.anchor.add(basis[axis]));if(end==null)return true;double dx=end[0]-center[0],dy=end[1]-center[1],length=dx*dx+dy*dy;if(length<.001)return true;double amount=((x-startX)*dx+(y-startY)*dy)/length;
        if(mode==Options.GizmoMode.SCALE){double factor=Math.clamp(snap(1+amount/(5*pickScale),mc.hasShiftDown()?.0001:1d/16),.1,8);size=mc.hasControlDown()||handle==3?new Vec3(factor,factor,factor):new Vec3(axis==0?factor:1,axis==1?factor:1,axis==2?factor:1);}
        else delta=basis[axis].scale(snap(amount,increment));return true;
    }
    static boolean release(EditorScreen screen){
        if(dragging==null)return false;Target t=dragging;Options original=ClientState.options,request=Packets.JSON.fromJson(Packets.JSON.toJson(original),Options.class);try{
            if(t.kind==Kind.NODE){Point p=new Point((int)Math.round(t.anchor.x+delta.x),(int)Math.round(t.anchor.y+delta.y),(int)Math.round(t.anchor.z+delta.z));screen.moveHandle(p);return true;}
            request.yaw=angles.y;request.pitch=angles.x;request.roll=angles.z;request.sizeX=size.x;request.sizeY=size.y;request.sizeZ=size.z;request.offsetX=(int)Math.round(delta.x);request.offsetY=(int)Math.round(delta.y);request.offsetZ=(int)Math.round(delta.z);ClientState.options=request;
            if(t.kind==Kind.PLACEMENT){PlacementPreview.origin=PlacementPreview.origin.add(request.offsetX,request.offsetY,request.offsetZ);if(mode!=Options.GizmoMode.MOVE)CraftiumClient.send("clipboard_transform",null,null);}
            else if(t.kind==Kind.ENTITY){request.entityX=delta.x;request.entityY=delta.y;request.entityZ=delta.z;request.entityScaleX=size.x;request.entityScaleY=size.y;request.entityScaleZ=size.z;CraftiumClient.send("entity_gizmo",CraftiumClient.point(t.entity.blockPosition()),null);}
            else CraftiumClient.send("selection_gizmo",null,null);
            original.gizmoYaw=(original.gizmoYaw+angles.y)%360;original.gizmoPitch=(original.gizmoPitch+angles.x)%360;original.gizmoRoll=(original.gizmoRoll+angles.z)%360;
        }finally{ClientState.options=original;cancel();}return true;
    }
    static void cancel(){dragging=null;handle=-1;delta=angles=Vec3.ZERO;size=new Vec3(1,1,1);normalization=Vec3.ZERO;normalizationDirty=true;}
    private static Vec3 rawTransform(Vec3 world){Vec3 p=world.subtract(dragging.anchor);var rotated=Geometry.rotate(new Geometry.Vector(p.x*size.x,p.y*size.y,p.z*size.z),angles.y,angles.x,angles.z);return dragging.anchor.add(rotated.x(),rotated.y(),rotated.z());}
    private static Vec3 transformed(Vec3 world){if(dragging==null)return world;if(normalizationDirty){normalization=Vec3.ZERO;if(dragging.kind==Kind.PLACEMENT||dragging.kind==Kind.SELECTION){AABB bounds;if(dragging.kind==Kind.PLACEMENT){Point p=PlacementPreview.origin;bounds=bounds().move(p.x(),p.y(),p.z());}else{var status=ClientState.status;Point min=status.min(),max=status.max();bounds=new AABB(min.x(),min.y(),min.z(),max.x()+1,max.y()+1,max.z()+1);}double x=Double.POSITIVE_INFINITY,y=x,z=x;for(int i=0;i<8;i++){Vec3 corner=rawTransform(new Vec3((i&1)==0?bounds.minX:bounds.maxX,(i&2)==0?bounds.minY:bounds.maxY,(i&4)==0?bounds.minZ:bounds.maxZ));x=Math.min(x,corner.x);y=Math.min(y,corner.y);z=Math.min(z,corner.z);}normalization=new Vec3(bounds.minX-x,bounds.minY-y,bounds.minZ-z);}normalizationDirty=false;}return rawTransform(world).add(normalization).add(delta);}
    /** One outline segment of a selection being transformed, moved by the transform in progress. */
    static void line(Vec3 from,Vec3 to){Overlay.line(transformed(from),transformed(to),0xff8be3b5,1.5f);}
    static boolean dragging(){return dragging!=null;}
    static boolean selectionPreview(){return dragging!=null&&dragging.kind==Kind.SELECTION;}
    static void box(AABB box){if(dragging==null){Overlay.box(box,0xff8be3b5,1,0x2247d29b);return;}Vec3[] points=new Vec3[8];for(int i=0;i<8;i++)points[i]=transformed(new Vec3((i&1)==0?box.minX:box.maxX,(i&2)==0?box.minY:box.maxY,(i&4)==0?box.minZ:box.maxZ));for(int i=0;i<8;i++)for(int bit:new int[]{1,2,4})if((i&bit)==0)Overlay.line(points[i],points[i|bit],0xff8be3b5,1.5f);}
    static void draw(EditorScreen screen){
        Target t=dragging==null?target(screen):dragging;if(t==null)return;
        render(t.anchor.add(delta),t.kind==Kind.NODE?Options.GizmoMode.MOVE:ClientState.options.gizmoMode,handle);
    }
    /** The handles at {@code anchor} for show only, as in builder mode where they can't be dragged. */
    static void display(Vec3 anchor){render(anchor,ClientState.options.gizmoMode,-1);}
    private static final int CENTER=0xffffffff,CENTER_ACTIVE=0xffffd66d;
    /** The gizmo's scale at the last click, kept for the drag that follows so the handles do not shift under the cursor. */
    private static double pickScale=1;
    /** The distance, in blocks, at which the handles are drawn at the size they were designed for. */
    private static final double DESIGN_DISTANCE=8;
    /** How much to scale the gizmo so it covers the same part of the screen whatever its distance from the camera. */
    private static double screenScale(Vec3 anchor){
        double distance=ClientCompat.camera(Minecraft.getInstance()).position().distanceTo(anchor);
        return Math.clamp(distance/DESIGN_DISTANCE,.05,40);
    }
    /** Move handles end in flat triangular arrowheads, rotate handles are rings, scale handles are squares, and the centre is a square of its own. */
    private static void render(Vec3 anchor,Options.GizmoMode currentMode,int active){
        Vec3[] directions=axes();double k=screenScale(anchor);
        for(int i=0;i<3;i++){int color=COLORS[i];
            if(currentMode==Options.GizmoMode.ROTATE){Vec3 a=directions[(i+1)%3],b=directions[(i+2)%3];for(int j=0;j<96;j++){double x=j*Math.PI/48,y=(j+1)*Math.PI/48;Overlay.line(anchor.add(a.scale(Math.cos(x)*3.5*k)).add(b.scale(Math.sin(x)*3.5*k)),anchor.add(a.scale(Math.cos(y)*3.5*k)).add(b.scale(Math.sin(y)*3.5*k)),color,active==i?3:1.5f);}}
            else if(currentMode==Options.GizmoMode.SCALE){Vec3 end=anchor.add(directions[i].scale(5*k));Overlay.line(anchor,end,color,active==i?4:2);Overlay.box(new AABB(end.add(-.2*k,-.2*k,-.2*k),end.add(.2*k,.2*k,.2*k)),color,active==i?2:1,color&0x99ffffff|0x66000000);}
            else{Vec3 tip=anchor.add(directions[i].scale(5.3*k)),base=anchor.add(directions[i].scale(4.5*k));Overlay.line(anchor,base,color,active==i?4:2);arrowhead(tip,base,color,active==i?3:2,k);}
            if(currentMode==Options.GizmoMode.MOVE){Vec3 a=directions[(i+1)%3],b=directions[(i+2)%3],point=anchor.add(a.scale(1.4*k)).add(b.scale(1.4*k));Overlay.point(point,color,7);}
        }
        if(currentMode!=Options.GizmoMode.ROTATE){int color=active==3?CENTER_ACTIVE:CENTER;Overlay.box(new AABB(anchor.add(-.17*k,-.17*k,-.17*k),anchor.add(.17*k,.17*k,.17*k)),color,2,color&0x00ffffff|0x88000000);}
    }
    /** A flat triangle from {@code base} to {@code tip}, turned to face the camera so it reads the same from any angle. */
    private static void arrowhead(Vec3 tip,Vec3 base,int color,float width,double k){
        Vec3 axis=tip.subtract(base),toCamera=ClientCompat.camera(Minecraft.getInstance()).position().subtract(base);
        Vec3 side=axis.cross(toCamera);
        if(side.lengthSqr()<1e-9)return; // looking straight down the axis: nothing to show but the tip
        side=side.normalize().scale(.3*k);
        Vec3 left=base.add(side),right=base.subtract(side);
        Overlay.line(tip,left,color,width);Overlay.line(left,right,color,width);Overlay.line(right,tip,color,width);
        int fill=color&0x00ffffff|0x99000000;
        Overlay.face(tip,left,right,right,fill);Overlay.face(right,left,tip,tip,fill); // both windings, so culling cannot hide it
    }
}
