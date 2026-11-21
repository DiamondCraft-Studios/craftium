package dev.craftium.client.mixin;

import dev.craftium.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the local player out of the crawling pose while No clip carries them through blocks (see the server twin). */
@Mixin(Player.class)
abstract class ClientPlayerPoseMixin {
    @Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
    private void craftium$noClipFits(Pose pose, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof LocalPlayer player
                && player.isCreative()
                && player.getAbilities().flying
                && CraftiumClient.supported()
                && ClientState.options.noClip) cir.setReturnValue(true);
    }
}
