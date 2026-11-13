package dev.craftium.server;

import dev.craftium.core.Point;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A player's clipboard: a map from where a block is, relative to the copy's corner, to the block. It behaves as the insertion-ordered map it replaces, so everything that
 * reads or fills the clipboard is unchanged, but it can be {@linkplain #pack() packed}: the positions as three int arrays and the blocks as indexes into a palette of
 * the distinct ones, about 14 bytes a block where a map of objects costs about 128. A clipboard of two million blocks is then about 28 MB instead of 270.
 *
 * <p>Reading a packed clipboard builds each entry as it is asked for. Changing it, other than clearing it, unpacks it first and it stays loose until it is packed again,
 * which the server does after every request, so a clipboard is only ever loose for as long as it is being worked on.
 */
final class ClipboardMap extends AbstractMap<Point, BlockSnapshot> {
    /** A smaller clipboard is not worth the palette. */
    static final int MIN_PACK = 256;
    private static final int MAX_PALETTE = 0xFFFF;

    private LinkedHashMap<Point, BlockSnapshot> loose = new LinkedHashMap<>();
    private int[] x, y, z;
    private char[] block;
    private BlockSnapshot[] palette;
    /** Where each position is in the arrays, for {@link #get}: built when first asked for, and dropped when the clipboard changes. */
    private HashMap<Point, Integer> index;

    boolean packed() { return x != null; }

    /** Packs the clipboard when it is big enough and its blocks are few enough kinds. Does nothing when it is packed already. */
    void pack() {
        if (packed() || loose.size() < MIN_PACK) return;
        int n = loose.size();
        var xs = new int[n]; var ys = new int[n]; var zs = new int[n];
        var blocks = new char[n];
        var kinds = new HashMap<BlockSnapshot, Integer>();
        var list = new java.util.ArrayList<BlockSnapshot>();
        int i = 0;
        for (var e : loose.entrySet()) {
            var at = e.getKey();
            xs[i] = at.x(); ys[i] = at.y(); zs[i] = at.z();
            var snapshot = e.getValue();
            Integer known = kinds.get(snapshot);
            if (known == null) {
                if (list.size() >= MAX_PALETTE) return; // too many kinds of block: it stays loose
                known = list.size();
                list.add(snapshot);
                kinds.put(snapshot, known);
            }
            blocks[i++] = (char) (int) known;
        }
        x = xs; y = ys; z = zs; block = blocks; palette = list.toArray(new BlockSnapshot[0]);
        loose = new LinkedHashMap<>();
        index = null;
    }

    /** What the clipboard holds in memory, for the budget. */
    long bytes() {
        if (!packed()) return (long) loose.size() * 128;
        long total = 64 + (long) x.length * 14;
        for (var snapshot : palette) {
            total += 96;
            if (snapshot.data() != null) total += snapshot.data().toString().length() * 2L;
        }
        return total;
    }

    private void unpack() {
        if (!packed()) return;
        var all = new LinkedHashMap<Point, BlockSnapshot>(Math.max(16, x.length * 4 / 3 + 1));
        for (int i = 0; i < x.length; i++) all.put(new Point(x[i], y[i], z[i]), palette[block[i]]);
        loose = all;
        x = y = z = null; block = null; palette = null; index = null;
    }

    @Override public int size() { return packed() ? x.length : loose.size(); }

    @Override public boolean isEmpty() { return size() == 0; }

    @Override public BlockSnapshot get(Object key) {
        if (!packed()) return loose.get(key);
        if (!(key instanceof Point)) return null;
        if (index == null) {
            var built = new HashMap<Point, Integer>(x.length * 4 / 3 + 1);
            for (int i = 0; i < x.length; i++) built.put(new Point(x[i], y[i], z[i]), i);
            index = built;
        }
        Integer at = index.get(key);
        return at == null ? null : palette[block[at]];
    }

    @Override public boolean containsKey(Object key) {
        if (!packed()) return loose.containsKey(key);
        return get(key) != null;
    }

    @Override public BlockSnapshot put(Point key, BlockSnapshot value) { unpack(); return loose.put(key, value); }

    @Override public BlockSnapshot remove(Object key) { unpack(); return loose.remove(key); }

    @Override public void clear() {
        x = y = z = null; block = null; palette = null; index = null;
        loose = new LinkedHashMap<>();
    }

    @Override public Set<Entry<Point, BlockSnapshot>> entrySet() {
        if (!packed()) return loose.entrySet();
        return new AbstractSet<>() {
            @Override public int size() { return x.length; }
            @Override public Iterator<Entry<Point, BlockSnapshot>> iterator() {
                final int[] xs = x, ys = y, zs = z; final char[] blocks = block; final BlockSnapshot[] colours = palette;
                return new Iterator<>() {
                    int i;
                    @Override public boolean hasNext() { return i < xs.length; }
                    @Override public Entry<Point, BlockSnapshot> next() {
                        if (i >= xs.length) throw new NoSuchElementException();
                        var entry = new SimpleImmutableEntry<>(new Point(xs[i], ys[i], zs[i]), colours[blocks[i]]);
                        i++;
                        return entry;
                    }
                };
            }
        };
    }
}
