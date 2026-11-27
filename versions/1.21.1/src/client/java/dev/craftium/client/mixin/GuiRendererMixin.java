package dev.craftium.client.mixin;

import dev.craftium.client.FontTextures;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.1 version: smooths the letters of the fonts only while the interface is drawn, so text in the world and
 * the viewport is left as the game draws it. 1.21.1 has no separate GUI renderer: the interface is everything the frame
 * draws from the moment it makes its GuiGraphics.
 */
@Mixin(GameRenderer.class)
abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At(value = "NEW", target = "net/minecraft/client/gui/GuiGraphics"))
    private void craftium$smoothBegin(CallbackInfo ci) {
        FontTextures.begin();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void craftium$smoothEnd(CallbackInfo ci) {
        FontTextures.end();
    }
}
