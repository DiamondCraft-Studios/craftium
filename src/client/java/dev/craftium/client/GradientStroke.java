package dev.craftium.client;

import dev.craftium.core.BlockList;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The Gradient Painter's line with Match the ring on: the blocks the brush ring covers swept from start to end, as the preview shows
 * them while the line is dragged and as the stroke names them to the server when it is let go.
 */
public final class GradientStroke {
    private GradientStroke() {}

    /** The end the preview last showed, and when: the preview follows the end in steps, not at every block. */
    private static Point shownStart, shownEnd;

    private static long shownAt;
    /** How long the preview may lag behind an end that has stopped moving by less than a step. */
    private static final long CATCH_UP_MS = 150;

    /** A new line begins: the world is looked at afresh and the preview starts from its first end. */
    public static void reset() {
        ScreenFootprint.forgetSweep();
        shownStart = shownEnd = null;
    }

    /**
     * The end the preview shows for the line being dragged: the real end once it has moved a quarter of the ring from the one shown,
     * or has been somewhere else for a moment. Working the swept ring out again for every block the pointer crosses is what a big ring
     * cannot afford.
     */
    static Point previewEnd(Options o) {
        Point a = o.gradientAnchors.getFirst(), b = o.gradientAnchors.getLast();
        long now = System.currentTimeMillis();
        int step = Math.max(1, o.radius / 4);
        if (shownEnd == null
                || !a.equals(shownStart)
                || Math.max(
                                Math.abs(b.x() - shownEnd.x()),
                                Math.max(Math.abs(b.y() - shownEnd.y()), Math.abs(b.z() - shownEnd.z())))
                        >= step
                || !b.equals(shownEnd) && now - shownAt >= CATCH_UP_MS) {
            shownStart = a;
            shownEnd = b;
            shownAt = now;
        }
        return shownEnd;
    }

    /**
     * The blocks the ring swept from {@code a} to {@code b} covers, or null when the tool does not paint in screen space or the swept ring
     * does not fit on the screen: the server then puts the brush shape down along the line.
     */
    static List<Point> covered(Options o, Point a, Point b) {
        var ring = ScreenFootprint.active(o);
        var level = Minecraft.getInstance().level;
        if (ring == null || level == null) return null;
        return ScreenFootprint.coveredAlong(level, o, a, b, ring);
    }

    /** {@link #covered} as a request carries it in {@link Options#screenBlocks}; empty for the brush shape. */
    public static String encoded(Point a, Point b) {
        var o = ClientState.options;
        var blocks = covered(o, a, b);
        if (blocks == null) {
            if (ScreenFootprint.active(o) != null)
                ClientState.notify("Part of the line was off the screen, so it was painted with the brush shape");
            return "";
        }
        return blocks.size() > Options.MAX_SCREEN_BLOCKS ? "" : BlockList.encode(blocks);
    }
}
