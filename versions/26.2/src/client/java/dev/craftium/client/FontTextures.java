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
 * The textures the game keeps the letters of its fonts in. In {@link TextMode.Mode#LINEAR} they are sampled with linear filtering only while the game draws its
 * interface (see {@link #begin}) with a Craftium screen shown at a fractional interface scale. Text drawn in the world, such as signs, name tags and the
 * labels of the viewport overlay, is drawn earlier in the frame and is sampled the way the game does.
 */
public final class FontTextures {
    private FontTextures(){}
    /** Each font texture with the sampler the game gave it. */
    private static final Map<AbstractTexture,GpuSampler> ALL=Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean linear,wanted;

    static{InterfaceScale.listener=FontTextures::update;}

    /** A font texture has been made. */
    public static void add(AbstractTexture texture){
        ALL.put(texture,texture.getSampler());
        set(texture,texture.getSampler(),linear);
    }

    /** Notes whether the interface is to be drawn smoothed; {@link #begin} puts it into effect. */
    public static void update(){wanted=TextMode.mode()==TextMode.Mode.LINEAR&&InterfaceScale.zoom()!=1;if(!wanted)apply(false);}

    /** The game is about to draw its interface: smooth the letters if they are to be. */
    public static void begin(){if(wanted)apply(true);}

    /** The interface is drawn: the letters are the game's own again for everything drawn after it. */
    public static void end(){apply(false);}

    private static void apply(boolean on){
        if(on==linear)return;
        linear=on;
        synchronized(ALL){for(var entry:ALL.entrySet())set(entry.getKey(),entry.getValue(),linear);}
    }

    private static void set(AbstractTexture texture,GpuSampler original,boolean linear){
        ((AbstractTextureAccessor)texture).craftium$setSampler(linear?RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.NEAREST,false):original);
    }
}
