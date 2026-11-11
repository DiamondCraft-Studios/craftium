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
    @Override public final void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){fitScale();var gfx=Gfx.of(g);double zoom=dev.craftium.client.InterfaceScale.zoom();if(zoom==1){draw(gfx,mouseX,mouseY,delta);return;}gfx.pose().pushMatrix();gfx.pose().scale((float)zoom,(float)zoom);try{draw(gfx,mouseX,mouseY,delta);}finally{gfx.pose().popMatrix();}}

    /** Draws the backdrop. The default is the vanilla one; override with an empty body to suppress it. */
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
    protected final void relayout(){var window=minecraft.getWindow();super.resize(window.getGuiScaledWidth(),window.getGuiScaledHeight());}
    @Override public void resize(int width,int height){dev.craftium.client.InterfaceScale.apply(minecraft,canvas());var window=minecraft.getWindow();super.resize(window.getGuiScaledWidth(),window.getGuiScaledHeight());}
    protected void drawBackground(Gfx g,int mouseX,int mouseY,float delta){super.extractBackground(g.raw,mouseX,mouseY,delta);}
    /** Draws the screen. Call {@link #drawWidgets} to render the registered widgets. */
    protected abstract void draw(Gfx g,int mouseX,int mouseY,float delta);
    /** Renders the screen's registered widgets, as the vanilla render pass does. */
    protected final void drawWidgets(Gfx g,int mouseX,int mouseY,float delta){var saved=dev.craftium.client.FlatBoxes.before(this,g);try{super.extractRenderState(g.raw,mouseX,mouseY,delta);}finally{dev.craftium.client.FlatBoxes.after(saved);}}
    /** Renders another screen as this one's backdrop, for a pop-up that must not hide it. */
    protected final void drawUnder(Screen s,Gfx g,int mouseX,int mouseY,float delta){s.extractRenderState(g.raw,mouseX,mouseY,delta);}
}
