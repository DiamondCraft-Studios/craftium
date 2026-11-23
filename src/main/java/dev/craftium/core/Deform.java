package dev.craftium.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Deform brush does: it moves the wall the brush is on in (Push) or out (Pull) by moving its blocks, never by adding or removing
 * material, so a wall of any thickness, even the one-block shell of a hollow mountain, keeps its thickness and stays closed. The server builds
 * with it and the hover preview draws with it.
 *
 * <p>Each block of the area takes the block that was where the push brings it from, as Distort does, so blocks keep their states and contents.
 * The push is greatest at the centre and fades to nothing at the rim, measured across the push and not along it, so a column of blocks along
 * the push moves as one. Strength is kept below two thirds of the radius, so neighbours never move more than a block apart, and where they move
 * one block apart the seam between them is filled, so a one-block wall does not come apart at the corners.
 *
 * <p>A stroke of dabs is worked out as one move on the world as it was before the stroke (see {@link Stroke}), so overlapping dabs push an
 * even layer and the surface keeps its own roughness.
 */
public final class Deform {
    private static final Point[] NEIGHBOURS = {
        new Point(1, 0, 0),
        new Point(-1, 0, 0),
        new Point(0, 1, 0),
        new Point(0, -1, 0),
        new Point(0, 0, 1),
        new Point(0, 0, -1)
    };

    private static final Geometry.Vector ZERO = new Geometry.Vector(0, 0, 0);

    private Deform() {}

    /** The most a dab may move a block, for a brush of {@code radius}: below two thirds of it, so the push never folds over on itself. */
    public static int maxStrength(int radius) {
        return Math.max(1, 2 * radius / 3);
    }

    /** How far a dab moves the centre of the wall, in whole blocks, within {@link #maxStrength}. */
    public static int strength(Options o) {
        return (int) Math.clamp(Math.round(o.strength), 1, maxStrength(o.radius));
    }

    /**
     * The blocks one dab changes, each with the place its new block is taken from, in a stable order. The brush is {@code brush} around the
     * block {@code c} clicked on its {@code normal} side. Pull when {@link Options#invert} is on, Push otherwise. Empty when the click is not on
     * a solid block facing open air.
     */
    public static Map<Point, Point> plan(
            Collection<Point> brush, Point c, Point normal, Options o, BrushRules.Cells world, int margin) {
        return new Stroke().add(brush, c, normal, o, world, margin);
    }

    /**
     * A stroke of dabs, all worked out on the world as it was before the stroke, so the stroke moves the surface it was painted on and not
     * what its own earlier dabs left. Each dab takes its direction from that surface. Where dabs overlap, a block moves as far as the dab that
     * moves it furthest, not the sum of them, along their directions blended by how much each moves it: a stroke over the same place pushes
     * it in as an even layer, and the surface keeps its own roughness. A new dab works out again only the blocks it can reach.
     */
    public static final class Stroke {
        /** One dab: where it was put, its direction out of the surface, the way blocks move, how far, and how far it reaches. */
        private record Dab(Point c, Geometry.Vector n, Geometry.Vector d, int s, double reach, double extent) {}

        private static final int CELL = 8;
        private final Map<Long, List<Dab>> index = new HashMap<>();
        private final Set<Point> area = new HashSet<>();
        private final Set<Point> wall = new HashSet<>();
        private final Set<Point> open = new HashSet<>();
        private final Map<Point, Point> plan = new HashMap<>();
        private int most;
        private int dabs;

        /** How many dabs moved something. */
        public int dabs() {
            return dabs;
        }

