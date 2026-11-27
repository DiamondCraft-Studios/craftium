package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Minecraft 1.21.1 implementation. Two things differ from later versions, and both stay inside this class:
 * <ul>
 *   <li>Input arrives as coordinates, buttons and key codes. The final overrides below turn each call into the input
 *       record later versions pass and call the method shared screens override; their {@code super} calls come back
 *       here and go on to vanilla in 1.21.1's form.
 *   <li>{@code Screen.render} draws the background itself. This screen draws its background first, as later
 *       versions do, and keeps vanilla from drawing it again when the widgets are drawn.
 * </ul>
 */
public abstract class CraftiumScreen extends Screen {
    /** Set while vanilla draws the widgets, so its own background call draws nothing. */
    private boolean widgetsOnly;

    protected CraftiumScreen(Component title) {
        super(title);
    }

    @Override
    public final void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!widgetsOnly) drawBackground(Gfx.of(g), mouseX, mouseY, delta);
    }

    @Override
    public final void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g, mouseX, mouseY, delta);
        renderContent(g, mouseX, mouseY, delta);
    }

    /** Everything but the background, at the screen's own zoom. */
    private void renderContent(GuiGraphics g, int mouseX, int mouseY, float delta) {
        fitScale();
        var gfx = Gfx.of(g);
        double zoom = dev.craftium.client.InterfaceScale.zoom();
        if (zoom == 1) {
            draw(gfx, mouseX, mouseY, delta);
            return;
        }
        gfx.pose().pushMatrix();
        gfx.pose().scale((float) zoom, (float) zoom);
        try {
            draw(gfx, mouseX, mouseY, delta);
        } finally {
            gfx.pose().popMatrix();
        }
    }

    /** Later versions ask whether a screen belongs to the game's own HUD; 1.21.1 does not, so this only gives shared screens something to override. */
    public boolean isInGameUi() {
        return false;
    }

    /** The canvas, in interface units, that this screen needs to fit; null for one that takes any size. */
    protected int[] canvas() {
        return null;
    }
    /** True while the screen must not change size under the pointer, as with a slider being dragged. */
    protected boolean holdScale() {
        return false;
    }

    private void fitScale() {
        if (!holdScale() && dev.craftium.client.InterfaceScale.apply(minecraft, canvas())) relayout();
    }
    /** Ctrl+= and Ctrl+- (and the other interface-size keys) work on every Craftium screen; a screen that overrides keyPressed calls this first. */
    protected final boolean interfaceKey(KeyEvent event) {
        int step = dev.craftium.client.InterfaceScale.hotkey(event);
        if (step == 0) return false;
        resizeInterface(step);
        return true;
    }

    protected final void resizeInterface(int step) {
        dev.craftium.client.InterfaceScale.resize(minecraft, step);
        relayout();
    }

    // Input: 1.21.1's calls in, the later records out.

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        return mouseClicked(InputBridge.mouse(x, y, button), InputBridge.doubleClick(button));
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        return mouseReleased(InputBridge.mouse(x, y, button));
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(event.x(), event.y(), event.button());
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return mouseDragged(InputBridge.mouse(x, y, button), dx, dy);
    }

    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return super.mouseDragged(event.x(), event.y(), event.button(), dx, dy);
    }

    @Override
    public final boolean keyPressed(int key, int scancode, int modifiers) {
        return keyPressed(new KeyEvent(key, scancode, modifiers));
    }

    public boolean keyPressed(KeyEvent event) {
        return interfaceKey(event) || super.keyPressed(event.key(), event.scancode(), event.modifiers());
    }

    @Override
    public final boolean keyReleased(int key, int scancode, int modifiers) {
        return keyReleased(new KeyEvent(key, scancode, modifiers));
    }

    public boolean keyReleased(KeyEvent event) {
        return super.keyReleased(event.key(), event.scancode(), event.modifiers());
    }

    @Override
    public final boolean charTyped(char character, int modifiers) {
        return charTyped(new CharacterEvent(character, modifiers));
    }

    public boolean charTyped(CharacterEvent event) {
        boolean handled = false;
        for (char c : Character.toChars(event.codepoint())) handled |= super.charTyped(c, event.modifiers());
        return handled;
    }

    /** Lays the screen out again at the window's current GUI scale. */
    protected final void relayout() {
        var window = minecraft.getWindow();
        super.resize(minecraft, window.getGuiScaledWidth(), window.getGuiScaledHeight());
    }

    @Override
    public void resize(net.minecraft.client.Minecraft mc, int width, int height) {
        dev.craftium.client.InterfaceScale.apply(minecraft, canvas());
        var window = minecraft.getWindow();
        super.resize(mc, window.getGuiScaledWidth(), window.getGuiScaledHeight());
    }

    protected void drawBackground(Gfx g, int mouseX, int mouseY, float delta) {
        super.renderBackground(g.raw, mouseX, mouseY, delta);
    }

    protected abstract void draw(Gfx g, int mouseX, int mouseY, float delta);

    protected final void drawWidgets(Gfx g, int mouseX, int mouseY, float delta) {
        var saved = dev.craftium.client.FlatBoxes.before(this, g);
        widgetsOnly = true;
        try {
            super.render(g.raw, mouseX, mouseY, delta);
        } finally {
            widgetsOnly = false;
            dev.craftium.client.FlatBoxes.after(saved);
        }
    }
    /** Renders another screen as this one's backdrop, for a pop-up that must not hide it. */
    protected final void drawUnder(Screen s, Gfx g, int mouseX, int mouseY, float delta) {
        // The screen below zooms itself; undo this screen's zoom first so it is not applied twice (at 1.5x the backdrop
        // shrank to 0.75 of itself). Like later versions, the screen below is drawn without its background.
        double zoom = dev.craftium.client.InterfaceScale.zoom();
        if (zoom != 1) {
            g.pose().pushMatrix();
            g.pose().scale((float) (1 / zoom), (float) (1 / zoom));
        }
        try {
            if (s instanceof CraftiumScreen below) below.renderContent(g.raw, mouseX, mouseY, delta);
            else s.render(g.raw, mouseX, mouseY, delta);
        } finally {
            if (zoom != 1) g.pose().popMatrix();
        }
    }
}
