package dev.craftium.server;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.PropertyValues;
import net.minecraft.server.level.ServerPlayer;

/**
 * One use of a tool, as the tool's {@link ToolBehaviour} sees it: where the player clicked, what the player has set, and a planner that reads the
 * world as it will be once the edits waiting have been built. A behaviour makes a plan and hands it to {@link #submit}; it has no way to write to
 * the world, so every plan passes the {@link EditGate}.
 *
 * @param point  the block that was clicked
 * @param normal the side of it that was clicked, as a step out of the block
 * @param outside whether the tool is a plugin's, whose plans are not made by the editor's planner and so are also held to the block limit by the gate
 * @param settings the values of the settings the tool declared, or null for a tool of the editor's, which has none
 */
record ToolUse(ServerPlayer player, Session session, Options options, Point point, Point normal, EditPlanner plan, boolean outside, PropertyValues settings) {
    ToolUse(ServerPlayer player, Session session, Options options, Point point, Point normal, EditPlanner plan) { this(player, session, options, point, normal, plan, false, null); }

    /** Hands an edit in: it is checked by the gate, then built or, when edits are confirmed first, shown as a ghost until the player says so. */
    void submit(Session.Edit edit) {
        if (outside) EditGate.checkLimit(edit, EditGate.limit(options));
        EditorServer.start(player, session, edit);
    }

    /** Tells the player something in the status line. */
    void say(String message) { EditorServer.send(player, session, message, false); }
}
