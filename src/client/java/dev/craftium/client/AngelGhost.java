package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.List;

/** Shows the held block in the air where angel placement would put it, as long as the player is not aiming at a block. */
final class AngelGhost {
    private AngelGhost(){}
    static void draw(Minecraft mc){
        var o=ClientState.options;
        if(!o.angelPlacement||mc.player==null||mc.level==null||!mc.player.isCreative()||!CraftiumClient.supported()||ClientCompat.screen(mc)!=null)return;
        if(!(mc.player.getMainHandItem().getItem() instanceof BlockItem item))return;
        if(mc.hitResult instanceof BlockHitResult block&&block.getType()==HitResult.Type.BLOCK)return;
        var at=CraftiumClient.angelHit(mc).getBlockPos();
        Overlay.blockStates(List.of(item.getBlock().defaultBlockState()),List.of(at),OutlineShape.GHOST);
    }
}
