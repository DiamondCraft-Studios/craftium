package dev.craftium.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * What a selection is, for an AI that has to decide where to build: how many blocks, its bounds, whether it is a solid box or an
 * irregular shape (a lasso or a magic wand picks any set of blocks), whether it is flat, what it is made of and how much clear space
 * there is above it. Pure: the blocks are read through {@link World}, so it needs no game. It is written for a selection of a million
 * blocks: primitive arrays and one pass per question, not a boxed object per block or per column.
 */
public final class SelectionReport {
    /** The block at a position as an id without properties, or null when it was not read. */
    public interface World { String at(int x, int y, int z); }

    /** How far above the selection to look for something in the way. */
    public static final int HEADROOM = 24;
    static final int MAX_LAYERS = 16, MAX_BLOCKS = 12, MAX_OBSTRUCTIONS = 6;
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private static final String WORLD_TOP = "craftium:outside_world";

    private SelectionReport() {}

    /** The highest selected y of each (x, z) column, in an open-addressing table of primitives. */
    private static final class Columns {
        private long[] keys = new long[1 << 12];
        private int[] tops = new int[1 << 12];
        private boolean[] filled = new boolean[1 << 12];
        private int size;

        int size() { return size; }

        void raise(long key, int y) {
            int mask = keys.length - 1, at = slot(key, mask);
            while (filled[at]) {
                if (keys[at] == key) { if (y > tops[at]) tops[at] = y; return; }
                at = (at + 1) & mask;
            }
            filled[at] = true; keys[at] = key; tops[at] = y;
            if (++size * 2 > keys.length) grow();
        }

        private static int slot(long key, int mask) { return (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask; }

        private void grow() {
            long[] oldKeys = keys; int[] oldTops = tops; boolean[] oldFilled = filled;
            keys = new long[oldKeys.length * 2]; tops = new int[oldKeys.length * 2]; filled = new boolean[oldKeys.length * 2];
            int mask = keys.length - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (!oldFilled[i]) continue;
                int at = slot(oldKeys[i], mask);
                while (filled[at]) at = (at + 1) & mask;
                filled[at] = true; keys[at] = oldKeys[i]; tops[at] = oldTops[i];
            }
        }
    }

