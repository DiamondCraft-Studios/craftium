package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;
import java.util.List;

/**
 * Gradient Painter: holding the use button on a block sets the first anchor, dragging moves the second to the block under the cursor, and letting go
 * paints the gradient between them at once, then clears the line. A click that never moved to another block paints nothing.
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
            editor.request("use");
            // Painted: the line and its points go, and the next drag starts a new gradient. The request has already
            // taken the points with it.
            editor.options().nodes.clear();
        } else {
            ClientState.notify("Hold right-click on a block and drag to another to paint a gradient");
        }
    }

    /** Putting the tool away, or choosing another, drops its anchors. */
    @Override
    public void unchosen(EditorContext editor) {
        editor.options().nodes.clear();
        start = null;
        moved = false;
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