        /**
         * Adds a dab, put on the block {@code c} clicked on its {@code normal} side with {@code brush} around it, and works out again what it
         * reaches. {@code world} is the world as it was before the stroke. Returns the blocks whose source changed, in a stable order, each
         * with its new source: itself when the block is back to as it was. Nothing when the click is not on a solid block facing open air.
         */
        public Map<Point, Point> add(
                Collection<Point> brush, Point c, Point normal, Options o, BrushRules.Cells world, int margin) {
            var changed = new LinkedHashMap<Point, Point>();
            var inside = brush instanceof Set<Point> set ? set : new HashSet<>(brush);
            var front = BrushRules.front(inside, c, normal, world, margin);
            if (front == null) return changed;
            wall.addAll(body(inside, world.solid(c) ? c : c.add(-normal.x(), -normal.y(), -normal.z()), world));
            open.addAll(front.open());
            var n = BrushRules.sculptDirection(
                    brush.stream().filter(front::contains).toList(), normal, o, front.filled(world));
            int s = strength(o);
            // The way the blocks move: in for Push, out for Pull.
            var d = o.invert ? n : n.scale(-1);
            var dab = new Dab(c, n, d, s, o.radius + 1, o.radius + s + 1);
            index(dab);
            dabs++;
            most = Math.max(most, s);
            // The area that changes: the brush, and as far past it as the blocks can go.
            for (Point p : brush) {
                area.add(p);
                area.addAll(BrushRules.traverse(p, d, s + .5));
            }
            // Everything this dab can change: where it moves blocks and their neighbours, and the blocks that can take
            // theirs from the
            // wall and air it adds to the move.
            var move = new Moving(wall, open, world);
            var shifts = new HashMap<Point, Geometry.Vector>();
            int r = o.radius + Math.max(s + 2, margin + most + 1);
            for (int x = -r; x <= r; x++)
                for (int y = -r; y <= r; y++)
                    for (int z = -r; z <= r; z++) {
                        if (x * x + y * y + z * z > r * r) continue;
                        Point p = c.add(x, y, z);
                        if (!area.contains(p)) continue;
                        Point taken = taken(p, move, shifts);
                        if (taken.equals(plan.getOrDefault(p, p))) continue;
                        if (taken.equals(p)) plan.remove(p);
                        else plan.put(p, taken);
                        changed.put(p, taken);
                    }
            return changed;
        }

        /** Every block the stroke changes so far, with the place its new block is taken from. */
        public Map<Point, Point> plan() {
            return Collections.unmodifiableMap(plan);
        }

        private void index(Dab dab) {
            int e = (int) Math.ceil(dab.extent());
            Point c = dab.c();
            for (int x = Math.floorDiv(c.x() - e, CELL); x <= Math.floorDiv(c.x() + e, CELL); x++)
                for (int y = Math.floorDiv(c.y() - e, CELL); y <= Math.floorDiv(c.y() + e, CELL); y++)
                    for (int z = Math.floorDiv(c.z() - e, CELL); z <= Math.floorDiv(c.z() + e, CELL); z++)
                        index.computeIfAbsent(key(x, y, z), k -> new ArrayList<>())
                                .add(dab);
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x1fffff) << 42 | ((long) y & 0x1fffff) << 21 | (long) z & 0x1fffff;
        }

        /** Where the block at {@code x} is taken from, with the seam filled; {@code x} to leave it as it is. */
        private Point taken(Point x, Moving move, Map<Point, Geometry.Vector> shifts) {
            var world = move.world();
            Point from = round(x, shifts.computeIfAbsent(x, this::shift));
            Point taken = from.equals(x) ? x : take(x, from, move);
            // The seam: where a neighbour moves a block further or less far, a wall block between the two places fills
            // an empty one.
            if (!world.solid(taken) && !move.fixed(x))
                for (Point step : NEIGHBOURS) {
                    Point y = x.add(step.x(), step.y(), step.z());
                    Point other = round(x, shifts.computeIfAbsent(y, this::shift));
                    if (other.equals(from)) continue;
                    Point block = firstWall(from, other, move);
                    if (block != null) {
                        taken = block;
                        break;
                    }
                }
            // Air moved onto air changes nothing.
            if (!taken.equals(x) && world.air(x) && world.air(taken)) return x;
            return taken;
        }

