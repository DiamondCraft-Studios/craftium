package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Objects;

/**
 * Outlines what a Magic Wand click on the hovered block would select, as the cursor moves. It runs the same search the
 * server runs, but stops as soon as the result reaches {@code magicPreviewLimit} blocks: a big match is most likely
 * terrain, and giving up early keeps the cost of hovering small no matter how large the area under the cursor is.
 */
final class MagicPreview {
    private static final int STROKE=0xff7fd8ff,FILL=0x2240c0ff;
    /** The search gives up after visiting this many cells, which bounds the work when a block has few surface neighbours. */
    private static final int VISIT_CAP=6000;
    private static final int[][] SIDES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static Object key;
    private static OutlineShape shape=OutlineShape.EMPTY;
    private MagicPreview(){}

    static void draw(Minecraft mc,BlockHitResult hit){
        var o=ClientState.options;
        if(hit==null||mc.level==null||o.tool!=Tool.MAGIC_SELECT||!o.magicPreview||TransformGizmo.dragging()||PlacementPreview.active()){key=null;shape=OutlineShape.EMPTY;return;}
        BlockPos at=hit.getBlockPos();
        // The world can change under a still cursor, so the result is also refreshed about once a second.
        Object now=java.util.List.of(at,o.compare,o.magicPreviewLimit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,mc.level.getGameTime()/20);
        if(!Objects.equals(key,now)){key=now;shape=compute(mc,at,o);}
        if(shape.isEmpty())return;
        shape.draw(Vec3.ZERO,(a,b)->Overlay.line(a,b,STROKE,1),STROKE,FILL,true);
    }

    private static OutlineShape compute(Minecraft mc,BlockPos at,Options o){
        var level=mc.level;var origin=level.getBlockState(at);
        if(origin.isAir())return OutlineShape.EMPTY;
        var originData=o.compare==Options.Compare.NBT?data(level,at):null;
        var world=new MagicWand.World(){
            public boolean loaded(Point p){return level.hasChunk(p.x()>>4,p.z()>>4);}
            public boolean air(Point p){return level.getBlockState(pos(p)).isAir();}
            public boolean matches(Point p){
                var s=level.getBlockState(pos(p));
                return switch(o.compare){
                    case ANY->!s.isAir();
                    case BLOCK->s.is(origin.getBlock());
                    case STATE->s==origin;
                    case TAG->s.is(origin.getBlock())||origin.getTags().anyMatch(s::is);
                    case NBT->s.is(origin.getBlock())&&Objects.equals(originData,data(level,pos(p)));
                };
            }
            public boolean surface(Point p){int count=0;for(int[] d:SIDES)if(solid(p.add(d[0],d[1],d[2])))count++;return count<6;}
            private boolean solid(Point p){var s=level.getBlockState(pos(p));return !s.isAir()&&s.getFluidState().isEmpty();}
        };
        var result=MagicWand.select(CraftiumClient.point(at),world,new MagicWand.Params(o.magicPreviewLimit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,128,VISIT_CAP));
        if(result.truncated()||result.found().size()>=o.magicPreviewLimit)return OutlineShape.EMPTY; // too similar: probably the terrain
        return OutlineShape.of(result.found());
    }
    /** The block entity data as the client knows it, which can lack what the server never syncs. */
    private static net.minecraft.nbt.CompoundTag data(net.minecraft.world.level.Level level,BlockPos at){var entity=level.getBlockEntity(at);return entity==null?null:entity.saveWithFullMetadata(level.registryAccess());}
    private static BlockPos pos(Point p){return new BlockPos(p.x(),p.y(),p.z());}
}
