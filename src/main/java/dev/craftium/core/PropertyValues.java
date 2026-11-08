package dev.craftium.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values of the settings one tool declares ({@link PropertySpec}). A value is always inside what its setting allows: a number is held to its
 * range, a whole number is rounded, a choice wraps round. Every value is a number, so a snapshot is the same on both sides of the wire.
 */
public final class PropertyValues {
    private final List<PropertySpec> specs;
    private final Map<String, Double> values = new LinkedHashMap<>();

    public PropertyValues(List<PropertySpec> specs) {
        this.specs = List.copyOf(specs);
        var seen = new java.util.HashSet<String>();
        for (PropertySpec spec : this.specs) {
            if (!seen.add(spec.id())) throw new IllegalArgumentException("Two properties are called " + spec.id());
            values.put(spec.id(), spec.number());
        }
    }

    public List<PropertySpec> specs() { return specs; }

    private PropertySpec spec(String id) {
        for (PropertySpec spec : specs) if (spec.id().equals(id)) return spec;
        throw new IllegalArgumentException("No property is called " + id);
    }

    public double number(String id) { return values.get(spec(id).id()); }
    public int integer(String id) { return (int) Math.round(number(id)); }
    public boolean toggled(String id) { return number(id) != 0; }
    /** The index of the choice the setting is on. */
    public int choice(String id) { return integer(id); }
    /** The name of the choice the setting is on. */
    public String choiceName(String id) { return spec(id).choices().get(integer(id)); }

    /** Sets a setting, held to what it allows. A number that is not a number (NaN) changes nothing. */
    public void set(String id, double value) {
        var spec = spec(id);
        if (Double.isNaN(value)) return;
        values.put(id, switch (spec.kind()) {
            case NUMBER -> Math.clamp(value, spec.min(), spec.max());
            case INTEGER -> (double) Math.clamp(Math.round(value), (long) spec.min(), (long) spec.max());
            case TOGGLE -> value != 0 ? 1.0 : 0.0;
            case CHOICE -> (double) Math.floorMod(Math.round(value), spec.choices().size());
        });
    }

    public void setToggled(String id, boolean on) { set(id, on ? 1 : 0); }
    /** Moves a choice on (or back, with a negative step), wrapping round. */
    public void step(String id, int step) { set(id, number(id) + step); }
    public void reset(String id) { values.put(id, spec(id).number()); }

    /** The values by setting id, as they are now; a copy. */
    public Map<String, Double> snapshot() { return Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
}
