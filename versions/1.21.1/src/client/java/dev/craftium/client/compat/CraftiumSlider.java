package dev.craftium.client.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Minecraft 1.21.1 implementation: sliders paint in {@code renderWidget}, and the input calls of 1.21.1 are turned
 * into the input records later versions pass, the same way {@link CraftiumScreen} does.
 */
public abstract class CraftiumSlider extends AbstractSliderButton {
    protected CraftiumSlider(int x, int y, int width, int height, Component label, double value) {
        super(x, y, width, height, label, value);
    }

    @Override
    public final void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        paint(Gfx.of(g), mouseX, mouseY, delta);
    }

    protected abstract void paint(Gfx g, int mouseX, int mouseY, float delta);

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
        return super.keyPressed(event.key(), event.scancode(), event.modifiers());
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
}
