package dev.craftium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.craftium.client.mixin.AbstractTextureAccessor;
import java.util.Collections;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import java.util.WeakHashMap;

/**
 * The textures the game keeps the letters of its fonts in. In {@link TextMode.Mode#LINEAR} they are sampled with linear filtering while a Craftium screen is
 * shown at a fractional interface scale, and the way the game sampled them at any other time.
 */
public final class FontTextures {
    private FontTextures(){}
    /** Each font texture with the sampler the game gave it. */
    private static final Map<AbstractTexture,GpuSampler> ALL=Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean linear;

    static{InterfaceScale.listener=FontTextures::update;}

    /** A font texture has been made. */
    public static void add(AbstractTexture texture){
        ALL.put(texture,texture.getSampler());
        set(texture,texture.getSampler(),linear);
    }

    /** Sets every font texture to what the mode and the interface scale now call for. */
    public static void update(){
        boolean want=TextMode.mode()==TextMode.Mode.LINEAR&&InterfaceScale.zoom()!=1;
        if(want==linear)return;
        linear=want;
        synchronized(ALL){for(var entry:ALL.entrySet())set(entry.getKey(),entry.getValue(),linear);}
    }

    private static void set(AbstractTexture texture,GpuSampler original,boolean linear){
        ((AbstractTextureAccessor)texture).craftium$setSampler(linear?RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.NEAREST,false):original);
    }
}
