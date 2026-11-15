package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ScriptTemplatesTest {
    @Test
    void acceptsMultipleBlockProperties() {
        var r = ScriptTemplates.expand("return $blockState(Material,oak_stairs[facing=east,half=top])$", Map.of());
        assertEquals("return blocks[\"oak_stairs[facing=east,half=top]\"]", r.source());
    }

    @Test
    void expandsNumbersBooleansAndBlocks() {
        var result = ScriptTemplates.expand(
                "$once$ local h=$int(Height,5,1,10)$ local t=$float(Threshold,0.5,0,1)$ return $boolean(On,true)$ and $blockState(Material,stone)$",
                Map.of("Height", "99"));
        assertTrue(result.once());
        assertTrue(result.source().contains("h=10"));
        assertTrue(result.source().contains("t=0.5"));
        assertTrue(result.source().contains("blocks[\"stone\"]"));
        assertEquals(4, result.variables().size());
    }

    @Test
    void rejectsLuaInjectionInVariableValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptTemplates.expand("$int(Height,5,1,10)$", Map.of("Height", "1; while true do end")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptTemplates.expand(
                        "$blockState(Material,stone)$", Map.of("Material", "stone\"] error() --")));
    }

    @Test
    void rejectsConflictingDefinitions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScriptTemplates.expand("$int(Size,1,0,5)$ $float(Size,2,0,5)$", Map.of()));
    }
}
