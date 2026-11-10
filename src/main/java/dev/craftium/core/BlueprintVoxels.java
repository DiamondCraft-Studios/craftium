package dev.craftium.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * The blocks of a blueprint in a form small enough to travel with its listing, so the browser can draw the same picture the AI build
 * sessions show. A cell is an index into the blueprint's palette of block ids, or {@link #EMPTY}; cells run x first, then z, then y.
 * They are stored as gzipped runs of equal cells, and a blueprint too big or too varied for the limits here is listed without them.
 */
public final class BlueprintVoxels {
    public static final int EMPTY = -1, MAX_VOLUME = 262_144, MAX_PALETTE = 64, MAX_ENCODED = 4000;
    private BlueprintVoxels() {}

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
