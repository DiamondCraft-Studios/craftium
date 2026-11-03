package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.10 implementation: buttons paint in {@code renderWidget}. */
public abstract class CraftiumButton extends Button {
    protected CraftiumButton(int x,int y,int width,int height,Component label,Runnable action){super(x,y,width,height,label,b->action.run(),DEFAULT_NARRATION);}

    @Override protected final void renderWidget(GuiGraphics g,int mouseX,int mouseY,float delta){paint(Gfx.of(g),mouseX,mouseY,delta);}
    protected abstract void paint(Gfx g,int mouseX,int mouseY,float delta);
}
