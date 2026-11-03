package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import dev.craftium.client.compat.Tex;

/** One shared mark for the mod listing, editor and builder HUD. */
final class CraftiumBrand {
    private static final Tex ICON=Tex.of("craftium","icon.png");
    private CraftiumBrand(){}
    static void draw(Gfx graphics,int x,int y,int size){
        graphics.blit(ICON,x,y,0,0,size,size,128,128,128,128,0xffffffff);
    }
}
