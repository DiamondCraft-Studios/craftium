package dev.craftium.core;

import java.util.*;

/** Collects block names as a palette plus run-length runs, [paletteIndex, count], in the order they are added. */
public final class BlockRuns {
    /** What stands in for a position that is not part of the world: above or below its height limits, or past the world border. */
    public static final String OUTSIDE = "craftium:outside_world";

    private final LinkedHashMap<String, Integer> index = new LinkedHashMap<>();
    private final List<int[]> runs = new ArrayList<>();
    private int last = -1, count;

    public void add(String name) {
        int id = index.computeIfAbsent(name, k -> index.size());
        if (id == last) { count++; return; }
        flush();
        last = id;
        count = 1;
    }

    private void flush() {
        if (count > 0) runs.add(new int[]{last, count});
        count = 0;
    }

    public List<String> palette() { return List.copyOf(index.keySet()); }

    /** The runs so far, including the one still open. */
    public List<int[]> runs() {
        flush();
        return runs;
    }

    /** The palette index a name was given, adding it if it is new. */
    public int indexOf(String name) { return index.computeIfAbsent(name, k -> index.size()); }
}
