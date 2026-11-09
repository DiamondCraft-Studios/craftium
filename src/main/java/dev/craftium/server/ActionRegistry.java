package dev.craftium.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The actions that are not the editor's own. A request names an action, and the editor's own are matched one by one in {@code EditorServer.handle};
 * an action of a plugin's has a namespaced name, {@code plugin_id:action}, which no action of the editor's can have, so the two never meet. The
 * registry is looked at after the request has been checked for permission and throttled like any other, and a namespaced name nobody registered is
 * an error and never falls through to the editor's own.
 */
final class ActionRegistry {
    private ActionRegistry() {}

    /** The form of a namespaced action name: lower case letters, digits and underscores on both sides of one colon. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*");

    private static final Map<String, ActionHandler> HANDLERS = new ConcurrentHashMap<>();

    /** Whether the name is a namespaced one, which only the registry answers. */
    static boolean isNamespaced(String action) { return action != null && NAME.matcher(action).matches(); }

    /** The namespace of a namespaced action name: {@code plugin_id} in {@code plugin_id:action}. */
    static String namespaceOf(String action) { return action.substring(0, action.indexOf(':')); }

    /** Makes {@code action} do {@code handler}. Throws when the name is not a namespaced one or is already taken. */
    static void register(String action, ActionHandler handler) {
        if (!isNamespaced(action)) throw new IllegalArgumentException("An action is named plugin_id:action, in lower case letters, digits and underscores: " + action);
        if (handler == null) throw new IllegalArgumentException("Action " + action + " needs a handler");
        if (HANDLERS.putIfAbsent(action, handler) != null) throw new IllegalArgumentException("Action " + action + " is already registered");
    }

    /** The handler of a namespaced action, or null when nobody registered it. */
    static ActionHandler find(String action) { return action == null ? null : HANDLERS.get(action); }

    /** Takes back every action of one namespace, such as when a plugin is reloaded or removed. Returns how many there were. */
    static int unregister(String namespace) {
        int[] removed = {0};
        HANDLERS.keySet().removeIf(name -> { boolean ours = namespaceOf(name).equals(namespace); if (ours) removed[0]++; return ours; });
        return removed[0];
    }

    static void clear() { HANDLERS.clear(); }
}
