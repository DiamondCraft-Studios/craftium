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
