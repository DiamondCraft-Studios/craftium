package dev.craftium.client.interaction;

/** Each click sends the request for the block under the cursor, which takes it as an end point; a held button does not send more (Line). */
final class LineInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) { return false; }

    @Override public boolean repeatsWhenDragged() { return false; }
}
