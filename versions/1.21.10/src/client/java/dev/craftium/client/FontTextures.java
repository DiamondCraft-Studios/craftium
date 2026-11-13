package dev.craftium.client;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * The textures the game keeps the letters of its fonts in. In {@link TextMode.Mode#LINEAR} they are sampled with linear filtering only while the game draws its
 * interface (see {@link #begin}) with a Craftium screen shown at a fractional interface scale. Text drawn in the world, such as signs, name tags and the
 * labels of the viewport overlay, is drawn earlier in the frame and keeps the game's own nearest-pixel sampling.
 */
public final class FontTextures {
    private FontTextures(){}
    private static final Set<AbstractTexture> ALL=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static boolean linear,wanted;

    static{InterfaceScale.listener=FontTextures::update;}

    /** A font texture has been made. */
    public static void add(AbstractTexture texture){
        ALL.add(texture);
        texture.setFilter(linear,false);
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
        synchronized(ALL){for(var texture:ALL)texture.setFilter(linear,false);}
    }
}
