package dev.craftium.core;

import java.util.*;

/** Commas inside a block's property list belong to that state. */
public final class MaterialList {
    /** The most blocks a palette holds. */
    public static final int MAX = 256;

    private MaterialList() {}

    public static List<String> split(String source) {
        var result = new ArrayList<String>();
        int start = 0, depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                if (--depth < 0) throw new IllegalArgumentException("Unbalanced block properties");
            } else if (c == ',' && depth == 0) {
                add(result, source.substring(start, i));
                start = i + 1;
            }
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced block properties");
        add(result, source.substring(start));
        if (result.size() > MAX) throw new IllegalArgumentException("Palette limit: " + MAX + " entries");
        return result;
    }

    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    /**
     * An even threshold for {@link #index}'s {@code random}, from 0 to 1, in a 4 by 4 Bayer pattern that shifts with the height, so a mixed band
     * reads as a fine, regular weave instead of noise.
     */
    public static double ordered(int x, int y, int z) {
        return (BAYER[Math.floorMod(x + y, 4) * 4 + Math.floorMod(z + 2 * y, 4)] + .5) / 16;
    }

    private static void add(List<String> result, String value) {
        if (!value.isBlank()) result.add(value.trim());
    }

    public static int index(double value, List<Double> weights, int size, double random, double dither) {
        if (size <= 1) return 0;
        double[] centres = new double[size];
        double sum = 0;
        for (int i = 0; i < size; i++) {
            double w = i < weights.size() ? Math.clamp(weights.get(i), .01, 100) : 1;
            centres[i] = sum + w / 2;
            sum += w;
        }
        double first = centres[0], span = centres[size - 1] - first, t = Math.clamp(value, 0, 1) * span + first;
        for (int i = 0; i < size - 1; i++)
            if (t <= centres[i + 1]) {
                double fraction = (t - centres[i]) / (centres[i + 1] - centres[i]);
                return fraction > .5 + (random - .5) * Math.clamp(dither, 0, 1) ? i + 1 : i;
            }
        return size - 1;
    }
}
