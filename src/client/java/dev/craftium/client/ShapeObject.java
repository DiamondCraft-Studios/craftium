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
public final class ShapeObject {
    /** Where the shape is, how it is turned (a rotation matrix, applied after the sizes), and its size along each of its own axes. */
    record Pose(Point origin,double[][] rotation,double sizeX,double sizeY,double sizeZ){
        Pose withOrigin(Point at){return new Pose(at,rotation,sizeX,sizeY,sizeZ);}
        double yaw(){return euler(rotation)[0];}
        double pitch(){return euler(rotation)[1];}
        double roll(){return euler(rotation)[2];}
    }

    private static final double[][] IDENTITY={{1,0,0},{0,1,0},{0,0,1}};
    /** Shapes are limited to this reach (see {@link Geometry#shape}), so sizes are limited to keep inside it. */
    private static final double REACH=BrushMath.MAX_REACH;
    private static Pose pose;
    private ShapeObject(){}

    public static boolean active(){return pose!=null;}
    static Pose pose(){return pose;}
    /** True while the transform gizmo works on the shape: with the Move, Rotate and Scale tools, and with the Shape tool itself. */
    static boolean gizmoOn(){
        if(pose==null)return false;
        var tool=ClientState.options.tool;
        return tool==Tool.SHAPE||tool.transform()&&tool!=Tool.PIVOT;
    }

    /** How the shape is turned while it is only a hover preview, so the turn chosen before putting it down is the turn it is put down with. */
    private static double[][] hover=IDENTITY;

    /** Puts the shape down at {@code at}; if it is already down, moves it there and keeps its turn and size. */
    public static void place(Point at){
        if(pose==null){var o=ClientState.options;pose=new Pose(at,hover,o.sizeX,o.sizeY,o.sizeZ);ClientState.notify("Shape ready · Move, Rotate and Scale change it · Enter to build · Esc to cancel");}
        else pose=pose.withOrigin(at);
    }
    /** The hover preview's pose when it is turned, or null when it is not and the plain brush shape is the preview. */
    static Pose hoverPose(){var o=ClientState.options;return hover==IDENTITY&&dev.craftium.core.ShapeSizes.isDefault(o)?null:new Pose(new Point(0,0,0),hover,o.sizeX,o.sizeY,o.sizeZ);}
    /** The sizes chosen in the shape's size controls go to a shape already put down. */
    static void adoptSizes(){var o=ClientState.options;if(pose!=null)pose=fit(new Pose(pose.origin(),pose.rotation(),o.sizeX,o.sizeY,o.sizeZ),o.outerRadius());}
    /** The size controls show the sizes a shape put down has, which its handles may have changed. */
    static void syncOptions(){var o=ClientState.options;if(pose!=null){o.sizeX=pose.sizeX();o.sizeY=pose.sizeY();o.sizeZ=pose.sizeZ();}}
    /** Turns the shape, or the hover preview before it is down, by {@code degrees} about a world axis (right-handed). */
    static void turnAbout(Vec3 axis,double degrees){
        double length=axis.length();if(length==0)return;
        double x=axis.x/length,y=axis.y/length,z=axis.z/length,c=Math.cos(Math.toRadians(degrees)),s=Math.sin(Math.toRadians(degrees)),t=1-c;
        double[][] turn={{t*x*x+c,t*x*y-s*z,t*x*z+s*y},{t*x*y+s*z,t*y*y+c,t*y*z-s*x},{t*x*z-s*y,t*y*z+s*x,t*z*z+c}};
        for(var row:turn)for(int j=0;j<3;j++)row[j]=Math.abs(row[j])<1e-9?0:Math.rint(row[j]*1e9)/1e9; // quarter turns stay exact
        if(pose!=null)pose=new Pose(pose.origin(),multiply(turn,pose.rotation()),pose.sizeX(),pose.sizeY(),pose.sizeZ());
        else hover=multiply(turn,hover);
    }
    static void cancel(){pose=null;}
    /** Undo and redo put the shape back as it was waiting to be built ("x,y,z,yaw,pitch,roll,sizeX,sizeY,sizeZ"), or take it away again ("none"). */
    static void restore(String text){
        if(text.equals("none")){pose=null;return;}
        try{
            String[] v=text.split(",");if(v.length!=9)return;
            pose=new Pose(new Point(Integer.parseInt(v[0]),Integer.parseInt(v[1]),Integer.parseInt(v[2])),matrix(Double.parseDouble(v[3]),Double.parseDouble(v[4]),Double.parseDouble(v[5])),Double.parseDouble(v[6]),Double.parseDouble(v[7]),Double.parseDouble(v[8]));
        }catch(NumberFormatException ignored){}
    }
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
        o.radius=Math.clamp(o.radius+step,1,dev.craftium.core.ShapeLimits.maxRadius(o));
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
    static Options request(Pose p){
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
        var key=List.<Object>of(o.shape,o.radius,o.hollow,o.outline,o.outlineThickness,o.pixelPerfect,o.strokeAlign,o.polygonSides,Math.round(p.yaw()*1e4),Math.round(p.pitch()*1e4),Math.round(p.roll()*1e4),Math.round(p.sizeX()*1e4),Math.round(p.sizeY()*1e4),Math.round(p.sizeZ()*1e4));
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
        made.outline().drawPlain(new Vec3(at.x(),at.y(),at.z()),ClientState.options.previewColor,(float)ClientState.options.selectionWidth,ClientState.options.previewFill,true,false);
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
        // Pointing straight up or down (a quarter tilt) the yaw and roll turn about the same line, so the two formulas above find nothing to
        // read; the whole turn is then taken as roll, which is read from the first column.
        if(Math.abs(m[2][1])>1-1e-9){yaw=0;roll=Math.atan2(m[1][0],m[0][0]);}
        return new double[]{Math.toDegrees(yaw),Math.toDegrees(pitch),Math.toDegrees(roll)};
    }
    static double[][] multiply(double[][] a,double[][] b){
        double[][] m=new double[3][3];
        for(int i=0;i<3;i++)for(int j=0;j<3;j++)for(int k=0;k<3;k++)m[i][j]+=a[i][k]*b[k][j];
        return m;
    }
}
