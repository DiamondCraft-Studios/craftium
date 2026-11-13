package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** A hollow sphere must be watertight: nothing from the middle can walk out, face to face, through a gap in the wall. */
class HollowSphereSealTest {
    /** Whether the empty middle connects, face to face, to the outside of the blocks. */
    private static boolean leaks(Collection<Point> blocks, int lim) {
        var wall = new HashSet<>(blocks);
        var seen = new HashSet<Point>();
        var queue = new ArrayDeque<Point>();
        queue.add(new Point(0, 0, 0));
        seen.add(new Point(0, 0, 0));
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            var p = queue.poll();
            for (var d : dirs) {
                var q = new Point(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                if (Math.abs(q.x()) > lim || Math.abs(q.y()) > lim || Math.abs(q.z()) > lim) return true;
                if (wall.contains(q) || !seen.add(q)) continue;
                queue.add(q);
            }
        }
        return false;
    }

    private static Options sphere(Options.ShapeKind kind, int radius, int thickness) {
        var o = new Options();
        o.shapeKind = kind;o.shape = BrushMath.Shape.SPHERE;o.radius = radius;o.hollow = true;o.outlineThickness = thickness;
        return o;
    }

    @Test
    void brushWallOfOneHasNoHoles() {
        for (int r = 2; r <= 70; r += r < 30 ? 1 : 7) {
            var b = BrushMath.brush(new Point(0, 0, 0), r, BrushMath.Shape.SPHERE, true, 1, false, 0);
            assertFalse(leaks(b, r + 2), "brush radius " + r + " leaks");
        }
    }

    @Test
    void turnedHollowSphereHasNoHoles() {
        for (int t : new int[]{1, 2}) for (double[] turn : new double[][]{{17, 0, 0}, {30, 20, 10}, {45, 45, 45}, {0, 0, 33}, {5, 80, 0}}) {
            for (int r = 3; r <= 30; r += 3) {
                var o = sphere(Options.ShapeKind.BRUSH, r, t);
                o.yaw = turn[0];o.pitch = turn[1];o.roll = turn[2];
                var b = Geometry.shape(new Point(0, 0, 0), o);
                assertTrue(b.size() > 0);
                assertFalse(leaks(b, r + 4), "t=" + t + " r=" + r + " turn=" + java.util.Arrays.toString(turn) + " leaks");
            }
        }
    }

    @Test
    void turnedPixelPerfectSphereHasNoHoles() {
        for (int t : new int[]{1, 2}) for (double[] turn : new double[][]{{17, 0, 0}, {30, 20, 10}, {45, 45, 45}}) {
            for (int r = 3; r <= 30; r += 3) {
                var o = sphere(Options.ShapeKind.BRUSH, r, t);
                o.pixelPerfect = true;
                o.yaw = turn[0];o.pitch = turn[1];o.roll = turn[2];
                var b = Geometry.shape(new Point(0, 0, 0), o);
                assertFalse(leaks(b, r + 4), "pixel perfect t=" + t + " r=" + r + " turn=" + java.util.Arrays.toString(turn) + " leaks");
            }
        }
    }

    @Test
    void sealingAddsOnlyAFewBlocks() {
        for (int t : new int[]{1, 3}) {
            var plain = sphere(Options.ShapeKind.BRUSH, 20, t);
            var turned = sphere(Options.ShapeKind.BRUSH, 20, t);
            turned.yaw = 17;turned.pitch = 9;
            int a = Geometry.shape(new Point(0, 0, 0), plain).size(), b = Geometry.shape(new Point(0, 0, 0), turned).size();
            assertTrue(Math.abs(b - a) < a * 0.15, "t=" + t + " plain " + a + " turned " + b);
        }
    }

    @Test
    void turnedWallKeepsItsThickness() {
        // A wall of one block is one block thick through the poles, turned or not.
        var o = sphere(Options.ShapeKind.BRUSH, 10, 1);
        var plain = Geometry.shape(new Point(0, 0, 0), o);
        o.yaw = 90;
        var quarter = Geometry.shape(new Point(0, 0, 0), o);
        assertTrue(new HashSet<>(quarter).equals(new HashSet<>(plain)), "a quarter turn of a sphere is the same sphere");
    }

    @Test
    void scaledAndTurnedSpheresHaveNoHoles() {
        double[][] cases = {{1, 1, 1, 0, 0, 0}, {1.5, 1, 1, 0, 0, 0}, {1, 1, 1, 30, 0, 0}, {1.5, 1, 1, 30, 20, 10}, {1, 1, 1, 45, 45, 45}};
        for (var c : cases) for (int r : new int[]{8, 20}) {
            var o = sphere(Options.ShapeKind.SPHERE, r, 1);
            o.sizeX = c[0];o.sizeY = c[1];o.sizeZ = c[2];o.yaw = c[3];o.pitch = c[4];o.roll = c[5];
            var b = Geometry.shape(new Point(0, 0, 0), o);
            assertFalse(leaks(b, (int) (r * 2.5) + 3), "r=" + r + " case=" + java.util.Arrays.toString(c) + " leaks");
        }
    }
}
