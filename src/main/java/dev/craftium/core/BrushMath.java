package dev.craftium.core;

import java.util.ArrayList;
import java.util.List;

public final class BrushMath {
    private BrushMath() {}
    /**
     * New shapes go at the end: a saved setting names its shape, and the order here is not the order the picker shows.
     * Pointed shapes stand on their base (the apex is at +radius), flat ones lie in the horizontal plane through the centre.
     */
    public enum Shape { SPHERE, CUBE, OCTAHEDRON, CYLINDER, PLANE, DISK, PYRAMID, CONE, HEMISPHERE, TORUS, TUBE, POLYGON, IRREGULAR_POLYGON, STAR }

    /**
     * What only the irregular polygon and the star read. {@code seed} picks which irregular polygon a number of sides makes; {@code inner} is how far
     * in the star's notches go, as a share of the radius (near 0 the points are needles, near 1 it is a plain polygon).
     */
    public record Form(int seed, double inner) {
        public static final Form DEFAULT = new Form(1, .4);
    }

    /**
     * A flat shape drawn as a fan of triangles from its centre: corner {@code i} lies at {@code angle[i]} (ascending, in [0, 2π)) and {@code radius[i]}
     * times the reach from the centre. Any polygon that can be seen whole from its centre is one, which covers both the irregular polygon and the star.
     */
    private record Fan(double[] angle, double[] radius) {}
    private record FanKey(Shape shape, int sides, Form form) {}
    private static volatile java.util.Map.Entry<FanKey, Fan> lastFan;

