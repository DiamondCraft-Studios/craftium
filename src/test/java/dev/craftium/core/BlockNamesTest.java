package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlockNamesTest {
    private static final List<String> KNOWN = List.of(
        "minecraft:stone", "minecraft:stone_bricks", "minecraft:stone_brick_stairs", "minecraft:stone_stairs", "minecraft:cobblestone",
        "minecraft:spruce_stairs", "minecraft:spruce_planks", "minecraft:spruce_slab", "minecraft:oak_planks", "minecraft:dark_oak_planks",
        "minecraft:deepslate_tile_stairs", "minecraft:deepslate_tiles", "minecraft:smooth_sandstone_stairs", "minecraft:smooth_stone",
        "minecraft:short_grass", "minecraft:grass_block", "minecraft:red_sandstone", "minecraft:oak_log");

    private static String best(String wanted) { return BlockNames.suggest(wanted, KNOWN, 3).getFirst(); }

    @Test void aMissingPluralIsFound() {
        assertEquals("minecraft:spruce_stairs", best("spruce_stair"));
        assertEquals("minecraft:stone_bricks", best("minecraft:stone_brick"));
        assertEquals("minecraft:oak_planks", best("oak_plank"));
        assertEquals("minecraft:deepslate_tile_stairs", best("deepslate_tile_stair[facing=north]"));
    }

    @Test void anExtraWordOrAShortenedOneIsFound() {
        assertEquals("minecraft:dark_oak_planks", best("dark_oak_wood_planks"));
        assertEquals("minecraft:cobblestone", best("cobble"));
    }

    @Test void aBlockThatDoesNotExistGetsTheNearestRealOnes() {
        var smoothStoneStairs = BlockNames.suggest("smooth_stone_stairs", KNOWN, 3);
        assertTrue(smoothStoneStairs.contains("minecraft:smooth_stone") || smoothStoneStairs.contains("minecraft:stone_stairs"), smoothStoneStairs.toString());
        assertTrue(BlockNames.suggest("grass", KNOWN, 3).containsAll(List.of("minecraft:short_grass", "minecraft:grass_block")));
    }

    @Test void nothingIsSuggestedWhenNothingIsClose() {
        assertTrue(BlockNames.suggest("zzqx", KNOWN, 3).isEmpty());
    }

    @Test void theIdOfANameHasItsNamespaceAndNoState() {
        assertEquals("minecraft:oak_stairs", BlockNames.id("oak_stairs[facing=north]"));
        assertEquals("mymod:thing", BlockNames.id("mymod:thing"));
    }

    @Test void distanceIsLevenshtein() {
        assertEquals(3, BlockNames.distance("kitten", "sitting"));
        assertEquals(0, BlockNames.distance("stone", "stone"));
    }
}
