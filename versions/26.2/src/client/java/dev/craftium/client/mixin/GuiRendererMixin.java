package dev.craftium.client.mixin;

import dev.craftium.client.FontTextures;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import java.util.function.Consumer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooths the letters of the fonts only for the texts a Craftium screen draws, so text in the world and the viewport, and the game's own interface
 * (the F3 screen, chat, the hotbar), is left as the game draws it.
 */
@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @ModifyArg(method="prepareText",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/state/gui/GuiRenderState;forEachText(Ljava/util/function/Consumer;)V"))
    private Consumer<GuiTextRenderState> craftium$smoothOwn(Consumer<GuiTextRenderState> game){return (Consumer)FontTextures.forEachText(game);}
    @Inject(method="render",at=@At("RETURN"))
    private void craftium$smoothEnd(CallbackInfo ci){FontTextures.end();}
}
