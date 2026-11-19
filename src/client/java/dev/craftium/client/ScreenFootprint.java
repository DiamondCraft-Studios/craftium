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
