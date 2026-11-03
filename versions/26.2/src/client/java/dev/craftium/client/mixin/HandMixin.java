package dev.craftium.client.mixin;

import dev.craftium.client.BuilderMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the held item and the hand while a builder tool is chosen. */
@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
abstract class HandMixin {
    @Inject(method="submitHandsWithItems",at=@At("HEAD"),cancellable=true)
    private void craftium$noHand(float partialTicks,com.mojang.blaze3d.vertex.PoseStack poseStack,net.minecraft.client.renderer.SubmitNodeCollector collector,net.minecraft.client.player.LocalPlayer player,int light,CallbackInfo ci){
        if(BuilderMode.active())ci.cancel();
    }
}
