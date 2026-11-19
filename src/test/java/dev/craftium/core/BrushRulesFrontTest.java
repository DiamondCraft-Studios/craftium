package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class BrushRulesFrontTest {
    /** A platform one block thick at y = 0, a wall under it at x = -2, and a lone block standing on the platform at (1, 1, 0). */
    private static final Predicate<Point> SOLID =
            p -> p.y() == 0 || p.x() == -2 && p.y() < 0 || p.equals(new Point(1, 1, 0));

    private static final BrushRules.Cells WORLD = new BrushRules.Cells() {
        public boolean solid(Point p) {
            return SOLID.test(p);
        }

        public boolean air(Point p) {
            return !SOLID.test(p);
        }
    };

    private static List<Point> ball(Point c, int r) {
        var cells = new ArrayList<Point>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) if (x * x + y * y + z * z <= r * r) cells.add(c.add(x, y, z));
        return cells;
    }

    @Test
    void brushUnderAThinPlatformKeepsBelowIt() {
        var c = new Point(0, 0, 0);
        var front = BrushRules.front(ball(c, 3), c, new Point(0, -1, 0), WORLD, 2);
        assertNotNull(front);
        assertTrue(front.contains(new Point(0, -1, 0)), "the air under the platform");
        assertTrue(front.contains(new Point(-1, -1, 0)), "the alcove corner");
        assertTrue(front.contains(new Point(0, 0, 0)), "the platform itself");
        assertTrue(front.contains(new Point(-2, -1, 0)), "the wall, joined to the platform");
        assertFalse(front.contains(new Point(0, 1, 0)), "the air on top of the platform");
        assertFalse(front.contains(new Point(1, 1, 0)), "a block standing on top of the platform");
    }

    @Test
    void weldDoesNotFillAboveAThinPlatform() {
        var o = new Options();
        var c = new Point(0, 0, 0);
        var cells = ball(c, 3);
        var front = BrushRules.front(cells, c, new Point(0, -1, 0), WORLD, 2);
        for (Point p : cells)
            if (front.contains(p) && BrushRules.weld(p, o, front.filled(WORLD)))
                assertTrue(p.y() < 0, "welded above at " + p);
    }

    /** An upside-down L one block thick: the air above the platform and behind the wall must not keep Weld out of the corner. */
    @Test
    void weldFillsTheCornerUnderAThinUpsideDownL() {
        var o = new Options();
        var c = new Point(0, 0, 0);
        var corner = new Point(-1, -1, 0);
        var cells = ball(c, 4);
        var front = BrushRules.front(cells, c, new Point(0, -1, 0), WORLD, BrushRules.reach(o));
        assertFalse(BrushRules.weld(corner, o, WORLD), "the far side's air thins out the corner");
        assertTrue(BrushRules.weld(corner, o, front.filled(WORLD)), "the corner, measured as if the L were thick");
        assertFalse(BrushRules.weld(new Point(3, -1, 0), o, front.filled(WORLD)), "a flat underside does not grow");
    }

    @Test
    void anAimedFaceThatIsNotSolidAgainstOpenHasNoLimit() {
        var c = new Point(0, 5, 0);
        assertNull(BrushRules.front(ball(c, 2), c, new Point(0, 1, 0), WORLD, 2));
        assertNull(BrushRules.front(ball(c, 2), c, new Point(0, 0, 0), WORLD, 2));
    }
}
