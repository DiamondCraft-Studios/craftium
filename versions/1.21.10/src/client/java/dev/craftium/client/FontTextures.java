package dev.craftium.client;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * The textures the game keeps the letters of its fonts in. In {@link TextMode.Mode#LINEAR} they are sampled with linear filtering while a Craftium screen is
 * shown at a fractional interface scale, and with the game's own nearest-pixel sampling at any other time.
 */
public final class FontTextures {
    private FontTextures(){}
    private static final Set<AbstractTexture> ALL=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static boolean linear;

    static{InterfaceScale.listener=FontTextures::update;}

    /** A font texture has been made. */
    public static void add(AbstractTexture texture){
        ALL.add(texture);
        texture.setFilter(linear,false);
    }

    /** Sets every font texture to what the mode and the interface scale now call for. */
    public static void update(){
        boolean want=TextMode.mode()==TextMode.Mode.LINEAR&&InterfaceScale.zoom()!=1;
        if(want==linear)return;
        linear=want;
        synchronized(ALL){for(var texture:ALL)texture.setFilter(linear,false);}
    }
}
