package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Slider base that paints through {@link Gfx} and keeps the vanilla cursor handling. 26.2 implementation. */
public abstract class CraftiumSlider extends AbstractSliderButton {
    protected CraftiumSlider(int x,int y,int width,int height,Component label,double value){super(x,y,width,height,label,value);}

    @Override public final void extractWidgetRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){paint(Gfx.of(g),mouseX,mouseY,delta);handleCursor(g);}
    protected abstract void paint(Gfx g,int mouseX,int mouseY,float delta);
}
