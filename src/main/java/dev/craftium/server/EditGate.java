package dev.craftium.server;

import dev.craftium.core.Options;
import dev.craftium.core.Selection;
import net.minecraft.server.level.ServerPlayer;

/**
 * The checks every edit passes before it is staged or built, whoever planned it: a tool of the editor, the clipboard, the Builder, the AI or a
 * plugin. An edit is a plan, a list of changes; nothing that plans writes to the world, and nothing reaches the world without being admitted here.
 * <p>
 * What the gate checks is what the editor always checked in {@code EditorServer.start}: the edit fits the history, and every position it touches may be changed
 * by this player (the protection plugins and claims the server has). A plan that did not come from the {@code EditPlanner}, which holds a tool to the
 * block limit as it plans, is also held to it here, see {@link #checkLimit}.
 */
final class EditGate {
    private EditGate() {}

    /** Throws {@link IllegalArgumentException} with the reason when the player may not make this edit as it stands. */
    static void check(ServerPlayer player, Session.Edit edit) {
        checkHistory(edit.estimatedBytes());
        EditProtection.check(player, edit);
    }

    /** An edit has to fit in the history, which is what lets it be undone. */
    static void checkHistory(long bytes) {
        if (bytes > Selection.HISTORY_BYTES)
            throw new IllegalArgumentException("Edit exceeds the " + Selection.HISTORY_BYTES / 1024 / 1024 + " MiB history budget; use a smaller region");
    }

    /** The most blocks one edit of a plan from outside the planner may change: the player's block limit, never past the largest selection. */
    static int limit(Options options) { return Math.clamp(options.blockLimit, 1, Selection.LIMIT); }

    /** Holds a plan that was not made by the planner to the block limit. */
    static void checkLimit(Session.Edit edit, int limit) { checkLimit(edit.changes().size(), limit); }

    static void checkLimit(int blocks, int limit) {
        if (blocks > limit) throw new IllegalArgumentException("The plan changes " + blocks + " blocks, past the block limit of " + limit);
    }
}
