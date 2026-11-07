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
    private record Label(String text,Vec3 pos,int color,int background,float scale) implements Prim {}
    private record Quad(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color,boolean scene) implements Prim {}
    private record Blocks(List<BlockPos> models,List<BlockPos> places,java.util.Set<BlockPos> placed,List<net.minecraft.world.level.block.state.BlockState> states,int tint,boolean cached) implements Prim {}

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
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{building.clear();shown=List.of();pendingFrame=frame=null;LOOSE.clear();});
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
    public static void face(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){out.add(new Quad(a,b,c,d,color,false));}
    /** A filled quad that belongs to the scene: blocks in front of it hide it. Drawn in the pass that tests depth. */
    public static void sceneFace(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){out.add(new Quad(a,b,c,d,color,true));}
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
    public static void blocks(List<BlockPos> models,List<BlockPos> places,int tint){out.add(new Blocks(models,places,new java.util.HashSet<>(places),null,tint,false));}
    /**
     * Draws {@code states.get(i)} at {@code places.get(i)}, for blocks that are not in the world yet, such as an edit
     * waiting to be confirmed. Both lists must have the same size and are not copied.
     */
    public static void blockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,int tint){blockStates(states,places,new java.util.HashSet<>(places),tint);}
    /** As above, for a caller that keeps {@code placed} (the same positions as a set) between ticks instead of paying to rebuild it each time. */
    public static void blockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,java.util.Set<BlockPos> placed,int tint){out.add(new Blocks(places,places,placed,states,tint,false));}
    /**
     * As {@link #blockStates(List,List,int)}, for a ghost that stays the same from frame to frame: the same two list objects must be
     * passed for as long as it does. What only depends on the blocks (which sides are on the outer surface, their models and tints)
     * is worked out once, the first frame, and kept until a frame passes without the ghost. In exchange the ghost does not notice
     * the world changing around it after that first frame.
     */
    public static void cachedBlockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,int tint){out.add(new Blocks(places,places,null,states,tint,true));}
    public static void text(String text,BlockPos pos,int color,int background,float scale){text(text,new Vec3(pos.getX()+.5,pos.getY()+1.3,pos.getZ()+.5),color,background,scale);}
    /** A label centred on the point {@code at}. */
    public static void text(String text,Vec3 at,int color,int background,float scale){out.add(new Label(text,at,color,background,scale));}

    // ---- drawing

    private static void draw(WorldRenderContext context){
        var prims=shown;
        var mc=Minecraft.getInstance();
        if(frame!=null&&mc.level!=null){
            var extra=new ArrayList<Prim>(prims);var saved=out;out=extra;
            try{frame.run();}finally{out=saved;}
            prims=extra;
        }
        if(prims.isEmpty()||mc.level==null){PREPARED.clear();return;}
        var camera=ClientCompat.camera(mc);
        var view=new View(camera.position(),camera.getLeftVector(),camera.getUpVector(),
            2*Math.tan(Math.toRadians(ClientCompat.fov(mc,camera))/2)/Math.max(1,mc.getWindow().getHeight()));
        // Block ghosts go first, then the lines, boxes and points over them, so handles and outlines are never covered by a ghost.
        TOUCHED.clear();
        for(var prim:prims)if(prim instanceof Blocks g)blocks(context,mc,view,g);
        PREPARED.keySet().retainAll(TOUCHED); // a ghost that was not drawn this frame is gone
        var pose=context.matrices().last();
        var quads=context.consumers().getBuffer(RenderType.textBackgroundSeeThrough());
        for(var prim:prims)switch(prim){
            case Box b->{
                if(visible(b.fill))faces(quads,pose,view,b.box,b.fill);
                if(visible(b.stroke)&&b.width>0)edges(quads,pose,view,b);
            }
            case Line l->{if(!l.scene&&visible(l.color))strip(quads,pose,view,l.from,l.to,l.color,l.width);}
            case Point p->{if(visible(p.color))square(quads,pose,view,p);}
            case Quad q->{if(!q.scene&&visible(q.color))quad(quads,pose,view,q.color,q.a,q.b,q.c,q.d);}
            case Label ignored->{}
            case Blocks ignored->{}
        }
        // Scene lines are drawn by the pipeline of the game's ordinary text background, which tests depth, so blocks in front hide them.
        if(prims.stream().anyMatch(p->p instanceof Line l&&l.scene||p instanceof Quad q&&q.scene)){
            var scene=context.consumers().getBuffer(RenderType.textBackground());
            for(var prim:prims){
                if(prim instanceof Line l&&l.scene&&visible(l.color))strip(scene,pose,view,l.from,l.to,l.color,l.width);
                else if(prim instanceof Quad q&&q.scene&&visible(q.color))quad(scene,pose,view,q.color,q.a,q.b,q.c,q.d);
            }
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
    /** Depth precision runs out with distance, so the further a block is the more its ghost stands clear of it. */
    private static float ghostScale(double dx,double dy,double dz){return (float)Math.min(.03,GHOST_SCALE-1+Math.sqrt(dx*dx+dy*dy+dz*dz)*.0004)+1f;}
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

    /** What a cached ghost needs for every block that has anything to show, worked out once: see {@link #cachedBlockStates}. */
    private static final class Prepared {
        final List<BlockPos> pos=new ArrayList<>();
        final List<net.minecraft.world.level.block.state.BlockState> states=new ArrayList<>();
        /** For each block the sides of it on the outer surface, one bit per {@link Direction#ordinal()}. */
        final List<Integer> outer=new ArrayList<>();
        /** Whether the block has quads that belong to no side (a flower, say), which are drawn whenever they face the camera. */
        final List<Boolean> loose=new ArrayList<>();
        final List<List<net.minecraft.client.renderer.block.model.BlockModelPart>> parts=new ArrayList<>();
        int[] tints;
    }
    private static final java.util.Map<List<BlockPos>,Prepared> PREPARED=new java.util.IdentityHashMap<>();
    private static final java.util.Set<List<BlockPos>> TOUCHED=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private static Prepared prepare(Minecraft mc,Blocks ghost){
        var level=mc.level;var out=new Prepared();
        var placed=ghost.placed!=null?ghost.placed:new java.util.HashSet<>(ghost.places);
        var random=RandomSource.create();
        for(int i=0;i<ghost.places.size();i++){
            BlockPos pos=ghost.places.get(i);var state=ghost.states.get(i);
            if(state.isAir()||state.getRenderShape()!=RenderShape.MODEL)continue; // fluids and block entities have no plain model
            random.setSeed(state.getSeed(pos));
            var parts=mc.getBlockRenderer().getBlockModel(state).collectParts(random);
            int mask=0;
            for(Direction face:FACES)if(!placed.contains(pos.relative(face))&&!hidden(level,pos,face))mask|=1<<face.ordinal();
            boolean loose=false;for(var part:parts)if(!part.getQuads(null).isEmpty())loose=true;
            if(mask==0&&!loose)continue; // buried inside the ghost: nothing of it can ever be seen
            out.pos.add(pos);out.states.add(state);out.outer.add(mask);out.loose.add(loose);out.parts.add(parts);
        }
        out.tints=new int[out.pos.size()*4];java.util.Arrays.fill(out.tints,-2);
        return out;
    }

    /** The sides of a block facing a camera at ({@code dx},{@code dy},{@code dz}) from its centre, one bit per {@link Direction#ordinal()}, as {@link #facing} judges them. */
    private static int facingMask(double dx,double dy,double dz){
        return (-dy>.5?1:0)|(dy>.5?2:0)|(-dz>.5?4:0)|(dz>.5?8:0)|(-dx>.5?16:0)|(dx>.5?32:0);
    }

    private static void cachedBlocks(WorldRenderContext context,Minecraft mc,View view,Blocks ghost){
        TOUCHED.add(ghost.places);
        var prepared=PREPARED.computeIfAbsent(ghost.places,k->prepare(mc,ghost));
        var consumer=context.consumers().getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        var stack=context.matrices();
        float a=(ghost.tint>>>24)/255f,r=(ghost.tint>>16&255)/255f,g=(ghost.tint>>8&255)/255f,b=(ghost.tint&255)/255f;
        for(int i=0;i<prepared.pos.size();i++){
            BlockPos pos=prepared.pos.get(i);
            double dx=view.eye.x-pos.getX()-.5,dy=view.eye.y-pos.getY()-.5,dz=view.eye.z-pos.getZ()-.5;
            int visible=prepared.outer.get(i)&facingMask(dx,dy,dz);
            boolean loose=prepared.loose.get(i);
            if(visible==0&&!loose)continue; // every side that shows faces away: nothing to draw, so not even the transform is set up
            var state=prepared.states.get(i);
            final int base=i*4;final var tints=prepared.tints;
            final var tintOf=(java.util.function.IntUnaryOperator)index->{ // biome tints such as grass and leaves, looked up once per block
                if(index<0||index>=4)return 0xFFFFFF;
                if(tints[base+index]==-2){int t=ClientCompat.blockTint(mc,state,pos,index);tints[base+index]=t==-1?0xFFFFFF:t&0xFFFFFF;}
                return tints[base+index];
            };
            stack.pushPose();
            stack.translate(pos.getX()+.5-view.eye.x,pos.getY()+.5-view.eye.y,pos.getZ()+.5-view.eye.z);
            float grow=ghostScale(dx,dy,dz);
            stack.scale(grow,grow,grow);
            stack.translate(-.5,-.5,-.5);
            var pose=stack.last();
            for(var part:prepared.parts.get(i)){
                if(loose)for(var quad:part.getQuads(null))if(facing(quad.direction(),dx,dy,dz,0)){int t=tintOf.applyAsInt(quad.tintIndex());consumer.putBulkData(pose,quad,r*(t>>16&255)/255f,g*(t>>8&255)/255f,b*(t&255)/255f,a,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);}
                if(visible!=0)for(Direction face:FACES){
                    if((visible>>face.ordinal()&1)==0)continue;
                    for(var quad:part.getQuads(face)){int t=tintOf.applyAsInt(quad.tintIndex());consumer.putBulkData(pose,quad,r*(t>>16&255)/255f,g*(t>>8&255)/255f,b*(t&255)/255f,a,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);}
                }
            }
            stack.popPose();
        }
    }

    /** The sides of the block at {@code pos} that show from a camera ({@code dx},{@code dy},{@code dz}) from its centre: facing it, not touching another ghost block and not covered as in the world. One bit per {@link Direction#ordinal()}. */
    private static int visibleSides(net.minecraft.world.level.BlockGetter level,java.util.Set<BlockPos> placed,BlockPos pos,double dx,double dy,double dz){
        int turned=facingMask(dx,dy,dz),mask=0;
        if(turned==0)return 0;
        for(Direction face:FACES)if((turned>>face.ordinal()&1)!=0&&!placed.contains(pos.relative(face))&&!hidden(level,pos,face))mask|=1<<face.ordinal();
        return mask;
    }
    /** Whether a block state has quads that belong to no side (a flower, say). Worked out once per state, because it only depends on the model. */
    private static final java.util.Map<net.minecraft.world.level.block.state.BlockState,Boolean> LOOSE=new java.util.IdentityHashMap<>();
    private static boolean hasLooseQuads(Minecraft mc,net.minecraft.world.level.block.state.BlockState state,BlockPos pos){
        var known=LOOSE.get(state);
        if(known!=null)return known;
        var random=RandomSource.create(state.getSeed(pos));
        boolean loose=false;for(var part:mc.getBlockRenderer().getBlockModel(state).collectParts(random))if(!part.getQuads(null).isEmpty())loose=true;
        LOOSE.put(state,loose);return loose;
    }

    private static void blocks(WorldRenderContext context,Minecraft mc,View view,Blocks ghost){
        if(ghost.cached&&ghost.states!=null){cachedBlocks(context,mc,view,ghost);return;}
        var level=mc.level;
        var consumer=context.consumers().getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        var stack=context.matrices();
        float a=(ghost.tint>>>24)/255f,r=(ghost.tint>>16&255)/255f,g=(ghost.tint>>8&255)/255f,b=(ghost.tint&255)/255f;
        var random=RandomSource.create();
        for(int i=0;i<ghost.places.size();i++){
            BlockPos pos=ghost.places.get(i);
            var state=ghost.states!=null?ghost.states.get(i):level.getBlockState(ghost.models.get(i));
            if(state.isAir()||state.getRenderShape()!=RenderShape.MODEL)continue; // fluids and block entities have no plain model
            final var tints=new int[]{-2,-2,-2,-2};
            final var tintOf=(java.util.function.IntUnaryOperator)index->{ // biome tints such as grass and leaves, looked up once per block
                if(index<0||index>=tints.length)return 0xFFFFFF;
                if(tints[index]==-2){int t=ClientCompat.blockTint(mc,state,pos,index);tints[index]=t==-1?0xFFFFFF:t&0xFFFFFF;}
                return tints[index];
            };
            double dx=view.eye.x-pos.getX()-.5,dy=view.eye.y-pos.getY()-.5,dz=view.eye.z-pos.getZ()-.5;
            // Most blocks of a big ghost are buried or turned away: decide that from the sides alone, before any model or transform is touched.
            int visible=visibleSides(level,ghost.placed,pos,dx,dy,dz);
            if(visible==0&&!hasLooseQuads(mc,state,pos))continue;
            random.setSeed(state.getSeed(pos));
            var parts=mc.getBlockRenderer().getBlockModel(state).collectParts(random);
            stack.pushPose();
            stack.translate(pos.getX()+.5-view.eye.x,pos.getY()+.5-view.eye.y,pos.getZ()+.5-view.eye.z);
            float grow=ghostScale(dx,dy,dz);
            stack.scale(grow,grow,grow);
            stack.translate(-.5,-.5,-.5);
            var pose=stack.last();
            for(var part:parts){
                for(var quad:part.getQuads(null))if(facing(quad.direction(),dx,dy,dz,0)){int t=tintOf.applyAsInt(quad.tintIndex());consumer.putBulkData(pose,quad,r*(t>>16&255)/255f,g*(t>>8&255)/255f,b*(t&255)/255f,a,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);}
                for(Direction face:FACES){
                    if((visible>>face.ordinal()&1)==0)continue; // only the outer surface: not a side that touches another ghost, is covered as in the world, or faces away
                    for(var quad:part.getQuads(face)){int t=tintOf.applyAsInt(quad.tintIndex());consumer.putBulkData(pose,quad,r*(t>>16&255)/255f,g*(t>>8&255)/255f,b*(t&255)/255f,a,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);}
                }
            }
            stack.popPose();
        }
    }

    /** A camera-facing label over the block, positioned and scaled as the 26.2 implementation does. */
    private static void label(WorldRenderContext context,Minecraft mc,net.minecraft.client.Camera camera,View view,Label label){
        var stack=context.matrices();
        stack.pushPose();
        stack.translate(label.pos.x-view.eye.x,label.pos.y-view.eye.y,label.pos.z-view.eye.z);
        stack.mulPose(camera.rotation());
        stack.scale(label.scale,-label.scale,label.scale);
        Font font=mc.font;
        font.drawInBatch(label.text,-font.width(label.text)/2f,0,label.color,false,stack.last().pose(),context.consumers(),Font.DisplayMode.SEE_THROUGH,label.background,LightTexture.FULL_BRIGHT);
        stack.popPose();
    }
}
