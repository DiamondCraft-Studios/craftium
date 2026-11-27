package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumSlider;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A slider drawn in the editor's style. Ctrl-click types a value, middle-click resets it, and while dragging Ctrl steps
 * by 1 and Shift by 0.01, in the units of {@link #range}. The cursor is hidden while a drag is going.
 */
abstract class FlatSlider extends CraftiumSlider {
    private double min, max = 1, initial = Double.NaN;
    private boolean sliding, cursorHidden;
    /** Where the pointer and the value (in units) were when the drag began; the value moves by the distance from there. */
    private double slideStartX, slideStartUnits;

    private NumberEntry entry;
    private boolean selectingText;

    FlatSlider(int x, int y, int width, int height, Component label, double value) {
        super(x, y, width, height, label, value);
    }

    void setRaw(double value) {
        this.value = value;
        updateMessage();
    }
    /** The units the value is typed and stepped in, and the value a middle click resets to (in the same units). */
    FlatSlider range(double min, double max, double reset) {
        this.min = min;
        this.max = max;
        this.initial = reset;
        return this;
    }

    private double units() {
        return min + value * (max - min);
    }

    private void setUnits(double units) {
        double raw = max == min ? 0 : Math.clamp((units - min) / (max - min), 0, 1);
        if (raw == value) return;
        value = raw;
        applyValue();
        updateMessage();
    }
    /** Ctrl steps a value by 1 and Shift by 0.01. */
    static double step(double units, boolean ctrl, boolean shift) {
        return ctrl ? Math.round(units) : shift ? Math.round(units * 100) / 100d : units;
    }
    /** A drag moves the value from where it began by the distance moved, one track width being the whole range, so a click alone changes nothing. Every slider in the mod drags this way. */
    private void slideTo(double mouseX, boolean ctrl, boolean shift) {
        setUnits(step(slideStartUnits + (mouseX - slideStartX) / (getWidth() - 4) * (max - min), ctrl, shift));
    }
    /** How every slider looks: the track, the part filled up to {@code fraction}, and the value (or the digits being typed). */
    static void track(
            Gfx g,
            int left,
            int top,
            int width,
            int height,
            double fraction,
            Component text,
            boolean typing,
            boolean hovered,
            boolean cursorColour) {
        var o = ClientState.options;
        int filled = (int) Math.round(Math.clamp(fraction, 0, 1) * (width - 4));
        g.fill(left, top, left + width, top + height, hovered ? Hover.fill(0xff1b2b40) : 0xff1b2b40);
        g.fill(left + 2, top + 2, left + 2 + filled, top + height - 2, hovered ? Hover.fill(0xff315473) : 0xff315473);
        g.outline(left, top, width, height, typing || hovered ? o.uiAccent : o.uiBorder);
        g.centeredText(
                Minecraft.getInstance().font,
                text,
                left + width / 2,
                top + (height - 8) / 2,
                cursorColour ? 0xffffffff : o.uiText);
    }

    private void hideCursor(boolean hide) {
        if (hide == cursorHidden) return;
        cursorHidden = hide;
        GLFW.glfwSetInputMode(
                ClientCompat.windowHandle(),
                GLFW.GLFW_CURSOR,
                hide ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
    }

    private void stopSliding() {
        sliding = false;
        hideCursor(false);
    }

    private void finishTyping(boolean apply) {
        var finished = entry;
        entry = null;
        selectingText = false;
        if (!apply || finished == null) return;
        String text = finished.text();
        try {
            setUnits(Double.parseDouble(text.trim()));
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!active || !visible || !isMouseOver(event.x(), event.y())) {
            finishTyping(true);
            return false;
        }
        if (event.button() == 2) {
            if (!Double.isNaN(initial)) setUnits(initial);
            return true;
        }
        if (event.button() != 0) return false;
        var mc = Minecraft.getInstance();
        if (entry != null) { // already typing: a click puts the caret there, Shift stretches the selection, Ctrl or a
            // double click selects all
            if (ClientCompat.ctrlDown() || doubleClick) entry.selectAll();
            else entry.click(event.x(), getX(), getWidth(), ClientCompat.shiftDown());
            selectingText = true;
            return true;
        }
        if (ClientCompat.ctrlDown()) {
            String text = Double.toString(Math.rint(units() * 1000) / 1000);
            entry = new NumberEntry();
            entry.start(text.endsWith(".0") ? text.substring(0, text.length() - 2) : text);
            return true; // all of it selected
        }
        finishTyping(true);
        sliding = true;
        hideCursor(true);
        slideStartX = event.x();
        slideStartUnits = units();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (selectingText && entry != null && event.button() == 0) {
            entry.drag(event.x(), getX(), getWidth());
            return true;
        }
        if (!sliding || event.button() != 0) return false;
        var mc = Minecraft.getInstance();
        slideTo(event.x(), ClientCompat.ctrlDown(), ClientCompat.shiftDown());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (selectingText) {
            selectingText = false;
            return true;
        }
        if (!sliding) return false;
        stopSliding();
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) finishTyping(true);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (entry == null) return super.keyPressed(event);
        switch (entry.key(event)) {
            case APPLY -> finishTyping(true);
            case CANCEL -> finishTyping(false);
            default -> {}
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (entry == null) return false;
        entry.type(event.codepointAsString());
        return true;
    }

    @Override
    protected void paint(Gfx g, int x, int y, float delta) {
        // A release that never reached the slider, such as the screen closing mid-drag, must not leave the cursor
        // hidden.
        if (sliding
                && GLFW.glfwGetMouseButton(ClientCompat.windowHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS)
            stopSliding();
        track(
                g,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                value,
                entry == null ? getMessage() : Component.empty(),
                entry != null,
                isHoveredOrFocused(),
                entry != null);
        if (entry != null) entry.paint(g, getX(), getY(), getWidth(), getHeight());
    }
}
