package dev.craftium.client;

import dev.craftium.core.Point;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The blocks inside the brush ring as the camera sees them: a ray goes through each sample of the circle on the screen and the first solid
 * block it meets is in the footprint. So what a screen-space brush reaches is what the ring shows, whatever the slope of the ground, and
 * nothing behind a hill or inside a cave is reached.
 */
final class ScreenFootprint {
    private ScreenFootprint() {}

    /**
     * The ring and the camera in the same screen pixels, as the editor draws them.
     *
     * @param eye       the camera
     * @param forward   unit vectors of the camera's look direction, right and up
     * @param focal     the distance of the screen from the eye, in pixels
     * @param centreX   the pixel the camera looks through
     * @param pointerX  the middle of the ring
     * @param radius    the ring's radius in pixels
     * @param depth     how far along the look direction the block under the pointer is
     */
    record View(
            Vec3 eye,
            Vec3 forward,
            Vec3 right,
            Vec3 up,
            double focal,
            double centreX,
            double centreY,
            double pointerX,
            double pointerY,
            double radius,
            double depth,
            int screenWidth,
            int screenHeight) {
        /** What changes the footprint, rounded so that a still camera and pointer give the same key. */
        List<Object> key() {
            return List.of(
                    Math.round(eye.x * 50),
                    Math.round(eye.y * 50),
                    Math.round(eye.z * 50),
                    Math.round(forward.x * 500),
                    Math.round(forward.y * 500),
                    Math.round(forward.z * 500),
                    Math.round(pointerX),
                    Math.round(pointerY),
                    Math.round(radius),
                    Math.round(depth * 4));
        }
    }

    /** The ring as of the last frame, or null when no screen-space brush ring is up. */
    static volatile View view;

    /** The ring while the tool in {@code o} paints in screen space and the server understands it, or null. */
    static View active(dev.craftium.core.Options o) {
        return o.tool.screenBrush()
                        && o.screenSpace
                        && ClientHandshake.serverHas(dev.craftium.core.Handshake.SCREEN_BRUSH)
                ? view
                : null;
    }

    private static List<Object> cachedKey = List.of();
    private static List<Point> cached = List.of();

    /**
     * The solid blocks the ring covers, as the preview shows them and as a stroke names them to the server: within reach of {@code at},
     * the block under the pointer, as the server keeps them. Worked out again when the camera, the pointer, the ring, the world (once a
     * second) or the mask changes, so the preview and each use of a stroke share one answer.
     */
    static List<Point> covered(
            net.minecraft.world.level.Level level, dev.craftium.core.Options o, BlockPos at, View v) {
        var key = List.<Object>of(v.key(), at, o.radius, o.mask, List.copyOf(o.maskActive), level.getGameTime() / 20);
        if (key.equals(cachedKey)) return cached;
        var mask = new PaintMask(level, o);
        int reach = dev.craftium.core.Options.screenReach(o.radius);
        var kept = new ArrayList<Point>();
        for (Point p : blocks(v, o.radius, q -> mask.solid(new Point(q.getX(), q.getY(), q.getZ()))))
            if (Math.abs(p.x() - at.getX()) <= reach
                    && Math.abs(p.y() - at.getY()) <= reach
                    && Math.abs(p.z() - at.getZ()) <= reach) kept.add(p);
        cachedKey = key;
        cached = kept;
        return kept;
    }

    /** Samples a ring may take, so a ring across the whole screen at close range stays cheap. */
    private static final int MAX_SAMPLES = 150_000;

    /**
     * The first solid block of the rays through the ring, within {@code reach} blocks of the block under the pointer in depth.
     * {@code solid} says whether a block stops a ray.
     */
    static List<Point> blocks(View v, double reach, java.util.function.Predicate<BlockPos> solid) {
        double step = Math.max(v.radius / (reach + .5) / 3, Math.sqrt(Math.PI * v.radius * v.radius / MAX_SAMPLES));
        step = Math.max(step, 1);
        double near = Math.max(.1, v.depth - 2 * reach - 4), far = v.depth + 2 * reach + 4;
        var found = new LinkedHashSet<Point>();
        var memo = new HashMap<Long, Boolean>();
        java.util.function.Predicate<BlockPos> stops = p -> memo.computeIfAbsent(p.asLong(), k -> solid.test(p));
        for (double dy = -v.radius; dy <= v.radius; dy += step)
            for (double dx = -v.radius; dx <= v.radius; dx += step) {
                if (dx * dx + dy * dy > v.radius * v.radius) continue;
                double sx = v.pointerX + dx, sy = v.pointerY + dy;
                if (sx < 0 || sy < 0 || sx >= v.screenWidth || sy >= v.screenHeight) continue;
                // The direction is scaled so that its part along the look direction is 1: the ray's parameter is the
                // depth.
                Vec3 dir = v.forward
                        .add(v.right.scale((sx - v.centreX) / v.focal))
                        .add(v.up.scale((v.centreY - sy) / v.focal));
                var hit = march(v.eye, dir, near, far, stops);
                if (hit != null) found.add(new Point(hit.getX(), hit.getY(), hit.getZ()));
            }
        return new ArrayList<>(found);
    }

