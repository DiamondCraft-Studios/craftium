package dev.craftium.client.mixin;

import dev.craftium.client.FontTextures;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link FontTextures} which texts a Craftium screen made, so that only those are smoothed. */
@Mixin(GuiRenderState.class)
abstract class GuiRenderStateMixin {
    @Inject(method="addText",at=@At("HEAD"))
    private void craftium$own(GuiTextRenderState text,CallbackInfo ci){FontTextures.submitted(text);}
}
