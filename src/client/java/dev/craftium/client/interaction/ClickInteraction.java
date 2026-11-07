package dev.craftium.client.interaction;

/** The use button sends the request for the block under the cursor, and a held button repeats it. Most tools work this way. */
final class ClickInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) { return false; }
}