    /** A ray of the swept ring: the block it first meets from the eye ({@link Long#MIN_VALUE} for none) and that block's depth, or for none how far it was followed. */
    private record Ray(long hit, double depth) {}

    /**
     * The rays of the Gradient Painter's swept ring for one camera, and which blocks stop them. The camera stays still while a line is
     * dragged, so a ray through the same pixel meets the same block: moving the end only marches the rays the ring newly reaches. Dropped
     * when the camera moves or a new line begins, so the world is looked at afresh for each line.
     */
    private static final class Sweep {
        final List<Object> camera;
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Ray> rays =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        /** 1 for a block that stops a ray, 2 for one that does not. */
        final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap solid =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        /** 1 for a solid block the eye sees a face of, 2 for one it does not; the fill between the rays asks it. */
        final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap visible =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

        Sweep(List<Object> camera) {
            this.camera = camera;
        }
    }

    private static Sweep sweep;
    /** Rays the last {@link #alongLine} had to march, for the slow-sweep log. */
    private static int marched;

    /** What places the rays on the screen: the camera, the screen and the lens, without the pointer or the ring. */
    static List<Object> cameraKey(View v) {
        var key = v.key();
        return List.of(key.subList(0, 6), v.screenWidth, v.screenHeight, Math.round(v.focal * 10));
    }

    /** Forgets the swept ring's rays and the last line worked out: a new line looks at the world as it is now. */
    static void forgetSweep() {
        sweep = null;
        lineKey = List.of();
        lineCached = null;
    }

