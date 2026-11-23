package dev.craftium.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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

    /** The tools that keep to the side of the surface the brush was put on, see {@link #front}. */
    public static boolean keepsToFront(Tool tool) {
        return switch (tool) {
            case WELD, MELT, SMOOTH, ROUGHEN, SHATTER, SCULPT_DRAW -> true;
            default -> false;
        };
    }

    /**
     * The side of the surface a sculpting brush was put on. {@code cells} are the brush blocks on that side; {@code region} is the brush
     * and a margin around it, and {@code open} the empty blocks of the region on that side. An empty block of the region that is not open
     * lies behind a thin wall or floor.
     */
    public record Front(Set<Point> cells, Set<Point> region, Set<Point> open) {
        public boolean contains(Point p) {
            return cells.contains(p);
        }

        /**
         * {@code world} with the empty blocks behind a thin wall or floor counted as solid, for measuring how much solid surrounds a
         * block: a wall one block thick then weighs as a thick one, so the air beyond it does not keep Weld out of the corners in front.
         */
        public Cells filled(Cells world) {
            return new Cells() {
                public boolean solid(Point p) {
                    return world.solid(p) || region.contains(p) && !open.contains(p);
                }

                public boolean air(Point p) {
                    return !solid(p) && world.air(p);
                }
            };
        }
    }

    /** How far around a block {@link #density} looks. */
    public static int reach(Options o) {
        return Math.clamp((int) Math.ceil(o.smoothing), 1, 3);
    }

    /**
     * The brush blocks on the side of the surface the brush was put on, so a sculpting brush does not reach through a thin wall or floor to
     * the far side. The open part is the empty blocks joined, through empty blocks of the brush and {@code margin} blocks around it, to the
     * one in front of the aimed face. The solid part is the solid blocks joined, through solid brush blocks, to the aimed block, less those
     * that touch only empty blocks of the far side. Null, for no limit, when the aimed block and the one in front of it are not one solid
     * and one empty.
     */
    public static Front front(Collection<Point> cells, Point c, Point normal, Cells world, int margin) {
        if (normal == null || normal.x() == 0 && normal.y() == 0 && normal.z() == 0) return null;
        Point solidSeed, openSeed;
        if (world.solid(c)) {
            solidSeed = c;
            openSeed = c.add(normal.x(), normal.y(), normal.z());
        } else {
            openSeed = c;
            solidSeed = c.add(-normal.x(), -normal.y(), -normal.z());
        }
        if (world.solid(openSeed) || !world.solid(solidSeed)) return null;
        var inside = cells instanceof Set<Point> set ? set : new HashSet<>(cells);
        // The margin is grown from the brush's outer blocks only, so a big brush does not pay for it block by block.
        var region = new HashSet<>(inside);
        for (Point p : inside) {
            boolean edge = false;
            for (Point n : NEIGHBOURS)
                if (!inside.contains(p.add(n.x(), n.y(), n.z()))) {
                    edge = true;
                    break;
                }
            if (!edge) continue;
            for (int x = -margin; x <= margin; x++)
                for (int y = -margin; y <= margin; y++)
                    for (int z = -margin; z <= margin; z++) region.add(p.add(x, y, z));
        }
        var open = flood(region, openSeed, p -> !world.solid(p));
        var solid = flood(inside, solidSeed, world::solid);
        var front = new HashSet<Point>();
        for (Point p : open) if (inside.contains(p)) front.add(p);
        for (Point p : solid) {
            boolean near = false, far = false;
            for (Point n : NEIGHBOURS) {
                Point at = p.add(n.x(), n.y(), n.z());
                if (!inside.contains(at) || world.solid(at)) continue;
                if (open.contains(at)) near = true;
                else far = true;
            }
            if (near || !far) front.add(p);
        }
        return new Front(front, region, open);
    }

    private static Set<Point> flood(Set<Point> inside, Point seed, Predicate<Point> passes) {
        var found = new HashSet<Point>();
        var queue = new ArrayDeque<Point>();
        found.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            for (Point n : NEIGHBOURS) {
                Point at = p.add(n.x(), n.y(), n.z());
                if (inside.contains(at) && !found.contains(at) && passes.test(at)) {
                    found.add(at);
                    queue.add(at);
                }
            }
        }
        return found;
    }

    /** How much of the blocks around {@code p} is solid, blurred by the tool's smoothing. */
    public static double density(Point p, Options o, Cells world) {
        double total = 0, weight = 0;
        int radius = reach(o);
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
     * The one way out of the surface for a whole Sculpt Draw dab: the open sides of every solid block of {@code cells} added up, so the
     * whole brush pushes or pulls along one line and the blocks move together. Straight up with Mask Y on; the clicked face's normal when
     * the open sides cancel out (or there are none).
     */
    public static Geometry.Vector sculptDirection(Collection<Point> cells, Point normal, Options o, Cells world) {
        if (o.maskY) return new Geometry.Vector(0, 1, 0);
        double x = 0, y = 0, z = 0;
        for (Point p : cells) {
            if (!world.solid(p)) continue;
            for (Point n : NEIGHBOURS)
                if (!world.solid(p.add(n.x(), n.y(), n.z()))) {
                    x += n.x();
                    y += n.y();
                    z += n.z();
                }
        }
        var sum = new Geometry.Vector(x, y, z);
        if (sum.length() < .01) sum = normal == null ? new Geometry.Vector(0, 1, 0) : Geometry.vector(normal);
        if (sum.length() < .01) sum = new Geometry.Vector(0, 1, 0);
        return sum.scale(1 / sum.length());
    }

    /**
     * The blocks Sculpt Draw puts (or, inverted, removes) for the brush block {@code p}, moving along {@code direction} from
     * {@link #sculptDirection}: for a surface block open on that side, a column of the blocks a line along it passes through, every one of
     * them, as deep as the strength allows nearer the centre. Pulling out stops at the first solid block; pushing in removes the surface
     * block and those behind it, and stops at the first empty one, so it never reaches through to another surface.
     */
    public static List<Point> sculpt(Point p, Point c, Geometry.Vector direction, Options o, Cells world) {
        var targets = new ArrayList<Point>();
        if (!world.solid(p) || !openToward(p, direction, world)) return targets;
        int length = o.tool == Tool.EXTRUDE
                ? Math.max(1, (int) o.strength)
                : Math.max(1, (int) Math.ceil(o.strength * (1 - Math.sqrt(p.distanceSquared(c)) / (o.radius + 1))));
        if (o.invert) {
            targets.add(p);
            // A column of n blocks along an axis takes the surface block and the n - 1 entered before 0.5 short of n.
            for (Point at : traverse(p, direction.scale(-1), length - .5)) {
                if (!world.solid(at)) break;
                targets.add(at);
            }
        } else
            for (Point at : traverse(p, direction, length)) {
                if (world.solid(at)) break;
                targets.add(at);
            }
        return targets;
    }

    /** Whether a block has an empty neighbour on the side {@code direction} points to. */
    private static boolean openToward(Point p, Geometry.Vector direction, Cells world) {
        for (Point n : NEIGHBOURS)
            if (n.x() * direction.x() + n.y() * direction.y() + n.z() * direction.z() > 1e-6
                    && !world.solid(p.add(n.x(), n.y(), n.z()))) return true;
        return false;
    }

    /**
     * The blocks a line from the centre of {@code p} along the unit {@code direction} enters before it has gone {@code distance}, in order,
     * leaving out {@code p}: every block it passes through, so a slanted line leaves no gaps (a grid walk, as Amanatides and Woo's).
     */
    static List<Point> traverse(Point p, Geometry.Vector direction, double distance) {
        var found = new ArrayList<Point>();
        int x = p.x(), y = p.y(), z = p.z();
        int sx = (int) Math.signum(direction.x()),
                sy = (int) Math.signum(direction.y()),
                sz = (int) Math.signum(direction.z());
        double dx = sx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(direction.x());
        double dy = sy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(direction.y());
        double dz = sz == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(direction.z());
        // From a block's centre the first face is half a block away on every axis.
        double tx = dx / 2, ty = dy / 2, tz = dz / 2;
        while (true) {
            double t = Math.min(tx, Math.min(ty, tz));
            if (t >= distance || Double.isInfinite(t)) return found;
            if (t == tx) {
                x += sx;
                tx += dx;
            } else if (t == ty) {
                y += sy;
                ty += dy;
            } else {
                z += sz;
                tz += dz;
            }
            found.add(new Point(x, y, z));
        }
    }
}
