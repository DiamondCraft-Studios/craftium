package dev.craftium.server;

/** What a registered action does when a player sends it. See {@link ActionRegistry}. */
@FunctionalInterface
interface ActionHandler {
    /** Throws {@link IllegalArgumentException} with a message for the player when the action cannot be done. */
    void run(ActionUse use);
}
