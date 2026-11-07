package dev.craftium.core;

import com.google.gson.*;
import java.util.*;

/**
 * What an AI preview holds, in a few lines: how many blocks it places and clears, the box it fills, the chunks it reaches and the commonest
 * blocks, with how the ones that face a direction are turned. It is the answer to "what did that spec do?", so the AI can check a build
 * without drawing it or reading every block. A wrong stair facing shows here at once: a gable roof has two facings in equal numbers.
 */
public final class PreviewSummary {
    /** Blocks listed by name; the rest are counted together, since a long tail of one-off blocks says little. */
    public static final int LISTED = 16;

    private static final class Kind {
        long count;
        final TreeMap<String, Long> facing = new TreeMap<>();
    }

    private final Map<String, Kind> kinds = new HashMap<>();
    private final Set<Long> chunks = new HashSet<>();
    private long placed, cleared;
    private int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;

    /**
     * One changed block.
     * @param block the block id without its state, such as {@code minecraft:spruce_stairs}
     * @param facing the value of its {@code facing} property, or null when it has none
     * @param air whether the change clears the block rather than placing one
     */
    public void add(int x, int y, int z, String block, String facing, boolean air) {
        x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
        x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
        chunks.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL));
        if (air) { cleared++; return; }
        placed++;
        var kind = kinds.computeIfAbsent(block, k -> new Kind());
        kind.count++;
        if (facing != null) kind.facing.merge(facing, 1L, Long::sum);
    }

    public JsonObject toJson() {
        var out = new JsonObject();
        out.addProperty("placed", placed);
        out.addProperty("cleared", cleared);
        if (placed + cleared == 0) return out;
        out.add("min", ints(x0, y0, z0));
        out.add("max", ints(x1, y1, z1));
        out.add("size", ints(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1));
        out.addProperty("chunks", chunks.size());
        var sorted = new ArrayList<>(kinds.entrySet());
        sorted.sort((a, b) -> a.getValue().count != b.getValue().count ? Long.compare(b.getValue().count, a.getValue().count) : a.getKey().compareTo(b.getKey()));
        var list = new JsonArray();
        long rest = 0;
        for (int i = 0; i < sorted.size(); i++) {
            var entry = sorted.get(i);
            if (i >= LISTED) { rest += entry.getValue().count; continue; }
            var item = new JsonObject();
            item.addProperty("block", entry.getKey());
            item.addProperty("count", entry.getValue().count);
            if (!entry.getValue().facing.isEmpty()) {
                var facing = new JsonObject();
                entry.getValue().facing.forEach(facing::addProperty);
                item.add("facing", facing);
            }
            list.add(item);
        }
        out.add("blocks", list);
        if (rest > 0) {
            out.addProperty("otherKinds", sorted.size() - LISTED);
            out.addProperty("otherBlocks", rest);
        }
        return out;
    }

    private static JsonArray ints(int... values) {
        var out = new JsonArray();
        for (int v : values) out.add(v);
        return out;
    }
}
