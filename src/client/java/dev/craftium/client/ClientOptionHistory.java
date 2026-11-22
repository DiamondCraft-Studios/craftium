package dev.craftium.client;

import dev.craftium.core.OptionDiff;
import dev.craftium.core.OptionHistory;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Undo and redo for the settings in the Tool options window, on the client where the settings live, in one order with the server's block edits (see {@link OptionHistory}).
 * A change is a gesture: from a press in the window until the pointer is up, nothing is being typed and the settings have been still for a moment, so a slider dragged
 * through forty values is one entry. What changed is found by comparing the settings before and after, so no control has to report anything.
 */
final class ClientOptionHistory {
    private ClientOptionHistory() {}
    /** How long the settings must be still before a gesture is taken as finished. */
    private static final long QUIET_MS = 300;

    static final OptionHistory history = new OptionHistory();
    private static Map<String, Object> baseline, observed;
    private static long lastChange;
    private static boolean mouseDown;
    private static int lastUndo = -1;
    private static boolean serverRedoExpected;

    static void pressed(boolean down) {
        mouseDown = down;
    }

    /** The player pressed in the Tool options window: whatever changes from here is one entry. */
    static void begin() {
        if (baseline != null) return;
        baseline = OptionDiff.snapshot(ClientState.options);
        observed = baseline;
        lastChange = System.currentTimeMillis();
    }

    /** Once a frame: ends a finished gesture, and drops what was undone when the server has a new edit. */
    static void tick(boolean typing) {
        int undo = ClientState.status.undo();
        if (lastUndo >= 0 && undo > lastUndo && !serverRedoExpected)
            history.clearRedo(); // a block edit was made, so nothing undone before it can come back
        if (undo != lastUndo) serverRedoExpected = false;
        lastUndo = undo;
        if (baseline == null) return;
        var now = OptionDiff.snapshot(ClientState.options);
        if (!OptionDiff.between(observed, now).isEmpty()) {
            lastChange = System.currentTimeMillis();
            observed = now;
        }
        if (mouseDown || typing || System.currentTimeMillis() - lastChange < QUIET_MS) return;
        finish(now);
    }

    private static void finish(Map<String, Object> now) {
        var change = OptionDiff.between(baseline, now);
        baseline = null;
        observed = null;
        if (!change.isEmpty())
            history.record(new OptionHistory.Entry(name(change), change.before(), change.after(), lastChange));
    }

    /**
     * The settings as they are now, for a change that is finished elsewhere, such as in a chooser or a colour picker opened from a window:
     * hand them to {@link #since} once it is done. A gesture still open is ended first, so it stays an entry of its own.
     */
    static Map<String, Object> mark() {
        flush();
        return OptionDiff.snapshot(ClientState.options);
    }

    /** Records what changed since {@code before} (from {@link #mark}) as one entry called {@code name}; nothing when nothing changed. */
    static void since(String name, Map<String, Object> before) {
        var change = OptionDiff.between(before, OptionDiff.snapshot(ClientState.options));
        if (!change.isEmpty())
            history.record(new OptionHistory.Entry(name, change.before(), change.after(), System.currentTimeMillis()));
    }

    /** Runs {@code change} and records what it changed as one entry called {@code name}. */
    static void change(String name, Runnable change) {
        var before = mark();
        change.run();
        since(name, before);
    }

    /** Ends a gesture that is still open, so a change made a moment ago is in the history before it is undone. */
    private static void flush() {
        if (baseline != null) finish(OptionDiff.snapshot(ClientState.options));
    }

    /** Undoes the newest change of settings when it is newer than the server's next edit; false when the server's is the one to undo. */
    static boolean undo() {
        flush();
        long serverAge = ClientState.status.undo() > 0 ? HistoryPulse.age(true) : -1;
        var entry = history.undo(System.currentTimeMillis(), serverAge);
        if (entry == null) return false;
        put(entry.before());
        ClientState.notify("Undid: " + entry.name());
        return true;
    }

    /** Redoes the change of settings that was undone last, if the last thing undone was one; false when it is the server's to redo. */
    static boolean redo() {
        flush();
        var entry = history.redo(ClientState.status.redo() > 0);
        if (entry == null) {
            serverRedoExpected = true;
            return false;
        }
        put(entry.after());
        ClientState.notify("Redid: " + entry.name());
        return true;
    }

    private static void put(Map<String, Object> values) {
        OptionDiff.apply(ClientState.options, values);
        ClientState.save();
        baseline = null;
        observed = null;
    }

    private static String name(OptionDiff.Change change) {
        if (change.after().size() != 1) return "Tool options (" + change.after().size() + " settings)";
        String field = change.after().keySet().iterator().next();
        String was = show(change.before().get(field)), now = show(change.after().get(field));
        // A list that changed inside, such as a moved gradient stop, reads the same before and after
        if (was.equals(now)) return OptionFields.human(field) + " changed";
        return OptionFields.human(field) + " " + was + " → " + now;
    }

    private static String show(Object v) {
        if (v == null) return "none";
        if (v instanceof Boolean b) return b ? "on" : "off";
        if (v instanceof Double d)
            return d == Math.rint(d) ? Long.toString(Math.round(d)) : String.format(Locale.ROOT, "%.2f", d);
        if (v instanceof Enum<?> e) return e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (v instanceof List<?> l) return l.size() + (l.size() == 1 ? " item" : " items");
        String s = v.toString();
        return s.length() > 24 ? s.substring(0, 23) + "…" : s;
    }
}
