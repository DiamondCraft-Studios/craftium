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
import java.util.Collection;
import java.util.List;

/**
 * In-world overlay primitives (lines, boxes, points, labels) drawn on top of the scene.
 * Colours are ARGB; a stroke width or fill colour of 0 means "none". Primitives submitted between
 * {@link #begin} and {@link Scope#close()} stay visible until the next tick's batch replaces them.
 * This is the 26.2 implementation. Lines, boxes, faces, points and labels go to the game's gizmo system;
 * block models have no gizmo, so they are queued here and submitted from the level render event each frame.
 */
public final class Overlay {
    private record Ghost(Collection<BlockPos> positions,int tint){}
    private static final List<Ghost> building=new ArrayList<>();
    private static List<Ghost> shown=List.of();
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{building.clear();shown=List.of();});
    }

    public static Scope begin(Minecraft mc){
        var collector=mc.collectPerTickGizmos();
        building.clear();
        return ()->{shown=List.copyOf(building);collector.close();};
    }

    public static void line(Vec3 from,Vec3 to,int color,float width){Gizmos.line(from,to,color,width).setAlwaysOnTop();}
    public static void box(AABB box,int strokeColor,float strokeWidth,int fillColor){Gizmos.cuboid(box,new GizmoStyle(strokeColor,strokeWidth,fillColor)).setAlwaysOnTop();}
    /** A filled quad. Corners are in order, counter-clockwise when seen from the side it faces. */
    public static void face(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){Gizmos.rect(a,b,c,d,GizmoStyle.fill(color)).setAlwaysOnTop();}
    /**
     * The real models of the blocks at {@code positions}, drawn translucent and tinted by {@code tint} (ARGB)
     * over the blocks themselves. The list is not copied and must not change after this call.
     */
    public static void blocks(Collection<BlockPos> positions,int tint){building.add(new Ghost(positions,tint));}
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

    private static void submitBlocks(LevelRenderContext context){
        var ghosts=shown;
        var mc=Minecraft.getInstance();
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
            for(BlockPos pos:ghost.positions()){
                var state=mc.level.getBlockState(pos);
                if(state.isAir()||state.getRenderShape()!=RenderShape.MODEL)continue; // fluids and block entities have no plain model
                random.setSeed(state.getSeed(pos));
                var parts=new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
                mc.getModelManager().getBlockStateModelSet().get(state).collectParts(random,parts);
                stack.pushPose();
                stack.translate(pos.getX()+.5-eye.x,pos.getY()+.5-eye.y,pos.getZ()+.5-eye.z);
                stack.scale(GHOST_SCALE,GHOST_SCALE,GHOST_SCALE);
                stack.translate(-.5,-.5,-.5);
                context.submitNodeCollector().submitCustomGeometry(stack,type,(pose,consumer)->{
                    for(var part:parts){
                        for(var q:part.getQuads(null))consumer.putBakedQuad(pose,q,quad);
                        for(Direction face:FACES){
                            if(hidden(mc.level,pos,face))continue; // the block next to it covers this side, as in the world
                            for(var q:part.getQuads(face))consumer.putBakedQuad(pose,q,quad);
                        }
                    }
                });
                stack.popPose();
            }
        }
    }
}
