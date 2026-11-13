package dev.craftium.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OptionDiffTest {
    @Test void nothingChangedIsEmpty() {
        var o = new Options();
        var s = OptionDiff.snapshot(o);
        assertTrue(OptionDiff.between(s, OptionDiff.snapshot(o)).isEmpty());
    }

    @Test void findsJustTheSettingsThatChanged() {
        var o = new Options();
        var before = OptionDiff.snapshot(o);
        o.radius = o.radius + 4;
        o.hollow = !o.hollow;
        var change = OptionDiff.between(before, OptionDiff.snapshot(o));
        assertEquals(Set.of("radius", "hollow"), change.after().keySet());
        assertEquals(before.get("radius"), change.before().get("radius"));
        assertEquals(o.radius, change.after().get("radius"));
    }

    @Test void applyingTheBeforeValuesPutsTheSettingsBack() {
        var o = new Options();
        var before = OptionDiff.snapshot(o);
        int radius = o.radius;
        o.radius = radius + 3;
        var change = OptionDiff.between(before, OptionDiff.snapshot(o));
        OptionDiff.apply(o, change.before());
        assertEquals(radius, o.radius);
        OptionDiff.apply(o, change.after());
        assertEquals(radius + 3, o.radius);
    }

    @Test void aSnapshotKeepsItsOwnCopyOfAList() {
        var o = new Options();
        var snapshot = OptionDiff.snapshot(o);
        o.materials = "minecraft:stone";
        o.maskActive.add("minecraft:dirt");
        var change = OptionDiff.between(snapshot, OptionDiff.snapshot(o));
        assertTrue(change.after().containsKey("maskActive"), "a block added to a list is a change");
        assertFalse(((java.util.List<?>) change.before().get("maskActive")).contains("minecraft:dirt"));
    }

    @Test void valuesAreValidatedWhenPutBack() {
        var o = new Options();
        OptionDiff.apply(o, java.util.Map.of("radius", 1_000_000));
        assertTrue(o.radius <= BrushMath.MAX_RADIUS);
    }
}
