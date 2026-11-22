package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;
import java.util.List;

/**
 * Gradient Painter: holding the use button on a block sets the first anchor, dragging moves the second to the block under the cursor, and letting go
 * fixes it. A click that never moved to another block paints with the anchors that are set.
 */
final class DragPointsInteraction implements Interaction {
    private Point start;
    private boolean moved;

    @Override
    public boolean pressed(EditorContext editor, double x, double y) {
        var hit = editor.target();
        start = hit == null ? null : CraftiumClient.point(hit.getBlockPos());
        moved = false;
        return true; // nothing is sent on the press: it may be the start of a drag
    }

    @Override
    public boolean dragged(EditorContext editor, double x, double y) {
        var hit = editor.target();
        if (start == null || hit == null) return true;
        var end = CraftiumClient.point(hit.getBlockPos());
        if (end.equals(start) && !moved) return true;
        moved = true;
        var points = editor.options().nodes;
        points.clear();
        points.add(start);
        points.add(end);
        return true;
    }

    @Override
    public void released(EditorContext editor) {
        boolean dragged = moved;
        var from = start;
        start = null;
        moved = false;
        if (from == null) return;
        if (dragged) {
            ClientState.notify("Gradient set · right-click to paint it");
        } else if (editor.options().nodes.size() >= 2) {
            editor.request("use");
        } else {
            ClientState.notify("Hold right-click on a block and drag to another to set the gradient");
        }
    }

    @Override
    public boolean use(EditorContext editor) {
        return true;
    }

    @Override
    public boolean repeatsWhenDragged() {
        return false;
    }

    @Override
    public List<Point> points(EditorContext editor) {
        return editor.options().nodes;
    }
}
