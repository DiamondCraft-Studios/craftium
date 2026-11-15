package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaskTreeTest {
    @Test
    void roundTripsNestedRulesWithStatePropertiesAndOffsets() {
        String value =
                "all(block(minecraft:oak_stairs[facing=east,half=top]),offset(0,-1,0,any(surface,sky)),not(y(-64,0)))";
        assertEquals(value, MaskTree.parse(value).expression());
    }

    @Test
    void preventsCyclesAndExcessNotInputs() {
        var root = MaskTree.parse("all(not(surface),any(sky,selection))");
        var not = root.children.get(0);
        var any = root.children.get(1);
        assertFalse(MaskTree.move(root, any, not));
        assertFalse(MaskTree.move(root, root, any));
        assertTrue(MaskTree.move(root, not, any));
        assertEquals("all(any(sky,selection,not(surface)))", root.expression());
    }
}
