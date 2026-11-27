package dev.craftium.client.mixin;

import dev.craftium.client.compat.ClientCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft 1.21.1 version: remembers the field of view the world is drawn with, so the cursor can be matched to it. */
@Mixin(GameRenderer.class)
abstract class FovMixin {
    @Inject(method = "getFov", at = @At("RETURN"))
    private void craftium$renderedFov(
            Camera camera, float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (useFovSetting) ClientCompat.renderedFov = cir.getReturnValue().floatValue();
    }
}
