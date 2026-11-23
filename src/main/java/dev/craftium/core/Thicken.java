package dev.craftium.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What one dab of the Thicken brush does: it grows the surface the brush is on outward by a few blocks, each surface block along its own
 * side, so a curved wall, a floor or the inside of a hollow mountain gains an even layer that follows its shape. Invert thins the surface
 * the same way instead. The server builds with it and the hover preview draws with it.
 *
 * <p>It keeps to the side of the surface the brush was put on ({@link BrushRules#front}): it grows only into the open air on that side, so
 * the outside of a one-block shell is never touched from the inside. The layer is thickest at the centre of the brush and tapers to nothing
 * at its rim, so strokes blend into the surface around them instead of leaving a step; Falloff ({@link Options#deformFalloff}, shared with
 * Deform) sets how wide that fading edge is. Each new block copies the surface block nearest to
 * it, so the stone, ore and dirt of a wall carry on into its new layer.
 */
public final class Thicken {
    /** The most blocks one dab adds or takes off at the centre of the brush. */
    public static final int MAX_THICKNESS = 5;

    private static final Point[] NEIGHBOURS = {
        new Point(1, 0, 0),
        new Point(-1, 0, 0),
        new Point(0, 1, 0),
        new Point(0, -1, 0),
        new Point(0, 0, 1),
        new Point(0, 0, -1)
    };

    private Thicken() {}

    /**
     * What a dab changes: {@code added}, each empty block that gains a block with the surface block it copies, and {@code removed}, the
     * blocks taken off; both in a stable order.
     */
    public record Plan(Map<Point, Point> added, Set<Point> removed) {
        static final Plan NONE = new Plan(Map.of(), Set.of());

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    /** How many blocks a dab adds at the centre of the brush: the Strength, in whole blocks. */
    public static int thickness(Options o) {
        return (int) Math.clamp(Math.round(o.strength), 1, MAX_THICKNESS);
    }

    /**
     * One dab with the brush {@code brush} around the block {@code c}, clicked on its {@code normal} side: Thicken, or Thin when
     * {@link Options#invert} is on. Empty when the click is not on a solid block facing open air.
     */
    public static Plan plan(Collection<Point> brush, Point c, Point normal, Options o, BrushRules.Cells world) {
        return plan(brush, c, normal, o, world, false);
    }

    /**
     * One dab as {@link #plan(Collection, Point, Point, Options, BrushRules.Cells)}, where {@code sheet} says the brush is not a ball but the
     * surface blocks the ring covers on the screen ({@link Options#screenBlocks}): those are the surface, every one of them on the side the
     * camera sees, the taper reaches as far across as they do, and Thin follows the wall behind them instead of keeping to the brush.
     */
    public static Plan plan(
            Collection<Point> brush, Point c, Point normal, Options o, BrushRules.Cells world, boolean sheet) {
        Set<Point> inside = brush instanceof Set<Point> set ? set : new LinkedHashSet<>(brush);
        int most = thickness(o);
        // The margin lets the layer grow past the rim of the brush as far as it can reach.
        var front = BrushRules.front(inside, c, normal, world, most + 1);
        if (front == null) return Plan.NONE;
        if (!sheet) {
            // As Deform does, the dab works on a column of the radius across the surface, not the ball: on a curved
            // wall the
            // ball leaves out the wall near its rim, so with a low Falloff the layer would stop short of the ring.
            var across = BrushRules.sculptDirection(
                    inside.stream().filter(front::contains).toList(), normal, o, front.filled(world));
            var column = Deform.column(c, across, o.radius);
            var wider = BrushRules.front(column, c, normal, world, most + 1);
            if (wider != null) {
                inside = column;
                front = wider;
            }
        }
        var surface = new LinkedHashSet<Point>();
        for (Point s : inside)
            if (world.solid(s) && (sheet || front.contains(s)) && touches(s, front.open())) surface.add(s);
        if (surface.isEmpty()) return Plan.NONE;
        // The taper is measured across the surface, not along it, so the whole thickness under the centre gets the full
        // amount.
        var n = BrushRules.sculptDirection(
                sheet ? surface : inside.stream().filter(front::contains).toList(), normal, o, front.filled(world));
        double reach = o.radius + 1;
        if (sheet) {
            reach = 1;
            for (Point s : surface) reach = Math.max(reach, Taper.across(s, c, n) + 1);
        }
        var taper = new Taper(c, n, reach, Deform.ramp(reach, most, o.deformFalloff));
        if (!o.invert) return thicken(surface, front, world, most, taper);
        var brushed = inside;
        var side = front;
        Predicate<Point> wall = sheet
                ? joined(surface, side.region(), world)::contains
                : at -> brushed.contains(at) && world.solid(at) && side.contains(at);
        return thin(surface, front, most, taper, wall);
    }

    /** Every empty block on the brushed side within the local thickness of a surface block gains a copy of the nearest one. */
    private static Plan thicken(
            Set<Point> surface, BrushRules.Front front, BrushRules.Cells world, int most, Taper taper) {
        var source = new LinkedHashMap<Point, Point>();
        var distance = new HashMap<Point, Integer>();
        for (Point s : surface) {
            double local = most * taper.weight(s);
            if (local < 1) continue;
            int r = (int) Math.floor(local);
            double limit = local * local;
            for (int x = -r; x <= r; x++)
                for (int y = -r; y <= r; y++)
                    for (int z = -r; z <= r; z++) {
                        int d = x * x + y * y + z * z;
                        if (d == 0 || d > limit) continue;
                        Point at = s.add(x, y, z);
                        if (!front.open().contains(at) || world.solid(at)) continue;
                        Integer best = distance.get(at);
                        if (best != null && best <= d) continue;
                        distance.put(at, d);
                        source.put(at, s);
                    }
        }
        return new Plan(source, Set.of());
    }

    /** Every solid block of the brushed wall within the local thickness of the open air in front of it is taken off. */
    private static Plan thin(Set<Point> surface, BrushRules.Front front, int most, Taper taper, Predicate<Point> wall) {
        var removed = new LinkedHashSet<Point>();
        var skin = new LinkedHashSet<Point>();
        for (Point s : surface)
            for (Point n : NEIGHBOURS) {
                Point at = s.add(n.x(), n.y(), n.z());
                if (front.open().contains(at)) skin.add(at);
            }
        for (Point a : skin) {
            double local = most * taper.weight(a);
            if (local < 1) continue;
            int r = (int) Math.floor(local);
            double limit = local * local;
            for (int x = -r; x <= r; x++)
                for (int y = -r; y <= r; y++)
                    for (int z = -r; z <= r; z++) {
                        int d = x * x + y * y + z * z;
                        if (d == 0 || d > limit) continue;
                        Point at = a.add(x, y, z);
                        if (wall.test(at)) removed.add(at);
                    }
        }
        return new Plan(Map.of(), removed);
    }

    /**
     * How much of the thickness a block gets: all of it near the axis through the centre along {@code n}, fading smoothly to none at the rim
     * over an edge {@code ramp} wide, which the Falloff sets as it does for Deform ({@link Deform#ramp}).
     */
    private record Taper(Point c, Geometry.Vector n, double reach, double ramp) {
        double weight(Point p) {
            double t = Math.clamp((reach - across(p, c, n)) / ramp, 0, 1);
            return t * t * (3 - 2 * t);
        }

        /** How far {@code p} is from the axis through {@code c} along {@code n}. */
        static double across(Point p, Point c, Geometry.Vector n) {
            var r = new Geometry.Vector(p.x() - c.x(), p.y() - c.y(), p.z() - c.z());
            return r.subtract(n.scale(r.dot(n))).length();
        }
    }

    /**
     * The solid blocks of {@code region} joined through faces, edges or corners to the surface: the wall behind the blocks the ring covers,
     * and not another one past a gap, which Thin must not reach.
     */
    private static Set<Point> joined(Set<Point> surface, Set<Point> region, BrushRules.Cells world) {
        var found = new HashSet<>(surface);
        var queue = new ArrayDeque<>(surface);
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        Point q = p.add(dx, dy, dz);
                        if (region.contains(q) && world.solid(q) && found.add(q)) queue.add(q);
                    }
        }
        return found;
    }

    private static boolean touches(Point p, Set<Point> open) {
        for (Point n : NEIGHBOURS) if (open.contains(p.add(n.x(), n.y(), n.z()))) return true;
        return false;
    }
}
