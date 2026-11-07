package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;

/** Each click adds a control point, and Enter builds from them (Path, Modelling). A held button does not add points as it moves. */
final class PointsInteraction implements Interaction {
    static final int MAX_POINTS = 64;

    @Override public boolean use(EditorContext editor) { addPoint(editor); return true; }

    /** Adds the block under the cursor as the next control point, up to the limit. Shared with the tools that take points only while Shift is held. */
    static void addPoint(EditorContext editor) {
        var hit = editor.target();
        var points = editor.options().nodes;
        if (hit != null && points.size() < MAX_POINTS) {
            points.add(CraftiumClient.point(hit.getBlockPos()));
            ClientState.notify("Added point " + points.size() + " · Enter to build · View → Studio inspector for settings");
        }
    }

    @Override public boolean repeatsWhenDragged() { return false; }

    @Override public boolean usesPoints() { return true; }

    @Override public boolean extrudePoint(EditorContext editor) {
        var points = editor.options().nodes;
        if (points.isEmpty()) return false;
        if (points.size() >= MAX_POINTS) { ClientState.notify("Maximum " + MAX_POINTS + " control points"); return true; }
        points.add(points.getLast());
        editor.selectNode(points.size() - 1);
        return true;
    }
}
