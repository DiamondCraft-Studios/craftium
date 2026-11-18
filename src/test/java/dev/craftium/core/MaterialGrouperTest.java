package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialGrouperTest {
    private static MaterialGrouper.Slot slot(String material, String kind) {
        return new MaterialGrouper.Slot(material, kind);
    }

    @Test
    void aModsWoodIsOneMaterialWithItsKinds() {
        var out = MaterialGrouper.group(List.of(
                "mod:maple_planks",
                "mod:maple_stairs",
                "mod:maple_slab",
                "mod:maple_fence_gate",
                "mod:maple_log",
                "mod:stripped_maple_log",
                "mod:maple_wood",
                "mod:stripped_maple_wood",
                "mod:maple_leaves"));
        assertEquals(slot("mod:maple", "base"), out.get("mod:maple_planks"));
        assertEquals(slot("mod:maple", "stairs"), out.get("mod:maple_stairs"));
        assertEquals(slot("mod:maple", "fence_gate"), out.get("mod:maple_fence_gate"));
        assertEquals(slot("mod:maple", "log"), out.get("mod:maple_log"));
        assertEquals(slot("mod:maple", "stripped_log"), out.get("mod:stripped_maple_log"));
        assertEquals(slot("mod:maple", "stripped_wood"), out.get("mod:stripped_maple_wood"));
        assertEquals(slot("mod:maple", "leaves"), out.get("mod:maple_leaves"));
    }

    @Test
    void aBlockWithNoKindJoinsTheMaterialItsNameMatches() {
        var out = MaterialGrouper.group(List.of(
                "mod:stone_bricks",
                "mod:stone_brick_stairs",
                "mod:cobblestone",
                "mod:cobblestone_wall",
                "mod:copper_block",
                "mod:copper_slab"));
        assertEquals(slot("mod:stone_brick", "base"), out.get("mod:stone_bricks")); // plural of the material
        assertEquals(slot("mod:cobblestone", "base"), out.get("mod:cobblestone")); // the material's own name
        assertEquals(slot("mod:copper", "base"), out.get("mod:copper_block")); // with _block on it
    }

    @Test
    void aBlockThatIsNotAMaterialJoinsNothing() {
        var out = MaterialGrouper.group(List.of("mod:glass", "mod:maple_stairs", "mod:torch", "mod:maple_wall_torch"));
        assertFalse(out.containsKey("mod:glass"));
        assertFalse(out.containsKey("mod:torch"));
        assertFalse(out.containsKey("mod:maple_wall_torch")); // wall in the middle of the name is not a wall
    }

    @Test
    void twoModsNeverShareAMaterial() {
        var out = MaterialGrouper.group(List.of("a:fir_planks", "a:fir_stairs", "b:fir_planks", "b:fir_slab"));
        assertEquals("a:fir", out.get("a:fir_stairs").material());
        assertEquals("b:fir", out.get("b:fir_slab").material());
    }

    @Test
    void theFirstBlockToMatchTakesTheBaseAndASecondIsLeftOut() {
        var out = MaterialGrouper.group(List.of("mod:marble", "mod:marbles", "mod:marble_slab"));
        assertEquals(slot("mod:marble", "base"), out.get("mod:marble"));
        assertFalse(out.containsKey("mod:marbles"));
    }

    @Test
    void strippedOnlyAppliesToLogsAndWood() {
        var out = MaterialGrouper.group(List.of("mod:stripped_oak_stairs", "mod:stripped_oak_log"));
        assertNull(out.get("mod:stripped_oak_stairs"));
        assertEquals(slot("mod:oak", "stripped_log"), out.get("mod:stripped_oak_log"));
    }
}
