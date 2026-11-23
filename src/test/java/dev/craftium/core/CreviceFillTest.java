package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class CreviceFillTest {
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

    private static List<Point> ball(Point c, int r) {
        var cells = new ArrayList<Point>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) if (x * x + y * y + z * z <= r * r) cells.add(c.add(x, y, z));
        return cells;
    }

    private static Options options(int width, int sides) {
        var o = new Options();
        o.tool = Tool.CREVICE_FILL;
        o.creviceWidth = width;
        o.creviceSides = sides;
        return o;
    }

    private static Set<Point> fill(List<Point> cells, Options o, BrushRules.Cells world) {
        return new HashSet<>(BrushRules.crevices(cells, o, world));
    }

    /** Ground up to y = 0 with a crack {@code width} blocks wide (x from 0) and 4 deep running along z. */
    private static Predicate<Point> crack(int width) {
        return p -> p.y() <= 0 && !(p.x() >= 0 && p.x() < width && p.y() >= -3);
    }

    @Test
    void flatGroundGetsNothing() {
        var cells = ball(new Point(0, 1, 0), 4);
        assertTrue(fill(cells, options(4, 1), world(p -> p.y() <= 0)).isEmpty());
    }

    @Test
    void aCrackFillsRightToTheBottomAndNothingAboveTheSurface() {
        var cells = ball(new Point(0, 1, 0), 4);
        var filled = fill(cells, options(2, 1), world(crack(1)));
        for (Point p : cells)
            if (p.x() == 0 && p.y() >= -3 && p.y() <= 0) assertTrue(filled.contains(p), "crack block " + p);
        for (Point p : filled) assertTrue(p.y() <= 0, "filled above the surface at " + p);
    }

    @Test
    void aCrackWiderThanTheGapWidthIsLeft() {
        var cells = ball(new Point(1, 1, 0), 4);
        assertTrue(fill(cells, options(2, 1), world(crack(3))).isEmpty());
        assertFalse(fill(cells, options(3, 1), world(crack(3))).isEmpty());
    }

    @Test
    void holesSettingSkipsALongCrackButFillsAHole() {
        var cells = ball(new Point(0, 1, 0), 4);
        assertTrue(fill(cells, options(2, 2), world(crack(1))).isEmpty(), "a crack is closed on one axis only");
        Predicate<Point> hole = p -> p.y() <= 0 && !(p.x() == 0 && p.z() == 0 && p.y() >= -3);
        var filled = fill(cells, options(2, 2), world(hole));
        for (int y = -3; y <= 0; y++) assertTrue(filled.contains(new Point(0, y, 0)), "hole block at y " + y);
        assertEquals(4, filled.size());
        assertTrue(fill(cells, options(2, 3), world(hole)).isEmpty(), "a hole open at the top is not a hollow");
    }

    @Test
    void hollowsSettingFillsAVoidClosedAllRound() {
        Predicate<Point> pocket = p -> !(p.x() >= 0 && p.x() <= 1 && p.y() == 0 && p.z() == 0);
        var filled = fill(ball(new Point(0, 0, 0), 3), options(2, 3), world(pocket));
        assertEquals(Set.of(new Point(0, 0, 0), new Point(1, 0, 0)), filled);
    }

    /**
     * A one-block pit in the ground with a block hanging two above it: the pit fills first, and then the block between the pit and the
     * hanging one is closed in from below and above, so it fills in the next pass.
     */
    @Test
    void laterPassesFillWhatEarlierOnesClosedIn() {
        var pit = new Point(0, 0, 0);
        var hanging = new Point(0, 2, 0);
        Predicate<Point> solid = p -> p.y() <= 0 && !p.equals(pit) || p.equals(hanging);
        var filled = BrushRules.crevices(ball(new Point(0, 1, 0), 3), options(1, 1), world(solid));
        assertEquals(List.of(pit, new Point(0, 1, 0)), filled, "the pit, then the block over it, in that order");
    }

    @Test
    void theResultDoesNotDependOnTheOrderOfTheBrushBlocks() {
        var cells = ball(new Point(0, 1, 0), 4);
        var reversed = new ArrayList<>(cells);
        java.util.Collections.reverse(reversed);
        var w = world(crack(2));
        assertEquals(fill(cells, options(2, 1), w), fill(reversed, options(2, 1), w));
    }
}
