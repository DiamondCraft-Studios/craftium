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
 * server runs, but stops as soon as the result reaches {@code hoverPreviewLimit} blocks: a big match is most likely
 * terrain, and giving up early keeps the cost of hovering small no matter how large the area under the cursor is.
 */
final class MagicPreview {
    /** The search gives up after visiting this many cells, which bounds the work when a block has few surface neighbours. */
    private static final int VISIT_CAP=6000;
    private static final int[][] SIDES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static Object key;
    private static OutlineShape shape=OutlineShape.EMPTY;
    /** A bucket fill can be far bigger than a hover preview should be, so its outline stops at these. */
    static final int BUCKET_LIMIT=20_000;
    private static final int BUCKET_VISITS=80_000;
    private static Object bucketKey;
    private static OutlineShape bucketShape=OutlineShape.EMPTY;
    private MagicPreview(){}

    static void draw(Minecraft mc,BlockHitResult hit){
        var o=ClientState.options;
        if(hit==null||mc.level==null||o.tool!=Tool.MAGIC_SELECT||!o.magicPreview||TransformGizmo.dragging()||PlacementPreview.active()){key=null;shape=OutlineShape.EMPTY;return;}
        BlockPos at=hit.getBlockPos();
        // The world can change under a still cursor, so the result is also refreshed about once a second.
        Object now=java.util.List.of(at,o.compare,o.hoverPreviewLimit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,java.util.List.copyOf(o.maskActive),o.maskExclude,o.maskOff,mc.level.getGameTime()/20);
        if(!Objects.equals(key,now)){key=now;shape=compute(mc,at,o);}
        if(shape.isEmpty())return;
        shape.drawPlain(Vec3.ZERO,ClientState.options.hoverColor,(float)ClientState.options.selectionWidth,ClientState.options.hoverFill,true,false);
    }

    /**
     * Outlines what dropping a dragged block at {@code hit} would fill: the blocks connected to it that are of its own type,
     * whichever tool is active. A null hit clears it, so the outline goes the moment the pointer leaves the world.
     */
    static void drawBucket(Minecraft mc,BlockHitResult hit){
        var o=ClientState.options;
        if(hit==null||mc.level==null){bucketKey=null;bucketShape=OutlineShape.EMPTY;return;}
        BlockPos at=hit.getBlockPos();
        if(SelectionOverlay.contains(at)){ // dropped on the selection, the block fills the whole selection: outline that rather than what is connected
            bucketKey=null;bucketShape=OutlineShape.EMPTY;
            SelectionOverlay.outline().drawPlain(Vec3.ZERO,ClientState.options.hoverColor,(float)ClientState.options.selectionWidth,ClientState.options.hoverFill,true,false);
            return;
        }
        Object now=java.util.List.of(at,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,o.selectLimit,o.hoverPreviewLimit,mc.level.getGameTime()/20);
        if(!Objects.equals(bucketKey,now)){
            bucketKey=now;bucketShape=OutlineShape.EMPTY;
            if(!mc.level.getBlockState(at).isAir()){
                // As with the Magic Wand hover preview, a match this big is most likely terrain (grass, a superflat floor), so it is not outlined.
                int limit=Math.min(o.selectLimit,o.hoverPreviewLimit);
                var result=MagicWand.select(CraftiumClient.point(at),world(mc,at,o,Options.Compare.BLOCK,false),new MagicWand.Params(limit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,128,VISIT_CAP));
                if(!result.truncated()&&result.found().size()<o.hoverPreviewLimit)bucketShape=OutlineShape.of(result.found());
            }
        }
        if(!bucketShape.isEmpty())bucketShape.drawPlain(Vec3.ZERO,ClientState.options.hoverColor,(float)ClientState.options.selectionWidth,ClientState.options.hoverFill,true,false);
    }

    private static MagicWand.Result bucketSearch(Minecraft mc,BlockPos at,Options o){
        return MagicWand.select(CraftiumClient.point(at),world(mc,at,o,Options.Compare.BLOCK,false),new MagicWand.Params(Math.min(o.selectLimit,BUCKET_LIMIT),o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,128,BUCKET_VISITS));
    }
    /** How many blocks a bucket fill at {@code at} would reach, counting up to {@link #BUCKET_LIMIT}. */
    static int bucketCount(Minecraft mc,BlockPos at,Options o){return mc.level.getBlockState(at).isAir()?0:bucketSearch(mc,at,o).found().size();}

    private static OutlineShape compute(Minecraft mc,BlockPos at,Options o){
        if(mc.level.getBlockState(at).isAir())return OutlineShape.EMPTY;
        var world=world(mc,at,o);int limit=o.hoverPreviewLimit;
        var result=MagicWand.select(CraftiumClient.point(at),world,new MagicWand.Params(limit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,128,VISIT_CAP));
        if(result.truncated()||result.found().size()>=limit)return OutlineShape.EMPTY; // too similar: probably the terrain
        return OutlineShape.of(result.found());
    }
    /** What the search needs to know about the world, comparing blocks with the one at {@code at} as the Magic Wand's settings say. */
    static MagicWand.World world(Minecraft mc,BlockPos at,Options o){return world(mc,at,o,o.compare,true);}
    /** As above with the comparison chosen by the caller, and the block mask applied only when {@code masked}. */
    static MagicWand.World world(Minecraft mc,BlockPos at,Options o,Options.Compare compare,boolean masked){
        var level=mc.level;var origin=level.getBlockState(at);var blockMask=masked?dev.craftium.server.BlockMask.of(o):null; // read now: a search may outlast a change of setting
        var originData=compare==Options.Compare.NBT?data(level,at):null;
        return new MagicWand.World(){
            public boolean loaded(Point p){return level.hasChunk(p.x()>>4,p.z()>>4);}
            public boolean air(Point p){return level.getBlockState(pos(p)).isAir();}
            public boolean matches(Point p){
                var s=level.getBlockState(pos(p));
                if(blockMask!=null&&!blockMask.allows(s))return false;
                return switch(compare){
                    case ANY->!s.isAir();
                    case BLOCK->s.is(origin.getBlock());
                    case STATE->s==origin;
                    case TAG->s.is(origin.getBlock())||net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(origin.getBlock()).tags().anyMatch(s::is);
                    case NBT->s.is(origin.getBlock())&&Objects.equals(originData,data(level,pos(p)));
                };
            }
            public boolean surface(Point p){int count=0;for(int[] d:SIDES)if(solid(p.add(d[0],d[1],d[2])))count++;return count<6;}
            private boolean solid(Point p){var s=level.getBlockState(pos(p));return !s.isAir()&&s.getFluidState().isEmpty();}
        };
    }
    /** The block entity data as the client knows it, which can lack what the server never syncs. */
    private static net.minecraft.nbt.CompoundTag data(net.minecraft.world.level.Level level,BlockPos at){var entity=level.getBlockEntity(at);return entity==null?null:entity.saveWithFullMetadata(level.registryAccess());}
    private static BlockPos pos(Point p){return new BlockPos(p.x(),p.y(),p.z());}
}
