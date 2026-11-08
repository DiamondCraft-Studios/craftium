package dev.craftium.core;

import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Lays out the settings a tool declares as a form in the options panel: one row for each, in order, with the sizes and gaps the built-in panels
 * use. It knows nothing of how a control is drawn; the screen gives it a {@link PanelBuilder}.
 */
public final class PropertyForm {
    private PropertyForm() {}

    /** The room a slider takes: its label sits above the track. */
    public static final int SLIDER_ROW = 28;
    public static final int TOGGLE_ROW = 22;
    public static final int CHOICE_ROW = 26;
    public static final int CHOICE_HEIGHT = 20;
    /** A slider's label is written this far above its track. */
    private static final int LABEL_ABOVE = 10;

    /** What the form needs of a panel. Coordinates are in the panel's own units, from its top left corner. */
    public interface PanelBuilder {
        /** A slider with its label above it. {@code integer} shows the value without decimals. */
        void slider(int x, int y, int width, String label, double value, double min, double max, double start, DoubleConsumer set, boolean integer);
        /** A switch with its label beside it. */
        void toggle(int x, int y, String label, boolean value, Runnable action);
        /** A button that cycles: a click goes to the next value and a right click back to the one before. */
        void cycle(int x, int y, int width, int height, String label, Runnable next, Runnable previous);
    }

    /** Draws the form with its top at {@code y} and returns where the next thing under it can start. */
    public static int draw(PanelBuilder panel, PropertyValues values, int x, int y, int width) {
        for (PropertySpec spec : values.specs()) {
            String id = spec.id();
            switch (spec.kind()) {
                case NUMBER, INTEGER -> {
                    boolean whole = spec.kind() == PropertySpec.Kind.INTEGER;
                    panel.slider(x, y + LABEL_ABOVE, width, spec.label(), values.number(id), spec.min(), spec.max(), spec.number(), v -> values.set(id, v), whole);
                    y += SLIDER_ROW;
                }
                case TOGGLE -> {
                    panel.toggle(x, y, spec.label(), values.toggled(id), () -> values.setToggled(id, !values.toggled(id)));
                    y += TOGGLE_ROW;
                }
                case CHOICE -> {
                    panel.cycle(x, y, width, CHOICE_HEIGHT, spec.label() + ": " + values.choiceName(id), () -> values.step(id, 1), () -> values.step(id, -1));
                    y += CHOICE_ROW;
                }
            }
        }
        return y;
    }

    /** The settings of a tool as the form lays them out, for a caller that only needs to know how tall it is. */
    public static int height(List<PropertySpec> specs) {
        int total = 0;
        for (PropertySpec spec : specs) total += switch (spec.kind()) { case NUMBER, INTEGER -> SLIDER_ROW; case TOGGLE -> TOGGLE_ROW; case CHOICE -> CHOICE_ROW; };
        return total;
    }
}
