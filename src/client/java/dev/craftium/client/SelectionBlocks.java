package dev.craftium.client;

import dev.craftium.core.Point;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * The blocks the selection is made of, with how many of each, for the Selection window. The count is made on the render thread a slice of a frame at a time,
 * since the world is not safe to read from another thread, so a selection of a million blocks never stalls a frame. The last finished count stays on show until
 * the next one is done. A count is started again when the selection changes, and now and then while it stays, to pick up edits made to the world.
 */
final class SelectionBlocks {
    /** One kind of block in the selection. */
    record Entry(String id, int count) {}

    /** How long one frame may spend counting, in nanoseconds. */
    private static final long SLICE = 2_000_000L;
    /** How long a finished count is trusted before the world is read again, in milliseconds: shorter for a small selection, which is cheap to read. */
    private static long refreshAfter(int size) {
        return size > 200_000 ? 3000 : 700;
    }

    private static List<Point> source = List.of(), scanning = List.of();
    private static int index;
    private static Map<Block, int[]> working = new HashMap<>();
    private static List<Entry> entries = List.of();
    private static int total, air;
    private static long finishedAt;
    private static boolean done = true;
    private static final BlockPos.MutableBlockPos AT = new BlockPos.MutableBlockPos();

    private SelectionBlocks() {}
    /** The blocks of the last finished count, the most numerous first. Air is not among them. */
    static List<Entry> entries() {
        return entries;
    }
    /** How many selected blocks the last finished count covered, air included. */
    static int total() {
        return total;
    }
    /** How many of them were air. */
    static int air() {
        return air;
    }
    /** How far the count in progress has got, 0 to 1, or 1 when none is. */
    static double progress() {
        return done || scanning.isEmpty() ? 1 : (double) index / scanning.size();
    }

    static boolean counting() {
        return !done;
    }
    /** Called each frame the Selection window is shown: starts a count when the selection is new or the last is old, and works on the one in progress. */
    static void update(Minecraft mc) {
        var level = mc.level;
        var points = SelectionOverlay.points();
        if (level == null || points.isEmpty()) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = points != source;
        if (changed || (done && now - finishedAt > refreshAfter(points.size()))) {
            source = points;
            scanning = points;
            index = 0;
            working = new HashMap<>();
            done = false;
        }
        if (done) return;
        // a small selection is counted whole at once, so what is shown is never a frame behind it
        long end = scanning.size() <= 4096 ? Long.MAX_VALUE : System.nanoTime() + SLICE;
        int n = scanning.size();
        while (index < n) {
            for (int k = 0; k < 256 && index < n; k++, index++) {
                var p = scanning.get(index);
                var state = level.getBlockState(AT.set(p.x(), p.y(), p.z()));
                working.computeIfAbsent(state.getBlock(), b -> new int[1])[0]++;
            }
            if (System.nanoTime() > end) return;
        }
        finish();
    }

    private static void finish() {
        var list = new ArrayList<Entry>();
        int sum = 0, airCount = 0;
        for (var e : working.entrySet()) {
            int count = e.getValue()[0];
            sum += count;
            var state = e.getKey().defaultBlockState();
            if (state.isAir()) airCount += count;
            else list.add(new Entry(BuiltInRegistries.BLOCK.getKey(e.getKey()).toString(), count));
        }
        list.sort(Comparator.comparingInt(Entry::count).reversed().thenComparing(Entry::id));
        entries = List.copyOf(list);
        total = sum;
        air = airCount;
        working = new HashMap<>();
        done = true;
        finishedAt = System.currentTimeMillis();
    }
    /** Forgets everything, for no selection or no world. */
    static void reset() {
        source = scanning = List.of();
        entries = List.of();
        working = new HashMap<>();
        total = air = index = 0;
        done = true;
    }
    /** A count for a cell: 950, 1.2k, 34k, 1.5M. */
    static String abbreviate(int n) {
        if (n < 1000) return Integer.toString(n);
        if (n < 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1000d);
        if (n < 1_000_000) return n / 1000 + "k";
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000d);
    }
}
