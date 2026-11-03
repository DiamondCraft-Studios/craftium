package dev.craftium.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 1.21.10 implementation: the open screen is a public field on the client. */
public final class ClientCompat {
    private ClientCompat(){}

    public static Screen screen(Minecraft mc){return mc.screen;}
    public static void setScreen(Minecraft mc,Screen screen){mc.setScreen(screen);}
    public static boolean hudHidden(Minecraft mc){return mc.options.hideGui;}
    public static void toggleHud(Minecraft mc){mc.options.hideGui=!mc.options.hideGui;}
}