    /**
     * @param world    reads blocks, or null to describe only the shape
     * @param headroom how many blocks above each column to check for obstructions (0 for none)
     * @param note     said as it is when the blocks could not all be read, or null
     */
    public static JsonObject describe(Set<Point> points, World world, int headroom, String note) {
        var out = new JsonObject();
        out.addProperty("ok", true);
        if (points.isEmpty()) {
            out.addProperty("selected", 0);
            out.addProperty("message", "Nothing is selected. Ask the player to select the place, or to give you coordinates, or read the ground yourself with get_surface and get_blocks.");
            return out;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        var columns = new Columns();
        for (Point p : points) {
            int x = p.x(), y = p.y(), z = p.z();
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
            columns.raise(((long) x << 32) | (z & 0xffffffffL), y);
        }
        int dx = maxX - minX + 1, dy = maxY - minY + 1, dz = maxZ - minZ + 1;
        boolean box = points.size() == (long) dx * dy * dz;

        // Layers: a count per y in an array (a world is a few thousand blocks tall), and the blocks, in the same pass.
        int[] perLayer = new int[dy];
        var counts = world == null ? null : new HashMap<String, int[]>();
        int unread = 0;
        for (Point p : points) {
            perLayer[p.y() - minY]++;
            if (counts == null) continue;
            String id = world.at(p.x(), p.y(), p.z());
            if (id == null) unread++; else counts.computeIfAbsent(id, k -> new int[1])[0]++;
        }
        int layerCount = 0;
        for (int n : perLayer) if (n > 0) layerCount++;
        boolean flat = layerCount == 1;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int i = 0; i < columns.keys.length; i++) {
            if (!columns.filled[i]) continue;
            lowest = Math.min(lowest, columns.tops[i]);
            highest = Math.max(highest, columns.tops[i]);
        }

        out.addProperty("selected", points.size());
        out.add("min", array(minX, minY, minZ));
        out.add("max", array(maxX, maxY, maxZ));
        out.add("dimensions", array(dx, dy, dz));
        out.addProperty("shape", box ? "box" : "irregular");
        out.addProperty("columns", columns.size());
        out.addProperty("flat", flat);
        out.addProperty("layerCount", layerCount);
        if (layerCount <= MAX_LAYERS) {
            var list = new JsonArray();
            for (int i = 0; i < perLayer.length; i++) {
                if (perLayer[i] == 0) continue;
                var item = new JsonObject();
                item.addProperty("y", minY + i);
                item.addProperty("count", perLayer[i]);
                list.add(item);
            }
            out.add("layers", list);
        }
        var top = new JsonObject();
        top.addProperty("lowest", lowest);
        top.addProperty("highest", highest);
        top.addProperty("level", lowest == highest);
        out.add("topSurface", top);

        String made = null, above = null;
        if (counts != null) {
            var sorted = ranked(counts);
            var blocks = new JsonArray();
            for (var e : sorted.subList(0, Math.min(MAX_BLOCKS, sorted.size()))) blocks.add(entry("block", e.getKey(), "count", e.getValue()));
            out.add("blocks", blocks);
            if (unread > 0) out.addProperty("unread", unread);
            if (!sorted.isEmpty()) {
                var first = sorted.get(0);
                made = sorted.size() == 1 ? "all " + first.getKey()
                    : "mostly " + first.getKey() + " (" + Math.round(100.0 * first.getValue() / (points.size() - unread)) + "%)";
            }

            if (headroom > 0) {
                var obstructions = new HashMap<String, int[]>();
                int clear = 0, minClear = headroom;
                boolean unknown = false;
                for (int i = 0; i < columns.keys.length; i++) {
                    if (!columns.filled[i]) continue;
                    int x = (int) (columns.keys[i] >> 32), z = (int) columns.keys[i], from = columns.tops[i] + 1;
                    int h = 0;
                    boolean blocked = false;
                    while (h < headroom) {
                        String id = world.at(x, from + h, z);
                        if (id == null) { unknown = true; break; }
                        if (AIR.contains(id)) { h++; continue; }
                        obstructions.computeIfAbsent(WORLD_TOP.equals(id) ? "(the world's height limit)" : id, k -> new int[1])[0]++;
                        blocked = true;
                        break;
                    }
                    if (!blocked && h == headroom) clear++;
                    if (h < minClear) minClear = h;
                }
                var report = new JsonObject();
                report.addProperty("checked", headroom);
                report.addProperty("columns", columns.size());
                report.addProperty("clearColumns", clear);
                report.addProperty("minClear", minClear);
                var rank = ranked(obstructions);
                var list = new JsonArray();
                for (var e : rank.subList(0, Math.min(MAX_OBSTRUCTIONS, rank.size()))) list.add(entry("block", e.getKey(), "columns", e.getValue()));
                report.add("obstructions", list);
                if (unknown) report.addProperty("partlyUnread", true);
                out.add("above", report);
                above = clear == columns.size() ? "nothing in the way for the " + headroom + " blocks above it"
                    : (columns.size() - clear) + " of " + columns.size() + " columns have something within " + headroom + " blocks above"
                        + (rank.isEmpty() ? "" : " (mostly " + rank.get(0).getKey() + ")");
            }
        }
        if (note != null) out.addProperty("note", note);

        var summary = new StringBuilder();
        summary.append(String.format(Locale.ROOT, "%,d", points.size())).append(" blocks, ").append(dx).append(" x ").append(dy).append(" x ").append(dz)
            .append(" (x by y by z), ").append(box ? "a solid box" : "an irregular shape").append(", ")
            .append(flat ? "flat at y=" + minY : "spanning y=" + minY + " to y=" + maxY);
        if (!flat && lowest != highest) summary.append(", its top between y=").append(lowest).append(" and y=").append(highest);
        if (made != null) summary.append("; ").append(made);
        if (above != null) summary.append("; ").append(above);
        out.addProperty("summary", summary.toString());
        return out;
    }

    private static List<Map.Entry<String, Integer>> ranked(Map<String, int[]> counts) {
        var list = new ArrayList<Map.Entry<String, Integer>>(counts.size());
        for (var e : counts.entrySet()) list.add(Map.entry(e.getKey(), e.getValue()[0]));
        list.sort((a, b) -> !a.getValue().equals(b.getValue()) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        return list;
    }

    private static JsonObject entry(String nameKey, String name, String countKey, int count) {
        var item = new JsonObject();
        item.addProperty(nameKey, name);
        item.addProperty(countKey, count);
        return item;
    }

    private static JsonArray array(int... values) {
        var out = new JsonArray();
        for (int v : values) out.add(v);
        return out;
    }
}
