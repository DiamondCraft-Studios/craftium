package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;
import java.util.List;

/**
 * Slope: the first click sets where the ramp starts, the second sets where it ends and builds the ramp between them. The click after that
 * starts a new ramp, so each ramp is two clicks and none is built over the last one by accident.
 */
final class AnchorPairInteraction implements Interaction {
    @Override
    public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit == null) return false;
        var point = CraftiumClient.point(hit.getBlockPos());
        var points = editor.options().nodes;
        if (points.size() != 1) {
            points.clear();
            points.add(point);
            ClientState.notify("Ramp start set · right-click where the ramp ends");
            return true;
        }
        if (point.x() == points.getFirst().x() && point.z() == points.getFirst().z()) {
            ClientState.notify("Pick an end away from the start: a ramp needs some length");
            return true;
        }
        points.add(point);
        return false;
    }

    /** A drag would move the end with every block it crosses and build a ramp each time; one click is one end. */
    @Override
    public boolean repeatsWhenDragged() {
        return false;
    }

    @Override
    public List<Point> points(EditorContext editor) {
        return editor.options().nodes;
    }
}
