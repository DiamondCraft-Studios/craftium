package dev.craftium.client;

import dev.craftium.client.compat.Gfx;

/**
 * A circle on the screen around the cursor, as wide as the brush reaches at the block under it, so the area a Paint or Sculpt stroke covers
 * can be told at a glance. It is drawn like the vanilla crosshair, inverting what is under it, so it shows on any ground. It is interface,
 * not world: it stays round and does not follow the surface.
 */
public final class BrushRing {
    private BrushRing() {}

    /** Whether the tool is one of the Paint or Sculpt brushes, which the ring is for. */
    public static boolean shownFor(dev.craftium.core.Tool tool) {
        return tool.brushUse() != dev.craftium.core.Tool.BrushUse.NONE
                && !dev.craftium.client.FluidPreview.handles(tool)
                && ("Paint".equals(tool.group) || "Sculpt".equals(tool.group));
    }

    /**
     * Draws the ring, one screen pixel thick, centred on ({@code cx}, {@code cy}) with {@code radius}, all in screen pixels. It is the midpoint
     * circle, so every step of the line is one pixel sideways or diagonal and none is doubled or missing: pixel-perfect. Each pixel is inverted
     * whole, and once, since inverting a pixel twice would put it back.
     */
    public static void draw(Gfx g, double cx, double cy, double radius) {
        int r = (int) Math.round(radius), x0 = (int) Math.round(cx - .5), y0 = (int) Math.round(cy - .5);
        if (r < 2) return;
        var drawn = new java.util.HashSet<Long>();
        int x = r, y = 0, error = 1 - r;
        while (x >= y) {
            plot(g, drawn, x0, y0, x, y);
            plot(g, drawn, x0, y0, y, x);
            plot(g, drawn, x0, y0, -y, x);
            plot(g, drawn, x0, y0, -x, y);
            plot(g, drawn, x0, y0, -x, -y);
            plot(g, drawn, x0, y0, -y, -x);
            plot(g, drawn, x0, y0, y, -x);
            plot(g, drawn, x0, y0, x, -y);
            y++;
            if (error < 0) error += 2 * y + 1;
            else {
                x--;
                error += 2 * (y - x) + 1;
            }
        }
    }

    private static void plot(Gfx g, java.util.Set<Long> drawn, int x0, int y0, int dx, int dy) {
        if (drawn.add((long) dx << 32 | (dy & 0xffffffffL))) g.invertFill(x0 + dx, y0 + dy, x0 + dx + 1, y0 + dy + 1, 0xffffffff);
    }
}
