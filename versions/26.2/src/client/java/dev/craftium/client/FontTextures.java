package dev.craftium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.craftium.client.mixin.AbstractTextureAccessor;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import java.util.WeakHashMap;

/**
 * The textures the game keeps the letters of its fonts in. In {@link TextMode.Mode#LINEAR} they are sampled with linear filtering only for the texts of a Craftium
 * screen (see {@link #forEachText}) shown at a fractional interface scale. Text drawn in the world, such as signs, name tags and the
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

    /** Notes whether the interface is to be drawn smoothed; {@link #forEachText} puts it into effect for the texts of Craftium screens only. */
    public static void update(){wanted=TextMode.mode()==TextMode.Mode.LINEAR&&InterfaceScale.zoom()!=1;if(!wanted)apply(false);}

    /** The texts a Craftium screen has asked to draw this frame; only these are smoothed, never the game's own (the F3 screen, chat, the hotbar). */
    private static final java.util.Set<Object> MINE=Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean drawing;

    /** A Craftium screen starts or stops putting its texts into the frame. */
    public static void drawing(boolean on){drawing=on;}

    /** The game has been handed a text to draw; remembers it if a Craftium screen made it. */
    public static void submitted(Object text){if(drawing&&wanted)synchronized(MINE){MINE.add(text);}}

    /** Wraps the game's walk over the texts of the frame, so that each is prepared with smoothing on only if a Craftium screen made it. */
    @SuppressWarnings("unchecked")
    public static Consumer<Object> forEachText(Consumer<?> game){
        Consumer<Object> inner=(Consumer<Object>)game;
        return text->{boolean mine;synchronized(MINE){mine=MINE.contains(text);}apply(wanted&&mine);inner.accept(text);};
    }

    /** The interface is drawn: the letters are the game's own again for everything drawn after it. */
    public static void end(){apply(false);synchronized(MINE){MINE.clear();}}

    private static void apply(boolean on){
        if(on==linear)return;
        linear=on;
        synchronized(ALL){for(var entry:ALL.entrySet())set(entry.getKey(),entry.getValue(),linear);}
    }

    private static void set(AbstractTexture texture,GpuSampler original,boolean linear){
        ((AbstractTextureAccessor)texture).craftium$setSampler(linear?RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.NEAREST,false):original);
    }
}
