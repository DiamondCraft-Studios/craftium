package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.10 implementation: sliders paint in {@code renderWidget}; there is no cursor-handling step. */
public abstract class CraftiumSlider extends AbstractSliderButton {
    protected CraftiumSlider(int x,int y,int width,int height,Component label,double value){super(x,y,width,height,label,value);}

    @Override public final void renderWidget(GuiGraphics g,int mouseX,int mouseY,float delta){paint(Gfx.of(g),mouseX,mouseY,delta);}
    protected abstract void paint(Gfx g,int mouseX,int mouseY,float delta);
}
