package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OutlineMathTest {
    private static final int[][] FACES = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private static Set<Point> solid(BrushMath.Shape shape, int radius) {
        return new HashSet<>(BrushMath.brush(new Point(0,0,0), radius, shape, false));
    }
    private static Set<Point> thin(BrushMath.Shape shape, int radius, int thickness) {
        return new HashSet<>(BrushMath.brush(new Point(0,0,0), radius, shape, true, thickness, true));
    }

    @Test void theOutlineIsPartOfTheShapeAndNeverThickerThanItsFaceBoundary() {
        for (var shape : BrushMath.Shape.values()) for (int r : new int[]{1, 2, 5, 9}) {
            var s = solid(shape, r); var outline = thin(shape, r, 1);
            assertTrue(s.containsAll(outline), shape + " " + r);
            for (Point p : outline) assertTrue(Arrays.stream(FACES).anyMatch(d -> !s.contains(p.add(d[0], d[1], d[2]))), shape + " " + r + " keeps a buried block");
        }
    }

    @Test void noPathFromOutsideReachesTheBlocksTheOutlineLeavesOut() {
        for (var shape : BrushMath.Shape.values()) for (int r : new int[]{3, 6, 10}) {
            if (shape == BrushMath.Shape.PLANE || shape == BrushMath.Shape.DISK) continue; // a sheet's outline is its edge, and its face is open by nature
            var s = solid(shape, r); var outline = thin(shape, r, 1);
            // Fill from outside through every cell that is not part of the outline, a face at a time.
            var seen = new HashSet<Point>(); var queue = new ArrayDeque<Point>();
            var start = new Point(-r - 2, -r - 2, -r - 2); seen.add(start); queue.add(start);
            while (!queue.isEmpty()) {
                var p = queue.poll();
                for (int[] d : FACES) {
                    var n = p.add(d[0], d[1], d[2]);
                    if (Math.abs(n.x()) > r + 2 || Math.abs(n.y()) > r + 2 || Math.abs(n.z()) > r + 2 || outline.contains(n) || !seen.add(n)) continue;
                    queue.add(n);
                }
            }
            for (Point p : s) if (!outline.contains(p)) assertFalse(seen.contains(p), shape + " " + r + " leaks at " + p.coordinates());
        }
    }

    @Test void aBoxKeepsItsEdgesAndCorners() {
        int r = 4;
        assertEquals(9 * 9 * 9 - 7 * 7 * 7, thin(BrushMath.Shape.CUBE, r, 1).size());
        assertTrue(thin(BrushMath.Shape.CUBE, r, 1).containsAll(List.of(new Point(r, r, r), new Point(-r, r, -r), new Point(r, -r, 0))));
    }

    @Test void theOutlineMirrorsAsTheShapeDoes() {
        var sphere = thin(BrushMath.Shape.SPHERE, 8, 1);
        for (Point p : sphere) {
            assertTrue(sphere.contains(new Point(-p.x(), p.y(), p.z())) && sphere.contains(new Point(p.x(), -p.y(), p.z())) && sphere.contains(new Point(p.x(), p.y(), -p.z())) && sphere.contains(new Point(p.z(), p.y(), p.x())));
        }
    }

    @Test void aThickerOutlineContainsTheThinnerOneAndGrowsWithThickness() {
        var one = thin(BrushMath.Shape.SPHERE, 9, 1); var two = thin(BrushMath.Shape.SPHERE, 9, 2); var three = thin(BrushMath.Shape.SPHERE, 9, 3);
        assertTrue(two.containsAll(one) && three.containsAll(two));
        assertTrue(one.size() < two.size() && two.size() < three.size());
    }

    @Test void aCircleIsOneBlockWideInEachColumnOrRow() {
        var ring = thin(BrushMath.Shape.DISK, 10, 1);
        // Seen from above a pixel perfect circle has no block with two neighbours on opposite faces that already link it up.
        int doubled = 0;
        for (Point p : ring) {
            boolean xn = ring.contains(p.add(1, 0, 0)) || ring.contains(p.add(-1, 0, 0)), zn = ring.contains(p.add(0, 0, 1)) || ring.contains(p.add(0, 0, -1));
            boolean corner = ring.contains(p.add(1, 0, 0)) && ring.contains(p.add(0, 0, 1)) || ring.contains(p.add(1, 0, 0)) && ring.contains(p.add(0, 0, -1))
                || ring.contains(p.add(-1, 0, 0)) && ring.contains(p.add(0, 0, 1)) || ring.contains(p.add(-1, 0, 0)) && ring.contains(p.add(0, 0, -1));
            if (xn && zn && corner) doubled++;
        }
        var plain = new HashSet<>(BrushMath.brush(new Point(0,0,0), 10, BrushMath.Shape.DISK, true));
        assertTrue(ring.size() <= plain.size());
        assertTrue(doubled <= 8, "a pixel perfect circle has few L shaped corners, found " + doubled);
    }
}
