package dev.craftium.core;

import java.util.*;

/**
 * Thin outlines of a solid set of blocks. The outline of a curved shape made by cutting off an inner copy of it is
 * uneven: two blocks thick where the wall runs on a slant and one where it runs straight. This keeps, in each place,
 * exactly the blocks within {@code thickness} of the outside measured along the direction the wall faces most, which is
 * how a hand-drawn pixel art circle looks: one block per column, no doubled steps, no gaps.
 */
public final class OutlineMath {
    private OutlineMath() {}

    /**
     * The outline of {@code solid}, {@code thickness} blocks thick. For each block the outward direction is taken from where
     * its neighbours are missing, and the block stays when stepping that way, along the axis the direction mostly follows,
     * leaves the shape within {@code thickness} steps. Where two axes follow it equally (the edges of a box, the 45 degree
     * parts of a curve) either may leave it, so edges and corners stay whole and the result mirrors as the shape does.
     * The outline never lets a face-to-face path through from outside to the blocks it leaves out.
     */
    public static List<Point> thin(Collection<Point> solid, int thickness) {
        if (solid.isEmpty()) return List.of();
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (Point p : solid) { x0 = Math.min(x0, p.x()); y0 = Math.min(y0, p.y()); z0 = Math.min(z0, p.z()); x1 = Math.max(x1, p.x()); y1 = Math.max(y1, p.y()); z1 = Math.max(z1, p.z()); }
        int sx = x1 - x0 + 3, sy = y1 - y0 + 3, sz = z1 - z0 + 3; // a block of margin all round, so a neighbour is always inside the grid
        var bits = new BitSet(sx * sy * sz);
        for (Point p : solid) bits.set(((p.x() - x0 + 1) * sy + (p.y() - y0 + 1)) * sz + (p.z() - z0 + 1));
        var result = new ArrayList<Point>();
        // A shape one block thick along an axis (a plane, a disk) is a sheet by nature, and its outline is the edge of the sheet,
        // so that axis is left out of everything below, as if the sheet went on for ever along it.
        boolean flatX = x1 == x0, flatY = y1 == y0, flatZ = z1 == z0;
        for (Point p : solid) {
            int cx = p.x() - x0 + 1, cy = p.y() - y0 + 1, cz = p.z() - z0 + 1;
            double nx = 0, ny = 0, nz = 0;
            int missing = 0;
            for (int dx = flatX ? 0 : -1; dx <= (flatX ? 0 : 1); dx++) for (int dy = flatY ? 0 : -1; dy <= (flatY ? 0 : 1); dy++) for (int dz = flatZ ? 0 : -1; dz <= (flatZ ? 0 : 1); dz++) {
                if ((dx | dy | dz) == 0 || bits.get(((cx + dx) * sy + cy + dy) * sz + cz + dz)) continue;
                double weight = 1d / (dx * dx + dy * dy + dz * dz); // faces count most, then edges, then corners
                nx += dx * weight; ny += dy * weight; nz += dz * weight; missing++;
            }
            if (missing == 0) continue; // buried: every neighbour is part of the shape
            // One block thick along an axis (a rod, a thin fin, the tip of a point): both its faces on that axis are open, so it is all outline.
            if (!flatX && open(bits, cx + 1, cy, cz, sy, sz) && open(bits, cx - 1, cy, cz, sy, sz) || !flatY && open(bits, cx, cy + 1, cz, sy, sz) && open(bits, cx, cy - 1, cz, sy, sz)
                || !flatZ && open(bits, cx, cy, cz + 1, sy, sz) && open(bits, cx, cy, cz - 1, sy, sz)) { result.add(p); continue; }
            double strongest = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz)));
            if (strongest < 1e-9) continue; // only corners are open and they cancel: nothing is exposed
            boolean keep = false;
            double[] normal = {nx, ny, nz};
            for (int axis = 0; axis < 3 && !keep; axis++) {
                if (Math.abs(normal[axis]) < strongest - 1e-9) continue;
                int step = normal[axis] > 0 ? 1 : -1;
                for (int j = 1; j <= thickness && !keep; j++) {
                    int ex = cx + (axis == 0 ? step * j : 0), ey = cy + (axis == 1 ? step * j : 0), ez = cz + (axis == 2 ? step * j : 0);
                    if (ex < 0 || ey < 0 || ez < 0 || ex >= sx || ey >= sy || ez >= sz || !bits.get((ex * sy + ey) * sz + ez)) keep = true;
                }
            }
            if (keep) result.add(p);
        }
        return result;
    }

    private static boolean open(BitSet bits, int x, int y, int z, int sy, int sz) { return !bits.get((x * sy + y) * sz + z); }
}
