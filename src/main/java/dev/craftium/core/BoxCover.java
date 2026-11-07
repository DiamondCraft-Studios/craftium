package dev.craftium.core;

import java.util.*;

/**
 * Covers the cells of a grid with as few boxes as a single greedy pass finds: each box holds cells of one kind, and no cell is in two
 * boxes. A wall, a floor or a roof is a handful of boxes where run-length runs along a row would be one per row, and boxes are what a
 * build is written in. Pure, so it needs no game.
 */
public final class BoxCover {
    /** A box of cells of one kind, in grid coordinates, both corners inclusive. */
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1, int id) {
        public long volume() { return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1); }
    }

    private BoxCover() {}

    /**
     * @param ids cell kinds, x fastest, then z, then y; a negative id is a cell to leave out
     * @return the boxes in reading order, or null when more than {@code maxBoxes} would be needed (noise, where boxes stop helping)
     */
    public static List<Box> cover(int[] ids, int sx, int sy, int sz, int maxBoxes) {
        if ((long) sx * sy * sz != ids.length) throw new IllegalArgumentException("The cells do not fill a " + sx + " by " + sy + " by " + sz + " grid");
        var out = new ArrayList<Box>();
        var used = new BitSet(ids.length);
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++) {
                int row = sx * (z + sz * y);
                for (int x = 0; x < sx; x++) {
                    int i = row + x, id = ids[i];
                    if (id < 0 || used.get(i)) continue;
                    int w = 1; // along x as far as the same kind runs
                    while (x + w < sx && ids[i + w] == id && !used.get(i + w)) w++;
                    int d = 1; // then as many whole rows of that width along z
                    rows:
                    while (z + d < sz) {
                        int r = x + sx * ((z + d) + sz * y);
                        for (int k = 0; k < w; k++) if (ids[r + k] != id || used.get(r + k)) break rows;
                        d++;
                    }
                    int h = 1; // then as many whole slabs of that footprint upward
                    slabs:
                    while (y + h < sy) {
                        for (int dz = 0; dz < d; dz++) {
                            int r = x + sx * ((z + dz) + sz * (y + h));
                            for (int k = 0; k < w; k++) if (ids[r + k] != id || used.get(r + k)) break slabs;
                        }
                        h++;
                    }
                    for (int dy = 0; dy < h; dy++)
                        for (int dz = 0; dz < d; dz++) {
                            int r = x + sx * ((z + dz) + sz * (y + dy));
                            used.set(r, r + w);
                        }
                    if (out.size() >= maxBoxes) return null;
                    out.add(new Box(x, y, z, x + w - 1, y + h - 1, z + d - 1, id));
                }
            }
        return out;
    }
}
