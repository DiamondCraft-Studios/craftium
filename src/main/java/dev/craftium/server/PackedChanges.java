package dev.craftium.server;

import dev.craftium.core.Point;

import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.RandomAccess;

/**
 * The block changes of an edit kept in the history, stored as parallel arrays instead of one {@link Session.Change}, one
 * {@link Point} and two references per block: 16 bytes a block (three ints for the position, two palette indexes) where the
 * objects cost about 128. Each distinct {@link BlockSnapshot} is kept once in a palette, so a fill of one block, or a hundred
 * chests with the same contents, are stored once.
 *
 * <p>It is a read-only {@link List}, so everything that reads an edit's changes (undo, redo, saving, merging) is unchanged: an
 * element is built when it is asked for and is as short-lived as any other.
 */
final class PackedChanges extends AbstractList<Session.Change> implements RandomAccess {
    /** A smaller list is not worth the palette. */
    static final int MIN_SIZE = 256;
    /** The most distinct snapshots a palette index can name. */
    private static final int MAX_PALETTE = 0xFFFF;
    /** What the palette's arrays and each entry cost beyond the entry's own block entity data. */
    private static final int ENTRY_BYTES = 96;

    private final int[] x, y, z;
    private final char[] before, after;
    private final BlockSnapshot[] palette;
    private final int bytes;

    private PackedChanges(int[] x, int[] y, int[] z, char[] before, char[] after, BlockSnapshot[] palette) {
        this.x = x; this.y = y; this.z = z; this.before = before; this.after = after; this.palette = palette;
        long total = 64 + (long) x.length * 16;
        for (var snapshot : palette) {
            total += ENTRY_BYTES;
            if (snapshot.data() != null) total += snapshot.data().toString().length() * 2L;
        }
        bytes = (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** The changes packed, or the same list when it is already packed, too small, or does not pack (a missing snapshot, or too many kinds of block). */
    static List<Session.Change> pack(List<Session.Change> changes) {
        int n = changes.size();
        if (n < MIN_SIZE || changes instanceof PackedChanges) return changes;
        var x = new int[n]; var y = new int[n]; var z = new int[n];
        var before = new char[n]; var after = new char[n];
        var index = new HashMap<BlockSnapshot, Integer>();
        var entries = new java.util.ArrayList<BlockSnapshot>();
        BlockSnapshot lastSnapshot = null; int lastIndex = 0; // a run of the same block is the common case
        int i = 0;
        for (var change : changes) {
            var pos = change.pos();
            x[i] = pos.x(); y[i] = pos.y(); z[i] = pos.z();
            var b = change.before(); var a = change.after();
            if (b == null || a == null) return changes;
            int bi, ai;
            if (b == lastSnapshot) bi = lastIndex; else { bi = indexOf(index, entries, b); if (bi < 0) return changes; lastSnapshot = b; lastIndex = bi; }
            if (a == lastSnapshot) ai = lastIndex; else { ai = indexOf(index, entries, a); if (ai < 0) return changes; lastSnapshot = a; lastIndex = ai; }
            before[i] = (char) bi; after[i] = (char) ai;
            i++;
        }
        return new PackedChanges(x, y, z, before, after, entries.toArray(new BlockSnapshot[0]));
    }

    private static int indexOf(HashMap<BlockSnapshot, Integer> index, java.util.ArrayList<BlockSnapshot> entries, BlockSnapshot snapshot) {
        var known = index.get(snapshot);
        if (known != null) return known;
        if (entries.size() >= MAX_PALETTE) return -1;
        int at = entries.size();
        entries.add(snapshot);
        index.put(snapshot, at);
        return at;
    }

    @Override public Session.Change get(int i) {
        return new Session.Change(new Point(x[i], y[i], z[i]), palette[before[i]], palette[after[i]]);
    }

    @Override public int size() { return x.length; }

    /** What this list holds in memory, for the history budget. */
    int bytes() { return bytes; }
}
