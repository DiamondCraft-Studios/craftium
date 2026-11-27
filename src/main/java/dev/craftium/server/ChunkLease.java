package dev.craftium.server;

import dev.craftium.compat.Compat;
import dev.craftium.core.Point;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps chunks loaded for as long as an edit needs them, so a structure can be moved from one far-away chunk to another without the
 * player walking there. A lease puts vanilla's own chunk tickets on the chunks, loads them (and makes the ones nobody has visited), and
 * takes the tickets off when it is closed, after which the chunks unload and save like any other. Nothing stays loaded after the edit.
 * <p>
 * Leases are counted per chunk, so two that overlap (the one an edit is planned under and the one it is applied under) each keep the
 * chunk until both are closed. Everything here runs on the server thread.
 */
final class ChunkLease implements AutoCloseable {
    /** Most chunks one lease may load that were not loaded already; past it the edit is refused instead of stalling the server. */
    static final int MAX_NEW_CHUNKS = 256;

    static final ChunkLease NONE = new ChunkLease(null, List.of());

    private static final Map<ServerLevel, Map<Long, Integer>> HELD = new WeakHashMap<>();
    /** Leases taken while a request is planned, closed when the request is done: by then the edit has taken its own. */
    private static final List<ChunkLease> PLANNING = new ArrayList<>();

    private final ServerLevel level;
    private final List<Long> keys;
    private boolean open = true;

    private ChunkLease(ServerLevel level, List<Long> keys) {
        this.level = level;
        this.keys = keys;
    }

    /** The chunks a set of blocks touches, collected before they are leased. */
    static final class Area {
        private final Set<Long> keys = new LinkedHashSet<>();

        Area block(int x, int z) {
            keys.add(key(x >> 4, z >> 4));
            return this;
        }

        /** Every chunk a rectangle of blocks reaches, corners included in either order. */
        Area box(int x0, int z0, int x1, int z1) {
            for (int cx = Math.min(x0, x1) >> 4; cx <= Math.max(x0, x1) >> 4; cx++)
                for (int cz = Math.min(z0, z1) >> 4; cz <= Math.max(z0, z1) >> 4; cz++) keys.add(key(cx, cz));
            return this;
        }

        /** The chunks the points reach once each is moved by the offset. */
        Area points(Iterable<Point> points, int dx, int dz) {
            long last = Long.MIN_VALUE;
            for (Point p : points) {
                long k = key((p.x() + dx) >> 4, (p.z() + dz) >> 4);
                if (k != last) keys.add(last = k);
            }
            return this;
        }

        boolean isEmpty() {
            return keys.isEmpty();
        }

        /** Loads them all or none: refused when they would need more than {@link #MAX_NEW_CHUNKS} that are not loaded. */
        ChunkLease lease(ServerLevel level) {
            return ChunkLease.take(level, keys);
        }
    }

    static Area area() {
        return new Area();
    }

    /** Holds a lease until the request being handled is over, which {@link #endPlanning} announces. */
    static ChunkLease planning(ChunkLease lease) {
        PLANNING.add(lease);
        return lease;
    }

    static void endPlanning() {
        if (PLANNING.isEmpty()) return;
        for (ChunkLease lease : PLANNING) lease.close();
        PLANNING.clear();
    }

    /** Lets go of every lease, as the server stops or the editor is cleared; the chunks are the world's again. */
    static void releaseAll() {
        for (var entry : HELD.entrySet())
            for (long key : entry.getValue().keySet()) Compat.removeLeaseTicket(entry.getKey(), pos(key));
        HELD.clear();
        PLANNING.clear();
    }

    private static ChunkLease take(ServerLevel level, Collection<Long> wanted) {
        var held = HELD.computeIfAbsent(level, l -> new HashMap<>());
        var border = level.getWorldBorder();
        var keys = new ArrayList<Long>();
        int fresh = 0;
        for (long key : wanted) {
            var at = pos(key);
            if (!border.isWithinBounds(at))
                continue; // past the border nothing can be built; the edit refuses those cells itself
            if (!held.containsKey(key)
                    && level.getChunkSource().getChunkNow((int) (key >> 32), (int) key) == null
                    && ++fresh > MAX_NEW_CHUNKS)
                throw new IllegalArgumentException("That edit reaches more than " + MAX_NEW_CHUNKS
                        + " chunks that are not loaded; use a smaller selection, or one closer to loaded ground");
            keys.add(key);
        }
        if (keys.isEmpty()) return NONE;
        var lease = new ChunkLease(level, keys);
        for (long key : keys) {
            if (held.merge(key, 1, Integer::sum) == 1) Compat.addLeaseTicket(level, pos(key));
        }
        try {
            // The tickets only ask; getting each chunk makes it ready now, so the edit can rely on it being there.
            for (long key : keys) level.getChunk((int) (key >> 32), (int) key);
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
        return lease;
    }

    /** Takes the tickets off the chunks no other lease still holds. Closing twice does nothing. */
    @Override
    public void close() {
        if (!open) return;
        open = false;
        if (level == null) return;
        var held = HELD.get(level);
        if (held == null) return;
        for (long key : keys) {
            Integer count = held.get(key);
            if (count == null) continue;
            if (count <= 1) {
                held.remove(key);
                Compat.removeLeaseTicket(level, pos(key));
            } else held.put(key, count - 1);
        }
        if (held.isEmpty()) HELD.remove(level);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static ChunkPos pos(long key) {
        return new ChunkPos((int) (key >> 32), (int) key);
    }
}
