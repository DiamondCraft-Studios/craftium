package dev.craftium.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Client API differences between Minecraft releases. 26.2 implementation. */
public final class ClientCompat {
    private ClientCompat(){}

    /** The screen currently open, or null while playing. */
    public static Screen screen(Minecraft mc){return mc.gui.screen();}
    /** Opens a screen, or returns to the game when {@code screen} is null. */
    public static void setScreen(Minecraft mc,Screen screen){mc.gui.setScreen(screen);}
    public static boolean hudHidden(Minecraft mc){return mc.gui.hud.isHidden();}
    /** Flips the HUD between shown and hidden. */
    public static void toggleHud(Minecraft mc){mc.gui.hud.toggle();}
}
