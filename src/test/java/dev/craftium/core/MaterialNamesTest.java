package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialNamesTest {
    @Test void woodGivesItsStairsSlabsAndPlanks() {
        assertEquals("minecraft:spruce_stairs", MaterialNames.candidates("spruce@stairs").getFirst());
        assertTrue(MaterialNames.candidates("spruce@slab").contains("minecraft:spruce_slab"));
        assertTrue(MaterialNames.candidates("spruce@block").contains("minecraft:spruce_planks"));
        assertTrue(MaterialNames.candidates("oak_planks@slab").contains("minecraft:oak_slab"));
        assertTrue(MaterialNames.candidates("crimson@log").contains("minecraft:crimson_stem"));
    }

    @Test void pluralStoneNamesDropTheirS() {
        assertTrue(MaterialNames.candidates("stone_bricks@stairs").contains("minecraft:stone_brick_stairs"));
        assertTrue(MaterialNames.candidates("stone_bricks@wall").contains("minecraft:stone_brick_wall"));
        assertTrue(MaterialNames.candidates("deepslate_tiles@slab").contains("minecraft:deepslate_tile_slab"));
        assertEquals("minecraft:stone_bricks", MaterialNames.candidates("stone_bricks@block").getFirst());
        assertTrue(MaterialNames.candidates("stone_bricks@mossy").contains("minecraft:mossy_stone_bricks"));
        assertTrue(MaterialNames.candidates("stone_bricks@cracked").contains("minecraft:cracked_stone_bricks"));
    }

    @Test void theNamespaceIsKeptAndTheStateIsLeftOut() {
        assertEquals("mymod:ash_stairs", MaterialNames.candidates("mymod:ash@stairs[facing=east]").getFirst());
        assertTrue(MaterialNames.isReference("spruce@stairs[facing=east]"));
        assertFalse(MaterialNames.isReference("spruce_stairs"));
    }

    @Test void anUnknownKindIsRefusedWithTheKindsThereAre() {
        var e = assertThrows(IllegalArgumentException.class, () -> MaterialNames.candidates("spruce@stair"));
        assertTrue(e.getMessage().contains("stairs, slab"), e.getMessage());
    }
}
