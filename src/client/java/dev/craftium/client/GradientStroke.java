package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The Gradient Painter's stroke while the button is held: the blocks the brush ring has covered so far, which the preview shows together
 * with the ring and which are painted when the button is let go.
 */
public final class GradientStroke {
    private GradientStroke() {}

    private static final LinkedHashSet<Point> covered = new LinkedHashSet<>();
    private static int version;
    private static boolean full;

    /** Starts a new stroke with nothing covered. */
    public static void clear() {
        if (covered.isEmpty() && !full) return;
        covered.clear();
        full = false;
        version++;
    }

    /**
     * Adds what the ring covers now, with the pointer on {@code at}, when the tool paints in screen space. Returns false once the stroke
     * names as many blocks as a request may carry, when nothing more is added.
     */
    public static boolean addRing(BlockPos at) {
        var o = ClientState.options;
        var ring = ScreenFootprint.active(o);
        var level = Minecraft.getInstance().level;
        if (ring == null || level == null || at == null) return true;
        int before = covered.size();
        for (Point p : ScreenFootprint.covered(level, o, at, ring)) {
            if (covered.size() >= Options.MAX_SCREEN_BLOCKS) {
                full = true;
                break;
            }
            covered.add(p);
        }
        if (covered.size() != before) version++;
        return !full;
    }

    /** Whether the ring paints this stroke, so its blocks are named, or the server puts the brush down along the path instead. */
    public static boolean ringActive() {
        return ScreenFootprint.active(ClientState.options) != null;
    }

    public static List<Point> covered() {
        return List.copyOf(covered);
    }

    static Set<Point> view() {
        return Collections.unmodifiableSet(covered);
    }

    /** Changes each time the covered blocks do, for the preview's key. */
    static int version() {
        return version;
    }
}
