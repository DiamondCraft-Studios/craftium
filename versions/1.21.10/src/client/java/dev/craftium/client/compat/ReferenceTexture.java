package dev.craftium.client.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** 1.21.10 implementation: filtering is a flag on the texture itself. */
public final class ReferenceTexture extends DynamicTexture {
    public ReferenceTexture(NativeImage image){super(()->"Craftium reference image",image);}

    public void filtering(boolean linear){setFilter(linear,false);}
}
