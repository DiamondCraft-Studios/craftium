package dev.craftium.client.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/**
 * Minecraft 1.21.1 only: turns the coordinate-and-button input calls of 1.21.1 into the input records later versions
 * pass, which the shared screens and widgets are written against. 1.21.1 does not tell a double click apart, so this
 * does, with the same 250 ms window later versions use.
 */
final class InputBridge {
    private InputBridge() {}

    private static final long DOUBLE_CLICK_MS = 250, SAME_CLICK_MS = 40;
    private static long lastClick, lastAsked;
    private static int lastButton = -1;
    private static boolean lastAnswer;

    /** The modifier keys held now, as GLFW modifier bits. */
    static int modifiers() {
        int bits = 0;
        if (Screen.hasShiftDown()) bits |= GLFW.GLFW_MOD_SHIFT;
        if (Screen.hasControlDown()) bits |= GLFW.GLFW_MOD_CONTROL;
        if (Screen.hasAltDown()) bits |= GLFW.GLFW_MOD_ALT;
        return bits;
    }

    static MouseButtonEvent mouse(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers()));
    }

    /**
     * Whether a press of {@code button} now follows the previous one closely enough to be a double click. A screen
     * hands the same press on to its widgets, which ask again within a few milliseconds: they get the same answer.
     */
    static boolean doubleClick(int button) {
        long now = net.minecraft.Util.getMillis();
        if (button == lastButton && now - lastAsked < SAME_CLICK_MS) return lastAnswer;
        boolean twice = button == lastButton && now - lastClick < DOUBLE_CLICK_MS;
        lastClick = twice ? 0 : now; // a third click starts a new pair
        lastButton = button;
        lastAsked = now;
        lastAnswer = twice;
        return twice;
    }
}
