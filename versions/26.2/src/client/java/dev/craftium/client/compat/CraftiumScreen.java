package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base for every Craftium screen. It owns the version-specific render hooks and hands subclasses a
 * {@link Gfx}, so screen code is written once for all supported releases. 26.2 implementation.
 */
public abstract class CraftiumScreen extends Screen {
    protected CraftiumScreen(Component title){super(title);}

    @Override public final void extractBackground(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){drawBackground(Gfx.of(g),mouseX,mouseY,delta);}
    @Override public final void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){draw(Gfx.of(g),mouseX,mouseY,delta);}

    /** Draws the backdrop. The default is the vanilla one; override with an empty body to suppress it. */
    protected void drawBackground(Gfx g,int mouseX,int mouseY,float delta){super.extractBackground(g.raw,mouseX,mouseY,delta);}
    /** Draws the screen. Call {@link #drawWidgets} to render the registered widgets. */
    protected abstract void draw(Gfx g,int mouseX,int mouseY,float delta);
    /** Renders the screen's registered widgets, as the vanilla render pass does. */
    protected final void drawWidgets(Gfx g,int mouseX,int mouseY,float delta){super.extractRenderState(g.raw,mouseX,mouseY,delta);}
}
