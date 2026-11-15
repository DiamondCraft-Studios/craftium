package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ConvexHullTest {
    @Test
    void reverseOrderedLinePreservesBothEnds() {
        assertEquals(
                9,
                ConvexHull.fill(List.of(new Point(8, 0, 0), new Point(0, 0, 0))).size());
    }

    @Test
    void randomHullsContainEveryInputPoint() {
        for (int seed = 0; seed < 20; seed++) {
            var random = new Random(seed);
            var points = new HashSet<Point>();
            for (int i = 0; i < 50; i++)
                points.add(new Point(random.nextInt(11), random.nextInt(11), random.nextInt(11)));
            assertTrue(ConvexHull.fill(points).containsAll(points));
        }
    }

    @Test
    void triangleDoesNotExpandToCoplanarBoundingRectangle() {
        var points = ConvexHull.fill(List.of(new Point(0, 0, 0), new Point(8, 0, 0), new Point(0, 8, 0)));
        assertTrue(points.contains(new Point(3, 3, 0)));
        assertFalse(points.contains(new Point(7, 7, 0)));
    }

    @Test
    void tetrahedronContainsExactlyItsIntegerInterior() {
        var points = ConvexHull.fill(
                List.of(new Point(0, 0, 0), new Point(8, 0, 0), new Point(0, 8, 0), new Point(0, 0, 8)));
        for (int x = 0; x <= 8; x++)
            for (int y = 0; y <= 8; y++)
                for (int z = 0; z <= 8; z++) assertEquals(x + y + z <= 8, points.contains(new Point(x, y, z)));
    }

    @Test
    void manySurfacePointsProduceTheCompleteCube() {
        var points = new ArrayList<Point>();
        for (int x = 0; x <= 16; x++)
            for (int y = 0; y <= 16; y++)
                for (int z = 0; z <= 16; z++)
                    if (x == 0 || y == 0 || z == 0 || x == 16 || y == 16 || z == 16) points.add(new Point(x, y, z));
        Collections.shuffle(points, new Random(42));
        assertEquals(17 * 17 * 17, ConvexHull.fill(points).size());
    }
}
