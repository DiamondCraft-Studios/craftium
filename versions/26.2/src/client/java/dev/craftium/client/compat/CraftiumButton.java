package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Button base that paints through {@link Gfx}. 26.2 implementation. */
public abstract class CraftiumButton extends Button {
    protected CraftiumButton(int x,int y,int width,int height,Component label,Runnable action){super(x,y,width,height,label,b->action.run(),DEFAULT_NARRATION);}

    @Override protected final void extractContents(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){paint(Gfx.of(g),mouseX,mouseY,delta);}
    protected abstract void paint(Gfx g,int mouseX,int mouseY,float delta);
}
