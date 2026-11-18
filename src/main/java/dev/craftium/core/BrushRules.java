package dev.craftium.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Which blocks the sculpting brushes (Weld, Melt, Roughen, Shatter, Distort, Sculpt Draw) change. The server builds with these rules
 * and the editor's hover preview draws with them, so the preview shows the blocks that will really change.
 */
public final class BrushRules {
    private static final Point[] NEIGHBOURS = {
        new Point(1, 0, 0),
        new Point(-1, 0, 0),
        new Point(0, 1, 0),
        new Point(0, -1, 0),
        new Point(0, 0, 1),
        new Point(0, 0, -1)
    };

    private BrushRules() {}

    /** The world as the rules see it. */
    public interface Cells {
        /** A block that is neither air nor a fluid. */
        boolean solid(Point p);

        boolean air(Point p);

        default int adjacent(Point p) {
            int count = 0;
            for (Point n : NEIGHBOURS) if (solid(p.add(n.x(), n.y(), n.z()))) count++;
            return count;
        }
    }

    /** How much of the blocks around {@code p} is solid, blurred by the tool's smoothing. */
    public static double density(Point p, Options o, Cells world) {
        double total = 0, weight = 0;
        int radius = Math.clamp((int) Math.ceil(o.smoothing), 1, 3);
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++) {
                    double w = Math.exp(-(x * x + y * y + z * z) / (2 * o.smoothing * o.smoothing));
                    weight += w;
                    if (world.solid(p.add(x, y, z))) total += w;
                }
        return total / weight;
    }

    /** Weld fills an empty block with enough solid around it. */
    public static boolean weld(Point p, Options o, Cells world) {
        return !world.solid(p) && density(p, o, world) >= o.threshold;
    }

    /** Melt erodes a solid block without enough solid around it. */
    public static boolean melt(Point p, Options o, Cells world) {
        return world.solid(p) && density(p, o, world) < o.threshold;
    }

    /** The block Roughen changes for {@code p}: {@code p} itself when it is knocked out, the empty block beside it that gains a block, or null. */
    public static Point roughen(Point p, Options o, Cells world, double noise) {
        if (!world.solid(p)
                || 6 - world.adjacent(p) < o.exposedFaces
                || BrushMath.hash(p.x(), p.y(), p.z(), o.seed) >= o.roughenRatio) return null;
        if (noise < .5) return p;
        for (Point direction : NEIGHBOURS) {
            Point at = p.add(direction.x(), direction.y(), direction.z());
            if (world.air(at)) return at;
        }
        return null;
    }

    /** Shatter cuts the seams where the cracks run. */
    public static boolean shatter(Point p, Options o, Cells world) {
        return world.solid(p) && fracture(p, o) < o.shatterWidth / o.noiseScale;
    }

    /** How far {@code p} is from a crack: the gap between its nearest and second nearest crack points. */
    public static double fracture(Point p, Options o) {
        // With one axis chosen only that coordinate counts, so the seams are flat sheets across the axis.
        var axis = o.shatterAxis;
        boolean ax = axis == Options.ShatterAxis.ALL || axis == Options.ShatterAxis.X,
                ay = axis == Options.ShatterAxis.ALL || axis == Options.ShatterAxis.Y,
                az = axis == Options.ShatterAxis.ALL || axis == Options.ShatterAxis.Z;
        double scale = o.noiseScale, x = ax ? p.x() / scale : 0, y = ay ? p.y() / scale : 0, z = az ? p.z() / scale : 0;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double first = Double.MAX_VALUE, second = Double.MAX_VALUE;
        for (int dx = ax ? -1 : 0; dx <= (ax ? 1 : 0); dx++)
            for (int dy = ay ? -1 : 0; dy <= (ay ? 1 : 0); dy++)
                for (int dz = az ? -1 : 0; dz <= (az ? 1 : 0); dz++) {
                    int a = ix + dx, b = iy + dy, c = iz + dz;
                    double px = ax ? a + BrushMath.hash(a, b, c, o.seed) : 0,
                            py = ay ? b + BrushMath.hash(a, b, c, o.seed + 1) : 0,
                            pz = az ? c + BrushMath.hash(a, b, c, o.seed + 2) : 0;
                    double d = (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z);
                    if (d < first) {
                        second = first;
                        first = d;
                    } else if (d < second) second = d;
                }
        return Math.sqrt(second) - Math.sqrt(first);
    }

    /**
     * Where Distort takes the block for {@code p} from. The distance is how far the noise moves a block, about half of it either way; it
     * fades out over the outer part of the brush with Smooth edges.
     */
    public static Point distortSource(Point p, Point c, Options o, NoiseField noise) {
        double fade = 1;
        if (o.distortSmoothEdges) {
            double edge = Math.sqrt(p.distanceSquared(c)) / (o.radius + .5), t = Math.clamp((edge - .6) / .4, 0, 1);
            fade = 1 - t * t * (3 - 2 * t);
        }
        double reachX = (o.distortSeparate ? o.distortX : o.distortDistance) * fade,
                reachY = (o.distortSeparate ? o.distortY : o.distortDistance) * fade,
                reachZ = (o.distortSeparate ? o.distortZ : o.distortDistance) * fade;
        int dx = (int) Math.round((noise.sample(p) - .5) * reachX * 2),
                dy = (int) Math.round((noise.sample(p.add(37, 71, 19)) - .5) * reachY * 2),
                dz = (int) Math.round((noise.sample(p.add(101, 13, 53)) - .5) * reachZ * 2);
        return p.add(dx, dy, dz);
    }

    /**
     * The blocks Sculpt Draw (or Extrude's older path) puts or removes for the brush block {@code p}: a column of blocks leaving the surface
     * along the way the open air lies, as long as the strength allows nearer the centre.
     */
    public static List<Point> sculpt(Point p, Point c, Point normal, Options o, Cells world) {
        var targets = new ArrayList<Point>();
        if (!world.solid(p) || !world.air(p.add(normal.x(), normal.y(), normal.z()))) return targets;
        int length = o.tool == Tool.EXTRUDE
                ? Math.max(1, (int) o.strength)
                : Math.max(1, (int) Math.ceil(o.strength * (1 - Math.sqrt(p.distanceSquared(c)) / (o.radius + 1))));
        Geometry.Vector direction = new Geometry.Vector(normal.x(), normal.y(), normal.z());
        if (o.tool == Tool.SCULPT_DRAW) {
            Geometry.Vector estimated = new Geometry.Vector(0, 0, 0);
            for (Point neighbour : NEIGHBOURS)
                if (!world.solid(p.add(neighbour.x(), neighbour.y(), neighbour.z())))
                    estimated = estimated.add(Geometry.vector(neighbour));
            if (estimated.length() > .01) direction = estimated.scale(1 / estimated.length());
            if (o.maskY) direction = new Geometry.Vector(0, 1, 0);
        }
        for (int i = 1; i <= length; i++) {
            Point delta = direction.scale(o.invert ? 1 - i : i).voxel();
            targets.add(p.add(delta.x(), delta.y(), delta.z()));
        }
        return targets;
    }
}
