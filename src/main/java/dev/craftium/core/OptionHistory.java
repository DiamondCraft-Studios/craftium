package dev.craftium.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * The undo history of the editor's own settings, kept on the client (settings are saved there) beside the server's history of blocks, with one Ctrl+Z over both: it undoes
 * whichever was done last. The server's side is only ever asked how long ago its next edit was made, so the two are ordered by age.
 */
public final class OptionHistory {
    /** One change of settings: what to put back to undo it ({@code before}) and to redo it ({@code after}), and when it was made, in epoch milliseconds. */
    public record Entry(String name, Map<String, Object> before, Map<String, Object> after, long time) {}
    /** The most changes of settings kept. */
    public static final int LIMIT = 100;

    private final Deque<Entry> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    /** Which history each undo that can still be redone came from, last first: true for this one, false for the server's. */
    private final Deque<Boolean> undone = new ArrayDeque<>();

    /** A change just made: it is the next to undo, and nothing undone before it can be redone any more. */
    public void record(Entry entry) {
        undo.push(entry);
        while (undo.size() > LIMIT) undo.removeLast();
        clearRedo();
    }
    /** Something else was done (a block edit): what was undone before it cannot come back. */
    public void clearRedo() {
        redo.clear();
        undone.clear();
    }

    public int undoCount() {
        return undo.size();
    }

    public int redoCount() {
        return redo.size();
    }
    /** How many of the undos that can be redone were the server's. */
    public int serverUndone() {
        int n = 0;
        for (boolean local : undone) if (!local) n++;
        return n;
    }

    /**
     * The change to undo here, or null when the server's edit is the one to undo (or there is nothing at all).
     * {@code serverAge} is how long ago the server's next edit to undo was made, in milliseconds, or -1 when it has none.
     */
    public Entry undo(long now, long serverAge) {
        var top = undo.peek();
        if (top == null || (serverAge >= 0 && serverAge < now - top.time())) {
            if (serverAge >= 0) undone.push(false);
            return null;
        }
        undo.pop();
        redo.push(top);
        undone.push(true);
        return top;
    }
    /** The change to redo here, or null when the server's is the one to redo: whichever was undone last. */
    public Entry redo(boolean serverHasRedo) {
        Boolean source = undone.peek();
        if (source != null) {
            undone.pop();
            if (!source || redo.isEmpty()) return null;
            var entry = redo.pop();
            undo.push(entry);
            return entry;
        }
        if (!redo.isEmpty() && !serverHasRedo) {
            var entry = redo.pop();
            undo.push(entry);
            return entry;
        }
        return null;
    }
}
