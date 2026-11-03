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
    private record Ghost(List<BlockPos> models,List<BlockPos> places,java.util.Set<BlockPos> placed,List<net.minecraft.world.level.block.state.BlockState> states,int tint){}
    private static final List<Ghost> building=new ArrayList<>();
    private static List<Ghost> shown=List.of();
    private static Runnable pendingFrame,frame;
    /** Each model is scaled this much about its centre so the ghost clears the real block instead of fighting it for depth. */
    private static final float GHOST_SCALE=1.004f;
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{building.clear();shown=List.of();pendingFrame=frame=null;});
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
    public static void box(AABB box,int strokeColor,float strokeWidth,int fillColor){Gizmos.cuboid(box,new GizmoStyle(strokeColor,strokeWidth,fillColor)).setAlwaysOnTop();}
    /** A filled quad. Corners are in order, counter-clockwise when seen from the side it faces. */
    public static void face(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){Gizmos.rect(a,b,c,d,GizmoStyle.fill(color)).setAlwaysOnTop();}
    /**
     * The real models of the blocks at {@code positions}, drawn translucent and tinted by {@code tint} (ARGB)
     * over the blocks themselves. The list is not copied and must not change after this call.
     */
    public static void blocks(List<BlockPos> positions,int tint){blocks(positions,positions,tint);}
    /**
     * Like {@link #blocks(List,int)}, but the model of {@code models.get(i)} is drawn at {@code places.get(i)}. This
     * previews blocks that are about to move. Both lists must have the same size and are not copied.
     */
    public static void blocks(List<BlockPos> models,List<BlockPos> places,int tint){building.add(new Ghost(models,places,new java.util.HashSet<>(places),null,tint));}
    /**
     * Draws {@code states.get(i)} at {@code places.get(i)}, for blocks that are not in the world yet, such as an edit
     * waiting to be confirmed. Both lists must have the same size and are not copied.
     */
    public static void blockStates(List<net.minecraft.world.level.block.state.BlockState> states,List<BlockPos> places,int tint){building.add(new Ghost(places,places,new java.util.HashSet<>(places),states,tint));}
    public static void point(Vec3 at,int color,float size){Gizmos.point(at,color,size).setAlwaysOnTop();}
    /**
     * A label that always faces the camera, floating over the block at {@code pos}. {@code scale} is world
     * units per font pixel, like a name tag (0.025 is name-tag size). The game's gizmo text has no
     * background and divides its scale by 16, so the background is ignored here and the scale is multiplied back.
     */
    public static void text(String text,BlockPos pos,int color,int background,float scale){Gizmos.billboardTextOverBlock(text,pos,0,color,scale*16f).setAlwaysOnTop();}

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

    private static void submitBlocks(LevelRenderContext context){
        var ghosts=shown;
        var mc=Minecraft.getInstance();
        var hook=frame;
        if(hook!=null&&mc.level!=null)try(var collector=mc.levelRenderer.collectPerFrameRenderThreadGizmos()){hook.run();}
        if(ghosts.isEmpty()||mc.level==null)return;
        var eye=ClientCompat.camera(mc).position();
        var stack=context.poseStack();
        var type=RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
        var random=RandomSource.create();
        for(var ghost:ghosts){
            var quad=new QuadInstance();
            quad.setColor(ghost.tint());
            quad.setLightCoords(FULL_BRIGHT);
            quad.setOverlayCoords(OverlayTexture.NO_OVERLAY);
            for(int i=0;i<ghost.places().size();i++){
                BlockPos pos=ghost.places().get(i);
                var state=ghost.states()!=null?ghost.states().get(i):mc.level.getBlockState(ghost.models().get(i));
                if(state.isAir()||state.getRenderShape()!=RenderShape.MODEL)continue; // fluids and block entities have no plain model
                random.setSeed(state.getSeed(pos));
                var parts=new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
                mc.getModelManager().getBlockStateModelSet().get(state).collectParts(random,parts);
                final double dx=eye.x-pos.getX()-.5,dy=eye.y-pos.getY()-.5,dz=eye.z-pos.getZ()-.5;
                stack.pushPose();
                stack.translate(pos.getX()+.5-eye.x,pos.getY()+.5-eye.y,pos.getZ()+.5-eye.z);
                stack.scale(GHOST_SCALE,GHOST_SCALE,GHOST_SCALE);
                stack.translate(-.5,-.5,-.5);
                context.submitNodeCollector().submitCustomGeometry(stack,type,(pose,consumer)->{
                    for(var part:parts){
                        for(var q:part.getQuads(null))if(facing(q.direction(),dx,dy,dz,0))consumer.putBakedQuad(pose,q,quad);
                        for(Direction face:FACES){
                            // Only the outer surface: not a side that touches another ghost, is covered as in the world, or faces away.
                            if(ghost.placed().contains(pos.relative(face))||hidden(mc.level,pos,face)||!facing(face,dx,dy,dz,.5))continue;
                            for(var q:part.getQuads(face))consumer.putBakedQuad(pose,q,quad);
                        }
                    }
                });
                stack.popPose();
            }
        }
    }
}
