package dev.craftium.client.interaction;

import dev.craftium.core.Point;

import java.util.List;

/** Clicks work as they do for any tool, and a click with Shift held adds an anchor point instead (Gradient Painter). */
final class ShiftPointsInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        if (!editor.shiftDown()) return false;
        PointsInteraction.addPoint(editor);
        return true;
    }

    @Override public List<Point> points(EditorContext editor) { return editor.options().nodes; }
}
