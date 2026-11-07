package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;

import java.util.List;

/**
 * Two clicks mark the opposite corners of a box, and each corner can then be dragged by its handle (Box Select). The first click leaves one
 * corner, the second a second one, and a third starts over. A held button does not repeat the click, and choosing another tool drops the
 * corners but choosing this one again keeps them.
 */
final class BoxInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit != null) {
            Point point = CraftiumClient.point(hit.getBlockPos());
            var corners = ClientState.boxCorners;
            if (corners.size() == 1) { corners.add(point); editor.selectNode(1); }
            else { corners.clear(); corners.add(point); editor.selectNode(0); }
        }
        return false; // the click goes on to the server, which selects the box between the corners once there are two
    }

    @Override public boolean repeatsWhenDragged() { return false; }

    @Override public List<Point> points(EditorContext editor) { return ClientState.boxCorners; }

    /** Both corners of a box show a gizmo; grabbing the one that is not selected selects it and starts the drag. */
    @Override public void grabHandle(EditorContext editor, double x, double y) {
        var corners = ClientState.boxCorners;
        if (corners.size() != 2) return;
        for (int i = 0; i < 2; i++)
            if (i != editor.selectedNode() && editor.hitsPointHandles(corners.get(i), x, y)) { editor.selectNode(i); break; }
    }

    /** The box follows the handles, replacing the box it made before. */
    @Override public void handlesMoved(EditorContext editor) {
        var corners = ClientState.boxCorners;
        if (corners.size() != 2 || ClientState.status.busy()) return;
        editor.options().position1 = corners.get(0);
        editor.options().position2 = corners.get(1);
        CraftiumClient.send("select_box_adjust", null, null);
    }

    @Override public void chosen(EditorContext editor) {}
}
