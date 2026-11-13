package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureSettingsTest {
    @Test void aBlueprintIsAFormatOfItsOwn() {
        var s = new StructureSettings();
        s.format = StructureSettings.BLUEPRINT;
        s.validate();
        assertTrue(s.blueprint());
        assertFalse(s.bedrock());
        assertFalse(s.schematic());
    }

    @Test void anUnknownFormatFallsBackToJava() {
        var s = new StructureSettings();
        s.format = "nonsense";
        s.validate();
        assertEquals(StructureSettings.JAVA, s.format);
    }

    @Test void aBuildObjectIdCannotBeNegative() {
        var s = new StructureSettings();
        s.objectId = -4;
        s.validate();
        assertEquals(0, s.objectId);
        s.objectId = 7;
        s.validate();
        assertEquals(7, s.objectId);
    }
}
