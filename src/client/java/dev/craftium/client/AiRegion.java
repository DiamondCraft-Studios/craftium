package dev.craftium.client;

import dev.craftium.core.Point;
import java.util.*;

/**
 * A box of the world as the server reports it, read in the tiles its limits allow. For the AI tools that need to look at blocks
 * around something (a selection, say) and not draw a picture of them.
 */
final class AiRegion {
    private final int x0, y0, z0, sx, sy, sz;
    private final int[] ids;
    /** The block of each palette index as an id with no properties. */
    private final List<String> bases;

    private AiRegion(int x0, int y0, int z0, int sx, int sy, int sz, int[] ids, List<String> bases) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0; this.sx = sx; this.sy = sy; this.sz = sz; this.ids = ids; this.bases = bases;
    }

    /** Reads the box from (x0, y0, z0) of the given size. The caller keeps it within {@link AiView#MAX_BLOCKS}. */
    static AiRegion read(int x0, int y0, int z0, int sx, int sy, int sz) throws Exception {
        var names = new ArrayList<String>();
        var index = new HashMap<String, Integer>();
        int[] ids = new int[sx * sy * sz];
        int tile = AiView.TILE;
        for (int ty = 0; ty < sy; ty += tile)
            for (int tz = 0; tz < sz; tz += tile)
                for (int tx = 0; tx < sx; tx += tile) {
                    int tw = Math.min(tile, sx - tx), th = Math.min(tile, sy - ty), td = Math.min(tile, sz - tz);
                    var reply = AiBridge.read("ai_blocks", new Point(x0 + tx, y0 + ty, z0 + tz), new Point(x0 + tx + tw - 1, y0 + ty + th - 1, z0 + tz + td - 1));
                    var palette = reply.getAsJsonArray("palette");
                    int[] global = new int[palette.size()];
                    for (int i = 0; i < global.length; i++) global[i] = index.computeIfAbsent(baseId(palette.get(i).getAsString()), k -> { names.add(k); return names.size() - 1; });
                    int at = 0;
                    for (var element : reply.getAsJsonArray("runs")) {
                        var run = element.getAsJsonArray();
                        int id = global[run.get(0).getAsInt()], count = run.get(1).getAsInt();
                        for (int k = 0; k < count; k++, at++) {
                            int lx = at % tw, lz = (at / tw) % td, ly = at / (tw * td);
                            ids[tx + lx + sx * (tz + lz + sz * (ty + ly))] = id;
                        }
                    }
                }
        return new AiRegion(x0, y0, z0, sx, sy, sz, ids, names);
    }

    /** The block at a world position as an id with no properties, or null when it lies outside what was read. */
    String at(int x, int y, int z) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return null;
        return bases.get(ids[lx + sx * (lz + sz * ly)]);
    }

    private static String baseId(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
