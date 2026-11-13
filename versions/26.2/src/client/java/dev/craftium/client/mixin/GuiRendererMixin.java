package dev.craftium.client.mixin;

import dev.craftium.client.FontTextures;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Smooths the letters of the fonts only while the interface is drawn, so text in the world and the viewport is left as the game draws it. */
@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @Inject(method="render",at=@At("HEAD"))
    private void craftium$smoothBegin(CallbackInfo ci){FontTextures.begin();}
    @Inject(method="render",at=@At("RETURN"))
    private void craftium$smoothEnd(CallbackInfo ci){FontTextures.end();}
}
