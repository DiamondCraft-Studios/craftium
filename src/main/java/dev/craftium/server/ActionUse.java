package dev.craftium.server;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.minecraft.server.level.ServerPlayer;

/**
 * One request for a registered action, as its {@link ActionHandler} sees it. Like a {@link ToolUse} it has no way to write to the world: the plan it
 * hands in goes through the {@link EditGate}, and, as it is a plan from outside the editor's tools, is held to the player's block limit.
 *
 * @param target the block the request named, or null when it named none
 * @param normal the side of it, as a step out of the block, or null
 */
record ActionUse(ServerPlayer player, Session session, Options options, Point target, Point normal) {
    /** A planner that reads the world as it will be once the edits waiting have been built. */
    EditPlanner plan() { return new EditPlanner(player.level(), session, options); }

    /** Hands an edit in: held to the block limit, checked by the gate, then built or, when edits are confirmed first, shown as a ghost until the player says so. */
    void submit(Session.Edit edit) {
        EditGate.checkLimit(edit, EditGate.limit(options));
        EditorServer.start(player, session, edit);
    }

    /** Tells the player something in the status line. */
    void say(String message) { EditorServer.send(player, session, message, false); }
}
