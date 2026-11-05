package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceFillTest {
    /** A world of the blocks given, with everything else air. */
    private static SurfaceFill.Cells solid(Set<Point> blocks) {
        return new SurfaceFill.Cells() {
            public boolean air(Point p) { return !blocks.contains(p); }
            public boolean matches(Point p) { return blocks.contains(p); }
        };
    }
    private static Set<Point> slab(int size, int y) {
        var out = new HashSet<Point>();
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) out.add(new Point(x, y, z));
        return out;
    }

    @Test void aFaceIsEveryConnectedBlockWithAirInFront() {
        var floor = slab(4, 0);
        assertEquals(16, SurfaceFill.face(solid(floor), new Point(1, 0, 1), new Point(0, 1, 0), 1000).size());
    }

    @Test void aBlockUnderAnotherIsNotPartOfTheFace() {
        var blocks = slab(3, 0);blocks.add(new Point(1, 1, 1)); // a pillar block on the middle of the floor
        var face = SurfaceFill.face(solid(blocks), new Point(0, 0, 0), new Point(0, 1, 0), 1000);
        assertEquals(8, face.size());
        assertFalse(face.contains(new Point(1, 0, 1)));
    }

    @Test void theFaceStopsAtTheLimit() {
        assertEquals(5, SurfaceFill.face(solid(slab(10, 0)), new Point(5, 0, 5), new Point(0, 1, 0), 5).size());
    }

    @Test void aFloodFillsConnectedSpaceUpToTheLimitAndDirections() {
        var world = new SurfaceFill.Cells() { // open air inside a 5-wide box; the box walls and floor are blocks
            public boolean air(Point p) { return matches(p); }
            public boolean matches(Point p) { return p.x() >= 0 && p.x() < 5 && p.z() >= 0 && p.z() < 5 && p.y() >= 0 && p.y() < 3; }
        };
        assertEquals(75, SurfaceFill.flood(world, new Point(2, 1, 2), 1000, true, true, true, false).size());
        assertEquals(10, SurfaceFill.flood(world, new Point(2, 1, 2), 10, true, true, true, false).size());
        assertEquals(3, SurfaceFill.flood(world, new Point(2, 0, 2), 1000, true, false, false, false).size()); // straight up only
    }
}
