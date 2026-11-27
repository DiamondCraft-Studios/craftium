package net.minecraft.client.input;

/** Craftium's 1.21.1 copy; see package-info. A key pressed or released. */
public record KeyEvent(int key, int scancode, int modifiers) implements InputWithModifiers {}
