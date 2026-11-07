package dev.craftium.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * What a selection is, for an AI that has to decide where to build: how many blocks, its bounds, whether it is a solid box or an
 * irregular shape (a lasso or a magic wand picks any set of blocks), whether it is flat, what it is made of and how much clear space
 * there is above it. Pure: the blocks are read through {@link World}, so it needs no game.
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
        var layers = new TreeMap<Integer, Integer>();
        var columns = new HashMap<Long, int[]>(); // (x, z) -> {x, z, highest selected y}
        for (Point p : points) {
            minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z());
            layers.merge(p.y(), 1, Integer::sum);
            long key = ((long) p.x() << 32) ^ (p.z() & 0xffffffffL);
            var column = columns.computeIfAbsent(key, k -> new int[]{p.x(), p.z(), p.y()});
            column[2] = Math.max(column[2], p.y());
        }
        int dx = maxX - minX + 1, dy = maxY - minY + 1, dz = maxZ - minZ + 1;
        boolean box = points.size() == (long) dx * dy * dz;
        boolean flat = layers.size() == 1;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (var column : columns.values()) { lowest = Math.min(lowest, column[2]); highest = Math.max(highest, column[2]); }

        out.addProperty("selected", points.size());
        out.add("min", array(minX, minY, minZ));
        out.add("max", array(maxX, maxY, maxZ));
        out.add("dimensions", array(dx, dy, dz));
        out.addProperty("shape", box ? "box" : "irregular");
        out.addProperty("columns", columns.size());
        out.addProperty("flat", flat);
        out.addProperty("layerCount", layers.size());
        if (layers.size() <= MAX_LAYERS) {
            var list = new JsonArray();
            for (var layer : layers.entrySet()) { var item = new JsonObject(); item.addProperty("y", layer.getKey()); item.addProperty("count", layer.getValue()); list.add(item); }
            out.add("layers", list);
        }
        var top = new JsonObject();
        top.addProperty("lowest", lowest);
        top.addProperty("highest", highest);
        top.addProperty("level", lowest == highest);
        out.add("topSurface", top);

        String made = null, above = null;
        if (world != null) {
            var counts = new HashMap<String, Integer>();
            int unread = 0;
            for (Point p : points) {
                String id = world.at(p.x(), p.y(), p.z());
                if (id == null) unread++; else counts.merge(id, 1, Integer::sum);
            }
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
                var obstructions = new HashMap<String, Integer>();
                int clear = 0, minClear = headroom;
                boolean unknown = false;
                for (var column : columns.values()) {
                    int h = 0;
                    boolean blocked = false;
                    while (h < headroom) {
                        String id = world.at(column[0], column[2] + 1 + h, column[1]);
                        if (id == null) { unknown = true; break; }
                        if (AIR.contains(id)) { h++; continue; }
                        obstructions.merge(WORLD_TOP.equals(id) ? "(the world's height limit)" : id, 1, Integer::sum);
                        blocked = true;
                        break;
                    }
                    if (!blocked && h == headroom) clear++;
                    minClear = Math.min(minClear, h);
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

    private static List<Map.Entry<String, Integer>> ranked(Map<String, Integer> counts) {
        var list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
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
