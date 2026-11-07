package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Replace mode: the held block as a translucent ghost on the block it will replace. Unlike the other ghosts it is not tinted.
 * When the held block is smaller than the block it replaces, such as a slab on a full block, the ghost is hidden inside the
 * real block, so the part of the target that stays, which is the held block, is tinted white to show the change.
 */
final class ReplaceGhost {
    /** Translucent white, which leaves the block's own colours as they are. */
    private static final int UNTINTED=0x90FFFFFF;
    /** What is kept: the part of the replaced block that the held block fills. Fainter than the ghost, as it lies over the block. */
    private static final int KEPT=0x40FFFFFF;
    private ReplaceGhost(){}
    /** Whether the held shape lies wholly inside the target's, so that nothing of it can be seen, and the target has more. */
    static boolean hidden(VoxelShape held,VoxelShape target){
        if(held.isEmpty()||target.isEmpty())return false;
        return !Shapes.joinIsNotEmpty(held,target,BooleanOp.ONLY_FIRST)&&Shapes.joinIsNotEmpty(target,held,BooleanOp.ONLY_FIRST);
    }
    static void draw(Minecraft mc){
        if(!ClientState.options.replaceMode||mc.player==null||mc.level==null||!mc.player.isCreative()||!CraftiumClient.supported()||ClientCompat.screen(mc)!=null)return;
        if(!(mc.player.getMainHandItem().getItem() instanceof BlockItem item))return;
        if(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return;
        var placement=HeldGhost.placement(mc,item,hit);
        Overlay.blockStates(placement.states(),placement.places(),UNTINTED);
        SlabSplit.suppress(true); // the shapes are taken whole, not as the half of a double slab that is aimed at
        try{
            for(int i=0;i<placement.places().size();i++){
                var pos=placement.places().get(i);
                var held=placement.states().get(i).getShape(mc.level,pos);
                var target=mc.level.getBlockState(pos).getShape(mc.level,pos);
                if(!hidden(held,target))continue;
                for(var box:held.toAabbs())Overlay.box(box.move(pos),0,0,KEPT);
            }
        }finally{SlabSplit.suppress(false);}
    }
}
