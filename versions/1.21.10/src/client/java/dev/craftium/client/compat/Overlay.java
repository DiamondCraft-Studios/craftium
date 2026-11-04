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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
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
    private sealed interface Prim permits Line,Box,Point,Label,Quad,Blocks {}
    private record Line(Vec3 from,Vec3 to,int color,float width,boolean scene) implements Prim {}
    private record Box(AABB box,int stroke,float width,int fill) implements Prim {}
    private record Point(Vec3 at,int color,float size) implements Prim {}
    private record Label(String text,BlockPos pos,int color,int background,float scale) implements Prim {}
    private record Quad(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color) implements Prim {}
    private record Blocks(List<BlockPos> models,List<BlockPos> places,java.util.Set<BlockPos> placed,List<net.minecraft.world.level.block.state.BlockState> states,int tint) implements Prim {}

    private static final List<Prim> building=new ArrayList<>();
    private static List<Prim> shown=List.of();
    /** Where the primitives being submitted go: the tick's batch, or the frame's own while a frame hook runs. */
    private static List<Prim> out=building;
    private static Runnable pendingFrame,frame;

    private Overlay(){}

    /** One tick's batch of primitives. */
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    /** Registers the frame callback that draws the published batch, and clears it on disconnect. */
    public static void install(){
        WorldRenderEvents.END_MAIN.register(Overlay::draw);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{building.clear();shown=List.of();pendingFrame=frame=null;});
    }

    public static Scope begin(Minecraft mc){building.clear();pendingFrame=null;return ()->{shown=List.copyOf(building);frame=pendingFrame;};}

    /**
     * Draws with {@code draw} on every frame, not just every tick, until the next batch replaces it. For what follows the
     * camera or the pointer, like a gizmo, which would otherwise step at the tick rate. Block models belong in the tick's batch.
     */
    public static void frame(Runnable draw){pendingFrame=draw;}

    public static void line(Vec3 from,Vec3 to,int color,float width){out.add(new Line(from,to,color,width,false));}
    /** A line that belongs to the scene: blocks in front of it hide it, so it passes through them. Drawn in a pass that tests depth. */
    public static void sceneLine(Vec3 from,Vec3 to,int color,float width){out.add(new Line(from,to,color,width,true));}
    public static void box(AABB box,int strokeColor,float strokeWidth,int fillColor){out.add(new Box(box,strokeColor,strokeWidth,fillColor));}
    public static void point(Vec3 at,int color,float size){out.add(new Point(at,color,size));}
    /** A filled quad. Corners are in order, counter-clockwise when seen from the side it faces. */
    public static void face(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){out.add(new Quad(a,b,c,d,color));}
    /**
     * The real models of the blocks at {@code positions}, drawn translucent and tinted by {@code tint} (ARGB)
     * over the blocks themselves, so stairs, slabs and fences are highlighted in their true shape. The list is
     * not copied and must not change after this call.
     */
    public static void blocks(List<BlockPos> positions,int tint){blocks(positions,positions,tint);}
    /**
     * Like {@link #blocks(List,int)}, but the model of {@code models.get(i)} is drawn at {@code places.get(i)}. This
     * previews blocks that are about to move. Both lists must have the same size and are not copied.
     */
    public static void blocks(List<BlockPos> models,List<BlockPos> places,int tint){out.add(new Blocks(models,places,new java.util.HashSet<>(places),null,tint));}
    /**
     * Draws {@code states.get(i)} at {@code places.get(i)}, for blocks that are not in the world yet, such as an edit
     * waiting to be confirmed. Both lists must have the same size and are not copied.
     */
    public static void blockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,int tint){blockStates(states,places,new java.util.HashSet<>(places),tint);}
    /** As above, for a caller that keeps {@code placed} (the same positions as a set) between ticks instead of paying to rebuild it each time. */
    public static void blockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,java.util.Set<BlockPos> placed,int tint){out.add(new Blocks(places,places,placed,states,tint));}
    public static void text(String text,BlockPos pos,int color,int background,float scale){out.add(new Label(text,pos,color,background,scale));}

    // ---- drawing

    private static void draw(WorldRenderContext context){
        var prims=shown;
        var mc=Minecraft.getInstance();
        if(frame!=null&&mc.level!=null){
            var extra=new ArrayList<Prim>(prims);var saved=out;out=extra;
            try{frame.run();}finally{out=saved;}
            prims=extra;
        }
        if(prims.isEmpty()||mc.level==null)return;
        var camera=ClientCompat.camera(mc);
        var view=new View(camera.position(),camera.getLeftVector(),camera.getUpVector(),
            2*Math.tan(Math.toRadians(ClientCompat.fov(mc,camera))/2)/Math.max(1,mc.getWindow().getHeight()));
        // Block ghosts go first, then the lines, boxes and points over them, so handles and outlines are never covered by a ghost.
        for(var prim:prims)if(prim instanceof Blocks g)blocks(context,mc,view,g);
        var pose=context.matrices().last();
        var quads=context.consumers().getBuffer(RenderType.textBackgroundSeeThrough());
        for(var prim:prims)switch(prim){
            case Box b->{
                if(visible(b.fill))faces(quads,pose,view,b.box,b.fill);
                if(visible(b.stroke)&&b.width>0)edges(quads,pose,view,b);
            }
            case Line l->{if(!l.scene&&visible(l.color))strip(quads,pose,view,l.from,l.to,l.color,l.width);}
            case Point p->{if(visible(p.color))square(quads,pose,view,p);}
            case Quad q->{if(visible(q.color))quad(quads,pose,view,q.color,q.a,q.b,q.c,q.d);}
            case Label ignored->{}
            case Blocks ignored->{}
        }
        // Scene lines are drawn by the pipeline of the game's ordinary text background, which tests depth, so blocks in front hide them.
        if(prims.stream().anyMatch(p->p instanceof Line l&&l.scene)){
            var scene=context.consumers().getBuffer(RenderType.textBackground());
            for(var prim:prims)if(prim instanceof Line l&&l.scene&&visible(l.color))strip(scene,pose,view,l.from,l.to,l.color,l.width);
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

    /** Each model is scaled this much about its centre so the ghost clears the real block instead of fighting it for depth. */
    private static final float GHOST_SCALE=1.004f;
    private static final Direction[] FACES=Direction.values();

    /** True when the neighbouring block's matching side is fully solid, so a face on this side could never be seen. */
    private static boolean hidden(net.minecraft.world.level.BlockGetter level,BlockPos pos,Direction face){
        BlockPos next=pos.relative(face);
        return level.getBlockState(next).isFaceSturdy(level,next,face.getOpposite());
    }

    /**
     * True when a quad facing {@code d} can be seen from the camera, whose offset from the block's centre is
     * ({@code dx},{@code dy},{@code dz}). {@code reach} is how far out the quad sits: 0.5 for the block's own sides,
     * 0 for quads inside the model. Back faces are skipped, because the pipeline does not cull them and they
     * would show through the near side as the inside of the selection.
     */
    private static boolean facing(Direction d,double dx,double dy,double dz,double reach){
        return d.getStepX()*dx+d.getStepY()*dy+d.getStepZ()*dz>reach;
    }

    private static void blocks(WorldRenderContext context,Minecraft mc,View view,Blocks ghost){
        var level=mc.level;
        var consumer=context.consumers().getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        var stack=context.matrices();
        float a=(ghost.tint>>>24)/255f,r=(ghost.tint>>16&255)/255f,g=(ghost.tint>>8&255)/255f,b=(ghost.tint&255)/255f;
        var random=RandomSource.create();
        for(int i=0;i<ghost.places.size();i++){
            BlockPos pos=ghost.places.get(i);
            var state=ghost.states!=null?ghost.states.get(i):level.getBlockState(ghost.models.get(i));
            if(state.isAir()||state.getRenderShape()!=RenderShape.MODEL)continue; // fluids and block entities have no plain model
            random.setSeed(state.getSeed(pos));
            var parts=mc.getBlockRenderer().getBlockModel(state).collectParts(random);
            double dx=view.eye.x-pos.getX()-.5,dy=view.eye.y-pos.getY()-.5,dz=view.eye.z-pos.getZ()-.5;
            stack.pushPose();
            stack.translate(pos.getX()+.5-view.eye.x,pos.getY()+.5-view.eye.y,pos.getZ()+.5-view.eye.z);
            stack.scale(GHOST_SCALE,GHOST_SCALE,GHOST_SCALE);
            stack.translate(-.5,-.5,-.5);
            var pose=stack.last();
            for(var part:parts){
                for(var quad:part.getQuads(null))if(facing(quad.direction(),dx,dy,dz,0))consumer.putBulkData(pose,quad,r,g,b,a,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
                for(Direction face:FACES){
                    // Only the outer surface: not a side that touches another ghost, is covered as in the world, or faces away.
                    if(ghost.placed.contains(pos.relative(face))||hidden(level,pos,face)||!facing(face,dx,dy,dz,.5))continue;
                    for(var quad:part.getQuads(face))consumer.putBulkData(pose,quad,r,g,b,a,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
                }
            }
            stack.popPose();
        }
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
