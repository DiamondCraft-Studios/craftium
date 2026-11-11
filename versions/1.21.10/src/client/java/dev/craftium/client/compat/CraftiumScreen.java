package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.10 implementation: screens still render through {@code render}/{@code renderBackground}. */
public abstract class CraftiumScreen extends Screen {
    protected CraftiumScreen(Component title){super(title);}

    @Override public final void renderBackground(GuiGraphics g,int mouseX,int mouseY,float delta){drawBackground(Gfx.of(g),mouseX,mouseY,delta);}
    @Override public final void render(GuiGraphics g,int mouseX,int mouseY,float delta){fitScale();var gfx=Gfx.of(g);double zoom=dev.craftium.client.InterfaceScale.zoom();if(zoom==1){draw(gfx,mouseX,mouseY,delta);return;}gfx.pose().pushMatrix();gfx.pose().scale((float)zoom,(float)zoom);try{draw(gfx,mouseX,mouseY,delta);}finally{gfx.pose().popMatrix();}}

    /** The canvas, in interface units, that this screen needs to fit; null for one that takes any size. */
    protected int[] canvas(){return null;}
    /** Sets the GUI scale this screen is shown at, which the window's resizing and the other screens would otherwise have changed. */
    /** True while the screen must not change size under the pointer, as with a slider being dragged. */
    protected boolean holdScale(){return false;}
    private void fitScale(){if(!holdScale()&&dev.craftium.client.InterfaceScale.apply(minecraft,canvas()))relayout();}
    /** Ctrl+= and Ctrl+- (and the other interface-size keys) work on every Craftium screen; a screen that overrides keyPressed calls this first. */
    protected final boolean interfaceKey(net.minecraft.client.input.KeyEvent event){int step=dev.craftium.client.InterfaceScale.hotkey(event);if(step==0)return false;resizeInterface(step);return true;}
    protected final void resizeInterface(int step){dev.craftium.client.InterfaceScale.resize(minecraft,step);relayout();}
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event){return interfaceKey(event)||super.keyPressed(event);}
    /** Lays the screen out again at the window's current GUI scale. */
    protected final void relayout(){var window=minecraft.getWindow();super.resize(minecraft,window.getGuiScaledWidth(),window.getGuiScaledHeight());}
    @Override public void resize(net.minecraft.client.Minecraft mc,int width,int height){dev.craftium.client.InterfaceScale.apply(minecraft,canvas());var window=minecraft.getWindow();super.resize(mc,window.getGuiScaledWidth(),window.getGuiScaledHeight());}
    protected void drawBackground(Gfx g,int mouseX,int mouseY,float delta){super.renderBackground(g.raw,mouseX,mouseY,delta);}
    protected abstract void draw(Gfx g,int mouseX,int mouseY,float delta);
    protected final void drawWidgets(Gfx g,int mouseX,int mouseY,float delta){super.render(g.raw,mouseX,mouseY,delta);}
    /** Renders another screen as this one's backdrop, for a pop-up that must not hide it. */
    protected final void drawUnder(Screen s,Gfx g,int mouseX,int mouseY,float delta){s.render(g.raw,mouseX,mouseY,delta);}
}
