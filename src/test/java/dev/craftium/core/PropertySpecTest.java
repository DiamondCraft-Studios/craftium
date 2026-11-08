package dev.craftium.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertySpecTest {
    @Test void aSettingIsDescribedByItsKindAndItsStart() {
        assertEquals(PropertySpec.Kind.NUMBER, PropertySpec.number("a", "A", 0, 1, 0.5).kind());
        assertEquals(PropertySpec.Kind.INTEGER, PropertySpec.integer("a", "A", 1, 5, 2).kind());
        assertEquals(1, PropertySpec.toggle("a", "A", true).number());
        assertEquals(List.of("x", "y"), PropertySpec.choice("a", "A", List.of("x", "y"), 1).choices());
    }

    @Test void anIdIsLowerCaseLettersDigitsAndUnderscoresStartingWithALetter() {
        for (String ok : new String[]{"a", "wall_height", "n2"}) assertDoesNotThrow(() -> PropertySpec.toggle(ok, "A", false), ok);
        for (String bad : new String[]{"", "A", "2a", "_a", "a-b", "a b", "ä"}) assertThrows(IllegalArgumentException.class, () -> PropertySpec.toggle(bad, "A", false), bad);
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.toggle(null, "A", false));
    }

    @Test void aLabelIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.toggle("a", " ", false));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.toggle("a", null, false));
    }

    @Test void aRangeNeedsItsLowestValueBelowItsHighestAndTheStartInside() {
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.number("a", "A", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.number("a", "A", 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.number("a", "A", 0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.number("a", "A", 0, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.number("a", "A", 0, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PropertySpec("a", "A", PropertySpec.Kind.INTEGER, 0, 5, 1.5, List.of()));
    }

    @Test void aChoiceNeedsTwoToSixteenNamedChoicesAndAStartThatIsOneOfThem() {
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.choice("a", "A", List.of("x"), 0));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.choice("a", "A", List.of("x", " "), 0));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.choice("a", "A", List.of("x", "y"), 2));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.choice("a", "A", List.of("x", "y"), -1));
        assertThrows(IllegalArgumentException.class, () -> PropertySpec.choice("a", "A", java.util.Collections.nCopies(PropertySpec.MAX_CHOICES + 1, "x"), 0));
        assertDoesNotThrow(() -> PropertySpec.choice("a", "A", java.util.Collections.nCopies(PropertySpec.MAX_CHOICES, "x"), 0));
    }

    @Test void theBuiltInToolsDeclareNoSettingsOfTheirOwn() {
        for (Tool tool : Tool.values()) assertTrue(tool.spec().properties().isEmpty(), tool.name());
    }
}
