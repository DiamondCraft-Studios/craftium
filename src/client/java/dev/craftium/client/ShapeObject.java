package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * The shape the Shape tool has put down but not yet built: a shape of the foreground block with a position, a turn and a
 * size. It is not in the world and is not a selection of world blocks. Move, Rotate and Scale change these three things
 * and the shape is made again from them, so it is never stretched or resampled. Enter builds it, Esc drops it.
 *
 * The cells come from {@link Geometry#shape}, the same code the server runs when the shape is built, so what is
 * previewed is what is placed.
 */
final class ShapeObject {
    /** Where the shape is, how it is turned (a rotation matrix, applied after the sizes), and its size along each of its own axes. */
    record Pose(Point origin,double[][] rotation,double sizeX,double sizeY,double sizeZ){
        Pose withOrigin(Point at){return new Pose(at,rotation,sizeX,sizeY,sizeZ);}
        double yaw(){return euler(rotation)[0];}
        double pitch(){return euler(rotation)[1];}
        double roll(){return euler(rotation)[2];}
    }

    private static final int STROKE=0xff8be3b5,FILL=0x2247d29b;
    private static final double[][] IDENTITY={{1,0,0},{0,1,0},{0,0,1}};
    /** Shapes are limited to this reach (see {@link Geometry#shape}), so sizes are limited to keep inside it. */
    private static final double REACH=BrushMath.MAX_REACH;
    private static Pose pose;
    private ShapeObject(){}

    static boolean active(){return pose!=null;}
    static Pose pose(){return pose;}
    /** True while the transform gizmo works on the shape: with the Move, Rotate and Scale tools, and with the Shape tool itself. */
    static boolean gizmoOn(){
        if(pose==null)return false;
        var tool=ClientState.options.tool;
        return tool==Tool.SHAPE||tool.transform()&&tool!=Tool.PIVOT;
    }

    /** Puts the shape down at {@code at}; if it is already down, moves it there and keeps its turn and size. */
    static void place(Point at){
        if(pose==null){pose=new Pose(at,IDENTITY,1,1,1);ClientState.notify("Shape ready · Move, Rotate and Scale change it · Enter to build · Esc to cancel");}
        else pose=pose.withOrigin(at);
    }
    static void cancel(){pose=null;}
    static void set(Pose next){pose=next;}
    static void move(Point step){if(pose!=null)pose=pose.withOrigin(pose.origin().add(step.x(),step.y(),step.z()));}
    /** Turns the shape by {@code degrees} about the world axis (0 X, 1 Y, 2 Z), for the Edit menu. */
    static void turn(int axis,double degrees){
        if(pose==null)return;
        var turn=matrix(axis==1?degrees:0,axis==0?degrees:0,axis==2?degrees:0);
        pose=new Pose(pose.origin(),multiply(turn,pose.rotation()),pose.sizeX(),pose.sizeY(),pose.sizeZ());
    }

    /** Grows or shrinks the shape's radius by {@code step}; the sizes are kept within reach of the new radius. */
    static void adjustRadius(int step){
        var o=ClientState.options;
        o.radius=Math.clamp(o.radius+step,1,BrushMath.MAX_RADIUS);
        o.outlineThickness=Math.clamp(o.outlineThickness,1,o.radius);
        if(pose!=null)pose=fit(pose,o.outerRadius());
    }

    /** The middle of the shape, which is where its handles sit and what it turns and scales about. */
    static Vec3 centre(){var at=pose.origin();return new Vec3(at.x()+.5,at.y()+.5,at.z()+.5);}
    /** How many blocks the shape spans along each of its own axes, which is what the scale handles measure. */
    static Vec3 dims(){var o=ClientState.options;return new Vec3(2*o.radius*pose.sizeX()+1,2*o.radius*pose.sizeY()+1,2*o.radius*pose.sizeZ()+1);}
    /** The shape's own axes in the world, as the columns of its rotation. */
    static Vec3[] axes(){
        var m=pose.rotation();
        return new Vec3[]{new Vec3(m[0][0],m[1][0],m[2][0]),new Vec3(m[0][1],m[1][1],m[2][1]),new Vec3(m[0][2],m[1][2],m[2][2])};
    }

    /**
     * The pose after a handle has been dragged: Move changes the position, Rotate turns the shape about the world
     * axes, and Scale changes the size along its own axes by the factors in {@code size} (from the block counts the
     * handles measured). Only the pose changes, so the shape is made again and not deformed.
     */
    static Pose apply(Pose base,Options.GizmoMode mode,Vec3 delta,Vec3 angles,Vec3 size){
        switch(mode){
            case MOVE:return base.withOrigin(base.origin().add((int)Math.round(delta.x),(int)Math.round(delta.y),(int)Math.round(delta.z)));
            case ROTATE:return new Pose(base.origin(),multiply(matrix(angles.y,angles.x,angles.z),base.rotation()),base.sizeX(),base.sizeY(),base.sizeZ());
            default:{
                double r=Math.max(1,ClientState.options.radius);
                // The size that gives the block count the handle asked for: count = 2 * radius * size + 1.
                double x=resize(base.sizeX(),size.x,r),y=resize(base.sizeY(),size.y,r),z=resize(base.sizeZ(),size.z,r);
                return fit(new Pose(base.origin(),base.rotation(),x,y,z),ClientState.options.outerRadius());
            }
        }
    }
    private static double resize(double size,double factor,double radius){return factor==1?size:((2*radius*size+1)*factor-1)/(2*radius);}
    /** Keeps each size in range and the whole shape within the reach Geometry allows. */
    private static Pose fit(Pose p,double radius){
        double x=Math.clamp(p.sizeX(),.1,8),y=Math.clamp(p.sizeY(),.1,8),z=Math.clamp(p.sizeZ(),.1,8);
        double length=radius*Math.sqrt(x*x+y*y+z*z);
        if(length>REACH){double k=REACH/length;x*=k;y*=k;z*=k;}
        return new Pose(p.origin(),p.rotation(),x,y,z);
    }

    // ---- building

    /** The options the server builds the shape with: the ones in use, with this pose. */
    private static Options request(Pose p){
        Options original=ClientState.options,request=Packets.JSON.fromJson(Packets.JSON.toJson(original),Options.class);
        request.tool=Tool.SHAPE;request.shapeKind=Options.ShapeKind.BRUSH;
        request.yaw=p.yaw();request.pitch=p.pitch();request.roll=p.roll();request.sizeX=p.sizeX();request.sizeY=p.sizeY();request.sizeZ=p.sizeZ();
        return request;
    }
    /** Builds the shape in the world with the foreground block, and drops it. */
    static void confirm(){
        if(pose==null||ClientState.status.busy())return;
        Options original=ClientState.options;
        try{ClientState.options=request(pose);ClientState.strokeId=System.nanoTime();CraftiumClient.send("use",pose.origin(),new Point(0,1,0));}
        finally{ClientState.options=original;}
        pose=null;
    }

    // ---- showing

    /** What was made for one pose, kept while the shape, size and turn are the same. */
    private record Made(List<Object> key,OutlineShape outline,ShellGhost ghost){}
    private static Made made;

    private static Made make(Pose p){
        var o=ClientState.options;
        var key=List.<Object>of(o.shape,o.radius,o.hollow,o.outlineThickness,o.pixelPerfect,o.strokeAlign,Math.round(p.yaw()*1e4),Math.round(p.pitch()*1e4),Math.round(p.roll()*1e4),Math.round(p.sizeX()*1e4),Math.round(p.sizeY()*1e4),Math.round(p.sizeZ()*1e4));
        if(made!=null&&made.key().equals(key))return made;
        try{
            var cells=Geometry.shape(new Point(0,0,0),request(p));
            return made=new Made(key,OutlineShape.of(cells),ShellGhost.of(cells));
        }catch(IllegalArgumentException e){
            ClientState.notify(e.getMessage());
            return made=new Made(key,OutlineShape.EMPTY,ShellGhost.of(List.of()));
        }
    }

    /** Draws the shape: its outline, and the blocks it would be made of. While a handle is dragged it shows where the drag would leave it. */
    static void draw(Minecraft mc){
        if(pose==null||mc.level==null)return;
        var o=ClientState.options;
        Pose shown=TransformGizmo.shapeDuringDrag(pose);
        var made=make(shown);
        Point at=shown.origin();
        made.outline().draw(new Vec3(at.x(),at.y(),at.z()),(a,b)->Overlay.line(a,b,STROKE,1),STROKE,FILL,true);
        // The foreground block, so swapping it with the background block changes the shape at once; a carve shows what it would remove.
        made.ghost().draw(at,o.invert?null:ShellGhost.state(mc,o.block));
    }

    // ---- rotation maths, in the convention of Geometry.rotate: yaw about Y, then pitch about X, then roll about Z

    /** The matrix that turns a vector as {@link Geometry#rotate} does. */
    static double[][] matrix(double yaw,double pitch,double roll){
        var m=new double[3][3];
        for(int j=0;j<3;j++){
            var v=Geometry.rotate(new Geometry.Vector(j==0?1:0,j==1?1:0,j==2?1:0),yaw,pitch,roll);
            m[0][j]=v.x();m[1][j]=v.y();m[2][j]=v.z();
        }
        return m;
    }
    /** The yaw, pitch and roll, in degrees, that {@link #matrix} turns back into {@code m}. */
    static double[] euler(double[][] m){
        double pitch=Math.asin(Math.clamp(m[2][1],-1,1)),yaw=Math.atan2(m[2][0],m[2][2]),roll=Math.atan2(-m[0][1],m[1][1]);
        return new double[]{Math.toDegrees(yaw),Math.toDegrees(pitch),Math.toDegrees(roll)};
    }
    static double[][] multiply(double[][] a,double[][] b){
        double[][] m=new double[3][3];
        for(int i=0;i<3;i++)for(int j=0;j<3;j++)for(int k=0;k<3;k++)m[i][j]+=a[i][k]*b[k][j];
        return m;
    }
}
