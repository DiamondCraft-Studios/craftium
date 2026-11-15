package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PaletteAndBuilderTest {
    @Test
    void splitsFullStatesWithoutLosingProperties() {
        assertEquals(
                List.of("oak_stairs[facing=east,half=top]", "minecraft:stone"),
                MaterialList.split("oak_stairs[facing=east,half=top],minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> MaterialList.split("stone[foo=x"));
    }

    @Test
    void weightedPaletteRetainsEndpointsAndChangesTransitions() {
        assertEquals(0, MaterialList.index(0, List.of(1d, 9d, 1d), 3, .5, 0));
        assertEquals(2, MaterialList.index(1, List.of(1d, 9d, 1d), 3, .5, 0));
        assertEquals(1, MaterialList.index(.3, List.of(1d, 9d, 1d), 3, .5, 0));
        assertEquals(0, MaterialList.index(.3, List.of(9d, 1d, 1d), 3, .5, 0));
    }

    @Test
    void rectangularBuilderRotationIsBijectiveAndStaysInBounds() {
        var size = new Point(3, 2, 5);
        for (int turns = 0; turns < 4; turns++)
            for (int flip = -1; flip < 3; flip++) {
                var transformed = new HashSet<Point>();
                for (Point p : Selection.box(new Point(0, 0, 0), new Point(2, 1, 4))) {
                    Point result = BuilderTransform.apply(p, size, turns, flip);
                    assertTrue(result.x() >= 0 && result.x() < (turns % 2 == 0 ? 3 : 5));
                    assertTrue(result.z() >= 0 && result.z() < (turns % 2 == 0 ? 5 : 3));
                    transformed.add(result);
                }
                assertEquals(30, transformed.size());
            }
    }
}
