package dev.craftium.client.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Dynamic texture for reference images with switchable filtering. 26.2 implementation. */
public final class ReferenceTexture extends DynamicTexture {
    public ReferenceTexture(NativeImage image){super(()->"Craftium reference image",image);}

    /** Linear filtering smooths the image; otherwise pixels stay sharp. */
    public void filtering(boolean linear){
        sampler=RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,linear?FilterMode.LINEAR:FilterMode.NEAREST,FilterMode.NEAREST,false);
    }
}
