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

    /**
     * The first solid block of the rays through the ring swept along the line from {@code a} to {@code b} as the camera sees it: the
     * Gradient Painter's footprint. The ring keeps the size in the world it has at the pointer, so it grows on the screen towards the near
     * end. Null when part of the swept ring is off the screen or the line passes behind the camera, so the caller can fall back to the
     * brush shape instead of leaving out what cannot be seen.
     */
    static List<Point> alongLine(View v, Point a, Point b, double reach, java.util.function.Predicate<BlockPos> solid) {
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
        double step = Math.max(Math.min(ra, rb) / (reach + .5) / 3, Math.sqrt(area / MAX_SAMPLES));
        step = Math.max(step, 1);
        var found = new LinkedHashSet<Point>();
        var memo = new HashMap<Long, Boolean>();
        java.util.function.Predicate<BlockPos> stops = p -> memo.computeIfAbsent(p.asLong(), k -> solid.test(p));
        for (double sy = minY; sy <= maxY; sy += step)
            for (double sx = minX; sx <= maxX; sx += step) {
                double s = length2 == 0 ? 0 : Math.clamp(((sx - ax) * dx + (sy - ay) * dy) / length2, 0, 1);
                // depth along the line, interpolated as the screen does (in 1 / depth), and the ring's size there
                double depth = 1 / ((1 - s) / za + s / zb), r = worldRing / depth;
                double ex = sx - (ax + s * dx), ey = sy - (ay + s * dy);
                if (ex * ex + ey * ey > r * r) continue;
                Vec3 dir = v.forward
                        .add(v.right.scale((sx - v.centreX) / v.focal))
                        .add(v.up.scale((v.centreY - sy) / v.focal));
                var hit = march(v.eye, dir, Math.max(.1, depth - 2 * reach - 4), depth + 2 * reach + 4, stops);
                if (hit != null) found.add(new Point(hit.getX(), hit.getY(), hit.getZ()));
            }
        return new ArrayList<>(found);
    }

    private static double screenX(View v, Vec3 p, double depth) {
        return v.centreX + v.focal * p.subtract(v.eye).dot(v.right) / depth;
    }

    private static double screenY(View v, Vec3 p, double depth) {
        return v.centreY - v.focal * p.subtract(v.eye).dot(v.up) / depth;
    }

    private static List<Object> lineKey = List.of();
    private static List<Point> lineCached = List.of();

    /**
     * The Gradient Painter's footprint from {@code a} to {@code b}, as the preview shows it and the stroke names it: the solid blocks the
     * ring swept along the line covers, kept to those within reach of the line as the server keeps them. Null when the ring cannot be
     * swept on the screen (see {@link #alongLine}). Worked out again when the camera, the ends, the ring, the world (once a second) or the
     * mask changes.
     */
    static List<Point> coveredAlong(
            net.minecraft.world.level.Level level, dev.craftium.core.Options o, Point a, Point b, View v) {
        var cameraKey = new ArrayList<>(v.key());
        cameraKey.remove(6); // the pointer: the line, not the pointer, places the ring
        cameraKey.remove(6);
        var key =
                List.<Object>of(cameraKey, a, b, o.radius, o.mask, List.copyOf(o.maskActive), level.getGameTime() / 20);
        if (key.equals(lineKey)) return lineCached;
        var mask = new PaintMask(level, o);
        int reach = dev.craftium.core.Options.screenReach(o.radius);
        var swept = alongLine(v, a, b, o.radius, q -> mask.solid(new Point(q.getX(), q.getY(), q.getZ())));
        List<Point> kept = null;
        if (swept != null) {
            kept = new ArrayList<>();
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
