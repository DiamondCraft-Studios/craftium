package dev.craftium.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One setting a tool declares for itself: a number, a whole number, an on/off switch, or one choice out of a few. The editor draws the settings of a
 * tool that declares any as a form in its options panel, and keeps their values (see {@link PropertyValues}); a tool never draws controls itself.
 *
 * @param id      names the setting inside its tool: lower case letters, digits and underscores, starting with a letter
 * @param label   what the panel calls it
 * @param kind    what sort of value it holds
 * @param min     the lowest value of a number or whole number
 * @param max     the highest value of a number or whole number
 * @param number  the starting value: the number itself, 0 or 1 for a switch, the index of the choice for a choice
 * @param choices what a choice can be; empty for the other kinds
 */
public record PropertySpec(String id, String label, Kind kind, double min, double max, double number, List<String> choices) {
    public enum Kind { NUMBER, INTEGER, TOGGLE, CHOICE }

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*");
    /** The most choices a choice can have; a longer list is not a button that cycles. */
    public static final int MAX_CHOICES = 16;

    public PropertySpec {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("A property id is lower case letters, digits and underscores, starting with a letter: " + id);
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Property " + id + " needs a label");
        if (kind == null) throw new IllegalArgumentException("Property " + id + " needs a kind");
        choices = choices == null ? List.of() : List.copyOf(choices);
        switch (kind) {
            case NUMBER, INTEGER -> {
                if (!(Double.isFinite(min) && Double.isFinite(max) && min < max)) throw new IllegalArgumentException("Property " + id + " needs a range with its lowest value below its highest");
                if (!(number >= min && number <= max)) throw new IllegalArgumentException("Property " + id + " starts outside its range");
                if (kind == Kind.INTEGER && number != Math.rint(number)) throw new IllegalArgumentException("Property " + id + " is a whole number but starts on " + number);
            }
            case TOGGLE -> {
                if (number != 0 && number != 1) throw new IllegalArgumentException("Property " + id + " is a switch and starts on 0 or 1");
            }
            case CHOICE -> {
                if (choices.size() < 2 || choices.size() > MAX_CHOICES) throw new IllegalArgumentException("Property " + id + " needs between 2 and " + MAX_CHOICES + " choices");
                if (choices.stream().anyMatch(c -> c == null || c.isBlank())) throw new IllegalArgumentException("Property " + id + " has a choice with no name");
                if (number != Math.rint(number) || number < 0 || number >= choices.size()) throw new IllegalArgumentException("Property " + id + " starts on a choice that is not there");
            }
        }
    }

    public static PropertySpec number(String id, String label, double min, double max, double start) { return new PropertySpec(id, label, Kind.NUMBER, min, max, start, List.of()); }
    public static PropertySpec integer(String id, String label, int min, int max, int start) { return new PropertySpec(id, label, Kind.INTEGER, min, max, start, List.of()); }
    public static PropertySpec toggle(String id, String label, boolean start) { return new PropertySpec(id, label, Kind.TOGGLE, 0, 1, start ? 1 : 0, List.of()); }
    public static PropertySpec choice(String id, String label, List<String> choices, int start) { return new PropertySpec(id, label, Kind.CHOICE, 0, 0, start, choices); }
}
