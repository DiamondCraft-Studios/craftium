package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildShapesTest {
    private static final BuildShapes.RoofBlocks SPRUCE = new BuildShapes.RoofBlocks("spruce_stairs", "spruce_planks", "spruce_slab");

    private static Map<Point, String> map(List<BuildShapes.Placed> placed) {
        var out = new LinkedHashMap<Point, String>();
        for (var p : placed) assertNull(out.put(p.at(), p.block()), "placed twice at " + p.at());
        return out;
    }

    private static long count(Map<Point, String> blocks, String part) { return blocks.values().stream().filter(b -> b.contains(part)).count(); }

    @Test void gableRowsFaceTheRidgeAndMeetAtABlockRidgeWithACap() {
        // Footprint 7 along x by 5 along z, overhang 1: rows of 9 on each side for three courses, the ridge at z = 2 on the third.
        var roof = map(BuildShapes.roof("gable", 0, 0, 6, 4, 10, 1, null, null, SPRUCE));
        assertEquals(27, count(roof, "facing=south"));
        assertEquals(27, count(roof, "facing=north"));
        assertEquals(54, count(roof, "shape=straight"));
        for (int x = -1; x <= 7; x++) {
            assertEquals("spruce_planks", roof.get(new Point(x, 12, 2)), "ridge at x " + x);
            assertEquals("spruce_slab[type=bottom]", roof.get(new Point(x, 13, 2)));
        }
        // The gable ends are filled inside the footprint and nowhere past it.
        assertEquals("spruce_planks", roof.get(new Point(0, 11, 2)));
        assertEquals("spruce_planks", roof.get(new Point(6, 10, 1)));
        for (var p : roof.keySet()) {
            assertTrue(p.x() >= -1 && p.x() <= 7 && p.z() >= -1 && p.z() <= 5, "outside the overhang: " + p);
            if (roof.get(p).equals("spruce_planks") && p.y() < 12) assertTrue(p.x() >= 0 && p.x() <= 6 && p.z() >= 0 && p.z() <= 4, "fill outside the walls: " + p);
        }
    }

    @Test void theRidgeRunsAlongTheLongerSideUnlessToldOtherwise() {
        var along = map(BuildShapes.roof("gable", 0, 0, 2, 8, 0, 0, null, null, SPRUCE));
        assertTrue(count(along, "facing=east") > 0 && count(along, "facing=south") == 0);
        var across = map(BuildShapes.roof("gable", 0, 0, 2, 8, 0, 0, "x", null, SPRUCE));
        assertTrue(count(across, "facing=south") > 0 && count(across, "facing=east") == 0);
    }

    @Test void hipCornersAreOuterStairsAndTheTopIsCapped() {
        var roof = map(BuildShapes.roof("hip", 0, 0, 4, 4, 0, 0, null, null, SPRUCE));
        assertEquals(24, count(roof, "spruce_stairs"));
        assertEquals(8, count(roof, "shape=outer"));
        assertEquals(0, count(roof, "shape=inner"));
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) assertTrue(roof.get(new Point(c[0], 0, c[1])).contains("shape=outer"), "corner " + Arrays.toString(c));
        assertEquals("spruce_planks", roof.get(new Point(2, 1, 2)));
        assertEquals("spruce_slab[type=bottom]", roof.get(new Point(2, 2, 2)));
    }

    @Test void stairShapesFollowTheGameRule() {
        // An L of stairs, both rows facing in toward the corner of a roof: the corner stair is an outer one.
        var stairs = new LinkedHashMap<Point, BuildShapes.Facing>();
        stairs.put(new Point(0, 0, 0), BuildShapes.Facing.SOUTH);
        stairs.put(new Point(1, 0, 0), BuildShapes.Facing.SOUTH);
        stairs.put(new Point(0, 0, 1), BuildShapes.Facing.EAST);
        var shapes = BuildShapes.shapes(stairs, "bottom");
        assertTrue(shapes.get(new Point(0, 0, 0)).startsWith("outer"));
        assertEquals("straight", shapes.get(new Point(1, 0, 0)));
    }

    @Test void shedRisesOneBlockPerRowTowardItsHighSide() {
        var roof = map(BuildShapes.roof("shed", 0, 0, 2, 3, 5, 0, null, "north", SPRUCE));
        assertEquals(12, count(roof, "facing=north"));
        for (int k = 0; k < 4; k++) assertTrue(roof.get(new Point(1, 5 + k, 3 - k)).startsWith("spruce_stairs"), "row " + k);
        // The high wall under the top course is filled.
        assertEquals("spruce_planks", roof.get(new Point(1, 7, 0)));
    }

    @Test void aFlightClimbsTheWayItFacesAndFillsBelowEachStep() {
        var up = map(BuildShapes.stairs(new Point(0, 0, 0), new Point(0, 3, 3), 2, "oak_stairs", "stone"));
        assertEquals(8, count(up, "facing=south"));
        assertEquals(12, count(up, "stone"));
        assertTrue(up.containsKey(new Point(1, 3, 3)));
        var down = map(BuildShapes.stairs(new Point(0, 3, 0), new Point(0, 0, 3), 1, "oak_stairs", null));
        assertEquals(4, count(down, "facing=north"));
        assertThrows(IllegalArgumentException.class, () -> BuildShapes.stairs(new Point(0, 0, 0), new Point(0, 2, 3), 1, "oak_stairs", null));
        assertThrows(IllegalArgumentException.class, () -> BuildShapes.stairs(new Point(0, 0, 0), new Point(0, 1, 1), 1, "oak_stairs[facing=east]", null));
    }
}
