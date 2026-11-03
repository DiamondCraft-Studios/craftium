package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.10 implementation: screens still render through {@code render}/{@code renderBackground}. */
public abstract class CraftiumScreen extends Screen {
    protected CraftiumScreen(Component title){super(title);}

    @Override public final void renderBackground(GuiGraphics g,int mouseX,int mouseY,float delta){drawBackground(Gfx.of(g),mouseX,mouseY,delta);}
    @Override public final void render(GuiGraphics g,int mouseX,int mouseY,float delta){draw(Gfx.of(g),mouseX,mouseY,delta);}

    protected void drawBackground(Gfx g,int mouseX,int mouseY,float delta){super.renderBackground(g.raw,mouseX,mouseY,delta);}
    protected abstract void draw(Gfx g,int mouseX,int mouseY,float delta);
    protected final void drawWidgets(Gfx g,int mouseX,int mouseY,float delta){super.render(g.raw,mouseX,mouseY,delta);}
    /** Renders another screen as this one's backdrop, for a pop-up that must not hide it. */
    protected final void drawUnder(Screen s,Gfx g,int mouseX,int mouseY,float delta){s.render(g.raw,mouseX,mouseY,delta);}
}
