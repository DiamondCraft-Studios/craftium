package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.client.GradientStroke;
import dev.craftium.core.BlockList;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Gradient Painter, brushed as the Surface Painter is: pressing the use button on a block sets the start, holding it brushes the ring over
 * the surface while the end follows the block under the cursor, and letting go sets the end and paints at once what the brush passed
 * over, from the start's end of the Materials list to the end's. A click that never moved to another block paints nothing.
 */
final class DragPointsInteraction implements Interaction {
    /** A stroke as it is sent: its two ends, the face of the end block, the path the pointer took and the blocks the ring covered. */
    private record Stroke(List<Point> anchors, Point normal, String path, String covered) {}

    private Point start;
    /** The face of the end block the drag last crossed, sent with the stroke. */
    private Point normal = new Point(0, 1, 0);

    private boolean moved;
    /** The blocks the pointer passed over, in order, without the start; thinned to every other one when it fills up. */
    private final List<Point> path = new ArrayList<>();

    private boolean tooMany;
    /** A stroke let go of while the server was still applying the last one: sent as soon as the server is free. */
    private Stroke waiting;
    /** The stroke sent last, kept until the server answers, so one refused for being busy can wait its turn instead of being lost. */
    private Stroke sent;

    @Override
    public boolean pressed(EditorContext editor, double x, double y) {
        var hit = editor.target();
        start = hit == null ? null : CraftiumClient.point(hit.getBlockPos());
        moved = false;
        tooMany = false;
        path.clear();
        GradientStroke.clear();
        var anchors = editor.options().gradientAnchors;
        anchors.clear();
        if (start != null) {
            anchors.add(start);
            GradientStroke.addRing(hit.getBlockPos());
        }
        return true; // nothing is sent on the press: the stroke is painted when the button is let go
    }

    @Override
    public boolean dragged(EditorContext editor, double x, double y) {
        var hit = editor.target();
        if (start == null || hit == null) return true;
        if (!GradientStroke.addRing(hit.getBlockPos()) && !tooMany) {
            tooMany = true;
            ClientState.notify("This stroke covers as many blocks as one edit can; let go to paint it");
        }
        var end = CraftiumClient.point(hit.getBlockPos());
        if (end.equals(start) && !moved) return true;
        moved = true;
        normal = CraftiumClient.normal(hit);
        if (path.isEmpty() || !path.getLast().equals(end)) {
            if (path.size() >= Options.MAX_GRADIENT_PATH - 2) thin();
            path.add(end);
        }
        var anchors = editor.options().gradientAnchors;
        anchors.clear();
        anchors.add(start);
        anchors.add(end);
        return true;
    }

    /** Keeps every other point of a long path, and always its last. */
    private void thin() {
        var kept = new ArrayList<Point>(path.size() / 2 + 1);
        for (int i = 0; i < path.size(); i += 2) kept.add(path.get(i));
        if (!kept.getLast().equals(path.getLast())) kept.add(path.getLast());
        path.clear();
        path.addAll(kept);
    }

    @Override
    public void released(EditorContext editor) {
        boolean dragged = moved;
        var from = start;
        start = null;
        moved = false;
        var anchors = editor.options().gradientAnchors;
        if (from != null && dragged && anchors.size() == 2) {
            var stroke = new Stroke(
                    List.copyOf(anchors),
                    normal,
                    BlockList.encode(path),
                    GradientStroke.ringActive() ? BlockList.encode(GradientStroke.covered()) : "");
            if (ClientState.status.busy()) {
                waiting = stroke;
                ClientState.notify("This stroke paints as soon as the last one has finished");
            } else send(editor, stroke);
        } else if (from != null) {
            ClientState.notify("Hold right-click on a block and brush over to another to paint a gradient");
        }
        // The line, its points and the brushed blocks go, and the next press starts a new gradient
        anchors.clear();
        path.clear();
        GradientStroke.clear();
    }

    /**
     * Sends {@code stroke} for its end block, wherever the pointer is now: the block under it may be sky or a panel by then. The request
     * carries the stroke in the anchors and the path, which are put back as they were straight after, as a new drag may have begun.
     */
    private void send(EditorContext editor, Stroke stroke) {
        var o = editor.options();
        var current = List.copyOf(o.gradientAnchors);
        o.gradientAnchors.clear();
        o.gradientAnchors.addAll(stroke.anchors());
        o.gradientPath = stroke.path();
        o.screenBlocks = stroke.covered();
        try {
            editor.requestAt("use", stroke.anchors().getLast(), stroke.normal());
        } finally {
            o.gradientAnchors.clear();
            o.gradientAnchors.addAll(current);
            o.gradientPath = "";
            o.screenBlocks = "";
        }
        sent = stroke;
    }

    /** A stroke the server refused for being busy waits its turn; a waiting stroke is sent once the server is free. */
    @Override
    public void statusReceived(EditorContext editor) {
        var status = ClientState.status;
        if (sent != null) {
            if (status.error() && status.message().contains("please wait") && waiting == null) waiting = sent;
            sent = null;
        }
        if (waiting != null && !status.busy()) {
            var stroke = waiting;
            waiting = null;
            send(editor, stroke);
        }
    }

    /** Putting the tool away, or choosing another, drops its anchors, the brushed blocks and any stroke still waiting. */
    @Override
    public void unchosen(EditorContext editor) {
        editor.options().gradientAnchors.clear();
        GradientStroke.clear();
        path.clear();
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
