package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;

/** The first click sets an anchor; each later click moves the second point and sends the request (Slope). */
final class AnchorPairInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit == null) return false;
        var point = CraftiumClient.point(hit.getBlockPos());
        var points = editor.options().nodes;
        if (points.isEmpty()) {
            points.add(point);
            ClientState.notify("Slope anchor set · drag from the second point");
            return true;
        }
        if (points.size() == 1) points.add(point); else points.set(1, point);
        return false;
    }

    @Override public boolean usesPoints() { return true; }
}
