package dev.craftium.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * The textures the game keeps the letters of its fonts in. In {@link TextMode.Mode#LINEAR} they are sampled with linear filtering only for the texts of a Craftium
 * screen (see {@link #forEachText}) shown at a fractional interface scale. Text drawn in the world, such as signs, name tags and the
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
        synchronized(ALL){for(var texture:ALL)texture.setFilter(linear,false);}
    }
}
