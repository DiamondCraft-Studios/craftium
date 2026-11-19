package dev.craftium.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A list of block positions as one short text, for a request that names blocks the client chose (the screen-space brushes). Each position is
 * kept as its step from the one before, three zigzag varints, so blocks that follow one another cost a few characters instead of a whole
 * {@code {"x":..,"y":..,"z":..}}.
 */
public final class BlockList {
    private BlockList() {}

    public static String encode(List<Point> points) {
        var out = new ByteArrayOutputStream(points.size() * 3);
        int x = 0, y = 0, z = 0;
        for (Point p : points) {
            varint(out, p.x() - x);
            varint(out, p.y() - y);
            varint(out, p.z() - z);
            x = p.x();
            y = p.y();
            z = p.z();
        }
        return Base64.getEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** The positions in {@code text}, at most {@code max} of them; an empty text is none. Throws IllegalArgumentException for text that is not a list or is longer than allowed. */
    public static List<Point> decode(String text, int max) {
        if (text == null || text.isEmpty()) return List.of();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid block list");
        }
        var points = new ArrayList<Point>();
        int[] at = {0}, last = {0, 0, 0};
        while (at[0] < bytes.length) {
            if (points.size() >= max) throw new IllegalArgumentException("Too many blocks: the limit is " + max);
            for (int axis = 0; axis < 3; axis++) last[axis] = Math.addExact(last[axis], read(bytes, at));
            points.add(new Point(last[0], last[1], last[2]));
        }
        return points;
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        int v = (value << 1) ^ (value >> 31);
        while ((v & ~0x7f) != 0) {
            out.write(v & 0x7f | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int read(byte[] bytes, int[] at) {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= bytes.length) throw new IllegalArgumentException("Invalid block list");
            int b = bytes[at[0]++];
            v |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return (v >>> 1) ^ -(v & 1);
        }
        throw new IllegalArgumentException("Invalid block list");
    }
}
