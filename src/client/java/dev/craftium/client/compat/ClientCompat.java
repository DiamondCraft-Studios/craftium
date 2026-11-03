package dev.craftium.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Client API differences between Minecraft releases. 26.2 implementation. */
public final class ClientCompat {
    private ClientCompat(){}

    /** The screen currently open, or null while playing. */
    public static Screen screen(Minecraft mc){return mc.gui.screen();}
}
