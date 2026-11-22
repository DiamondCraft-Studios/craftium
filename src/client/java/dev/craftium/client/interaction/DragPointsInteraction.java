package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.client.GradientStroke;
import dev.craftium.core.Point;
import java.util.List;

/**
 * Gradient Painter: holding the use button on a block sets the first anchor, dragging moves the second to the block under the cursor, and letting go
 * paints the gradient between them at once, then clears the line. A click that never moved to another block paints nothing.
 */
final class DragPointsInteraction implements Interaction {
    private Point start;
    /** The face of the end block the drag last crossed, sent with the stroke. */
    private Point normal = new Point(0, 1, 0);

    private boolean moved;
    /** A stroke let go of while the server was still applying the last one, and its face: sent as soon as the server is free. */
    private List<Point> waiting;

    private Point waitingNormal;
    /** The stroke sent last, kept until the server answers, so one refused for being busy can wait its turn instead of being lost. */
    private List<Point> sent;

    private Point sentNormal;

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
        normal = CraftiumClient.normal(hit);
        var points = editor.options().gradientAnchors;
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
        var anchors = editor.options().gradientAnchors;
        if (dragged && anchors.size() == 2) {
            var stroke = List.copyOf(anchors);
            // The line and its points go, and the next drag starts a new gradient
            anchors.clear();
            if (ClientState.status.busy()) {
                waiting = stroke;
                waitingNormal = normal;
                ClientState.notify("This stroke paints as soon as the last one has finished");
            } else send(editor, stroke, normal);
        } else {
            ClientState.notify("Hold right-click on a block and drag to another to paint a gradient");
        }
    }

    /**
     * Sends {@code stroke} for its end block and {@code face}, wherever the pointer is now: the block under it may be sky or a panel by then.
     * The request carries the stroke in the anchors, which are put back as they were straight after, as a new drag may have begun.
     */
    private void send(EditorContext editor, List<Point> stroke, Point face) {
        var anchors = editor.options().gradientAnchors;
        var current = List.copyOf(anchors);
        anchors.clear();
        anchors.addAll(stroke);
        // With Match the ring on, the blocks the ring covers swept along the line, as the preview showed them
        editor.options().screenBlocks = GradientStroke.encoded(stroke.getFirst(), stroke.getLast());
        try {
            editor.requestAt("use", stroke.getLast(), face);
        } finally {
            anchors.clear();
            anchors.addAll(current);
            editor.options().screenBlocks = "";
        }
        sent = stroke;
        sentNormal = face;
    }

    /** A stroke the server refused for being busy waits its turn; a waiting stroke is sent once the server is free. */
    @Override
    public void statusReceived(EditorContext editor) {
        var status = ClientState.status;
        if (sent != null) {
            if (status.error() && status.message().contains("please wait") && waiting == null) {
                waiting = sent;
                waitingNormal = sentNormal;
            }
            sent = null;
        }
        if (waiting != null && !status.busy()) {
            var stroke = waiting;
            waiting = null;
            send(editor, stroke, waitingNormal);
        }
    }

    /** Putting the tool away, or choosing another, drops its anchors and any stroke still waiting. */
    @Override
    public void unchosen(EditorContext editor) {
        editor.options().gradientAnchors.clear();
        waiting = null;
        sent = null;
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
        return editor.options().gradientAnchors;
    }
}
