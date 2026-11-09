package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Selection;

/**
 * How a new selection combines with the current one, drawn as two squares: replace is one square, add two joined squares, subtract a square with
 * the second one cut out of it, and intersect two outlines with only where they meet filled. The cursor badge and the mode buttons draw the same icon.
 */
final class SelectionModeIcon {
    private SelectionModeIcon() {}

    /** The icon in a {@code size} pixel square whose corner is at {@code x}, {@code y}, in {@code ink}. Made for 14 to 20 pixels. */
    static void draw(Gfx g, Selection.Mode mode, int x, int y, int size, int ink) {
        int pad = size * 3 / 20, s = size * 9 / 20, ax = x + pad, ay = y + pad, bx = ax + size * 5 / 20, by = ay + size * 5 / 20;
        switch (mode) {
            case REPLACE -> g.fill(x + size / 4, y + size / 4, x + size * 3 / 4, y + size * 3 / 4, ink);
            case ADD -> { g.fill(ax, ay, ax + s, ay + s, ink); g.fill(bx, by, bx + s, by + s, ink); }
            case SUBTRACT -> { // the first square without the part the second covers, and the second as an outline
                g.fill(ax, ay, ax + s, by, ink); g.fill(ax, by, bx, ay + s, ink);
                g.outline(bx, by, s, s, ink);
            }
            case INTERSECT -> { g.outline(ax, ay, s, s, ink); g.outline(bx, by, s, s, ink); g.fill(bx, by, ax + s, ay + s, ink); }
        }
    }
}