    /**
     * The first solid block, seen from the eye, of the rays through the ring swept along the line from {@code a} to {@code b}: the
     * Gradient Painter's footprint. A ray whose first block is not within {@code reach} of the line's depth there takes nothing, so what is
     * behind a hill or a tree is not reached. The ring keeps the size in the world it has at the pointer, so it grows on the screen towards
     * the near end. The rays sit on a fixed grid of the screen whose spacing is a power of two, so the rays of one line are the next one's
     * too. Null when part of the swept ring is off the screen or the line passes behind the camera, so the caller can fall back to the
     * brush shape instead of leaving out what cannot be seen.
     */
    static List<Point> alongLine(
            View v, Point a, Point b, double reach, java.util.function.Predicate<BlockPos> isSolid) {
        Vec3 pa = Vec3.atCenterOf(new BlockPos(a.x(), a.y(), a.z())),
                pb = Vec3.atCenterOf(new BlockPos(b.x(), b.y(), b.z()));
        double za = pa.subtract(v.eye).dot(v.forward), zb = pb.subtract(v.eye).dot(v.forward);
        if (za < .5 || zb < .5 || v.depth <= 0) return null;
        double ax = screenX(v, pa, za), ay = screenY(v, pa, za), bx = screenX(v, pb, zb), by = screenY(v, pb, zb);
        double worldRing = v.radius * v.depth; // the ring's radius in pixels at depth 1
        double ra = worldRing / za, rb = worldRing / zb, rMax = Math.max(ra, rb);
        double minX = Math.min(ax - ra, bx - rb), maxX = Math.max(ax + ra, bx + rb);
        double minY = Math.min(ay - ra, by - rb), maxY = Math.max(ay + ra, by + rb);
        if (minX < 0 || minY < 0 || maxX >= v.screenWidth || maxY >= v.screenHeight) return null;
        double dx = bx - ax, dy = by - ay, length2 = dx * dx + dy * dy;
        double area = Math.PI * rMax * rMax + 2 * rMax * Math.sqrt(length2);
        // About a ray a block where the line is furthest: the rays only find the surface, and the fill below joins them
        double wanted = Math.max(1, Math.max(Math.min(ra, rb) / (reach + .5), Math.sqrt(area / MAX_SAMPLES)));
        int power = Math.min(20, (int) Math.ceil(Math.log(wanted) / Math.log(2) - 1e-9));
        double step = 1 << power;
        marched = 0;
        var camera = cameraKey(v);
        if (sweep == null || !sweep.camera.equals(camera)) sweep = new Sweep(camera);
        var cache = sweep;
        java.util.function.Predicate<BlockPos> stops = p -> {
            byte known = cache.solid.get(p.asLong());
            if (known == 0) {
                known = isSolid.test(p) ? (byte) 1 : (byte) 2;
                cache.solid.put(p.asLong(), known);
            }
            return known == 1;
        };
        var seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        var found = new ArrayList<Point>();
        for (long iy = (long) Math.ceil(minY / step); iy * step <= maxY; iy++)
            for (long ix = (long) Math.ceil(minX / step); ix * step <= maxX; ix++) {
                double sx = ix * step, sy = iy * step;
                double s = length2 == 0 ? 0 : Math.clamp(((sx - ax) * dx + (sy - ay) * dy) / length2, 0, 1);
                // depth along the line, interpolated as the screen does (in 1 / depth), and the ring's size there
                double depth = 1 / ((1 - s) / za + s / zb), r = worldRing / depth;
                double ex = sx - (ax + s * dx), ey = sy - (ay + s * dy);
                if (ex * ex + ey * ey > r * r) continue;
                double near = depth - 2 * reach - 4, far = depth + 2 * reach + 4;
                long id = (long) power << 56 | (ix & 0xfffffffL) << 28 | iy & 0xfffffffL;
                var ray = cache.rays.get(id);
                if (ray == null || ray.hit() == Long.MIN_VALUE && ray.depth() < far) {
                    Vec3 dir = v.forward
                            .add(v.right.scale((sx - v.centreX) / v.focal))
                            .add(v.up.scale((v.centreY - sy) / v.focal));
                    var hit = march(v.eye, dir, .1, far, stops);
                    marched++;
                    ray = hit == null
                            ? new Ray(Long.MIN_VALUE, far)
                            : new Ray(
                                    hit.asLong(),
                                    Vec3.atCenterOf(hit).subtract(v.eye).dot(v.forward));
                    cache.rays.put(id, ray);
                }
                if (ray.hit() != Long.MIN_VALUE && ray.depth() >= near && ray.depth() <= far && seen.add(ray.hit())) {
                    var at = BlockPos.of(ray.hit());
                    found.add(new Point(at.getX(), at.getY(), at.getZ()));
                }
            }
        // The rays are a grid on the screen, and ground seen at a slant puts several blocks between two of them. So
        // from
        // the blocks they found, the fill takes in each neighbour that is solid, inside the swept ring and seen by the
        // eye, until there is none left: every visible block of the surface in the ring, at any angle.
        var line = new Swept(v, za, zb, ax, ay, dx, dy, length2, worldRing, 2 * reach + 4);
        var queue = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
        for (long hit : seen) queue.enqueue(hit);
        var refused = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        var at = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && found.size() < dev.craftium.core.Options.MAX_SCREEN_BLOCKS) {
            long from = queue.dequeueLong();
            int fx = BlockPos.getX(from), fy = BlockPos.getY(from), fz = BlockPos.getZ(from);
            for (int ox = -1; ox <= 1; ox++)
                for (int oy = -1; oy <= 1; oy++)
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oy == 0 && oz == 0) continue;
                        at.set(fx + ox, fy + oy, fz + oz);
                        long id = at.asLong();
                        if (seen.contains(id) || refused.contains(id)) continue;
                        if (!stops.test(at) || !line.contains(at) || !visible(v, at, stops, cache)) {
                            refused.add(id);
                            continue;
                        }
                        seen.add(id);
                        found.add(new Point(at.getX(), at.getY(), at.getZ()));
                        queue.enqueue(id);
                    }
        }
        return found;
    }

    /** The ring swept along a line as the screen sees it, and the depths around the line a block may lie at. */
    private record Swept(
            View v,
            double za,
            double zb,
            double ax,
            double ay,
            double dx,
            double dy,
            double length2,
            double worldRing,
            double window) {
        /** Whether the middle of block {@code p} is inside the swept ring on the screen, and near the line's depth there. */
        boolean contains(BlockPos p) {
            Vec3 c = Vec3.atCenterOf(p);
            double z = c.subtract(v.eye).dot(v.forward);
            if (z < .1) return false;
            double sx = screenX(v, c, z), sy = screenY(v, c, z);
            double s = length2 == 0 ? 0 : Math.clamp(((sx - ax) * dx + (sy - ay) * dy) / length2, 0, 1);
            double depth = 1 / ((1 - s) / za + s / zb), r = worldRing / depth;
            double ex = sx - (ax + s * dx), ey = sy - (ay + s * dy);
            return ex * ex + ey * ey <= r * r && Math.abs(z - depth) <= window;
        }
    }

    /**
     * Whether the eye sees a face of solid block {@code p}: a face open to a block that does not stop rays, turned to the eye, with
     * nothing solid between the eye and it. Kept for the camera, since the fill asks again for every line of a drag.
     */
    private static boolean visible(View v, BlockPos p, java.util.function.Predicate<BlockPos> stops, Sweep cache) {
        long id = p.asLong();
        byte known = cache.visible.get(id);
        if (known != 0) return known == 1;
        boolean seen = false;
        Vec3 c = Vec3.atCenterOf(p);
        for (var d : net.minecraft.core.Direction.values()) {
            if (stops.test(p.relative(d))) continue; // a buried face
            // just outside the middle of the face, so the walk to it never enters the block itself
            Vec3 target = c.add(d.getStepX() * .51, d.getStepY() * .51, d.getStepZ() * .51);
            Vec3 to = target.subtract(v.eye);
            if (to.dot(new Vec3(d.getStepX(), d.getStepY(), d.getStepZ())) >= 0) continue; // turned away
            double z = to.dot(v.forward);
            if (z <= .2) continue;
            if (march(v.eye, to.scale(1 / z), .1, z - 1e-3, stops) == null) {
                seen = true;
                break;
            }
        }
        cache.visible.put(id, seen ? (byte) 1 : (byte) 2);
        return seen;
    }

    private static double screenX(View v, Vec3 p, double depth) {
        return v.centreX + v.focal * p.subtract(v.eye).dot(v.right) / depth;
    }

    private static double screenY(View v, Vec3 p, double depth) {
        return v.centreY - v.focal * p.subtract(v.eye).dot(v.up) / depth;
    }

    private static List<Object> lineKey = List.of();
    private static List<Point> lineCached;

    /**
     * The Gradient Painter's footprint from {@code a} to {@code b}, as the preview shows it and the stroke names it: the solid blocks the
     * ring swept along the line covers, kept to those within reach of the line as the server keeps them. Null when the ring cannot be
     * swept on the screen (see {@link #alongLine}). Worked out again only when the camera, the ends or the ring change; the world is
     * looked at afresh for each new line ({@link #forgetSweep}).
     */
    static List<Point> coveredAlong(
            net.minecraft.world.level.Level level, dev.craftium.core.Options o, Point a, Point b, View v) {
        var key = List.<Object>of(cameraKey(v), a, b, o.radius, Math.round(v.radius * v.depth));
        if (key.equals(lineKey)) return lineCached;
        int reach = dev.craftium.core.Options.screenReach(o.radius);
        long began = System.nanoTime();
        var swept = alongLine(v, a, b, o.radius, p -> {
            var state = level.getBlockState(p);
            return !state.isAir() && state.getFluidState().isEmpty();
        });
        long ms = (System.nanoTime() - began) / 1_000_000;
        if (ms >= 30) // for game tests of a big ring: what a slow sweep cost
        dev.craftium.Craftium.LOG.info(
                    "Gradient line footprint took {} ms: {} blocks, {} rays marched, {} rays kept",
                    ms,
                    swept == null ? "off screen" : swept.size(),
                    marched,
                    sweep == null ? 0 : sweep.rays.size());
        List<Point> kept = null;
        if (swept != null) {
            kept = new ArrayList<>(swept.size());
            for (Point p : swept) if (dev.craftium.core.BrushMath.nearSegment(p, a, b, reach)) kept.add(p);
        }
        lineKey = key;
        lineCached = kept;
        return kept;
    }

    /** The first block {@code stops} accepts along {@code eye + t * dir} for {@code t} from {@code near} to {@code far} (a voxel walk), or null. */
    static BlockPos march(Vec3 eye, Vec3 dir, double near, double far, java.util.function.Predicate<BlockPos> stops) {
        Vec3 start = eye.add(dir.scale(near));
        int x = (int) Math.floor(start.x), y = (int) Math.floor(start.y), z = (int) Math.floor(start.z);
        int sx = dir.x > 0 ? 1 : -1, sy = dir.y > 0 ? 1 : -1, sz = dir.z > 0 ? 1 : -1;
        double tx = next(start.x, dir.x, x, sx, near),
                ty = next(start.y, dir.y, y, sy, near),
                tz = next(start.z, dir.z, z, sz, near);
        double dx = dir.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.x),
                dy = dir.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.y),
                dz = dir.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.z);
        while (true) {
            var at = new BlockPos(x, y, z);
            if (stops.test(at)) return at;
            if (tx <= ty && tx <= tz) {
                if (tx > far) return null;
                x += sx;
                tx += dx;
            } else if (ty <= tz) {
                if (ty > far) return null;
                y += sy;
                ty += dy;
            } else {
                if (tz > far) return null;
                z += sz;
                tz += dz;
            }
        }
    }

    /** The parameter at which the ray leaves the block it starts in along one axis. */
    private static double next(double origin, double dir, int cell, int sign, double near) {
        if (dir == 0) return Double.POSITIVE_INFINITY;
        double edge = sign > 0 ? cell + 1 : cell;
        return near + (edge - origin) / dir;
    }
}
