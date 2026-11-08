package dev.craftium.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertyValuesTest {
    private static PropertyValues values() {
        return new PropertyValues(List.of(
            PropertySpec.number("scale", "Scale", 0.5, 4, 1),
            PropertySpec.integer("count", "Count", 1, 8, 3),
            PropertySpec.toggle("hollow", "Hollow", true),
            PropertySpec.choice("axis", "Axis", List.of("X", "Y", "Z"), 2)));
    }

    @Test void everySettingStartsOnItsDeclaredValue() {
        var v = values();
        assertEquals(1, v.number("scale"));
        assertEquals(3, v.integer("count"));
        assertTrue(v.toggled("hollow"));
        assertEquals(2, v.choice("axis"));
        assertEquals("Z", v.choiceName("axis"));
    }

    @Test void aNumberIsHeldToItsRangeAndAWholeNumberIsRounded() {
        var v = values();
        v.set("scale", 99);
        assertEquals(4, v.number("scale"));
        v.set("scale", -1);
        assertEquals(0.5, v.number("scale"));
        v.set("count", 4.6);
        assertEquals(5, v.integer("count"));
        v.set("count", 100);
        assertEquals(8, v.integer("count"));
    }

    @Test void aValueThatIsNotANumberChangesNothing() {
        var v = values();
        v.set("scale", Double.NaN);
        assertEquals(1, v.number("scale"));
    }

    @Test void aChoiceWrapsRoundInBothDirections() {
        var v = values();
        v.step("axis", 1);
        assertEquals("X", v.choiceName("axis"));
        v.step("axis", -1);
        assertEquals("Z", v.choiceName("axis"));
        v.set("axis", 7);
        assertEquals(1, v.choice("axis"));
    }

    @Test void resetPutsASettingBackAndASnapshotIsACopy() {
        var v = values();
        v.set("scale", 3);
        var snapshot = v.snapshot();
        v.reset("scale");
        assertEquals(3.0, snapshot.get("scale"));
        assertEquals(1.0, v.snapshot().get("scale"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("scale", 1.0));
    }

    @Test void aSettingThatIsNotDeclaredIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> values().number("missing"));
        assertThrows(IllegalArgumentException.class, () -> values().set("missing", 1));
    }

    @Test void twoSettingsWithTheSameIdAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PropertyValues(List.of(PropertySpec.toggle("a", "A", false), PropertySpec.toggle("a", "B", true))));
    }
}
