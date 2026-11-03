package dev.craftium.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft 1.21.10 implementation of {@link Overlay}. This release has no gizmo system, so primitives
 * are kept for the frame loop and drawn after the main world pass.
 *
 * <p>Nothing here writes depth. Lines, points and box faces are flat quads from the game's see-through
 * text-background pipeline, which has depth testing off, so every primitive draws on top of the scene.
 * Lines and points are camera-facing and sized in screen pixels so they keep the same thickness at any distance.
 */
public final class Overlay {
    private sealed interface Prim permits Line,Box,Point,Label {}
    private record Line(Vec3 from,Vec3 to,int color,float width) implements Prim {}
    private record Box(AABB box,int stroke,float width,int fill) implements Prim {}
    private record Point(Vec3 at,int color,float size) implements Prim {}
    private record Label(String text,BlockPos pos,int color,int background,float scale) implements Prim {}

    private static final List<Prim> building=new ArrayList<>();
    private static List<Prim> shown=List.of();

    private Overlay(){}

    /** One tick's batch of primitives. */
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    /** Registers the frame callback that draws the published batch, and clears it on disconnect. */
    public static void install(){
        WorldRenderEvents.END_MAIN.register(Overlay::draw);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{building.clear();shown=List.of();});
    }

    public static Scope begin(Minecraft mc){building.clear();return ()->shown=List.copyOf(building);}

    public static void line(Vec3 from,Vec3 to,int color,float width){building.add(new Line(from,to,color,width));}
    public static void box(AABB box,int strokeColor,float strokeWidth,int fillColor){building.add(new Box(box,strokeColor,strokeWidth,fillColor));}
    public static void point(Vec3 at,int color,float size){building.add(new Point(at,color,size));}
    public static void text(String text,BlockPos pos,int color,int background,float scale){building.add(new Label(text,pos,color,background,scale));}

    // ---- drawing

    private static void draw(WorldRenderContext context){
        var prims=shown;
        var mc=Minecraft.getInstance();
        if(prims.isEmpty()||mc.level==null)return;
        var camera=ClientCompat.camera(mc);
        var view=new View(camera.position(),camera.getLeftVector(),camera.getUpVector(),
            2*Math.tan(Math.toRadians(ClientCompat.fov(mc,camera))/2)/Math.max(1,mc.getWindow().getHeight()));
        var pose=context.matrices().last();
        var quads=context.consumers().getBuffer(RenderType.textBackgroundSeeThrough());
        for(var prim:prims)switch(prim){
            case Box b->{
                if(visible(b.fill))faces(quads,pose,view,b.box,b.fill);
                if(visible(b.stroke)&&b.width>0)edges(quads,pose,view,b);
            }
            case Line l->{if(visible(l.color))strip(quads,pose,view,l.from,l.to,l.color,l.width);}
            case Point p->{if(visible(p.color))square(quads,pose,view,p);}
            case Label ignored->{}
        }
        // Text asks the buffer source for its own buffers, which closes the one held above, so labels go last.
        for(var prim:prims)if(prim instanceof Label t)label(context,mc,camera,view,t);
    }

    /** Camera position, its screen axes, and how many world units one pixel spans one block away. */
    private record View(Vec3 eye,org.joml.Vector3f left,org.joml.Vector3f up,double pixel) {}

    private static boolean visible(int argb){return (argb>>>24)!=0;}

    private static void vertex(VertexConsumer out,PoseStack.Pose pose,View view,Vec3 at,int color){
        out.addVertex(pose,(float)(at.x-view.eye.x),(float)(at.y-view.eye.y),(float)(at.z-view.eye.z)).setColor(color).setLight(LightTexture.FULL_BRIGHT);
    }

    /** Emits a quad twice, once per winding, so the pipeline's face culling cannot hide it. */
    private static void twoSided(VertexConsumer out,PoseStack.Pose pose,View view,int color,Vec3 a,Vec3 b,Vec3 c,Vec3 d){
        vertex(out,pose,view,a,color);vertex(out,pose,view,b,color);vertex(out,pose,view,c,color);vertex(out,pose,view,d,color);
        vertex(out,pose,view,d,color);vertex(out,pose,view,c,color);vertex(out,pose,view,b,color);vertex(out,pose,view,a,color);
    }

    /** A line as a flat strip turned to face the camera, {@code pixels} wide on screen. */
    private static void strip(VertexConsumer out,PoseStack.Pose pose,View view,Vec3 from,Vec3 to,int color,float pixels){
        Vec3 along=to.subtract(from),toCamera=view.eye.subtract(from.add(to).scale(.5));
        double half=Math.max(pixels,.5f)*view.pixel*Math.max(toCamera.length(),.05)/2;
        Vec3 side=along.cross(toCamera);
        side=side.lengthSqr()<1e-12?new Vec3(view.up.x(),view.up.y(),view.up.z()):side;
        side=side.normalize().scale(half);
        twoSided(out,pose,view,color,from.add(side),to.add(side),to.subtract(side),from.subtract(side));
    }

    private static void square(VertexConsumer out,PoseStack.Pose pose,View view,Point p){
        double half=Math.max(p.size,.5f)*view.pixel*Math.max(view.eye.distanceTo(p.at),.05)/2;
        Vec3 left=new Vec3(view.left.x(),view.left.y(),view.left.z()).scale(half),up=new Vec3(view.up.x(),view.up.y(),view.up.z()).scale(half);
        twoSided(out,pose,view,p.color,p.at.add(left).add(up),p.at.subtract(left).add(up),p.at.subtract(left).subtract(up),p.at.add(left).subtract(up));
    }

    private static Vec3 corner(AABB box,int index){
        return new Vec3((index&1)==0?box.minX:box.maxX,(index&2)==0?box.minY:box.maxY,(index&4)==0?box.minZ:box.maxZ);
    }

    private static void edges(VertexConsumer out,PoseStack.Pose pose,View view,Box b){
        for(int i=0;i<8;i++)for(int bit:new int[]{1,2,4})if((i&bit)==0)strip(out,pose,view,corner(b.box,i),corner(b.box,i|bit),b.stroke,b.width);
    }

    /** The six faces, each wound counter-clockwise from outside so culling drops the ones facing away. */
    private static void faces(VertexConsumer out,PoseStack.Pose pose,View view,AABB box,int color){
        double[] min={box.minX,box.minY,box.minZ},max={box.maxX,box.maxY,box.maxZ};
        for(int axis=0;axis<3;axis++)for(int sign=-1;sign<=1;sign+=2){
            int u=(axis+1)%3,v=(axis+2)%3;
            double fixed=sign>0?max[axis]:min[axis];
            Vec3[] ring=new Vec3[4];
            double[][] uv={{min[u],min[v]},{max[u],min[v]},{max[u],max[v]},{min[u],max[v]}};
            for(int i=0;i<4;i++){double[] c=new double[3];c[axis]=fixed;c[u]=uv[i][0];c[v]=uv[i][1];ring[i]=new Vec3(c[0],c[1],c[2]);}
            double[] normal=new double[3];normal[axis]=sign;
            Vec3 cross=ring[1].subtract(ring[0]).cross(ring[2].subtract(ring[0]));
            boolean outward=cross.x*normal[0]+cross.y*normal[1]+cross.z*normal[2]>0;
            if(outward)quad(out,pose,view,color,ring[0],ring[1],ring[2],ring[3]);
            else quad(out,pose,view,color,ring[3],ring[2],ring[1],ring[0]);
        }
    }

    private static void quad(VertexConsumer out,PoseStack.Pose pose,View view,int color,Vec3 a,Vec3 b,Vec3 c,Vec3 d){
        vertex(out,pose,view,a,color);vertex(out,pose,view,b,color);vertex(out,pose,view,c,color);vertex(out,pose,view,d,color);
    }

    /** A camera-facing label over the block, positioned and scaled as the 26.2 implementation does. */
    private static void label(WorldRenderContext context,Minecraft mc,net.minecraft.client.Camera camera,View view,Label label){
        var stack=context.matrices();
        stack.pushPose();
        stack.translate(label.pos.getX()+.5-view.eye.x,label.pos.getY()+1.3-view.eye.y,label.pos.getZ()+.5-view.eye.z);
        stack.mulPose(camera.rotation());
        stack.scale(label.scale,-label.scale,label.scale);
        Font font=mc.font;
        font.drawInBatch(label.text,-font.width(label.text)/2f,0,label.color,false,stack.last().pose(),context.consumers(),Font.DisplayMode.SEE_THROUGH,label.background,LightTexture.FULL_BRIGHT);
        stack.popPose();
    }
}
