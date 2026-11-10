package dev.craftium.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * The blocks of a blueprint in a form small enough to travel with its listing, so the browser can draw the same picture the AI build
 * sessions show. A cell is an index into the blueprint's palette of block ids, or {@link #EMPTY}; cells run x first, then z, then y.
 * They are stored as gzipped runs of equal cells. A blueprint bigger than the picture can show, or whose cells will not fit the limits
 * here, is shrunk first (each cell standing for a cube of {@code scale} blocks), so every blueprint has a picture.
 */
public final class BlueprintVoxels {
    public static final int EMPTY = -1, MAX_VOLUME = 262_144, MAX_PALETTE = 255, MAX_ENCODED = 8000, GRID = 48, MAX_SCALE = 64;
    private BlueprintVoxels() {}

    /** The picture data of a blueprint: its block ids, the size of the cube each cell stands for, and the cells. */
    public record Packed(List<String> palette, int scale, String cells) {}

    /** The cells of a box this big once each cell stands for {@code scale} blocks along every side. */
    public static int cells(int blocks, int scale) { return (blocks + scale - 1) / scale; }

    /** Collects the blocks of a blueprint, then packs them. */
    public static final class Builder {
        private int[] xs = new int[1024], ys = new int[1024], zs = new int[1024], ids = new int[1024];
        private int count;
        private final List<String> names = new ArrayList<>();
        private final Map<String, Integer> indices = new HashMap<>();

        /** A block at a place (not air: the caller leaves those out); places below 0 are ignored. */
        public void add(int x, int y, int z, String block) {
            if (x < 0 || y < 0 || z < 0) return;
            Integer id = indices.get(block);
            if (id == null) { id = names.size(); indices.put(block, id); names.add(block); }
            if (count == xs.length) { int n = count * 2; xs = Arrays.copyOf(xs, n); ys = Arrays.copyOf(ys, n); zs = Arrays.copyOf(zs, n); ids = Arrays.copyOf(ids, n); }
            xs[count] = x; ys[count] = y; zs[count] = z; ids[count++] = id;
        }

        public int count() { return count; }

        /** The blocks as picture data for a box of this size, or null when there are none. */
        public Packed pack(int sx, int sy, int sz) {
            if (count == 0 || sx < 1 || sy < 1 || sz < 1) return null;
            // A palette past the limit keeps its most used blocks, and the rest take the most used one's place.
            int[] remap = new int[names.size()];
            var palette = new ArrayList<>(names);
            if (names.size() > MAX_PALETTE) {
                long[] used = new long[names.size()];
                for (int i = 0; i < count; i++) used[ids[i]]++;
                var order = new Integer[names.size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> Long.compare(used[b], used[a]));
                palette = new ArrayList<>();
                for (int rank = 0; rank < order.length; rank++) {
                    remap[order[rank]] = rank < MAX_PALETTE ? rank : 0;
                    if (rank < MAX_PALETTE) palette.add(names.get(order[rank]));
                }
            } else for (int i = 0; i < remap.length; i++) remap[i] = i;
            int scale = Math.max(1, cells(Math.max(sx, Math.max(sy, sz)), GRID));
            for (; scale <= MAX_SCALE; scale++) {
                int gx = cells(sx, scale), gy = cells(sy, scale), gz = cells(sz, scale);
                int[] grid = new int[gx * gy * gz];
                Arrays.fill(grid, EMPTY);
                long[] keys = new long[count];
                for (int i = 0; i < count; i++) {
                    int cell = index(gx, gz, Math.min(xs[i] / scale, gx - 1), Math.min(ys[i] / scale, gy - 1), Math.min(zs[i] / scale, gz - 1));
                    keys[i] = ((long) cell << 8) | remap[ids[i]];
                }
                Arrays.sort(keys);
                int i = 0;
                while (i < count) {
                    int cell = (int) (keys[i] >> 8), best = 0, bestRun = 0;
                    while (i < count && (int) (keys[i] >> 8) == cell) {
                        long key = keys[i];
                        int run = 0;
                        while (i < count && keys[i] == key) { run++; i++; }
                        if (run > bestRun) { bestRun = run; best = (int) (key & 255); }
                    }
                    grid[cell] = best; // a shrunk cell is drawn when any block of its cube is built, so a thin wall is still there
                }
                String text = encode(gx, gy, gz, grid);
                if (text != null) return new Packed(List.copyOf(palette), scale, text);
            }
            return null;
        }
    }

    public static int index(int sx, int sz, int x, int y, int z) { return (y * sz + z) * sx + x; }

    /** The cells as text, or null when they do not fit the limits. */
    public static String encode(int sx, int sy, int sz, int[] cells) {
        if (sx < 1 || sy < 1 || sz < 1 || (long) sx * sy * sz > MAX_VOLUME || cells.length != sx * sy * sz) return null;
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                int i = 0;
                while (i < cells.length) {
                    int value = cells[i], run = 1;
                    while (i + run < cells.length && cells[i + run] == value) run++;
                    varint(out, value + 1);
                    varint(out, run);
                    i += run;
                }
            }
            String text = Base64.getEncoder().encodeToString(bytes.toByteArray());
            return text.length() > MAX_ENCODED ? null : text;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** The cells of a box of this size, or null when the text is not a valid grid for it. */
    public static int[] decode(String text, int sx, int sy, int sz) {
        if (text == null || sx < 1 || sy < 1 || sz < 1 || (long) sx * sy * sz > MAX_VOLUME || text.length() > MAX_ENCODED) return null;
        int volume = sx * sy * sz;
        try (var in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text))))) {
            int[] cells = new int[volume];
            int at = 0;
            while (at < volume) {
                int value = varint(in) - 1, run = varint(in);
                if (run < 1 || run > volume - at || value < EMPTY || value >= MAX_PALETTE) return null;
                Arrays.fill(cells, at, at + run, value);
                at += run;
            }
            return cells;
        } catch (IOException | IllegalArgumentException e) { return null; }
    }

    private static void varint(DataOutput out, int value) throws IOException {
        while ((value & ~0x7f) != 0) { out.writeByte((value & 0x7f) | 0x80); value >>>= 7; }
        out.writeByte(value);
    }

    private static int varint(DataInput in) throws IOException {
        int value = 0, shift = 0, b;
        do { b = in.readUnsignedByte(); value |= (b & 0x7f) << shift; shift += 7; } while ((b & 0x80) != 0 && shift < 35);
        return value;
    }
}
