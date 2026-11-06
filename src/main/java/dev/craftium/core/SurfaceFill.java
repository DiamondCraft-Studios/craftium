package dev.craftium.core;

import java.util.*;

/**
 * The blocks an Extrude or a Floodfill click reaches. The server builds from this and the client draws the hover preview from it,
 * so what is outlined under the cursor is what the click changes.
 */
public final class SurfaceFill {
    private SurfaceFill() {}
    /** What the searches need to know about the world. */
    public interface Cells {
        boolean air(Point p);
        /** Whether {@code p} is a wall to a flood fill's gap check: something other than the space being filled. */
        default boolean solid(Point p) { return !air(p); }
        /** Whether the block at {@code p} is one the search may take: for a face, one that compares as the clicked block; for a flood, the same state as where it started. */
        boolean matches(Point p);
    }
    private static final Point[] SIDES = {new Point(1,0,0),new Point(-1,0,0),new Point(0,1,0),new Point(0,-1,0),new Point(0,0,1),new Point(0,0,-1)};

    /** The whole face the click lands on: connected blocks, level with the clicked one across the normal, that match and have air in front. */
    public static List<Point> face(Cells world, Point start, Point normal, int limit) {
        var found = new ArrayList<Point>();
        var todo = new ArrayDeque<Point>();var seen = new HashSet<Point>();todo.add(start);seen.add(start);
        while (!todo.isEmpty() && found.size() < limit) {
            Point at = todo.removeFirst();
            if (!world.matches(at) || !world.air(at.add(normal.x(), normal.y(), normal.z()))) continue;
            found.add(at);
            for (Point n : SIDES)
                if (n.x() * normal.x() + n.y() * normal.y() + n.z() * normal.z() == 0) { Point next = at.add(n.x(), n.y(), n.z()); if (seen.add(next)) todo.add(next); }
        }
        return found;
    }

    /** The cells a flood fill from {@code start} fills, nearest first, within 128 blocks of it and the directions it may spread in. */
    public static List<Point> flood(Cells world, Point start, int limit, boolean up, boolean down, boolean horizontal, boolean corners) {
        return flood(world, start, limit, Selection.LIMIT * 8, up, down, horizontal, corners);
    }
    /** As above, giving up after {@code visits} cells have been looked at, which bounds the work when the space is open. */
    public static List<Point> flood(Cells world, Point start, int limit, int visits, boolean up, boolean down, boolean horizontal, boolean corners) {
        return flood(world, start, limit, visits, 0, up, down, horizontal, corners);
    }
    /**
     * As above, closing gaps: with {@code gap} above 0, a cell squeezed between two walls at most that many cells apart, along any axis, counts as a wall too,
     * so a fill does not leak through a window, a doorway or a crack that narrow. The start cell is always filled.
     */
    public static List<Point> flood(Cells world, Point start, int limit, int visits, int gap, boolean up, boolean down, boolean horizontal, boolean corners) {
        var walls = new HashMap<Point, Boolean>();
        var found = new ArrayList<Point>();
        // Nearest by straight-line distance, not by steps: a queue in step order grows an octahedron in open space, this grows a ball.
        // The tie-breaks keep the order the same on the client and the server.
        var todo = new PriorityQueue<Point>(Comparator.<Point>comparingDouble(p -> p.distanceSquared(start)).thenComparingInt(Point::x).thenComparingInt(Point::y).thenComparingInt(Point::z));
        var seen = new HashSet<Point>();todo.add(start);seen.add(start);
        while (!todo.isEmpty() && found.size() < limit && seen.size() < visits) {
            Point at = todo.poll();
            if (at.distanceSquared(start) > 128 * 128 || !world.matches(at)) continue;
            if (!up && at.y() > start.y() || !down && at.y() < start.y() || !horizontal && (at.x() != start.x() || at.z() != start.z())) continue;
            if (gap > 0 && !at.equals(start) && closed(world, at, gap, walls)) continue;
            found.add(at);
            for (Point n : SIDES) { Point next = at.add(n.x(), n.y(), n.z()); if (seen.add(next)) todo.add(next); }
            if (corners) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) { Point next = at.add(x, 0, z); if (seen.add(next)) todo.add(next); }
        }
        return found;
    }

    /** Whether {@code at} sits in a gap of at most {@code gap} cells between two walls along some axis. */
    private static boolean closed(Cells world, Point at, int gap, Map<Point, Boolean> walls) {
        for (int axis = 0; axis < 3; axis++) {
            Point d = SIDES[axis * 2];
            int before = 0;
            for (int i = 1; i <= gap && before == 0; i++) if (wall(world, at.add(-i * d.x(), -i * d.y(), -i * d.z()), walls)) before = i;
            if (before == 0) continue;
            for (int i = 1; i <= gap + 1 - before; i++) if (wall(world, at.add(i * d.x(), i * d.y(), i * d.z()), walls)) return true;
        }
        return false;
    }
    private static boolean wall(Cells world, Point p, Map<Point, Boolean> walls) {
        return walls.computeIfAbsent(p, world::solid);
    }
}
