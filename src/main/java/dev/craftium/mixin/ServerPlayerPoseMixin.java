package dev.craftium.mixin;

import dev.craftium.server.EditorServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With No clip on, the player flies through blocks. The game then finds no room to stand, and drops them into the
 * crawling pose. While No clip is active the player always fits, so the pose stays whatever they asked for.
 */
@Mixin(Player.class)
abstract class ServerPlayerPoseMixin {
    @Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
    private void craftium$noClipFits(Pose pose, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer player && EditorServer.noClip(player)) cir.setReturnValue(true);
    }
}
