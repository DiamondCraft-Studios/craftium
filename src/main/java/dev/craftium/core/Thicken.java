package dev.craftium.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What one dab of the Thicken brush does: it grows the surface the brush is on outward by a few blocks, each surface block along its own
 * side, so a curved wall, a floor or the inside of a hollow mountain gains an even layer that follows its shape. Invert thins the surface
 * the same way instead. The server builds with it and the hover preview draws with it.
 *
 * <p>It keeps to the side of the surface the brush was put on ({@link BrushRules#front}): it grows only into the open air on that side, so
 * the outside of a one-block shell is never touched from the inside. The layer is thickest at the centre of the brush and tapers to nothing
 * at its rim, so strokes blend into the surface around them instead of leaving a step. Each new block copies the surface block nearest to
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
        var inside = brush instanceof Set<Point> set ? set : new LinkedHashSet<>(brush);
        int most = thickness(o);
        // The margin lets the layer grow past the rim of the brush as far as it can reach.
        var front = BrushRules.front(inside, c, normal, world, most + 1);
        if (front == null) return Plan.NONE;
        // The taper is measured across the surface, not along it, so the whole thickness under the centre gets the full
        // amount.
        var n = BrushRules.sculptDirection(
                inside.stream().filter(front::contains).toList(), normal, o, front.filled(world));
        var taper = new Taper(c, n, o.radius + 1);
        return o.invert ? thin(inside, front, world, most, taper) : thicken(inside, front, world, most, taper);
    }

    /** Every empty block on the brushed side within the local thickness of a surface block gains a copy of the nearest one. */
    private static Plan thicken(
            Set<Point> inside, BrushRules.Front front, BrushRules.Cells world, int most, Taper taper) {
        var source = new LinkedHashMap<Point, Point>();
        var distance = new HashMap<Point, Integer>();
        for (Point s : inside) {
            if (!world.solid(s) || !front.contains(s) || !touches(s, front.open())) continue;
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
    private static Plan thin(Set<Point> inside, BrushRules.Front front, BrushRules.Cells world, int most, Taper taper) {
        var removed = new LinkedHashSet<Point>();
        var skin = new LinkedHashSet<Point>();
        for (Point s : inside)
            if (world.solid(s) && front.contains(s))
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
                        if (inside.contains(at) && world.solid(at) && front.contains(at)) removed.add(at);
                    }
        }
        return new Plan(Map.of(), removed);
    }

    /** How much of the thickness a block gets: all of it on the axis through the centre along {@code n}, fading smoothly to none at the rim. */
    private record Taper(Point c, Geometry.Vector n, double reach) {
        double weight(Point p) {
            var r = new Geometry.Vector(p.x() - c.x(), p.y() - c.y(), p.z() - c.z());
            var across = r.subtract(n.scale(r.dot(n)));
            double t = Math.clamp(1 - across.length() / reach, 0, 1);
            return t * t * (3 - 2 * t);
        }
    }

    private static boolean touches(Point p, Set<Point> open) {
        for (Point n : NEIGHBOURS) if (open.contains(p.add(n.x(), n.y(), n.z()))) return true;
        return false;
    }
}
