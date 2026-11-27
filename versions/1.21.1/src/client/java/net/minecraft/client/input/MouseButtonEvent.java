package net.minecraft.client.input;

/** Craftium's 1.21.1 copy; see package-info. A mouse button pressed or released at a point in GUI coordinates. */
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) implements InputWithModifiers {
    public int button() {
        return buttonInfo.button();
    }

    @Override
    public int modifiers() {
        return buttonInfo.modifiers();
    }
}
