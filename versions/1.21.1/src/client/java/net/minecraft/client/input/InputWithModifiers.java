package net.minecraft.client.input;

import org.lwjgl.glfw.GLFW;

/** Craftium's 1.21.1 copy; see package-info. The GLFW modifier bits held when the input happened. */
public interface InputWithModifiers {
    int modifiers();

    default boolean hasShiftDown() {
        return (modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
    }

    default boolean hasControlDown() {
        return (modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
    }

    default boolean hasAltDown() {
        return (modifiers() & GLFW.GLFW_MOD_ALT) != 0;
    }
}
