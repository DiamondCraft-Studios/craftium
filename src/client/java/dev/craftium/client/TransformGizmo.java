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
    /** MODEL is the selected blocks under the Move, Rotate and Scale tools, which turn about the pivot; PIVOT is the pivot itself. */
    private enum Kind { PLACEMENT,NODE,ENTITY,SELECTION,MODEL,PIVOT,SHAPE }
    /** SHAPE is the Shape tool's unbuilt shape. Like MODEL it is measured in blocks, but it is made again from a new pose, never resampled. */
    private static boolean blocky(Kind kind){return kind==Kind.MODEL||kind==Kind.SHAPE;}
    /** True while the handles are the shape's own axes, which is when the Scale tool works on it. */
    private static boolean localBasis;
    /** The shape is scaled about its centre, and so are blocks with Alt held, so the handle you drag meets the pointer at half the move. */
    private static boolean centred(){return altDown()||dragging!=null&&dragging.kind==Kind.SHAPE;}
    /** The shape's pose with the drag in progress applied, or {@code base} when none is. */
    static ShapeObject.Pose shapeDuringDrag(ShapeObject.Pose base){
        return dragging!=null&&dragging.kind==Kind.SHAPE?ShapeObject.apply(base,mode,delta,angles,size):base;
    }
    private record Target(Kind kind,Vec3 anchor,Entity entity){}
    private static final int[] COLORS={0xffff7777,0xff8bef9a,0xff78b9ff};
    private static Target dragging;
    private static int handle=-1;
    private static double startX,startY,startAngle;
    /** Where the Move tool takes the pointer to be: it follows the real one, except for an entity, where holding Ctrl halves the speed for fine placement. */
    private static double slowX,slowY,lastX,lastY;
    private static Vec3 delta=Vec3.ZERO,angles=Vec3.ZERO,size=new Vec3(1,1,1);
    /**
     * Where a Move drag has taken the handle, before it is rounded to the grid. The blocks follow {@code delta}, which is
     * rounded, but the handle follows this, so it moves with the pointer instead of jumping a block at a time. Holding Ctrl
     * rounds it too. It equals {@code delta} when nothing is moving.
     */
    private static Vec3 smooth=Vec3.ZERO;
    /** Records a Move drag: the blocks go to {@code snapped}, and the handle to {@code free}, or to {@code snapped} as well with Ctrl held (entities, which have their own fine steps, always follow {@code snapped}). */
    private static void moveTo(Vec3 free,Vec3 snapped){
        delta=snapped;
        smooth=dragging.kind==Kind.ENTITY||Minecraft.getInstance().hasControlDown()?snapped:free;
    }
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
        if(target.kind==Kind.NODE){screen.moveHandle(screen.handlePoint().add(step.x(),step.y(),step.z()));screen.handlesMoved();return true;} // a box corner moved by a step reshapes the selection too
        if(target.kind==Kind.SHAPE){ShapeObject.move(step);return true;}
        Options original=ClientState.options,request=Packets.JSON.fromJson(Packets.JSON.toJson(original),Options.class);
        try{ClientState.options=request;request.yaw=request.pitch=request.roll=0;request.sizeX=request.sizeY=request.sizeZ=1;request.offsetX=step.x();request.offsetY=step.y();request.offsetZ=step.z();
            if(target.kind==Kind.ENTITY){double size=Minecraft.getInstance().hasShiftDown()?.0001:Minecraft.getInstance().hasControlDown()?1:1d/16;request.entityX=step.x()*size;request.entityY=step.y()*size;request.entityZ=step.z()*size;request.entityScaleX=request.entityScaleY=request.entityScaleZ=1;CraftiumClient.send("entity_gizmo",CraftiumClient.point(target.entity.blockPosition()),null);}
            else CraftiumClient.send("selection_gizmo",null,null);
        }finally{ClientState.options=original;}return true;
    }
    private static Target target(EditorScreen screen){
        if(PlacementPreview.active()){AABB bounds=bounds();Point p=PlacementPreview.origin;return new Target(Kind.PLACEMENT,new Vec3(p.x(),p.y(),p.z()).add(bounds.getCenter()),null);}
        Point node=screen.handlePoint();if(node!=null)return new Target(Kind.NODE,nodeAnchor(node,screen.handleIndex()),null);
        if(ShapeObject.gizmoOn())return new Target(Kind.SHAPE,ShapeObject.centre(),null);
        var status=ClientState.status;var o=ClientState.options;
        if(o.tool.transform()){
            if(o.tool==Tool.PIVOT)return new Target(Kind.PIVOT,pivotAnchor(screen),null);
            return status.selectionSize()>0?new Target(Kind.MODEL,pivot(),null):null;
        }
        if(o.gizmoTarget!=Options.GizmoTarget.ENTITY&&(o.tool==Tool.BOX_SELECT||o.gizmoTarget==Options.GizmoTarget.SELECTION)&&status.selectionSize()>0){Point min=status.min();return new Target(Kind.SELECTION,new Vec3(min.x(),min.y(),min.z()),null);}
        var mc=Minecraft.getInstance();if(mc.level!=null&&!ClientState.options.entityUuid.isBlank())for(var e:mc.level.entitiesForRendering())if(e.getUUID().toString().equals(ClientState.options.entityUuid)&&(e instanceof Display||e instanceof Marker))return new Target(Kind.ENTITY,e.position(),e);
        return null;
    }
    private static AABB bounds(){AABB bounds=PlacementPreview.shape.bounds();return bounds==null?new AABB(0,0,0,1,1,1):bounds;}
    private static Vec3[] axes(){var o=ClientState.options;if(localBasis&&ShapeObject.active())return ShapeObject.axes();Vec3[] result={new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)};if(o.gizmoLocal)for(int i=0;i<3;i++){var v=Geometry.rotate(new Geometry.Vector(result[i].x,result[i].y,result[i].z),o.gizmoYaw,o.gizmoPitch,o.gizmoRoll);result[i]=new Vec3(v.x(),v.y(),v.z());}return result;}
    private static double[] project(Vec3 at){var mc=Minecraft.getInstance();var camera=ClientCompat.camera(mc);Vec3 v=at.subtract(camera.position()),plane=ClientCompat.nearPlane(camera).getPointOnPlane(0,0);var near=ClientCompat.nearPlane(camera);Vec3 right=near.getPointOnPlane(1,0).subtract(plane).normalize(),up=near.getPointOnPlane(0,1).subtract(plane).normalize();double depth=v.dot(plane.normalize());if(depth<=.05)return null;double focal=mc.getWindow().getGuiScaledHeight()/(2*Math.tan(Math.toRadians(ClientCompat.fov(mc,camera))/2));return new double[]{mc.getWindow().getGuiScaledWidth()/2d+v.dot(right)*focal/depth,mc.getWindow().getGuiScaledHeight()/2d-v.dot(up)*focal/depth};}
    private static double distance(double x,double y,double[] a,double[] b){if(a==null||b==null)return Double.POSITIVE_INFINITY;double dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy,t=length<.001?0:Math.clamp(((x-a[0])*dx+(y-a[1])*dy)/length,0,1);return Math.pow(x-a[0]-dx*t,2)+Math.pow(y-a[1]-dy*t,2);}
    static boolean click(EditorScreen screen,double x,double y){
        if(ClientState.status.busy())return false;Target t=target(screen);if(t==null)return selectEntity(x,y);
        int chosen=pick(t,x,y);
        if(chosen<0)return selectEntity(x,y);dragging=t;handle=chosen;startX=slowX=lastX=x;startY=slowY=lastY=y;if(chosen==3&&mode==Options.GizmoMode.MOVE)grabFree(t.anchor,x,y);dragFlips=t.kind==Kind.NODE?flipsFor(t.anchor):null;delta=smooth=angles=Vec3.ZERO;size=new Vec3(1,1,1);startAngle=mode==Options.GizmoMode.ROTATE?(chosen==7?screenAngle(x,y,t.anchor):chosen<3?angle(x,y,t.anchor,handle):0):0;return true;
    }
    /** Whether a click here would grab a handle of the gizmo, without grabbing it. */
    static boolean hits(EditorScreen screen,double x,double y){
        if(ClientState.status.busy())return false;Target t=target(screen);return t!=null&&pick(t,x,y)>=0;
    }
    /** Which handle of the gizmo is under the pointer, or -1: 0 to 2 are the axes, 3 the centre, 4 to 6 the plane handles. */
    private static int pick(Target t,double x,double y){
        mode=effectiveMode(t);localBasis=t.kind==Kind.SHAPE&&mode==Options.GizmoMode.SCALE;basis=axesAt(t.anchor);pickScale=screenScale(t.anchor);pickExtent=extent(t);pickReach=reach(t,pickExtent);double best=100;int chosen=-1;double[] center=project(t.anchor);
        boolean plain=t.kind==Kind.MODEL||t.kind==Kind.PIVOT||t.kind==Kind.SHAPE&&ClientState.options.tool.transform(); // the transform tools show only their own handles
        // The centre: free move, even scaling for the Scale tool's circle; Rotate has the trackball instead, below.
        if((!plain||mode==Options.GizmoMode.SCALE&&blocky(t.kind))&&mode!=Options.GizmoMode.ROTATE&&center!=null&&Math.pow(x-center[0],2)+Math.pow(y-center[1],2)<81)chosen=3;
        if(chosen<0)for(int i=0;i<3;i++){
            if(mode==Options.GizmoMode.ROTATE){Vec3 a=basis[(i+1)%3],b=basis[(i+2)%3];for(int j=0;j<96;j++){double v=j*Math.PI/48,w=(j+1)*Math.PI/48;double d=distance(x,y,project(t.anchor.add(a.scale(Math.cos(v)*3.5*pickScale)).add(b.scale(Math.sin(v)*3.5*pickScale))),project(t.anchor.add(a.scale(Math.cos(w)*3.5*pickScale)).add(b.scale(Math.sin(w)*3.5*pickScale))));if(d<best){best=d;chosen=i;}}}
            else if(mode==Options.GizmoMode.SCALE&&pickExtent!=null){var pixel=project(faceCenter(faceBase(t),basis,pickExtent,i));double d=pixel==null?Double.POSITIVE_INFINITY:Math.pow(x-pixel[0],2)+Math.pow(y-pixel[1],2);if(d<144&&d<best){best=d;chosen=i;}}
            else{double d=distance(x,y,project(t.anchor.add(basis[i].scale((mode==Options.GizmoMode.SCALE?4.7:.5)*pickScale))),project(t.anchor.add(basis[i].scale(5*pickScale))));if(d<best){best=d;chosen=i;}}
        }
        if(mode==Options.GizmoMode.ROTATE&&chosen<0&&center!=null){
            // The ring that faces the camera turns about the view; inside the sphere is a trackball.
            Vec3[] cam=cameraAxes();
            var ring=project(t.anchor.add(cam[0].scale(4.4*pickScale)));var disc=project(t.anchor.add(cam[0].scale(3.5*pickScale)));
            double d=Math.hypot(x-center[0],y-center[1]);
            if(ring!=null&&Math.abs(d-Math.hypot(ring[0]-center[0],ring[1]-center[1]))<10)chosen=7;
            else if(disc!=null&&d<Math.hypot(disc[0]-center[0],disc[1]-center[1]))chosen=3;
        }
        if((mode==Options.GizmoMode.MOVE||mode==Options.GizmoMode.SCALE)&&chosen<0)for(int i=0;i<3;i++){Vec3 p=t.anchor.add(basis[(i+1)%3].scale(1.4*pickScale)).add(basis[(i+2)%3].scale(1.4*pickScale));var pixel=project(p);if(pixel!=null&&Math.pow(x-pixel[0],2)+Math.pow(y-pixel[1],2)<144){chosen=4+i;break;}}
        return chosen;
    }
    private static boolean selectEntity(double x,double y){var mc=Minecraft.getInstance();if(mc.level==null)return false;Entity closest=null;double best=100;for(var e:mc.level.entitiesForRendering()){if(!(e instanceof Display)&&!(e instanceof Marker)||e.distanceToSqr(mc.player)>128*128)continue;if(e instanceof Display&&!ClientState.options.showDisplayGizmos||e instanceof Marker&&!ClientState.options.showMarkerGizmos)continue;var pixel=project(e.position());if(pixel==null)continue;double d=Math.pow(x-pixel[0],2)+Math.pow(y-pixel[1],2);if(d<best){best=d;closest=e;}}if(closest==null)return false;ClientState.options.entityUuid=closest.getUUID().toString();ClientState.options.gizmoTarget=Options.GizmoTarget.ENTITY;CraftiumClient.send("entity_select_uuid",CraftiumClient.point(closest.blockPosition()),null);return true;}
    private static double angle(double x,double y,Vec3 center,int axis){var mc=Minecraft.getInstance();var camera=ClientCompat.camera(mc);Vec3 ray=ClientCompat.ray(camera,(float)(x/mc.getWindow().getGuiScaledWidth()*2-1),(float)(1-y/mc.getWindow().getGuiScaledHeight()*2));double divisor=ray.dot(basis[axis]);if(Math.abs(divisor)<1e-5){var pixel=project(center);return pixel==null?0:Math.atan2(y-pixel[1],x-pixel[0]);}double distance=center.subtract(camera.position()).dot(basis[axis])/divisor;Vec3 v=camera.position().add(ray.scale(distance)).subtract(center);return Math.atan2(v.dot(basis[(axis+2)%3]),v.dot(basis[(axis+1)%3]));}
    private static double snap(double value,double increment){return Math.rint(value/increment)*increment;}
    static boolean drag(double x,double y){
        if(dragging==null)return false;normalizationDirty=true;var mc=Minecraft.getInstance();boolean entity=dragging.kind==Kind.ENTITY;double increment=entity?(mc.hasShiftDown()?.0001:mc.hasControlDown()?1:1d/16):1;
        if(mode==Options.GizmoMode.MOVE){ // for an entity Ctrl halves the speed of a move: what the pointer travelled while it was held counts half
            double speed=entity&&mc.hasControlDown()?.5:1;slowX+=(x-lastX)*speed;slowY+=(y-lastY)*speed;lastX=x;lastY=y;x=slowX;y=slowY;
        }
        if(mode==Options.GizmoMode.ROTATE&&handle==3){ // trackball: a drag turns the blocks as if they were grabbed
            Vec3[] cam=cameraAxes();
            double sideways=Math.toRadians((x-startX)*.6),vertical=Math.toRadians((y-startY)*.6);
            setRotation(multiply(axisAngle(cam[1],sideways),axisAngle(cam[0],vertical)));
            return true;
        }
        if(mode==Options.GizmoMode.ROTATE&&handle==7){ // the ring facing the camera: turn about the view
            double view=Math.IEEEremainder(Math.toDegrees(screenAngle(x,y,dragging.anchor)-startAngle),360);
            view=snap(view,mc.hasShiftDown()?1:mc.hasControlDown()?5:blocky(dragging.kind)?15:5);
            setRotation(axisAngle(cameraAxes()[2],Math.toRadians(view)));
            return true;
        }
        if(mode==Options.GizmoMode.ROTATE){double degrees=Math.toDegrees(angle(x,y,dragging.anchor,handle)-startAngle);degrees=Math.IEEEremainder(degrees,360);degrees=snap(degrees,mc.hasShiftDown()?1:mc.hasControlDown()?(blocky(dragging.kind)?5:15):entity||ClientState.options.unlockRotation?5:blocky(dragging.kind)?15:90);Vec3 vector=basis[handle].scale(degrees);angles=new Vec3(vector.x,vector.y,vector.z);return true;}
        var center=project(dragging.anchor);if(center==null)return true;
        if(handle==3&&mode==Options.GizmoMode.MOVE){
            // The handle stays under the pointer at the depth it was grabbed, so it goes along when the camera does.
            Vec3 point=freePoint(ClientCompat.camera(mc).position(),pointerRay(x,y));
            Vec3 move=point.subtract(dragging.anchor);
            moveTo(move,new Vec3(snap(move.x,increment),snap(move.y,increment),snap(move.z,increment)));return true;
        }
        if(handle>=4&&mode==Options.GizmoMode.SCALE){ // a square in a plane scales the two axes of that plane together
            int a=(handle-4+1)%3,b=(handle-4+2)%3;
            double[] pa=project(dragging.anchor.add(basis[a])),pb=project(dragging.anchor.add(basis[b]));if(pa==null||pb==null)return true;
            double sx=(pa[0]-center[0])+(pb[0]-center[0]),sy=(pa[1]-center[1])+(pb[1]-center[1]),span=sx*sx+sy*sy;if(span<.001)return true;
            double amount=((x-startX)*sx+(y-startY)*sy)/span; // blocks along each of the two axes
            double[] factor={1,1,1};
            if(blocky(dragging.kind)&&pickExtent!=null){
                double[] blocks={pickExtent.x,pickExtent.y,pickExtent.z};
                for(int axis:new int[]{a,b})factor[axis]=gridFactor(blocks[axis],Math.max(1,blocks[axis])+amount*(centred()?2:1),mc.hasControlDown());
            }else{double all=Math.clamp(snap(1+amount/(5*pickScale),1d/16),.1,8);factor[a]=factor[b]=all;}
            size=new Vec3(factor[0],factor[1],factor[2]);
            return true;
        }
        if(mode==Options.GizmoMode.SCALE&&handle==3){ // the circle in the middle scales every axis: drag up to grow
            double all=Math.clamp(1+(startY-y)/120d,.05,8);
            if(blocky(dragging.kind)&&pickExtent!=null){
                boolean smooth=mc.hasControlDown();
                size=new Vec3(gridFactor(pickExtent.x,pickExtent.x*all,smooth),gridFactor(pickExtent.y,pickExtent.y*all,smooth),gridFactor(pickExtent.z,pickExtent.z*all,smooth));
            }else size=new Vec3(all,all,all);
            return true;
        }
        if(handle>=4){int a=(handle-4+1)%3,b=(handle-4+2)%3;double[] pa=project(dragging.anchor.add(basis[a])),pb=project(dragging.anchor.add(basis[b]));if(pa==null||pb==null)return true;double ax=pa[0]-center[0],ay=pa[1]-center[1],bx=pb[0]-center[0],by=pb[1]-center[1],det=ax*by-ay*bx;if(Math.abs(det)<.001)return true;double dx=x-startX,dy=y-startY;double u=(dx*by-dy*bx)/det,v=(dy*ax-dx*ay)/det;moveTo(basis[a].scale(u).add(basis[b].scale(v)),basis[a].scale(snap(u,increment)).add(basis[b].scale(snap(v,increment))));return true;}
        int axis=handle==3?1:handle;var end=project(dragging.anchor.add(basis[axis]));if(end==null)return true;double dx=end[0]-center[0],dy=end[1]-center[1],length=dx*dx+dy*dy;if(length<.001)return true;double amount=((x-startX)*dx+(y-startY)*dy)/length;
        if(mode==Options.GizmoMode.SCALE&&blocky(dragging.kind)&&pickExtent!=null){
            // The handle sits on a face, so the face follows the pointer: the far side stays put, or with Alt the centre does.
            // The size is a whole number of blocks unless Ctrl is held, which allows any size.
            double n=Math.max(1,new double[]{pickExtent.x,pickExtent.y,pickExtent.z}[axis]);
            double target=n+amount*(centred()?2:1);
            if(!mc.hasControlDown())target=Math.rint(target);
            double factor=Math.clamp(Math.max(target,mc.hasControlDown()?.1:1)/n,.01,64);
            size=new Vec3(axis==0?factor:1,axis==1?factor:1,axis==2?factor:1);
            return true;
        }
        if(mode==Options.GizmoMode.SCALE){double reach=pickReach==null?5*pickScale:Math.max(1,new double[]{pickReach.x,pickReach.y,pickReach.z}[axis]);double factor=Math.clamp(snap(1+amount/reach,mc.hasShiftDown()?.0001:1d/16),.1,8);size=mc.hasControlDown()||handle==3?new Vec3(factor,factor,factor):new Vec3(axis==0?factor:1,axis==1?factor:1,axis==2?factor:1);}
        else moveTo(basis[axis].scale(amount),basis[axis].scale(snap(amount,increment)));return true;
    }
    static boolean release(EditorScreen screen){
        if(dragging==null)return false;Target t=dragging;Options original=ClientState.options,request=Packets.JSON.fromJson(Packets.JSON.toJson(original),Options.class);try{
            if(t.kind==Kind.NODE){double shift=nodeOffset();Point p=dragFlips!=null?cornerFrom(t.anchor.add(delta),dragFlips):new Point((int)Math.round(t.anchor.x+delta.x-shift),(int)Math.round(t.anchor.y+delta.y-shift),(int)Math.round(t.anchor.z+delta.z-shift));screen.moveHandle(p);screen.handlesMoved();return true;}
            if(t.kind==Kind.SHAPE){ShapeObject.set(ShapeObject.apply(ShapeObject.pose(),mode,delta,angles,size));return true;} // the shape is made again from its new pose
            if(t.kind==Kind.PIVOT){original.pivotSet=true;original.pivotX=t.anchor.x+delta.x;original.pivotY=t.anchor.y+delta.y;original.pivotZ=t.anchor.z+delta.z;return true;}
            request.yaw=angles.y;request.pitch=angles.x;request.roll=angles.z;request.sizeX=size.x;request.sizeY=size.y;request.sizeZ=size.z;request.offsetX=(int)Math.round(delta.x);request.offsetY=(int)Math.round(delta.y);request.offsetZ=(int)Math.round(delta.z);ClientState.options=request;
            if(t.kind==Kind.PLACEMENT){PlacementPreview.origin=PlacementPreview.origin.add(request.offsetX,request.offsetY,request.offsetZ);if(mode!=Options.GizmoMode.MOVE)CraftiumClient.send("clipboard_transform",null,null);}
            else if(t.kind==Kind.ENTITY){request.entityX=delta.x;request.entityY=delta.y;request.entityZ=delta.z;request.entityScaleX=size.x;request.entityScaleY=size.y;request.entityScaleZ=size.z;CraftiumClient.send("entity_gizmo",CraftiumClient.point(t.entity.blockPosition()),null);}
            else if(t.kind==Kind.MODEL){
                if(request.offsetX==0&&request.offsetY==0&&request.offsetZ==0&&angles.equals(Vec3.ZERO)&&size.equals(new Vec3(1,1,1)))return true; // nothing was changed
                Vec3 about=mode==Options.GizmoMode.SCALE?aboutPoint():t.anchor;
                request.turnBlocks=true; // stairs and other oriented blocks turn with the selection
                request.aboutSet=true;request.aboutX=about.x;request.aboutY=about.y;request.aboutZ=about.z;
                CraftiumClient.send("selection_transform",null,null);
                // The pivot goes with the blocks when they are moved.
                // A pivot the player set goes with the blocks when they are moved; the selection's centre follows by itself.
                if(original.pivotSet){original.pivotX+=delta.x;original.pivotY+=delta.y;original.pivotZ+=delta.z;}
            }
            else CraftiumClient.send("selection_gizmo",null,null);
            original.gizmoYaw=(original.gizmoYaw+angles.y)%360;original.gizmoPitch=(original.gizmoPitch+angles.x)%360;original.gizmoRoll=(original.gizmoRoll+angles.z)%360;
        }finally{ClientState.options=original;cancel();}return true;
    }
    static void cancel(){dragging=null;dragFlips=null;handle=-1;delta=smooth=angles=Vec3.ZERO;size=new Vec3(1,1,1);normalization=Vec3.ZERO;normalizationDirty=true;}
    private static Vec3 rawTransform(Vec3 world){Vec3 about=aboutPoint();Vec3 p=world.subtract(about);var rotated=Geometry.rotate(new Geometry.Vector(p.x*size.x,p.y*size.y,p.z*size.z),angles.y,angles.x,angles.z);return about.add(rotated.x(),rotated.y(),rotated.z());}
    /**
     * The point the drag works about. The Scale tool scales from the far side of the selection, so a face moves to where it
     * is dragged and the opposite one stays; with Alt, or by the circle in the middle, it scales from the centre of the selection.
     * Everything else works about the target's anchor.
     */
    private static Vec3 aboutPoint(){
        if(dragging.kind==Kind.MODEL&&mode==Options.GizmoMode.SCALE){
            var s=ClientState.status;Point min=s.min(),max=s.max();
            return altDown()||handle==3?new Vec3((min.x()+max.x()+1)/2d,(min.y()+max.y()+1)/2d,(min.z()+max.z()+1)/2d):new Vec3(min.x(),min.y(),min.z());
        }
        return dragging.anchor;
    }
    private static boolean altDown(){
        var window=Minecraft.getInstance().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT)||com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT);
    }
    private static Vec3 transformed(Vec3 world){if(dragging==null)return world;if(dragging.kind==Kind.MODEL)return rawTransform(world).add(delta);if(normalizationDirty){normalization=Vec3.ZERO;if(dragging.kind==Kind.PLACEMENT||dragging.kind==Kind.SELECTION){AABB bounds;if(dragging.kind==Kind.PLACEMENT){Point p=PlacementPreview.origin;bounds=bounds().move(p.x(),p.y(),p.z());}else{var status=ClientState.status;Point min=status.min(),max=status.max();bounds=new AABB(min.x(),min.y(),min.z(),max.x()+1,max.y()+1,max.z()+1);}double x=Double.POSITIVE_INFINITY,y=x,z=x;for(int i=0;i<8;i++){Vec3 corner=rawTransform(new Vec3((i&1)==0?bounds.minX:bounds.maxX,(i&2)==0?bounds.minY:bounds.maxY,(i&4)==0?bounds.minZ:bounds.maxZ));x=Math.min(x,corner.x);y=Math.min(y,corner.y);z=Math.min(z,corner.z);}normalization=new Vec3(bounds.minX-x,bounds.minY-y,bounds.minZ-z);}normalizationDirty=false;}return rawTransform(world).add(normalization).add(delta);}
    /**
     * Where {@code eye} is relative to the selection as it was before the drag: the point the drag's transform sends to it. The outline is drawn
     * unmoved and then moved by the transform, so the silhouette the camera sees of the moved shape is the silhouette of the unmoved one seen
     * from here. An affine transform keeps straight lines straight, so this is exact for moves, turns and scales alike. The camera itself when
     * nothing is dragged or the transform cannot be undone.
     */
    static Vec3 eyeInShape(Vec3 eye){
        if(dragging==null)return eye;
        Vec3 about=aboutPoint(),moved=transformed(Vec3.ZERO).subtract(rawTransform(Vec3.ZERO)); // the part of the transform that is not the scale and turn: the move
        var back=Geometry.untransform(new Geometry.Vector(eye.x,eye.y,eye.z),new Geometry.Vector(about.x,about.y,about.z),new Geometry.Vector(size.x,size.y,size.z),angles.y,angles.x,angles.z,new Geometry.Vector(moved.x,moved.y,moved.z));
        return back==null?eye:new Vec3(back.x(),back.y(),back.z());
    }
    /** One outline segment of a selection being transformed, moved by the transform in progress. */
    static void line(Vec3 from,Vec3 to){Overlay.line(transformed(from),transformed(to),ClientState.options.previewColor,1.5f);}
    static boolean dragging(){return dragging!=null;}
    /** The mode of the handle being dragged, or null when none is held. */
    static Options.GizmoMode draggingMode(){return dragging==null?null:mode;}
    /** How far the selected blocks are being dragged by the Move tool, in whole blocks; null when no such drag is under way. */
    static Point moveDelta(){
        if(dragging==null||dragging.kind!=Kind.MODEL||mode!=Options.GizmoMode.MOVE)return null;
        return new Point((int)Math.round(delta.x),(int)Math.round(delta.y),(int)Math.round(delta.z));
    }
    /** Past these the landing is not worked out while dragging: too many blocks, or too much space to look through, for a frame. */
    private static final int LANDING_BLOCKS=20_000;
    private static final long LANDING_CELLS=400_000L;
    private static List<Point> landingFrom;private static Vec3 landingDelta,landingAngles,landingSize,landingAbout;private static OutlineShape landing;
    /**
     * The shape the selected blocks land in with a rotate or scale drag as it stands, with square edges like the blocks that
     * will be built, instead of the old outline turned into diagonal lines. Worked out again only when the drag changes. Null
     * when nothing is dragged, when the drag is a plain move (the moved outline is exact), or when the selection is too big.
     */
    static OutlineShape landing(List<Point> selected){
        if(dragging==null||dragging.kind!=Kind.MODEL||selected.isEmpty()||selected.size()>LANDING_BLOCKS)return null;
        if(angles.equals(Vec3.ZERO)&&size.equals(new Vec3(1,1,1)))return null;
        Vec3 about=aboutPoint();
        if(landingFrom!=selected||!delta.equals(landingDelta)||!angles.equals(landingAngles)||!size.equals(landingSize)||!about.equals(landingAbout)){
            landingFrom=selected;landingDelta=delta;landingAngles=angles;landingSize=size;landingAbout=about;
            var cells=VoxelTransform.landed(selected,new Geometry.Vector(about.x,about.y,about.z),angles.y,angles.x,angles.z,size.x,size.y,size.z,(int)Math.round(delta.x),(int)Math.round(delta.y),(int)Math.round(delta.z),LANDING_CELLS);
            landing=cells==null||cells.isEmpty()?null:OutlineShape.of(cells);
        }
        return landing;
    }
    /** True when the pointer is on one of the handles of a point gizmo at {@code at}, so a gizmo that is only shown can still be grabbed. */
    static boolean hitsPointHandles(Vec3 at,double x,double y){
        return pick(new Target(Kind.NODE,at,null),x,y)>=0;
    }
    /** The handle under the pointer, or -1; kept so the gizmo can light it up before it is clicked. */
    private static int hovered=-1;
    /** Whether the pointer is over a handle of the gizmo, and no handle is being held. */
    static boolean hovering(){return dragging==null&&hovered>=0;}
    private static final String[] PLANES={"YZ","ZX","XY"};
    static void updateHover(EditorScreen screen){
        hovered=-1;
        if(dragging!=null||ClientState.status.busy()||!screen.pointerInViewport())return;
        Target t=target(screen);
        if(t!=null)hovered=pick(t,screen.pointerX(),screen.pointerY());
    }
    /** What dragging the handle does, shown next to it while the pointer rests on it. */
    private static String handleName(Options.GizmoMode mode,int handle,boolean hasExtent){
        String verb=mode==Options.GizmoMode.ROTATE?"Rotate":mode==Options.GizmoMode.SCALE?"Scale":"Move";
        if(handle<3)return verb+" "+"XYZ".charAt(handle);
        if(handle==3)return mode==Options.GizmoMode.SCALE?"Scale evenly":mode==Options.GizmoMode.ROTATE?"Trackball":"Move freely";
        if(handle==7)return "Rotate view";
        return verb+" "+PLANES[handle-4];
    }
    private static int brighten(int argb){
        int r=argb>>16&255,g=argb>>8&255,b=argb&255;
        return 0xff000000|Math.min(255,r+70)<<16|Math.min(255,g+70)<<8|Math.min(255,b+70);
    }
    /** How far a handle point sits from its block's corner: a ruler's ends are drawn at the middle of their blocks, so the gizmo is too. */
    static double nodeOffset(){return dev.craftium.client.compat.ClientCompat.screen(net.minecraft.client.Minecraft.getInstance()) instanceof EditorScreen editor&&(!editor.toolActive()||ClientState.options.tool==Tool.MODIFY)?.5:0;}
    /** Where the handle being dragged is right now, or null when no handle point is being dragged. */
    static Vec3 liveNode(){return dragging!=null&&dragging.kind==Kind.NODE?dragging.anchor.add(delta):null;}
    static boolean selectionPreview(){return dragging!=null&&(dragging.kind==Kind.SELECTION||dragging.kind==Kind.MODEL);}
    static void box(AABB box){if(dragging==null){Overlay.box(box,ClientState.options.previewColor,1,ClientState.options.previewFill);return;}Vec3[] points=new Vec3[8];for(int i=0;i<8;i++)points[i]=transformed(new Vec3((i&1)==0?box.minX:box.maxX,(i&2)==0?box.minY:box.maxY,(i&4)==0?box.minZ:box.maxZ));for(int i=0;i<8;i++)for(int bit:new int[]{1,2,4})if((i&bit)==0)Overlay.line(points[i],points[i|bit],ClientState.options.previewColor,1.5f);}
    static void draw(EditorScreen screen){
        Target t=dragging==null?target(screen):dragging;if(t==null)return;
        if(dragging==null)updateHover(screen);else followCamera(screen);
        localBasis=t.kind==Kind.SHAPE&&effectiveMode(t)==Options.GizmoMode.SCALE;
        faceBase=faceBase(t).add(delta);plainHandles=t.kind==Kind.MODEL||t.kind==Kind.PIVOT||t.kind==Kind.SHAPE&&ClientState.options.tool.transform();
        render(t.anchor.add(dragging!=null&&effectiveMode(t)==Options.GizmoMode.MOVE?smooth:delta),effectiveMode(t),dragging!=null?handle:hovered,extent(t));
        faceBase=null;plainHandles=false;localBasis=false;
        if(dragging!=null){ruler(t.anchor.add(effectiveMode(t)==Options.GizmoMode.MOVE?smooth:delta));readout(t);}
        if(t.kind==Kind.PIVOT)Overlay.point(t.anchor.add(delta),0xffffd66d,11); // the pivot
    }
    /** A number with at most two decimals and no trailing zeros, and a sign when {@code signed}. */
    private static String number(double value,boolean signed){
        String text=java.math.BigDecimal.valueOf(Math.rint(value*100)/100).stripTrailingZeros().toPlainString();
        if(text.equals("-0"))text="0";
        return signed&&!text.startsWith("-")&&!text.equals("0")?"+"+text:text;
    }
    /** While a handle is held: the numbers of the transform in progress above the gizmo, so it can be set exactly: the distance moved, the angles turned, or the new size and scale. */
    private static void readout(Target t){
        Vec3 anchor=t.anchor.add(mode==Options.GizmoMode.MOVE?smooth:delta);
        String header;String[] lines=new String[3];double[] move={delta.x,delta.y,delta.z},turn={angles.x,angles.y,angles.z},factor={size.x,size.y,size.z};
        double[] extent=pickExtent==null?null:new double[]{Math.max(1,pickExtent.x),Math.max(1,pickExtent.y),Math.max(1,pickExtent.z)};
        switch(mode){
            case ROTATE -> {header="Rotate";for(int axis=0;axis<3;axis++)lines[axis]="XYZ".charAt(axis)+" "+number(turn[axis],true)+"°";}
            case SCALE -> {header=extent==null?"Scale":"Size";for(int axis=0;axis<3;axis++)lines[axis]="XYZ".charAt(axis)+" "+(extent==null?"×"+number(factor[axis],false):number(extent[axis]*factor[axis],false)+"  (×"+number(factor[axis],false)+")");}
            default -> {header="Move";for(int axis=0;axis<3;axis++)lines[axis]="XYZ".charAt(axis)+" "+number(move[axis],true);}
        }
        // Stacked over the gizmo, each axis in its own colour: the header on top, then X, Y and Z.
        double k=screenScale(anchor)*TEXT_SCALE/.05;Vec3 up=cameraAxes()[1],base=anchor.add(up.scale(6.2*screenScale(anchor)));
        Overlay.text(header,base.add(up.scale(2.4*k)),0xffffffff,0xcc151c26,(float)(TEXT_SCALE*screenScale(anchor)));
        for(int axis=0;axis<3;axis++)Overlay.text(lines[axis],base.add(up.scale((2-axis)*0.8*k)),COLORS[axis],0xcc151c26,(float)(TEXT_SCALE*screenScale(anchor)));
    }
    /** The size of the gizmo's text at the gizmo's design distance: 250% of the original .02. */
    private static final double TEXT_SCALE=.05;
    private static final double RULER_REACH=64;
    private static final int[] RULER_COLORS={0xaaff7777,0xaa8bef9a,0xaa78b9ff};
    /**
     * While a handle is held: a thin line through the gizmo along each global axis, hidden where blocks are in front of it, with a tick at every block and a longer one every
     * five, so the distance moved can be read off the world's own grid whichever way the gizmo itself is turned.
     */
    static void ruler(Vec3 at){
        for(int axis=0;axis<3;axis++){
            double[] c={at.x,at.y,at.z};int across=(axis+1)%3;
            double[] from=c.clone(),to=c.clone();from[axis]-=RULER_REACH;to[axis]+=RULER_REACH;
            Overlay.sceneLine(new Vec3(from[0],from[1],from[2]),new Vec3(to[0],to[1],to[2]),RULER_COLORS[axis],1);
            int first=(int)Math.ceil(c[axis]-RULER_REACH),last=(int)Math.floor(c[axis]+RULER_REACH);
            for(int n=first;n<=last;n++){
                double half=n%5==0?.25:.1;double[] a=c.clone(),b=c.clone();a[axis]=b[axis]=n;a[across]-=half;b[across]+=half;
                Overlay.sceneLine(new Vec3(a[0],a[1],a[2]),new Vec3(b[0],b[1],b[2]),RULER_COLORS[axis],1);
            }
        }
    }
    /** A line from one point to another, as thin as the ruler: white, or in the colour of the axis when it runs along exactly one world axis. */
    static void guideLine(Vec3 from,Vec3 to){
        int along=-1,differing=0;double[] a={from.x,from.y,from.z},b={to.x,to.y,to.z};
        for(int axis=0;axis<3;axis++)if(Math.abs(a[axis]-b[axis])>1e-6){differing++;along=axis;}
        if(differing==0)return;
        Overlay.sceneLine(from,to,differing==1?RULER_COLORS[along]:0xaaffffff,1);
    }
    /** The handles at {@code anchor} for show only, as in builder mode where they can't be dragged. */
    static void display(Vec3 anchor){show(anchor,ALL_AXES,true);}
    /** Like {@link #display(Vec3)}, but only the axes whose bit is set in {@code axes} (X is 1, Y is 2, Z is 4): the plane squares need both of theirs. */
    static void display(Vec3 anchor,int axes){show(anchor,axes,false);}
    /** The builder's handles can't be grabbed, so the plane squares and the centre knob, which only exist to be grabbed, are left out; the box corners' can be, so they keep theirs. */
    private static void show(Vec3 anchor,int axes,boolean faces){axisMask=axes;showFaces=faces;try{render(anchor,Options.GizmoMode.MOVE,-1,null);}finally{axisMask=ALL_AXES;showFaces=true;}}
    private static boolean showFaces=true;
    private static final int ALL_AXES=7;
    private static int axisMask=ALL_AXES;
    /**
     * The mode the gizmo can really be in for this target. Points, and the corners of a selection box, can only be
     * moved, so they ignore the rotate and scale the editor may be set to.
     */
    private static Options.GizmoMode effectiveMode(Target t){
        if(t.kind==Kind.NODE||t.kind==Kind.PIVOT||t.kind==Kind.SELECTION&&ClientState.options.tool==Tool.BOX_SELECT)return Options.GizmoMode.MOVE;
        if(t.kind==Kind.MODEL||t.kind==Kind.SHAPE&&ClientState.options.tool.transform())return switch(ClientState.options.tool){case ROTATE->Options.GizmoMode.ROTATE;case SCALE->Options.GizmoMode.SCALE;default->Options.GizmoMode.MOVE;};
        return ClientState.options.gizmoMode;
    }
    /** How far the target reaches along each axis, so scale handles can sit on its faces; null when it has no extent, like an entity. */
    private static Vec3 extent(Target t){
        if(t.kind==Kind.SELECTION||t.kind==Kind.MODEL){var s=ClientState.status;Point min=s.min(),max=s.max();return new Vec3(max.x()-min.x()+1,max.y()-min.y()+1,max.z()-min.z()+1);}
        if(t.kind==Kind.PLACEMENT){AABB b=bounds();return new Vec3(b.getXsize(),b.getYsize(),b.getZsize());}
        if(t.kind==Kind.SHAPE)return ShapeObject.dims();
        return null;
    }
    /** The middle of the face of the target that the scale handle for {@code axis} sits on: the far side along that axis. */
    /** How far a scale handle sits from what the scale is about: the extent from the corner, or from the pivot for the blocks. */
    private static Vec3 reach(Target t,Vec3 extent){
        if(extent==null||t.kind!=Kind.MODEL)return extent;
        var s=ClientState.status;Point max=s.max();
        return new Vec3(max.x()+1-t.anchor.x,max.y()+1-t.anchor.y,max.z()+1-t.anchor.z);
    }
    private static Vec3 faceCenter(Vec3 anchor,Vec3[] directions,Vec3 extent,int axis){
        double[] e={extent.x,extent.y,extent.z};Vec3 at=anchor;
        for(int j=0;j<3;j++)at=at.add(directions[j].scale(e[j]*(j==axis?1:.5)));
        return at;
    }
    /** Set while the Move, Rotate, Scale and Pivot tools draw: only their own handles, without the centre square or plane dots. */
    private static boolean plainHandles;
    private static Vec3 pickExtent,pickReach;
    /** The camera's right, up and forward directions, for the handles that face the screen. */
    private static Vec3[] cameraAxes(){
        var near=ClientCompat.nearPlane(ClientCompat.camera(Minecraft.getInstance()));
        Vec3 center=near.getPointOnPlane(0,0);
        return new Vec3[]{near.getPointOnPlane(1,0).subtract(center).normalize(),near.getPointOnPlane(0,1).subtract(center).normalize(),center.normalize()};
    }
    private static void circle(Vec3 at,Vec3 right,Vec3 up,double radius,int color,float width){
        for(int j=0;j<48;j++){
            double a=j*Math.PI/24,b=(j+1)*Math.PI/24;
            Overlay.line(at.add(right.scale(Math.cos(a)*radius)).add(up.scale(Math.sin(a)*radius)),at.add(right.scale(Math.cos(b)*radius)).add(up.scale(Math.sin(b)*radius)),color,width);
        }
    }
    /** A flat square in the plane of {@code a} and {@code b}, centred on {@code at}, drawn as an outline with a fill. */
    private static void planeSquare(Vec3 at,Vec3 a,Vec3 b,double half,int color,boolean hot){
        Vec3 p0=at.subtract(a.scale(half)).subtract(b.scale(half)),p1=at.add(a.scale(half)).subtract(b.scale(half)),p2=at.add(a.scale(half)).add(b.scale(half)),p3=at.subtract(a.scale(half)).add(b.scale(half));
        float width=hot?3:1.5f;
        Overlay.line(p0,p1,color,width);Overlay.line(p1,p2,color,width);Overlay.line(p2,p3,color,width);Overlay.line(p3,p0,color,width);
        int fill=color&0x00ffffff|(hot?0xaa000000:0x66000000);
        Overlay.face(p0,p1,p2,p3,fill);Overlay.face(p3,p2,p1,p0,fill); // both windings, so culling cannot hide it
    }
    private static double screenAngle(double x,double y,Vec3 anchor){var c=project(anchor);return c==null?0:Math.atan2(y-c[1],x-c[0]);}
    /** The rotation by {@code radians} about {@code axis}, as a matrix. */
    private static double[][] axisAngle(Vec3 axis,double radians){
        Vec3 u=axis.normalize();double c=Math.cos(radians),s=Math.sin(radians),t=1-c,x=u.x,y=u.y,z=u.z;
        return new double[][]{{t*x*x+c,t*x*y-s*z,t*x*z+s*y},{t*x*y+s*z,t*y*y+c,t*y*z-s*x},{t*x*z-s*y,t*y*z+s*x,t*z*z+c}};
    }
    private static double[][] multiply(double[][] a,double[][] b){
        double[][] m=new double[3][3];
        for(int i=0;i<3;i++)for(int j=0;j<3;j++)for(int k=0;k<3;k++)m[i][j]+=a[i][k]*b[k][j];
        return m;
    }
    /**
     * Sets the angles so that Geometry.rotate turns by the rotation {@code m}. That function applies yaw, then pitch,
     * then roll, so its matrix is Rz(roll)·Rx(pitch)·Ry(yaw), which this takes apart.
     */
    private static void setRotation(double[][] m){
        double pitch=Math.asin(Math.clamp(m[2][1],-1,1)),yaw=Math.atan2(m[2][0],m[2][2]),roll=Math.atan2(-m[0][1],m[1][1]);
        angles=new Vec3(Math.toDegrees(pitch),Math.toDegrees(yaw),Math.toDegrees(roll));
    }
    /** The scale along one axis for a wanted size in blocks: a whole number of blocks, or any size with Ctrl held. */
    private static double gridFactor(double blocks,double wanted,boolean smooth){
        double n=Math.max(1,blocks),target=smooth?wanted:Math.rint(wanted);
        return Math.clamp(Math.max(target,smooth?.1:1)/n,.01,64);
    }
    /** The depth and offset at which the centre handle was grabbed, so a drag keeps following the pointer as the camera moves. */
    private static Vec3 freeOrigin=Vec3.ZERO,freeNormal=new Vec3(0,0,1),freeOffset=Vec3.ZERO;
    private static double[] dragFlips;
    private static Vec3 pointerRay(double x,double y){
        var mc=Minecraft.getInstance();var camera=ClientCompat.camera(mc);
        return ClientCompat.ray(camera,(float)(x/mc.getWindow().getGuiScaledWidth()*2-1),(float)(1-y/mc.getWindow().getGuiScaledHeight()*2));
    }
    private static void grabFree(Vec3 anchor,double x,double y){
        Vec3 eye=ClientCompat.camera(Minecraft.getInstance()).position();
        // The handle slides over a plane that stays where it was grabbed, facing the camera then. Flying about moves the pointer's ray but not the plane, so the blocks follow the pointer and are not carried along with the player.
        freeOrigin=anchor;freeNormal=pointerCentre();freeOffset=Vec3.ZERO;
        freeOffset=anchor.subtract(freePoint(eye,pointerRay(x,y))); // so the handle does not jump to the pointer
    }
    private static Vec3 pointerCentre(){return pointerRay(Minecraft.getInstance().getWindow().getGuiScaledWidth()/2d,Minecraft.getInstance().getWindow().getGuiScaledHeight()/2d);}
    /** Where the pointer's ray from {@code eye} meets the grab plane, moved by the grab offset; the plane's own anchor when the ray runs along it. */
    private static Vec3 freePoint(Vec3 eye,Vec3 ray){
        double facing=ray.dot(freeNormal);if(Math.abs(facing)<1e-3)return freeOrigin;
        double reach=freeOrigin.subtract(eye).dot(freeNormal)/facing;
        return eye.add(ray.scale(Math.clamp(reach,0,4096))).add(freeOffset);
    }
    /** While the centre handle is held, keeps it with the pointer when only the camera moves (the player flying about). */
    static void followCamera(EditorScreen screen){
        if(dragging!=null&&(handle==3&&mode==Options.GizmoMode.MOVE||dragging.kind==Kind.MODEL&&mode==Options.GizmoMode.SCALE))drag(screen.pointerX(),screen.pointerY());
    }
    /**
     * For the corner handles of a box selection: which way each arrow points, so that it leads away from the box.
     * A corner on the low side of an axis points down it, one on the high side points up it. Null for any other handle.
     */
    private static double[] flipsFor(Vec3 anchor){
        for(int index=0;index<ClientState.boxCorners.size();index++){
            double[] flips=cornerFlips(index);if(flips==null)return null;
            if(cornerAnchor(index).distanceTo(anchor)<.01)return flips;
        }
        return null;
    }
    /**
     * Which side of its block each axis of a box corner's handle sits on: -1 the low face, 1 the high face. A corner below the other on
     * an axis takes the low side, one above it the high side; a lone corner, or one level with the other, takes low (first) or high (second).
     * Null when the tool is not Box select or there is no such corner.
     */
    static double[] cornerFlips(int index){
        var corners=ClientState.boxCorners;
        if(ClientState.options.tool!=Tool.BOX_SELECT||index<0||index>=corners.size())return null;
        if(corners.size()==1)return new double[]{-1,-1,-1};
        Point c=corners.get(index),other=corners.get(1-index);
        int[] mine={c.x(),c.y(),c.z()},theirs={other.x(),other.y(),other.z()};
        double[] flips=new double[3];
        for(int axis=0;axis<3;axis++)flips[axis]=mine[axis]<theirs[axis]?-1:mine[axis]>theirs[axis]?1:index==0?-1:1;
        return flips;
    }
    /** Where a box corner's handle sits: the very extremity of the bounds, the outer corner of its block. */
    static Vec3 cornerAnchor(int index){
        Point c=ClientState.boxCorners.get(index);double[] f=cornerFlips(index);
        return f==null?new Vec3(c.x(),c.y(),c.z()):new Vec3(c.x()+(f[0]>0?1:0),c.y()+(f[1]>0?1:0),c.z()+(f[2]>0?1:0));
    }
    /** The block of a box corner for a handle put at {@code anchor}, given the sides the handle is on. */
    private static Point cornerFrom(Vec3 anchor,double[] flips){
        return new Point((int)Math.round(anchor.x-(flips[0]>0?1:0)),(int)Math.round(anchor.y-(flips[1]>0?1:0)),(int)Math.round(anchor.z-(flips[2]>0?1:0)));
    }
    /** The block of the corner being dragged, as it is now; null when no box corner is being dragged. */
    static Point liveCorner(){return dragging!=null&&dragging.kind==Kind.NODE&&dragFlips!=null?cornerFrom(dragging.anchor.add(delta),dragFlips):null;}
    /** Where the handle of a node is shown: a box corner at the extremity of the bounds, any other node at its block (or its centre). */
    static Vec3 nodeAnchor(Point node,int index){
        if(index>=0&&index<ClientState.boxCorners.size()&&ClientState.boxCorners.get(index).equals(node)&&cornerFlips(index)!=null)return cornerAnchor(index);
        double shift=nodeOffset();return new Vec3(node.x()+shift,node.y()+shift,node.z()+shift);
    }
    /** A solid white cube on a box corner's handle, big enough to read from a distance. */
    static void cornerCube(Vec3 anchor){double h=.13*screenScale(anchor);Overlay.box(new AABB(anchor.add(-h,-h,-h),anchor.add(h,h,h)),0xffffffff,2,0xffffffff);}
    /** The axes for a gizmo at {@code anchor}, turned to point away from the box when it is a box corner. */
    private static Vec3[] axesAt(Vec3 anchor){
        Vec3[] directions=axes();
        boolean dragged=dragging!=null&&dragging.kind==Kind.NODE&&dragFlips!=null&&(anchor.distanceTo(dragging.anchor.add(delta))<1e-6||anchor.distanceTo(dragging.anchor.add(smooth))<1e-6); // the gizmo is drawn at the smoothed position
        double[] flips=dragged?dragFlips:flipsFor(anchor);
        if(flips!=null)for(int i=0;i<3;i++)directions[i]=directions[i].scale(flips[i]);
        return directions;
    }
    /** Where the Pivot tool's gizmo was put down when there was no selection and no pivot to show it at; null while one of those exists. */
    private static Vec3 parked;
    /**
     * Where the Pivot tool's gizmo sits. Without a selection or a set pivot there is no centre to show it at (an empty selection reads
     * as the world origin, far from where you are), so it is put down once, on the block under the pointer or a few blocks ahead of the
     * camera, and stays there to be grabbed.
     */
    private static Vec3 pivotAnchor(EditorScreen screen){
        var o=ClientState.options;
        if(o.pivotSet||ClientState.status.selectionSize()>0){parked=null;return pivot();}
        var mc=Minecraft.getInstance();
        if(parked!=null&&(mc.player==null||parked.distanceToSqr(mc.player.position())<128*128))return parked;
        var hit=screen.target();
        if(hit!=null)return parked=Vec3.atCenterOf(hit.getBlockPos());
        if(mc.player==null)return pivot();
        Vec3 ahead=mc.player.getEyePosition().add(mc.player.getLookAngle().scale(6));
        return parked=new Vec3(Math.floor(ahead.x)+.5,Math.floor(ahead.y)+.5,Math.floor(ahead.z)+.5);
    }
    /** The centre of the selection, or the pivot the player set: the point the Rotate and Scale tools work around. */
    static Vec3 pivot(){
        var o=ClientState.options;
        if(o.pivotSet)return new Vec3(o.pivotX,o.pivotY,o.pivotZ);
        var s=ClientState.status;Point min=s.min(),max=s.max();
        return new Vec3((min.x()+max.x()+1)/2d,(min.y()+max.y()+1)/2d,(min.z()+max.z()+1)/2d);
    }
    /** The corner the target's faces are measured from. */
    private static Vec3 faceBase(Target t){
        if(t.kind==Kind.MODEL){Point min=ClientState.status.min();return new Vec3(min.x(),min.y(),min.z());}
        if(t.kind==Kind.PLACEMENT){Point p=PlacementPreview.origin;AABB b=bounds();return new Vec3(p.x()+b.minX,p.y()+b.minY,p.z()+b.minZ);}
        if(t.kind==Kind.SHAPE){ // the corner of the shape's own box, so the scale handles sit on the middle of its faces
            Vec3 e=ShapeObject.dims();Vec3[] d=ShapeObject.axes();
            return t.anchor.subtract(d[0].scale(e.x/2)).subtract(d[1].scale(e.y/2)).subtract(d[2].scale(e.z/2));
        }
        return t.anchor;
    }
    private static Vec3 faceBase;
    private static final int CENTER=0xffffffff,CENTER_ACTIVE=0xffffd66d;
    /** The gizmo's scale at the last click, kept for the drag that follows so the handles do not shift under the cursor. */
    private static double pickScale=1;
    /** The distance, in blocks, at which the handles are drawn at the size they were designed for. */
    private static final double DESIGN_DISTANCE=8/.6; // 40% smaller than the size first chosen at 8
    /** How much to scale the gizmo so it covers the same part of the screen whatever its distance from the camera. */
    private static double screenScale(Vec3 anchor){
        double distance=ClientCompat.camera(Minecraft.getInstance()).position().distanceTo(anchor);
        return Math.clamp(distance/DESIGN_DISTANCE,.05,40);
    }
    /** Move handles end in flat triangular arrowheads, rotate handles are rings, scale handles are squares, and the centre is a square of its own. */
    private static void render(Vec3 anchor,Options.GizmoMode currentMode,int active,Vec3 extent){
        Vec3[] directions=axesAt(anchor);double k=screenScale(anchor);
        boolean held=ClientState.options.gizmoHideOthers&&dragging!=null&&active>=0; // while a handle is held, only that handle is drawn
        for(int i=0;i<3;i++){int color=active==i?brighten(COLORS[i]):COLORS[i];
            if((axisMask>>i&1)!=0&&!(held&&active!=i)){
            if(currentMode==Options.GizmoMode.ROTATE){
                Vec3 a=directions[(i+1)%3],b=directions[(i+2)%3];
                // Three full rings make a sphere of wire that is hard to look through, so each axis shows a short arc to grab,
                // and its whole ring appears only while the pointer is on it or it is being dragged.
                if(active!=i){for(int j=-6;j<6;j++){double x=j*Math.PI/48,y=(j+1)*Math.PI/48;Overlay.line(anchor.add(a.scale(Math.cos(x)*3.5*k)).add(b.scale(Math.sin(x)*3.5*k)),anchor.add(a.scale(Math.cos(y)*3.5*k)).add(b.scale(Math.sin(y)*3.5*k)),color,2.5f);}continue;}
                for(int j=0;j<96;j++){double x=j*Math.PI/48,y=(j+1)*Math.PI/48;Overlay.line(anchor.add(a.scale(Math.cos(x)*3.5*k)).add(b.scale(Math.sin(x)*3.5*k)),anchor.add(a.scale(Math.cos(y)*3.5*k)).add(b.scale(Math.sin(y)*3.5*k)),color,active==i?3:1.5f);}}
            else if(currentMode==Options.GizmoMode.SCALE){Vec3 end=extent!=null?faceCenter(faceBase!=null?faceBase:anchor,directions,extent,i):anchor.add(directions[i].scale(5*k));if(extent==null)Overlay.line(anchor,end,color,active==i?4:2);Overlay.box(new AABB(end.add(-.2*k,-.2*k,-.2*k),end.add(.2*k,.2*k,.2*k)),color,active==i?2:1,color&0x99ffffff|0x66000000);}
            else{Vec3 tip=anchor.add(directions[i].scale(5.3*k)),base=anchor.add(directions[i].scale(4.5*k));Overlay.line(anchor,base,color,active==i?4:2);arrowhead(tip,base,color,active==i?3:2,k);}
            }
            if(showFaces&&(currentMode==Options.GizmoMode.MOVE||currentMode==Options.GizmoMode.SCALE)&&(axisMask>>(i+1)%3&1)!=0&&(axisMask>>(i+2)%3&1)!=0&&!(held&&active!=4+i)){ // a square in each plane: moves, or scales, the two axes of that plane
                Vec3 a=directions[(i+1)%3],b=directions[(i+2)%3],at=anchor.add(a.scale(1.4*k)).add(b.scale(1.4*k));boolean hot=active==4+i;
                planeSquare(at,a,b,.2*k,hot?brighten(COLORS[i]):COLORS[i],hot);
            }
        }
        if(currentMode==Options.GizmoMode.ROTATE){
            // A ring that faces the camera turns about the view; hovering inside the sphere lights its outline, the trackball.
            Vec3[] cam=cameraAxes();
            if(!held||active==7)circle(anchor,cam[0],cam[1],4.4*k,active==7?0xffffffff:0x99c7d6e5,active==7?3:1.5f);
            if(active==3)circle(anchor,cam[0],cam[1],3.5*k,CENTER_ACTIVE,2f);
        }
        if(dragging==null&&active>=0)label(anchor,directions,k,extent,currentMode,active);
        if(currentMode==Options.GizmoMode.SCALE&&plainHandles&&!(held&&active!=3)){ // the Scale tool's circle in the middle scales every axis
            Vec3[] cam=cameraAxes();circle(anchor,cam[0],cam[1],.32*k,active==3?CENTER_ACTIVE:CENTER,active==3?3:2f);
        }
        if(showFaces&&currentMode!=Options.GizmoMode.ROTATE&&!plainHandles&&axisMask==ALL_AXES&&!(held&&active!=3)){int color=active==3?CENTER_ACTIVE:CENTER;Overlay.box(new AABB(anchor.add(-.17*k,-.17*k,-.17*k),anchor.add(.17*k,.17*k,.17*k)),color,2,color&0x00ffffff|0x88000000);}
    }
    /** Names the handle under the pointer next to it. */
    private static void label(Vec3 anchor,Vec3[] directions,double k,Vec3 extent,Options.GizmoMode mode,int handle){
        Vec3 spot;
        if(handle==3)spot=anchor;
        else if(handle==7)spot=anchor.add(cameraAxes()[1].scale(4.4*k));
        else if(handle>=4)spot=anchor.add(directions[(handle-3)%3].scale(1.4*k)).add(directions[(handle-2)%3].scale(1.4*k));
        else if(mode==Options.GizmoMode.SCALE&&extent!=null)spot=faceCenter(faceBase!=null?faceBase:anchor,directions,extent,handle);
        else if(mode==Options.GizmoMode.ROTATE)spot=anchor.add(directions[(handle+1)%3].scale(3.5*k));
        else spot=anchor.add(directions[handle].scale(5.3*k));
        Overlay.text(handleName(mode,handle,extent!=null),spot,handle>=0&&handle<3?COLORS[handle]:handle>=4&&handle<7?COLORS[handle-4]:0xffffffff,0xcc151c26,(float)(TEXT_SCALE*k)); // the handle's axis colour, or for a plane the colour of the axis across it // scaled with the gizmo, so it reads the same size at any distance
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
