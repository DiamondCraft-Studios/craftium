package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class BrushRulesSculptTest {
    private static BrushRules.Cells world(Predicate<Point> solid) {
        return new BrushRules.Cells() {
            public boolean solid(Point p) {
                return solid.test(p);
            }

            public boolean air(Point p) {
                return !solid.test(p);
            }
        };
    }

    /** A 45° staircase slope rising toward -x: every step is one block across and one block up. */
    private static final Predicate<Point> STAIRS = p -> p.y() <= -p.x();

    private static final Predicate<Point> FLAT = p -> p.y() <= 0;

    private static List<Point> ball(Point c, int r) {
        var cells = new ArrayList<Point>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) if (x * x + y * y + z * z <= r * r) cells.add(c.add(x, y, z));
        return cells;
    }

    private static Options options(double strength, boolean invert) {
        var o = new Options();
        o.tool = Tool.SCULPT_DRAW;
        o.strength = strength;
        o.invert = invert;
        // So wide that the falloff leaves the strength whole near the centre.
        o.radius = 10_000;
        return o;
    }

    private static Set<Point> sculpted(List<Point> cells, Point c, Options o, BrushRules.Cells w) {
        var direction = BrushRules.sculptDirection(cells, new Point(0, 1, 0), o, w);
        var all = new HashSet<Point>();
        for (Point p : cells) all.addAll(BrushRules.sculpt(p, c, direction, o, w));
        return all;
    }

    @Test
    void theWholeDabSharesTheSlopesDirection() {
        var w = world(STAIRS);
        var d = BrushRules.sculptDirection(ball(new Point(0, 0, 0), 4), new Point(0, 1, 0), options(2, true), w);
        assertEquals(d.x(), d.y(), 1e-9, "as far out as up on a 45° slope");
        assertTrue(d.x() > 0);
        assertEquals(0, d.z(), 1e-9);
    }

    @Test
    void maskYPushesStraightDown() {
        var o = options(2, true);
        o.maskY = true;
        var d = BrushRules.sculptDirection(ball(new Point(0, 0, 0), 4), new Point(1, 0, 0), o, world(STAIRS));
        assertEquals(new Geometry.Vector(0, 1, 0), d);
    }

    @Test
    void cancelledOpenSidesFallBackToTheClickedFace() {
        // A lone block: its open sides cancel out.
        var d = BrushRules.sculptDirection(
                List.of(new Point(0, 0, 0)), new Point(0, 0, -1), options(1, false), world(new Point(0, 0, 0)::equals));
        assertEquals(new Geometry.Vector(0, 0, -1), d);
    }

    /** Pushed in, a staircase slope loses an even layer: the cut is the same at every step, and no surface block is left as a rib. */
    @Test
    void pushingIntoAStaircaseCutsAnEvenLayer() {
        var w = world(STAIRS);
        var c = new Point(0, 0, 0);
        var cells = ball(c, 12);
        var gone = sculpted(cells, c, options(3, true), w);
        for (Point p : cells) {
            if (Math.abs(p.x()) > 3 || Math.abs(p.y()) > 3 || Math.abs(p.z()) > 3) continue;
            boolean surface =
                    STAIRS.test(p) && !STAIRS.test(p.add(1, 0, 0)) || STAIRS.test(p) && !STAIRS.test(p.add(0, 1, 0));
            if (surface) assertTrue(gone.contains(p), "surface block left standing at " + p);
            // One step down the slope looks the same.
            assertEquals(gone.contains(p), gone.contains(p.add(1, -1, 0)), "uneven cut at " + p);
            // So does the next row across.
            assertEquals(gone.contains(p), gone.contains(p.add(0, 0, 1)), "uneven cut at " + p);
        }
        for (Point p : gone) assertTrue(STAIRS.test(p), "removed air at " + p);
    }

    @Test
    void pullingOutOfAStaircaseAddsAnEvenLayer() {
        var w = world(STAIRS);
        var c = new Point(0, 0, 0);
        var cells = ball(c, 12);
        var added = sculpted(cells, c, options(3, false), w);
        assertFalse(added.isEmpty());
        for (Point p : cells) {
            if (Math.abs(p.x()) > 3 || Math.abs(p.y()) > 3 || Math.abs(p.z()) > 3) continue;
            assertEquals(added.contains(p), added.contains(p.add(1, -1, 0)), "uneven layer at " + p);
        }
        for (Point p : added) assertFalse(STAIRS.test(p), "added onto a solid block at " + p);
    }

    @Test
    void flatGroundMovesByTheStrength() {
        var w = world(FLAT);
        var c = new Point(0, 0, 0);
        var cells = ball(c, 6);
        var gone = sculpted(cells, c, options(3, true), w);
        assertTrue(gone.containsAll(List.of(new Point(0, 0, 0), new Point(0, -1, 0), new Point(0, -2, 0))));
        assertFalse(gone.contains(new Point(0, -3, 0)), "three layers, not four");
        var added = sculpted(cells, c, options(3, false), w);
        assertTrue(added.containsAll(List.of(new Point(0, 1, 0), new Point(0, 2, 0), new Point(0, 3, 0))));
        assertFalse(added.contains(new Point(0, 4, 0)));
    }

    @Test
    void pushingInStopsAtTheFarSide() {
        // A floor one block thick: pushed in by 3, only the floor goes, not the ground under the gap.
        var w = world(p -> p.y() == 0 || p.y() == -3);
        var c = new Point(0, 0, 0);
        var gone = sculpted(ball(c, 2), c, options(3, true), w);
        for (Point p : gone) assertEquals(0, p.y(), "reached through to " + p);
    }

    @Test
    void aSlantedLineLeavesNoGaps() {
        var d = new Geometry.Vector(1, 1, 0).scale(1 / Math.sqrt(2));
        var path = BrushRules.traverse(new Point(0, 0, 0), d, 5);
        var previous = new Point(0, 0, 0);
        for (Point p : path) {
            int steps =
                    Math.abs(p.x() - previous.x()) + Math.abs(p.y() - previous.y()) + Math.abs(p.z() - previous.z());
            assertEquals(1, steps, "jumped from " + previous + " to " + p);
            previous = p;
        }
        assertTrue(path.size() >= 6);
    }
}
