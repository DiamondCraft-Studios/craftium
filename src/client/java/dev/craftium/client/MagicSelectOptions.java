package dev.craftium.client;

import dev.craftium.core.IconCatalog;
import dev.craftium.core.Options;
import dev.craftium.core.Selection;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Magic Wand's search settings as one widget that fits in any panel: which way the search may spread (Up, Down, Horizontal, Corners), how a new
 * selection combines with the current one (the mode), and which blocks count as the same (the select type). They read and write the editor's
 * options, so every place that shows the widget shows the same settings. The Paint Bucket shows the directions and the type; the mode only means
 * something for a selection.
 *
 * <p>It draws through a {@link Host}, which is all it asks of a panel, so it does not depend on the editor screen.
 */
final class MagicSelectOptions {
    private MagicSelectOptions() {}

    /** The three groups of settings the widget can show. */
    enum Part { DIRECTIONS, MODE, TYPE }

    static final Set<Part> ALL = EnumSet.allOf(Part.class);
    /** For tools that fill what the search finds rather than select it. */
    static final Set<Part> SEARCH_ONLY = EnumSet.of(Part.DIRECTIONS, Part.TYPE);

    /** What the widget needs from the panel that shows it. */
    interface Host {
        void button(int x, int y, int width, int height, String label, boolean active, Runnable action, String tip);
        /** A 24 pixel icon from the catalog, drawn at three quarters of its size with its corner at {@code x}, {@code y}. */
        void icon(float x, float y, String iconId, boolean active);
        /** The icon for a selection mode, in a 13 pixel square with its corner at {@code x}, {@code y}. */
        void modeIcon(int x, int y, Selection.Mode mode, boolean active);
    }

    /** Draws the parts asked for, top down, from {@code y}; returns the room they took. */
    static int draw(Host host, Options o, int x, int y, int width, Set<Part> parts) {
        int end = y;
        if (parts.contains(Part.DIRECTIONS)) { directions(host, o, x, y, width); end = y + 40; }
        if (parts.contains(Part.MODE)) { mode(host, o, x, y + 45, width); end = y + 45 + 41; }
        if (parts.contains(Part.TYPE)) { int at = y + (parts.contains(Part.MODE) ? 95 : 45); type(host, o, x, at, width); end = at + 22; }
        return end - y;
    }

    /** Which ways the search may spread from the clicked block, two buttons a row with the allowed ones lit. */
    static void directions(Host host, Options o, int x, int y, int width) {
        int half = (width - 4) / 2;
        String[] labels = {"Up", "Down", "Horizontal", "Corners"};
        String[] tips = {"Spread to the blocks above the clicked one", "Spread to the blocks below the clicked one", "Spread sideways, level with the clicked block", "Also connect blocks that only touch at an edge or corner"};
        boolean[] on = {o.selectUp, o.selectDown, o.selectHorizontal, o.selectCorners};
        Runnable[] flip = {() -> o.selectUp = !o.selectUp, () -> o.selectDown = !o.selectDown, () -> o.selectHorizontal = !o.selectHorizontal, () -> o.selectCorners = !o.selectCorners};
        for (int i = 0; i < 4; i++) host.button(x + (i % 2) * (half + 4), y + (i / 2) * 21, half, 19, labels[i], on[i], flip[i], tips[i]);
    }

    /** How a new selection combines with the current one, as four buttons in a grid with the active one lit. */
    static void mode(Host host, Options o, int x, int y, int width) {
        int half = (width - 4) / 2;
        var modes = Selection.Mode.values();
        String[] tips = {"Replace the selection", "Add to the selection", "Subtract from the selection", "Keep only what overlaps the selection"};
        for (int i = 0; i < modes.length; i++) {
            final var mode = modes[i];
            int at = x + (i % 2) * (half + 4), row = y + (i / 2) * 22;
            host.button(at, row, half, 19, name(mode), o.mode == mode, () -> o.mode = mode, tips[i]);
            host.modeIcon(at + 5, row + 3, mode, o.mode == mode);
        }
    }

    /** Which blocks the search connects, as one icon button each with the active one lit. */
    static void type(Host host, Options o, int x, int y, int width) {
        var kinds = Options.Compare.values();
        int each = (width - 4 * (kinds.length - 1)) / kinds.length;
        String[] tips = {"Any: connect every non-air block", "Block: connect blocks of the same type (default)", "Block state: connect blocks of the same type and state", "Tag: connect blocks that share a block tag with the clicked one", "NBT: connect blocks of the same type with the same block entity data"};
        for (int i = 0; i < kinds.length; i++) {
            final var kind = kinds[i];
            int at = x + i * (each + 4);
            boolean active = o.compare == kind;
            host.button(at, y, each, 22, "", active, () -> o.compare = kind, tips[i]);
            host.icon(at + (each - 18) / 2f, y + 2, IconCatalog.compare(kind), active);
        }
    }

    private static String name(Enum<?> value) {
        String s = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
