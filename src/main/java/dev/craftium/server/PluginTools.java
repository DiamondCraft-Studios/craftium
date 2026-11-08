package dev.craftium.server;

import dev.craftium.core.PropertyValues;
import dev.craftium.core.ToolSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The tools that plugins add, by the id of each ({@code plugin_id:tool}). A request that names one finds its {@link ToolBehaviour} here, and the
 * values it carries are held to the settings the tool's {@link ToolSpec} declared. The tools of the editor are not here: they are the {@code Tool}
 * enum and {@link ToolBehaviours}, and {@code craftium} is the namespace they keep.
 */
final class PluginTools {
    private PluginTools() {}

    /** A plugin's tool: what it declared, and what it does. */
    record Registered(ToolSpec spec, ToolBehaviour behaviour) {
        /** The values of the tool's settings for one request: what the player set where it was declared, and what the tool started on for the rest. Anything the tool did not declare is left out. */
        PropertyValues settings(Map<String, Double> asked) {
            var values = new PropertyValues(spec.properties());
            if (asked != null) for (var setting : spec.properties()) { Double value = asked.get(setting.id()); if (value != null) values.set(setting.id(), value); }
            return values;
        }
    }

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*");
    static final String RESERVED = "craftium";

    private static final Map<String, Registered> TOOLS = new ConcurrentHashMap<>();

    /** Makes the tool the spec describes do {@code behaviour}. Throws when the id is not a plugin's or is already taken. */
    static void register(ToolSpec spec, ToolBehaviour behaviour) {
        if (spec == null || behaviour == null) throw new IllegalArgumentException("A plugin tool needs a spec and a behaviour");
        String id = spec.id();
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("A tool is named plugin_id:tool, in lower case letters, digits and underscores: " + id);
        if (id.startsWith(RESERVED + ":")) throw new IllegalArgumentException("The " + RESERVED + " namespace is the editor's own: " + id);
        if (TOOLS.putIfAbsent(id, new Registered(spec, behaviour)) != null) throw new IllegalArgumentException("Tool " + id + " is already registered");
    }

    static Registered find(String id) { return id == null ? null : TOOLS.get(id); }

    /** Takes back every tool of one namespace, such as when a plugin is reloaded or removed. Returns how many there were. */
    static int unregister(String namespace) {
        int[] removed = {0};
        TOOLS.keySet().removeIf(id -> { boolean ours = id.substring(0, id.indexOf(':')).equals(namespace); if (ours) removed[0]++; return ours; });
        return removed[0];
    }

    static void clear() { TOOLS.clear(); }
}
