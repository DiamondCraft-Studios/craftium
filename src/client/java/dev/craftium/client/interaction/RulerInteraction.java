package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;

/** Each click adds an end point to the rulers, and the second one finishes a ruler; the next starts another. Nothing is sent to the server (Ruler). */
final class RulerInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit != null) {
            try { ClientState.rulerPoints.add(CraftiumClient.point(hit.getBlockPos())); }
            catch (IllegalArgumentException e) { ClientState.notify(e.getMessage()); }
        }
        return true;
    }

    @Override public boolean repeatsWhenDragged() { return false; }
}
