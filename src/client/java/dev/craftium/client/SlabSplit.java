package dev.craftium.client;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
/**
 * The selection box of a double slab, split in its two halves: the outline shows only the half being aimed at, and holding Ctrl brings back the whole block.
 * The aim itself is always taken against the whole block, so the half under the crosshair can change from one frame to the next;
 * only what is drawn is split, which is why ray casts made by this mod and by the game's own pick suppress it.
 */
public final class SlabSplit {
    private SlabSplit(){}
    private static final VoxelShape BOTTOM=Shapes.box(0,0,0,1,.5,1),TOP=Shapes.box(0,.5,0,1,1,1);
    /** Greater than zero while a ray is being cast, which must hit the whole block. */
    private static int suppressed;
    public static void suppress(boolean on){suppressed=Math.max(0,suppressed+(on?1:-1));}
    /** The half of the double slab at the position that is aimed at, or null when the whole block is to be shown. */
    public static VoxelShape half(BlockPos pos){
        if(suppressed>0)return null;
        var mc=Minecraft.getInstance();
        if(mc.hasControlDown()||!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos))return null;
        boolean top=hit.getDirection()==Direction.UP||hit.getDirection()!=Direction.DOWN&&hit.getLocation().y-pos.getY()>=.5;
        return top?TOP:BOTTOM;
    }
}
