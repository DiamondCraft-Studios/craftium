package dev.craftium.client.interaction;

/** A click does nothing: these tools work through their handles on the selection, not by clicking the world (Move, Rotate, Scale). */
final class TransformInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) { return true; }
}
