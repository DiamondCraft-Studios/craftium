package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.List;

/** Replace mode: the held block as a translucent ghost on the block it will replace. Unlike the other ghosts it is not tinted. */
final class ReplaceGhost {
    /** Translucent white, which leaves the block's own colours as they are. */
    private static final int UNTINTED=0x90FFFFFF;
    private ReplaceGhost(){}
    static void draw(Minecraft mc){
        if(!ClientState.options.replaceMode||mc.player==null||mc.level==null||!mc.player.isCreative()||!CraftiumClient.supported()||ClientCompat.screen(mc)!=null)return;
        if(!(mc.player.getMainHandItem().getItem() instanceof BlockItem item))return;
        if(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return;
        Overlay.blockStates(List.of(item.getBlock().defaultBlockState()),List.of(hit.getBlockPos()),UNTINTED);
    }
}
