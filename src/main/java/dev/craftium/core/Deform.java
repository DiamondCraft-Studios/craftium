package dev.craftium.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What one dab of the Deform brush does: it moves the wall the brush is on in (Push) or out (Pull) by moving its blocks, never by adding or
 * removing material, so a wall of any thickness, even the one-block shell of a hollow mountain, keeps its thickness and stays closed. The server
 * builds with it and the hover preview draws with it.
 *
 * <p>Each block of the area takes the block that was where the push brings it from, as Distort does, so blocks keep their states and contents.
 * The push is greatest at the centre and fades to nothing at the rim, measured across the push and not along it, so a column of blocks along
 * the push moves as one. Strength is kept below two thirds of the radius, so neighbours never move more than a block apart, and where they move
 * one block apart the seam between them is filled, so a one-block wall does not come apart at the corners.
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
        var out = new LinkedHashMap<Point, Point>();
        var inside = brush instanceof Set<Point> set ? set : new HashSet<>(brush);
        var front = BrushRules.front(inside, c, normal, world, margin);
        if (front == null) return out;
        var wall = body(inside, world.solid(c) ? c : c.add(-normal.x(), -normal.y(), -normal.z()), world);
        var move = new Moving(wall, front.open(), world);
        var measured = front.filled(world);
        var n = BrushRules.sculptDirection(
                brush.stream().filter(front::contains).toList(), normal, o, measured);
        int s = strength(o);
        // The way the blocks move: in for Push, out for Pull.
        var d = o.invert ? n : n.scale(-1);
        double reach = o.radius + 1;
        // The area that changes: the brush, and as far past it as the blocks can go.
        var area = new LinkedHashSet<Point>(brush);
        for (Point p : brush) area.addAll(BrushRules.traverse(p, d, s + .5));
        for (Point x : area) {
            var shift = shift(x, c, n, d, s, reach);
            Point from = round(x, shift);
            Point taken = from.equals(x) ? x : take(x, from, move);
            // The seam: where a neighbour moves a block further or less far, a wall block between the two places fills
            // an empty one.
            if (!world.solid(taken) && !move.fixed(x))
                for (Point step : NEIGHBOURS) {
                    Point y = x.add(step.x(), step.y(), step.z());
                    Point other = round(x, shift(y, c, n, d, s, reach));
                    if (other.equals(from)) continue;
                    Point block = firstWall(from, other, move);
                    if (block != null) {
                        taken = block;
                        break;
                    }
                }
            if (!taken.equals(x)) out.put(x, taken);
        }
        return out;
    }

    /** How far back along the move the block now at {@code x} comes from: the strength, faded by the distance from the push's axis. */
    private static Geometry.Vector shift(Point x, Point c, Geometry.Vector n, Geometry.Vector d, int s, double reach) {
        var r = new Geometry.Vector(x.x() - c.x(), x.y() - c.y(), x.z() - c.z());
        var across = r.subtract(n.scale(r.dot(n)));
        double t = Math.clamp(1 - across.length() / reach, 0, 1);
        double w = t * t * (3 - 2 * t);
        return d.scale(-s * w);
    }

    private static Point round(Point x, Geometry.Vector shift) {
        return x.add((int) Math.round(shift.x()), (int) Math.round(shift.y()), (int) Math.round(shift.z()));
    }

    /**
     * What moves: the wall, all the solid blocks of the brush joined to the one clicked (the whole of a thick wall, not only
     * its outer face), and the open air in front of it. Anything else solid is fixed: the far side, or something standing inside the
     * hollow. A wall pushed onto it wins.
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
     * The first block of the wall in the box between {@code a} and {@code b}, both included, nearest {@code a} first, or null. A box and not
     * a line: places a diagonal step apart are joined through the blocks beside the diagonal, which must be filled too.
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
