package dev.craftium.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Describes a box of blocks for an AI in the fewest words. It offers two forms and picks the shorter:
 * <ul>
 *   <li><b>boxes</b>: for each block, the boxes it fills, as {@code [x1,y1,z1,x2,y2,z2]} in world coordinates. A wall or a roof is a few
 *       boxes, whatever its position in the box that was asked about, and the boxes are what a build is written in.</li>
 *   <li><b>runs</b>: a palette and run-length runs in reading order, as get_blocks answers. Better for noisy ground, where boxes stop
 *       helping.</li>
 * </ul>
 * Pure, so it needs no game.
 */
public final class BlockView {
    /** What a cell reads as in runs when it is not part of the view (a position the preview does not touch). */
    public static final String UNCHANGED = "craftium:unchanged";
    /** More boxes than this and runs are shorter, and the reply would be too long to be of use. */
    public static final int MAX_BOXES = 3_000;
    private static final int SUMMARY_BLOCKS = 6;
    /** Numbers written per entry: a box is [x1,y1,z1,x2,y2,z2], a run is [paletteIndex,count]. */
    private static final int BOX_COST = 6, RUN_COST = 2;

    private BlockView() {}

    /**
     * @param ids      the palette index of each cell, x fastest, then z, then y; negative for a cell that is not part of the view
     * @param omit     palette indexes left out of the boxes (air in a view of the world as it would be)
     * @param format   "auto" (the shorter), "boxes" or "runs"
     * @param unlisted what a position the boxes do not list means, said as it is in the reply
     */
    public static JsonObject describe(int x0, int y0, int z0, int sx, int sy, int sz, int[] ids, List<String> palette, Set<Integer> omit, String format, String unlisted) {
        if ((long) sx * sy * sz != ids.length) throw new IllegalArgumentException("The cells do not fill the box");
        if (format == null || format.isBlank()) format = "auto";
        if (!format.equals("auto") && !format.equals("boxes") && !format.equals("runs")) throw new IllegalArgumentException("\"format\" must be auto, boxes or runs");

        List<BoxCover.Box> boxes = null;
        if (!format.equals("runs")) {
            int[] masked = ids;
            if (!omit.isEmpty()) {
                boolean[] hide = new boolean[palette.size() + 1];
                for (int id : omit) if (id >= 0 && id < hide.length) hide[id] = true;
                masked = ids.clone();
                for (int i = 0; i < masked.length; i++) if (masked[i] >= 0 && masked[i] < hide.length && hide[masked[i]]) masked[i] = -1;
            }
            boxes = BoxCover.cover(masked, sx, sy, sz, MAX_BOXES);
        }
        if (format.equals("boxes") && boxes == null)
            throw new IllegalArgumentException("That needs more than " + MAX_BOXES + " boxes, which is no shorter than the blocks themselves; use format \"runs\", or read a smaller box");
        int runCount = format.equals("boxes") ? Integer.MAX_VALUE : countRuns(ids);
        // A box is six numbers and a run is two, so boxes are only the shorter reply when there are fewer than a third as many of them.
        boolean useBoxes = format.equals("boxes") || (format.equals("auto") && boxes != null && (long) boxes.size() * BOX_COST <= (long) runCount * RUN_COST);

        var out = new JsonObject();
        out.addProperty("ok", true);
        out.add("min", array(x0, y0, z0));
        out.add("size", array(sx, sy, sz));
        out.addProperty("format", useBoxes ? "boxes" : "runs");
        return useBoxes ? boxesReply(out, x0, y0, z0, boxes, palette, unlisted) : runsReply(out, ids, palette, omit);
    }

    private static JsonObject boxesReply(JsonObject out, int x0, int y0, int z0, List<BoxCover.Box> boxes, List<String> palette, String unlisted) {
        var byBlock = new LinkedHashMap<Integer, List<BoxCover.Box>>();
        var counts = new HashMap<Integer, Long>();
        long total = 0;
        for (var box : boxes) {
            byBlock.computeIfAbsent(box.id(), k -> new ArrayList<>()).add(box);
            counts.merge(box.id(), box.volume(), Long::sum);
            total += box.volume();
        }
        var order = new ArrayList<>(byBlock.keySet());
        order.sort((a, b) -> counts.get(b) != counts.get(a).longValue() ? Long.compare(counts.get(b), counts.get(a)) : Integer.compare(a, b));
        var blocks = new JsonArray();
        for (int id : order) {
            var item = new JsonObject();
            item.addProperty("block", palette.get(id));
            item.addProperty("count", counts.get(id));
            var list = new JsonArray();
            for (var box : byBlock.get(id)) list.add(array(box.x0() + x0, box.y0() + y0, box.z0() + z0, box.x1() + x0, box.y1() + y0, box.z1() + z0));
            item.add("boxes", list);
            blocks.add(item);
        }
        out.add("blocks", blocks);
        out.addProperty("entries", boxes.size());
        out.addProperty("boxFormat", "each box is [x1, y1, z1, x2, y2, z2], both corners inclusive, in world coordinates. " + unlisted);
        var parts = new ArrayList<String>();
        for (int id : order.subList(0, Math.min(SUMMARY_BLOCKS, order.size()))) parts.add(counts.get(id) + " " + shortName(palette.get(id)));
        out.addProperty("summary", boxes.isEmpty() ? "No blocks listed. " + unlisted
            : String.format(Locale.ROOT, "%,d blocks in %,d boxes: ", total, boxes.size()) + String.join(", ", parts) + (order.size() > SUMMARY_BLOCKS ? ", and " + (order.size() - SUMMARY_BLOCKS) + " more kinds" : ""));
        return out;
    }

    private static JsonObject runsReply(JsonObject out, int[] ids, List<String> palette, Set<Integer> omit) {
        var names = new ArrayList<>(palette);
        int unchanged = -1;
        var runs = new JsonArray();
        long listed = 0;
        int i = 0, entries = 0;
        while (i < ids.length) {
            int id = ids[i], j = i + 1;
            while (j < ids.length && ids[j] == id) j++;
            if (id < 0) { if (unchanged < 0) { unchanged = names.size(); names.add(UNCHANGED); } id = unchanged; }
            else if (!omit.contains(id)) listed += j - i;
            runs.add(array(id, j - i));
            entries++;
            i = j;
        }
        var list = new JsonArray();
        names.forEach(list::add);
        out.add("palette", list);
        out.addProperty("order", "x fastest, then z, then y from the minimum corner; each run is [paletteIndex, count]");
        out.add("runs", runs);
        out.addProperty("entries", entries);
        out.addProperty("summary", String.format(Locale.ROOT, "%,d blocks (not counting air) in %,d runs over %,d positions", listed, entries, ids.length));
        return out;
    }

    private static int countRuns(int[] ids) {
        int runs = 0;
        for (int i = 0; i < ids.length; i++) if (i == 0 || ids[i] != ids[i - 1]) runs++;
        return runs;
    }

    private static String shortName(String block) { return block.startsWith("minecraft:") ? block.substring(10) : block; }

    private static JsonArray array(int... values) {
        var out = new JsonArray();
        for (int v : values) out.add(v);
        return out;
    }
}
