package dev.craftium.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.*;

class PropertyFormTest {
    private static final List<PropertySpec> SPECS = List.of(
        PropertySpec.number("scale", "Scale", 0.5, 4, 1),
        PropertySpec.integer("count", "Count", 1, 8, 3),
        PropertySpec.toggle("hollow", "Hollow", false),
        PropertySpec.choice("axis", "Axis", List.of("X", "Y", "Z"), 1));

    /** A panel that writes down what it is asked to make, and keeps the actions so a test can use the controls. */
    private static final class Recorder implements PropertyForm.PanelBuilder {
        final List<String> calls = new ArrayList<>();
        final List<DoubleConsumer> sliders = new ArrayList<>();
        Runnable toggle, next, previous;

        @Override public void slider(int x, int y, int width, String label, double value, double min, double max, double start, DoubleConsumer set, boolean integer) {
            calls.add("slider " + x + "," + y + " w" + width + " '" + label + "' " + value + " [" + min + ".." + max + "] start " + start + (integer ? " whole" : ""));
            sliders.add(set);
        }
        @Override public void toggle(int x, int y, String label, boolean value, Runnable action) {
            calls.add("toggle " + x + "," + y + " '" + label + "' " + value);
            toggle = action;
        }
        @Override public void cycle(int x, int y, int width, int height, String label, Runnable next, Runnable previous) {
            calls.add("cycle " + x + "," + y + " " + width + "x" + height + " '" + label + "'");
            this.next = next;
            this.previous = previous;
        }
    }

    @Test void theFormLaysOutOneRowPerSettingInOrderAndReturnsWhereItEnds() {
        var panel = new Recorder();
        int end = PropertyForm.draw(panel, new PropertyValues(SPECS), 10, 311, 200);
        assertEquals(List.of(
            "slider 10,321 w200 'Scale' 1.0 [0.5..4.0] start 1.0",
            "slider 10,349 w200 'Count' 3.0 [1.0..8.0] start 3.0 whole",
            "toggle 10,367 'Hollow' false",
            "cycle 10,389 200x20 'Axis: Y'"), panel.calls);
        assertEquals(311 + 28 + 28 + 22 + 26, end);
        assertEquals(end - 311, PropertyForm.height(SPECS));
    }

    @Test void aFormWithNoSettingsTakesNoRoom() {
        var panel = new Recorder();
        assertEquals(311, PropertyForm.draw(panel, new PropertyValues(List.of()), 10, 311, 200));
        assertTrue(panel.calls.isEmpty());
        assertEquals(0, PropertyForm.height(List.of()));
    }

    @Test void theControlsChangeTheValuesAndTheNextDrawShowsThem() {
        var values = new PropertyValues(SPECS);
        var panel = new Recorder();
        PropertyForm.draw(panel, values, 0, 0, 100);
        panel.sliders.get(0).accept(9);                // the first slider is a number held to its range
        panel.toggle.run();
        panel.next.run();
        assertEquals(4, values.number("scale"));
        assertTrue(values.toggled("hollow"));
        assertEquals("Z", values.choiceName("axis"));
        var again = new Recorder();
        PropertyForm.draw(again, values, 0, 0, 100);
        assertTrue(again.calls.get(0).contains("'Scale' 4.0"), again.calls.get(0));
        assertTrue(again.calls.get(2).contains("true"), again.calls.get(2));
        assertTrue(again.calls.get(3).contains("'Axis: Z'"), again.calls.get(3));
        again.next.run();                              // wraps round to the first choice
        assertEquals("X", values.choiceName("axis"));
        again.previous.run();                          // and back
        assertEquals("Z", values.choiceName("axis"));
    }
}
