package dev.craftium.client.compat;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * In-world overlay primitives (lines, boxes, points, labels) drawn on top of the scene.
 * Colours are ARGB; a stroke width or fill colour of 0 means "none". Primitives submitted between
 * {@link #begin} and {@link Scope#close()} stay visible until the next tick's batch replaces them.
 * This is the 26.2 implementation. Lines, boxes, faces, points and labels go to the game's gizmo system;
 * block models have no gizmo, so they are queued here and submitted from the level render event each frame.
 */
public final class Overlay {
    private record Ghost(List<BlockPos> models,List<BlockPos> places,java.util.Set<BlockPos> placed,List<net.minecraft.world.level.block.state.BlockState> states,int tint,boolean cached){}
    private static final List<Ghost> building=new ArrayList<>();
    private static List<Ghost> shown=List.of();
    private static Runnable pendingFrame,frame;
    /** Each model is scaled this much about its centre so the ghost clears the real block instead of fighting it for depth. */
    private static final float GHOST_SCALE=1.004f;
    /** Depth precision runs out with distance, so the further a block is the more its ghost stands clear of it. */
    private static float ghostScale(double dx,double dy,double dz){return (float)Math.min(.03,GHOST_SCALE-1+Math.sqrt(dx*dx+dy*dy+dz*dz)*.0004)+1f;}
    private static final int FULL_BRIGHT=0xF000F0;
    private static final Direction[] FACES=Direction.values();

    private Overlay(){}

    /** One tick's batch of primitives. */
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    /** Registers the per-frame submission of block models, and clears them on disconnect. Gizmos need no setup. */
    public static void install(){
        LevelRenderEvents.COLLECT_SUBMITS.register(Overlay::submitBlocks);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{building.clear();shown=List.of();pendingFrame=frame=null;LOOSE.clear();});
    }

    public static Scope begin(Minecraft mc){
        var collector=mc.collectPerTickGizmos();
        building.clear();pendingFrame=null;
        return ()->{shown=List.copyOf(building);frame=pendingFrame;collector.close();};
    }

    /**
     * Draws with {@code draw} on every frame, not just every tick, until the next batch replaces it. For what follows the
     * camera or the pointer, like a gizmo, which would otherwise step at the tick rate. Only lines, boxes, faces, points
     * and labels belong in it; block models are queued per tick.
     */
    public static void frame(Runnable draw){pendingFrame=draw;}

    public static void line(Vec3 from,Vec3 to,int color,float width){Gizmos.line(from,to,color,width).setAlwaysOnTop();}
    /** A line that belongs to the scene: blocks in front of it hide it, so it passes through them. */
    public static void sceneLine(Vec3 from,Vec3 to,int color,float width){Gizmos.line(from,to,color,width);}
    public static void box(AABB box,int strokeColor,float strokeWidth,int fillColor){Gizmos.cuboid(box,new GizmoStyle(strokeColor,strokeWidth,fillColor)).setAlwaysOnTop();}
    /** A filled quad. Corners are in order, counter-clockwise when seen from the side it faces. */
    public static void face(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){Gizmos.rect(a,b,c,d,GizmoStyle.fill(color)).setAlwaysOnTop();}
    /** A filled quad that belongs to the scene: blocks in front of it hide it. */
    public static void sceneFace(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){Gizmos.rect(a,b,c,d,GizmoStyle.fill(color));}
    /**
     * The real models of the blocks at {@code positions}, drawn translucent and tinted by {@code tint} (ARGB)
     * over the blocks themselves. The list is not copied and must not change after this call.
     */
    public static void blocks(List<BlockPos> positions,int tint){blocks(positions,positions,tint);}
    /**
     * Like {@link #blocks(List,int)}, but the model of {@code models.get(i)} is drawn at {@code places.get(i)}. This
     * previews blocks that are about to move. Both lists must have the same size and are not copied.
     */
    public static void blocks(List<BlockPos> models,List<BlockPos> places,int tint){building.add(new Ghost(models,places,new java.util.HashSet<>(places),null,tint,false));}
    /**
     * Draws {@code states.get(i)} at {@code places.get(i)}, for blocks that are not in the world yet, such as an edit
     * waiting to be confirmed. Both lists must have the same size and are not copied.
     */
    public static void blockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,int tint){blockStates(states,places,new java.util.HashSet<>(places),tint);}
    /** As above, for a caller that keeps {@code placed} (the same positions as a set) between ticks instead of paying to rebuild it each time. */
    public static void blockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,java.util.Set<BlockPos> placed,int tint){building.add(new Ghost(places,places,placed,states,tint,false));}
    /**
     * As {@link #blockStates(List,List,int)}, for a ghost that stays the same from frame to frame: the same two list objects must be
     * passed for as long as it does. What only depends on the blocks (which sides are on the outer surface, their models and tints)
     * is worked out once, the first frame, and kept until a frame passes without the ghost. In exchange the ghost does not notice
     * the world changing around it after that first frame.
     */
    public static void cachedBlockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,int tint){building.add(new Ghost(places,places,null,states,tint,true));}
    public static void point(Vec3 at,int color,float size){Gizmos.point(at,color,size).setAlwaysOnTop();}
    /**
     * A label that always faces the camera, floating over the block at {@code pos}. {@code scale} is world
     * units per font pixel, like a name tag (0.025 is name-tag size). The game's gizmo text has no
     * background and divides its scale by 16, so the background is ignored here and the scale is multiplied back.
     */
    public static void text(String text,BlockPos pos,int color,int background,float scale){Gizmos.billboardTextOverBlock(text,pos,0,color,scale*16f).setAlwaysOnTop();}
    /** A label centred on the point {@code at}, with the same scale and background rules as above. */
    public static void text(String text,Vec3 at,int color,int background,float scale){Gizmos.billboardText(text,at,net.minecraft.gizmos.TextGizmo.Style.forColorAndCentered(color).withScale(scale*16f)).setAlwaysOnTop();}

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

    /** The ghost's colour with a block tint multiplied in, as the game multiplies a tint into a texture; the ghost's alpha is kept. */
    private static int tinted(int ghost,int tint){
        int r=(ghost>>16&255)*(tint>>16&255)/255,g=(ghost>>8&255)*(tint>>8&255)/255,b=(ghost&255)*(tint&255)/255;
        return ghost&0xFF000000|r<<16|g<<8|b;
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
        var parts=new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
        mc.getModelManager().getBlockStateModelSet().get(state).collectParts(RandomSource.create(state.getSeed(pos)),parts);
        boolean loose=false;for(var part:parts)if(!part.getQuads(null).isEmpty())loose=true;
        LOOSE.put(state,loose);return loose;
    }
    private static void submitBlocks(LevelRenderContext context){
        var ghosts=shown;
        var mc=Minecraft.getInstance();
        var hook=frame;
        if(hook!=null&&mc.level!=null)try(var collector=mc.levelRenderer.collectPerFrameRenderThreadGizmos()){hook.run();}
        if(ghosts.isEmpty()||mc.level==null){PREPARED.clear();return;}
        TOUCHED.clear();
        var eye=ClientCompat.camera(mc).position();
        var stack=context.poseStack();
        var type=RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
        var random=RandomSource.create();
        for(var ghost:ghosts){
            if(ghost.cached()&&ghost.states()!=null){cachedBlocks(context,mc,eye,type,ghost);continue;}
            var quad=new QuadInstance();
            quad.setColor(ghost.tint());
            quad.setLightCoords(FULL_BRIGHT);
            quad.setOverlayCoords(OverlayTexture.NO_OVERLAY);
            for(int i=0;i<ghost.places().size();i++){
                BlockPos pos=ghost.places().get(i);
                var state=ghost.states()!=null?ghost.states().get(i):mc.level.getBlockState(ghost.models().get(i));
                if(state.isAir()||state.getRenderShape()!=RenderShape.MODEL)continue; // fluids and block entities have no plain model
                final double dx=eye.x-pos.getX()-.5,dy=eye.y-pos.getY()-.5,dz=eye.z-pos.getZ()-.5;
                // Most blocks of a big ghost are buried or turned away: decide that from the sides alone, before any model or transform is touched.
                final int visible=visibleSides(mc.level,ghost.placed(),pos,dx,dy,dz);
                if(visible==0&&!hasLooseQuads(mc,state,pos))continue;
                final var tints=new int[]{-2,-2,-2,-2};
                final var tintOf=(java.util.function.IntUnaryOperator)index->{ // biome tints such as grass and leaves, looked up once per block
                    if(index<0||index>=tints.length)return 0xFFFFFF;
                    if(tints[index]==-2){int t=ClientCompat.blockTint(mc,state,pos,index);tints[index]=t==-1?0xFFFFFF:t&0xFFFFFF;}
                    return tints[index];
                };
                random.setSeed(state.getSeed(pos));
                var parts=new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
                mc.getModelManager().getBlockStateModelSet().get(state).collectParts(random,parts);
                stack.pushPose();
                stack.translate(pos.getX()+.5-eye.x,pos.getY()+.5-eye.y,pos.getZ()+.5-eye.z);
                float grow=ghostScale(dx,dy,dz);
                stack.scale(grow,grow,grow);
                stack.translate(-.5,-.5,-.5);
                context.submitNodeCollector().submitCustomGeometry(stack,type,(pose,consumer)->{
                    for(var part:parts){
                        for(var q:part.getQuads(null))if(facing(q.direction(),dx,dy,dz,0)){quad.setColor(tinted(ghost.tint(),tintOf.applyAsInt(q.materialInfo().tintIndex())));consumer.putBakedQuad(pose,q,quad);}
                        for(Direction face:FACES){
                            if((visible>>face.ordinal()&1)==0)continue; // only the outer surface: not a side that touches another ghost, is covered as in the world, or faces away
                            for(var q:part.getQuads(face)){quad.setColor(tinted(ghost.tint(),tintOf.applyAsInt(q.materialInfo().tintIndex())));consumer.putBakedQuad(pose,q,quad);}
                        }
                    }
                });
                stack.popPose();
            }
        }
        PREPARED.keySet().retainAll(TOUCHED); // a ghost that was not drawn this frame is gone
    }

    /** What a cached ghost needs for every block that has anything to show, worked out once: see {@link #cachedBlockStates}. */
    private static final class Prepared {
        final List<BlockPos> pos=new ArrayList<>();
        final List<net.minecraft.world.level.block.state.BlockState> states=new ArrayList<>();
        /** For each block the sides of it on the outer surface, one bit per {@link Direction#ordinal()}. */
        final List<Integer> outer=new ArrayList<>();
        /** Whether the block has quads that belong to no side (a flower, say), which are drawn whenever they face the camera. */
        final List<Boolean> loose=new ArrayList<>();
        final List<List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>> parts=new ArrayList<>();
        int[] tints;
    }
    private static final java.util.Map<List<BlockPos>,Prepared> PREPARED=new java.util.IdentityHashMap<>();
    private static final java.util.Set<List<BlockPos>> TOUCHED=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private static Prepared prepare(Minecraft mc,Ghost ghost){
        var level=mc.level;var out=new Prepared();
        var placed=ghost.placed()!=null?ghost.placed():new java.util.HashSet<>(ghost.places());
        var random=RandomSource.create();
        for(int i=0;i<ghost.places().size();i++){
            BlockPos pos=ghost.places().get(i);var state=ghost.states().get(i);
            if(state.isAir()||state.getRenderShape()!=RenderShape.MODEL)continue; // fluids and block entities have no plain model
            random.setSeed(state.getSeed(pos));
            var parts=new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
            mc.getModelManager().getBlockStateModelSet().get(state).collectParts(random,parts);
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

    private static void cachedBlocks(LevelRenderContext context,Minecraft mc,Vec3 eye,net.minecraft.client.renderer.rendertype.RenderType type,Ghost ghost){
        TOUCHED.add(ghost.places());
        var prepared=PREPARED.computeIfAbsent(ghost.places(),k->prepare(mc,ghost));
        var stack=context.poseStack();
        var quad=new QuadInstance();
        quad.setColor(ghost.tint());
        quad.setLightCoords(FULL_BRIGHT);
        quad.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        for(int i=0;i<prepared.pos.size();i++){
            BlockPos pos=prepared.pos.get(i);
            final double dx=eye.x-pos.getX()-.5,dy=eye.y-pos.getY()-.5,dz=eye.z-pos.getZ()-.5;
            final int visible=prepared.outer.get(i)&facingMask(dx,dy,dz);
            final boolean loose=prepared.loose.get(i);
            if(visible==0&&!loose)continue; // every side that shows faces away: nothing to draw, so not even the transform is set up
            var state=prepared.states.get(i);
            final int base=i*4;final var tints=prepared.tints;
            final var tintOf=(java.util.function.IntUnaryOperator)index->{ // biome tints such as grass and leaves, looked up once per block
                if(index<0||index>=4)return 0xFFFFFF;
                if(tints[base+index]==-2){int t=ClientCompat.blockTint(mc,state,pos,index);tints[base+index]=t==-1?0xFFFFFF:t&0xFFFFFF;}
                return tints[base+index];
            };
            var parts=prepared.parts.get(i);
            stack.pushPose();
            stack.translate(pos.getX()+.5-eye.x,pos.getY()+.5-eye.y,pos.getZ()+.5-eye.z);
            float grow=ghostScale(dx,dy,dz);
            stack.scale(grow,grow,grow);
            stack.translate(-.5,-.5,-.5);
            context.submitNodeCollector().submitCustomGeometry(stack,type,(pose,consumer)->{
                for(var part:parts){
                    if(loose)for(var q:part.getQuads(null))if(facing(q.direction(),dx,dy,dz,0)){quad.setColor(tinted(ghost.tint(),tintOf.applyAsInt(q.materialInfo().tintIndex())));consumer.putBakedQuad(pose,q,quad);}
                    if(visible!=0)for(Direction face:FACES){
                        if((visible>>face.ordinal()&1)==0)continue;
                        for(var q:part.getQuads(face)){quad.setColor(tinted(ghost.tint(),tintOf.applyAsInt(q.materialInfo().tintIndex())));consumer.putBakedQuad(pose,q,quad);}
                    }
                }
            });
            stack.popPose();
        }
    }
}
