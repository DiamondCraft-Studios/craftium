package dev.craftium.client.mixin;

import dev.craftium.client.FontTextures;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets {@link FontTextures} reach the textures of the fonts, to change how they are sampled. */
@Mixin(FontTexture.class)
abstract class FontTextureMixin {
    @Inject(method="<init>",at=@At("RETURN"))
    private void craftium$track(CallbackInfo ci){FontTextures.add((AbstractTexture)(Object)this);}
}
