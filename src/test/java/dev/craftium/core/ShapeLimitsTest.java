package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShapeLimitsTest {
    /** The brush as it was before only the cells that can be inside were looked at: every cell of the cube, in the same order. */
    private static List<Point> reference(int radius, BrushMath.Shape shape, boolean hollow, int thickness) {
        int wall = Math.clamp(thickness, 1, radius), inner = radius - wall;
        boolean cut = hollow && inner >= 0;
        var out = new ArrayList<Point>();
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
            if (!BrushMath.contains(shape, x, y, z, radius, BrushMath.DEFAULT_SIDES)) continue;
            if (cut && BrushMath.contains(shape, x, y, z, inner, BrushMath.DEFAULT_SIDES)) continue;
            out.add(new Point(x, y, z));
        }
        return out;
    }

    @Test void lookingOnlyWhereAShapeCanBeGivesTheSameBlocksInTheSameOrder() {
        for (var shape : BrushMath.Shape.values()) for (int radius : new int[]{1, 2, 5, 13, 24}) for (boolean hollow : new boolean[]{false, true})
            assertEquals(reference(radius, shape, hollow, 2), BrushMath.brush(new Point(0, 0, 0), radius, shape, hollow, 2, false, 0), shape + " " + radius + (hollow ? " hollow" : ""));
    }

    @Test void theEstimateIsCloseToTheBlocksTheShapeMakes() {
        for (var shape : BrushMath.Shape.values()) for (int radius : new int[]{8, 20, 45}) for (boolean hollow : new boolean[]{false, true}) {
            double real = BrushMath.brush(new Point(0, 0, 0), radius, shape, hollow, 2, false, 0, BrushMath.DEFAULT_SIDES, false, Integer.MAX_VALUE).size();
            double guess = ShapeLimits.blocks(shape, radius, hollow, 2, false, 0, BrushMath.DEFAULT_SIDES, false);
            String what = shape + " " + radius + (hollow ? " hollow" : "");
            assertTrue(guess >= real * .94 - 80, what + " guessed " + guess + " for " + real); // never guessed short, or a shape over the limit would pass
            assertTrue(guess <= real * 1.45 + 80, what + " guessed " + guess + " for " + real); // and not so high that a shape is held back for nothing
        }
    }

    @Test void aFlatRingMayBeFarWiderThanAFilledSphere() {
        var sphere = new Options(); sphere.shape = BrushMath.Shape.SPHERE;
        var ring = new Options(); ring.shape = BrushMath.Shape.DISK; ring.hollow = true;
        var disk = new Options(); disk.shape = BrushMath.Shape.DISK;
        assertTrue(ShapeLimits.maxRadius(sphere) < 70);
        assertEquals(BrushMath.MAX_RADIUS, ShapeLimits.maxRadius(ring));
        assertTrue(ShapeLimits.maxRadius(disk) > 300 && ShapeLimits.maxRadius(disk) < BrushMath.MAX_RADIUS);
    }

    @Test void theLargestRadiusFitsAndTheNextDoesNot() {
        for (var shape : new BrushMath.Shape[]{BrushMath.Shape.SPHERE, BrushMath.Shape.CUBE, BrushMath.Shape.DISK, BrushMath.Shape.CYLINDER}) {
            var o = new Options(); o.shape = shape;
            int largest = ShapeLimits.maxRadius(o);
            assertTrue(ShapeLimits.fits(shape, largest, false, 1, false, 0, 6, false), shape.toString());
            if (largest < BrushMath.MAX_RADIUS) assertFalse(ShapeLimits.fits(shape, largest + 1, false, 1, false, 0, 6, false), shape.toString());
        }
    }

    @Test void theBiggestSolidSphereIsBuiltWithinTheBlockLimit() {
        var o = new Options(); o.shape = BrushMath.Shape.SPHERE;
        int largest = ShapeLimits.maxRadius(o);
        assertTrue(BrushMath.brush(new Point(0, 0, 0), largest, BrushMath.Shape.SPHERE, false).size() <= Selection.LIMIT);
    }

    @Test void aHollowSphereAsWideAsTwelveChunksIsAllowed() {
        var o = new Options(); o.shape = BrushMath.Shape.SPHERE; o.hollow = true;
        assertTrue(ShapeLimits.maxRadius(o) >= 192);
    }

    @Test void aShapeTooBigToLookAtIsRefusedAtOnce() {
        long start = System.nanoTime();
        assertThrows(IllegalArgumentException.class, () -> BrushMath.brush(new Point(0, 0, 0), 900, BrushMath.Shape.CUBE, true));
        assertTrue(System.nanoTime() - start < 1_000_000_000L);
    }

    @Test void scaledSizesAreHeldToTheBlockLimit() {
        var o = new Options(); o.shape = BrushMath.Shape.SPHERE; o.radius = ShapeLimits.maxRadius(o); o.sizeX = o.sizeY = o.sizeZ = 8;
        var rows = ShapeSizes.rows(o.shape);
        ShapeSizes.fit(o, rows); ShapeSizes.fitBlocks(o, rows);
        assertTrue(o.sizeX < 8 && o.sizeX >= .1);
    }
}
