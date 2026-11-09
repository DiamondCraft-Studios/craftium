package dev.craftium.server;

/**
 * What a tool does with a click on the world: it reads what the player set and what is there, and makes a plan. The editor's own tools and a
 * plugin's tools are the same to the server: a {@code ToolBehaviour} found by {@link ToolBehaviours}.
 */
@FunctionalInterface
interface ToolBehaviour {
    /** Acts on one use of the tool. Throws {@link IllegalArgumentException} with a message for the player when the use cannot be done. */
    void use(ToolUse use);

    /**
     * The edit the tool would make for the block under the cursor, shown to the player as a ghost before they click. It is a plan, nothing more: it
     * is not built and not kept in the history. Null, the default, when the tool shows none.
     */
    default Session.Edit preview(ToolUse use) { return null; }
}
