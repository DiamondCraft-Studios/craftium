package dev.craftium.client.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@code FontTextures} change how a texture is sampled. */
@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {
    @Accessor("sampler") void craftium$setSampler(GpuSampler sampler);
}