    private static Fan fan(Shape shape, int sides, Form form) {
        var key = new FanKey(shape, sides, form);
        var last = lastFan;
        if (last != null && last.getKey().equals(key)) return last.getValue();
        int n = shape == Shape.STAR ? sides * 2 : sides;
        double[] angle = new double[n], radius = new double[n];
        if (shape == Shape.STAR) { // points up (towards -z) at full radius, with a notch between each pair at the inner radius
            double inner = Math.clamp(form.inner(), .05, .95);
            for (int k = 0; k < n; k++) { angle[k] = -Math.PI / 2 + Math.PI * k / sides; radius[k] = k % 2 == 0 ? 1 : inner; }
        } else { // random gaps between the corners and a random distance out to each, the farthest scaled to the full radius
            var random = new java.util.Random(form.seed() * 0x9E3779B97F4A7C15L + sides);
            double[] gap = new double[n];
            double total = 0, far = 0;
            for (int i = 0; i < n; i++) { gap[i] = .7 + .6 * random.nextDouble(); total += gap[i]; } // gaps within 0.7 to 1.3 of even, so no corner is ever more than half a turn from the next
            double at = random.nextDouble() * 2 * Math.PI;
            for (int i = 0; i < n; i++) { angle[i] = at; at += gap[i] / total * 2 * Math.PI; radius[i] = .5 + .5 * random.nextDouble(); far = Math.max(far, radius[i]); }
            for (int i = 0; i < n; i++) radius[i] /= far;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) { order[i] = i; angle[i] = ((angle[i] % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI); }
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> angle[i]));
        double[] sortedAngle = new double[n], sortedRadius = new double[n];
        for (int i = 0; i < n; i++) { sortedAngle[i] = angle[order[i]]; sortedRadius[i] = radius[order[i]]; }
        var made = new Fan(sortedAngle, sortedRadius);
        lastFan = java.util.Map.entry(key, made);
        return made;
    }

    /** The share of the disk of the same radius that the irregular polygon or star covers, from the corners themselves. */
    public static double fanShare(Shape shape, int sides, Form form) {
        var f = fan(shape, Math.max(MIN_SIDES, sides), form);
        int n = f.angle.length;
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double turn = f.angle[j] - f.angle[i];
            if (turn <= 0) turn += 2 * Math.PI;
            area += .5 * f.radius[i] * f.radius[j] * Math.sin(turn);
        }
        return area / Math.PI;
    }

    /**
     * Whether the point ({@code x}, {@code z}) is inside the irregular polygon or star with this many sides (points, for a star), measured with
     * its farthest corner {@code reach} from the centre. The corner after the point's own direction and the one before it make the edge it is tested against.
     */
    public static boolean fanContains(Shape shape, int sides, Form form, double x, double z, double reach) {
        if (x == 0 && z == 0) return true;
        var f = fan(shape, sides, form);
        int n = f.angle.length;
        double a = Math.atan2(z, x);
        if (a < 0) a += 2 * Math.PI;
        int i = java.util.Arrays.binarySearch(f.angle, a);
        if (i < 0) i = -i - 2; // the corner just before the point's direction; before the first one, the last wraps round
        if (i < 0) i = n - 1;
        int j = (i + 1) % n;
        double ax = Math.cos(f.angle[i]) * f.radius[i] * reach, az = Math.sin(f.angle[i]) * f.radius[i] * reach;
        double bx = Math.cos(f.angle[j]) * f.radius[j] * reach, bz = Math.sin(f.angle[j]) * f.radius[j] * reach;
        return (bx - ax) * (z - az) - (bz - az) * (x - ax) >= -1e-9;
    }

    /** The sides a polygon has until told otherwise; the slider runs to {@link #SLIDER_SIDES}, a typed value to {@link #MAX_SIDES}. */
    public static final int DEFAULT_SIDES = 6, MIN_SIDES = 3, SLIDER_SIDES = 64, MAX_SIDES = 256;

    /**
     * The picker's sections in display order, each with its shapes in the order they are shown: the common solids first,
     * then the curved and hollow forms, then the flat ones.
     */
    public static final java.util.Map<String, List<Shape>> GROUPS = groups();
    private static java.util.Map<String, List<Shape>> groups() {
        var all = new java.util.LinkedHashMap<String, List<Shape>>();
        all.put("Solids", List.of(Shape.CUBE, Shape.SPHERE, Shape.CYLINDER, Shape.CONE, Shape.PYRAMID, Shape.OCTAHEDRON));
        all.put("Curved", List.of(Shape.HEMISPHERE, Shape.TORUS, Shape.TUBE));
        all.put("Flat", List.of(Shape.PLANE, Shape.DISK, Shape.POLYGON, Shape.IRREGULAR_POLYGON, Shape.STAR));
        return java.util.Collections.unmodifiableMap(all);
    }

    public static boolean contains(Shape shape, int x, int y, int z, int radius) {
        return contains(shape, x, y, z, radius, DEFAULT_SIDES);
    }

    /** A regular polygon lying flat, as wide as the disk of the same radius, with an edge facing +x. */
    private static boolean polygon(int x, int z, double reach, int sides) {
        // The inscribed circle of a polygon whose corners touch the circle of radius reach.
        double apothem = reach * Math.cos(Math.PI / sides);
        for (int i = 0; i < sides; i++) {
            double a = 2 * Math.PI * i / sides;
            if (x * Math.cos(a) + z * Math.sin(a) > apothem) return false;
        }
        return true;
    }

    public static boolean contains(Shape shape, int x, int y, int z, int radius, int sides) {
        return contains(shape, x, y, z, radius, sides, Form.DEFAULT);
    }

    public static boolean contains(Shape shape, int x, int y, int z, int radius, int sides, Form form) {
        // Round shapes are measured to radius + 0.5: with the plain radius the block at the end of each axis passes while
        // its neighbours just miss, leaving a one block nub at every pole.
        int radial = x * x + z * z, side = Math.max(Math.abs(x), Math.abs(z)), rise = radius - y;
        double reach = radius + .5, reach2 = reach * reach;
        return switch (shape) {
            case SPHERE -> radial + y * y <= reach2;
            case CUBE -> Math.max(side, Math.abs(y)) <= radius;
            case OCTAHEDRON -> Math.abs(x) + Math.abs(y) + Math.abs(z) <= radius;
            case CYLINDER -> radial <= reach2 && Math.abs(y) <= radius;
            case PLANE -> y == 0 && side <= radius;
            case DISK -> y == 0 && radial <= reach2;
            case POLYGON -> y == 0 && radial <= reach2 && polygon(x, z, reach, sides);
            case IRREGULAR_POLYGON, STAR -> y == 0 && radial <= reach2 && fanContains(shape, Math.max(MIN_SIDES, sides), form, x, z, reach);
            // The width falls evenly from the full radius at the bottom to nothing at the top.
            case PYRAMID -> Math.abs(y) <= radius && 2 * side <= rise;
            case CONE -> Math.abs(y) <= radius && 4.0 * radial * radius * radius <= reach2 * rise * rise;
            case HEMISPHERE -> y >= 0 && radial + y * y <= reach2;
            // A ring whose tube is a quarter of the radius thick, centred three quarters of the way out.
            case TORUS -> { double d = Math.sqrt(radial) - .75 * radius, tube = .25 * radius + .5; yield d * d + y * y <= tube * tube; }
            case TUBE -> Math.abs(y) <= radius && radial <= reach2 && 4.0 * radial >= (double) radius * radius;
        };
    }

    /** The largest radius that can be set, typed or dragged to; the slider stops short of it, at 64. */
    public static final int MAX_RADIUS = 999;
    /** The farthest from its centre any block of a shape may be, however its wall is aligned or it is scaled. The work grows with its cube. */
    public static final int MAX_REACH = 999;

    public static List<Point> brush(Point center, int radius, Shape shape, boolean hollow) {
        return brush(center, radius, shape, hollow, 1, false, 0);
    }
    public static List<Point> brush(Point center, int radius, Shape shape, boolean hollow, int thickness, boolean pixelPerfect) {
        return brush(center, radius, shape, hollow, thickness, pixelPerfect, 0);
    }

    /**
     * The blocks of the shape. A hollow one keeps a wall {@code thickness} blocks thick (no more than the radius): by cutting off an
     * inner copy of the shape, or with {@code pixelPerfect} by keeping the blocks nearest the outside along each part of the wall's
     * own direction (see {@link OutlineMath#thin}), which is even all the way round. {@code shift} puts the outer edge that many
     * blocks past the radius, which is how the wall is aligned to the edge the radius gives (see {@link Options#outlineShift()}).
     * A shape of more than {@link Selection#LIMIT} blocks is refused as soon as it passes the limit.
     */
    public static List<Point> brush(Point center, int radius, Shape shape, boolean hollow, int thickness, boolean pixelPerfect, int shift) {
        return brush(center, radius, shape, hollow, thickness, pixelPerfect, shift, DEFAULT_SIDES);
    }

    /** As above, with {@code sides} for a {@link Shape#POLYGON}; other shapes ignore it. */
    public static List<Point> brush(Point center, int radius, Shape shape, boolean hollow, int thickness, boolean pixelPerfect, int shift, int sides) {
        return brush(center, radius, shape, hollow, thickness, pixelPerfect, shift, sides, false);
    }

    /** What an outline of {@code solid} keeps: the blocks on the rim of the shape in all three axis planes, which is its edges. A flat shape's is its ring. */
    public interface Solid { boolean at(int x, int y, int z); }
    public static boolean edge(Solid solid, int x, int y, int z) {
        if (!solid.at(x, y, z)) return false;
        boolean openX = !solid.at(x + 1, y, z) || !solid.at(x - 1, y, z), openY = !solid.at(x, y + 1, z) || !solid.at(x, y - 1, z), openZ = !solid.at(x, y, z + 1) || !solid.at(x, y, z - 1);
        return (openY || openZ) && (openX || openZ) && (openX || openY);
    }
    public static List<Point> edges(java.util.Collection<Point> solid) {
        var set = new java.util.HashSet<>(solid);
        Solid inside = (x, y, z) -> set.contains(new Point(x, y, z));
        return solid.stream().filter(p -> edge(inside, p.x(), p.y(), p.z())).toList();
    }

    /** As above; with {@code outline} only the shape's edges are kept, and the wall settings are ignored. */
    public static List<Point> brush(Point center, int radius, Shape shape, boolean hollow, int thickness, boolean pixelPerfect, int shift, int sides, boolean outline) {
        return brush(center, radius, shape, hollow, thickness, pixelPerfect, shift, sides, outline, Selection.LIMIT);
    }

    /** As above, refusing a shape of more than {@code limit} blocks; a preview may allow more than the {@link Selection#LIMIT} that can be built. */
    public static List<Point> brush(Point center, int radius, Shape shape, boolean hollow, int thickness, boolean pixelPerfect, int shift, int sides, boolean outline, int limit) {
        return brush(center, radius, shape, hollow, thickness, pixelPerfect, shift, sides, outline, limit, Form.DEFAULT);
    }

    /** As above, with the {@code form} an irregular polygon or a star is made from; other shapes ignore it. */
    public static List<Point> brush(Point center, int radius, Shape shape, boolean hollow, int thickness, boolean pixelPerfect, int shift, int sides, boolean outline, int limit, Form form) {
        if (outline) hollow = false;
        final int corners = Math.clamp(sides, MIN_SIDES, MAX_SIDES);
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("Radius must be 1–" + MAX_RADIUS);
        int wall = Math.clamp(thickness, 1, radius);
        final int outer = Math.min(radius + (hollow ? Math.max(0, shift) : 0), MAX_REACH);
        // Refused before any work when it is plainly too big: the cells to look at, or the blocks it must hold.
        if (ShapeLimits.cells(shape, outer) > ShapeLimits.SCAN_CELLS) throw new IllegalArgumentException("Shape is too large to build; use a smaller radius");
        if (ShapeLimits.blocks(shape, radius, hollow, thickness, pixelPerfect, shift, corners, outline, form) > Math.max(limit, 0) * 1.05 + 64)
            throw new IllegalArgumentException("Shape exceeds the " + String.format("%,d", limit) + " block limit; use a smaller radius, or make it hollow");
        var points = new ArrayList<Point>();
        int inner = outer - wall; // the edge of the cut; below zero there is nothing to cut out, so the shape stays solid
        boolean cut = hollow && !pixelPerfect && inner >= 0;
        Solid solid = (x, y, z) -> contains(shape, x, y, z, outer, corners, form);
        // Only the cells that can be inside are looked at: the cell test below is what decides, these bounds just leave out the ones it would refuse.
        final double reach2 = (outer + .5) * (outer + .5), tube = .25 * outer + .5;
        final boolean flat = shape == Shape.PLANE || shape == Shape.DISK || shape == Shape.POLYGON || shape == Shape.IRREGULAR_POLYGON || shape == Shape.STAR;
        final boolean round = shape == Shape.SPHERE || shape == Shape.HEMISPHERE, radial = shape == Shape.CYLINDER || shape == Shape.CONE || shape == Shape.TUBE || shape == Shape.TORUS || shape == Shape.DISK || shape == Shape.POLYGON || shape == Shape.IRREGULAR_POLYGON || shape == Shape.STAR || round;
        for (int x = -outer; x <= outer; x++) {
            if (radial && (double) x * x > reach2) continue;
            int yHigh = flat ? 0 : round ? (int) Math.sqrt(reach2 - (double) x * x) : shape == Shape.TORUS ? (int) tube : outer;
            int yLow = flat ? 0 : shape == Shape.HEMISPHERE ? 0 : -yHigh;
            for (int y = yLow; y <= yHigh; y++) {
                int zBound = round ? (int) Math.sqrt(Math.max(0, reach2 - (double) x * x - (double) y * y)) : radial ? (int) Math.sqrt(reach2 - (double) x * x) : outer;
                for (int z = -zBound; z <= zBound; z++) {
                    if (outline ? !edge(solid, x, y, z) : !solid.at(x, y, z) || cut && contains(shape, x, y, z, inner, corners, form)) continue;
                    points.add(center.add(x, y, z));
                    if (points.size() > limit) throw new IllegalArgumentException("Shape exceeds the " + String.format("%,d", limit) + " block limit; use a smaller radius, or make it hollow");
                }
            }
        }
        return hollow && pixelPerfect ? OutlineMath.thin(points, wall) : points;
    }

    public static List<Point> line(Point a, Point b) {
        int steps = Math.max(Math.max(Math.abs(b.x()-a.x()), Math.abs(b.y()-a.y())), Math.abs(b.z()-a.z()));
        if (steps > 512) throw new IllegalArgumentException("Line exceeds 512 blocks");
        var points = new ArrayList<Point>();
        if (steps == 0) return List.of(a);
        for (int i = 0; i <= steps; i++) {
            double t = (double)i / steps;
            points.add(new Point((int)Math.round(a.x() + (b.x()-a.x()) * t),
                (int)Math.round(a.y() + (b.y()-a.y()) * t), (int)Math.round(a.z() + (b.z()-a.z()) * t)));
        }
        return points;
    }

    /** Stable coherent value noise, independent of stroke order. */
    public static double noise(double x, double y, double z, long seed) {
        int ix = (int)Math.floor(x), iy = (int)Math.floor(y), iz = (int)Math.floor(z);
        double tx = smooth(x-ix), ty = smooth(y-iy), tz = smooth(z-iz);
        return lerp(lerp(lerp(hash(ix,iy,iz,seed),hash(ix+1,iy,iz,seed),tx),
                         lerp(hash(ix,iy+1,iz,seed),hash(ix+1,iy+1,iz,seed),tx),ty),
                    lerp(lerp(hash(ix,iy,iz+1,seed),hash(ix+1,iy,iz+1,seed),tx),
                         lerp(hash(ix,iy+1,iz+1,seed),hash(ix+1,iy+1,iz+1,seed),tx),ty),tz);
    }

    public static double hash(int x, int y, int z, long seed) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return ((h ^ (h >>> 31)) >>> 11) * 0x1.0p-53;
    }
    private static double smooth(double t) { return t*t*(3-2*t); }
    private static double lerp(double a, double b, double t) { return a+(b-a)*t; }
}