        /**
         * How far back along the move the block now at {@code x} comes from: as far as the dab that moves it furthest (its strength, faded by
         * the distance from its axis), along the dabs' ways blended by how far each moves it.
         */
        private Geometry.Vector shift(Point x) {
            var near =
                    index.get(key(Math.floorDiv(x.x(), CELL), Math.floorDiv(x.y(), CELL), Math.floorDiv(x.z(), CELL)));
            if (near == null) return ZERO;
            double furthest = 0, wx = 0, wy = 0, wz = 0;
            for (Dab dab : near) {
                var r = new Geometry.Vector(
                        x.x() - dab.c().x(),
                        x.y() - dab.c().y(),
                        x.z() - dab.c().z());
                var across = r.subtract(dab.n().scale(r.dot(dab.n())));
                double t = Math.clamp(1 - across.length() / dab.reach(), 0, 1);
                if (t <= 0) continue;
                double w = t * t * (3 - 2 * t);
                furthest = Math.max(furthest, dab.s() * w);
                wx += w * dab.d().x();
                wy += w * dab.d().y();
                wz += w * dab.d().z();
            }
            double length = Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (furthest == 0 || length < 1e-9) return ZERO;
            double k = -furthest / length;
            return new Geometry.Vector(wx * k, wy * k, wz * k);
        }
    }

    private static Point round(Point x, Geometry.Vector shift) {
        return x.add((int) Math.round(shift.x()), (int) Math.round(shift.y()), (int) Math.round(shift.z()));
    }

    /**
     * What moves: the wall, all the solid blocks of the brush joined to the one clicked (the whole of a thick wall, not only its outer face),
     * and the open air in front of it. Anything else solid is fixed: the far side, or something standing inside the hollow. A wall pushed onto
     * it wins.
     */
    private record Moving(Set<Point> wall, Set<Point> open, BrushRules.Cells world) {
        boolean movable(Point p) {
            return wall.contains(p) || open.contains(p);
        }

        boolean fixed(Point p) {
            return world.solid(p) && !wall.contains(p);
        }
    }

    /**
     * The solid blocks of {@code inside} joined to {@code seed} through faces, edges or corners: a wall that keeps air out through its faces
     * may still hold together only at its edges, as a slanted one-block wall does.
     */
    private static Set<Point> body(Set<Point> inside, Point seed, BrushRules.Cells world) {
        var found = new HashSet<Point>();
        if (!inside.contains(seed) || !world.solid(seed)) return found;
        var queue = new java.util.ArrayDeque<Point>();
        found.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        Point q = p.add(dx, dy, dz);
                        if (inside.contains(q) && world.solid(q) && found.add(q)) queue.add(q);
                    }
        }
        return found;
    }

    /** Where the block at {@code x} is taken from, given the place {@code from} the move brings it from; {@code x} to leave it as it is. */
    private static Point take(Point x, Point from, Moving move) {
        var world = move.world();
        if (move.movable(from)) return move.fixed(x) && !world.solid(from) ? x : from;
        // Air that is not part of the move (the inside of the hollow, or past the brush) comes along, except onto
        // something fixed.
        if (!world.solid(from)) return move.fixed(x) ? x : from;
        // A solid block that is not part of the move is not copied.
        return x;
    }

    /**
     * The first block of the wall in the box between {@code a} and {@code b}, both included, nearest {@code a} first, or null. A box and not a
     * line: places a diagonal step apart are joined through the blocks beside the diagonal, which must be filled too.
     */
    private static Point firstWall(Point a, Point b, Moving move) {
        Point best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = Math.min(a.x(), b.x()); x <= Math.max(a.x(), b.x()); x++)
            for (int y = Math.min(a.y(), b.y()); y <= Math.max(a.y(), b.y()); y++)
                for (int z = Math.min(a.z(), b.z()); z <= Math.max(a.z(), b.z()); z++) {
                    Point p = new Point(x, y, z);
                    int distance = Math.abs(x - a.x()) + Math.abs(y - a.y()) + Math.abs(z - a.z());
                    if (distance < bestDistance && move.wall().contains(p)) {
                        best = p;
                        bestDistance = distance;
                    }
                }
        return best;
    }
}
