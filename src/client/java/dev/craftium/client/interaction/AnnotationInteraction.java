package dev.craftium.client.interaction;

import dev.craftium.client.CraftiumClient;

/**
 * A click notes the face it lands on; in Draw mode the button held down collects the blocks it passes over (up to 256, none twice in a row) and
 * letting go sends them as one freehand annotation (Annotation).
 */
final class AnnotationInteraction implements Interaction {
    private static final int MAX_POINTS = 256;

    private static boolean drawing(EditorContext editor) { return editor.options().annotationMode.equals("DRAW"); }

    @Override public boolean beforeUse(EditorContext editor) {
        var hit = editor.target();
        if (hit == null) return false;
        var options = editor.options();
        options.annotationNormal = CraftiumClient.normal(hit);
        if (!drawing(editor)) return false;
        var point = CraftiumClient.point(hit.getBlockPos());
        var points = options.annotationPoints;
        if (points.size() < MAX_POINTS && (points.isEmpty() || !points.getLast().equals(point))) points.add(point);
        return true;
    }

    @Override public boolean use(EditorContext editor) { return false; }

    @Override public void strokeBegan(EditorContext editor) { if (drawing(editor)) editor.options().annotationPoints.clear(); }

    @Override public void strokeEnded(EditorContext editor) { if (drawing(editor) && editor.options().annotationPoints.size() > 1) editor.request("annotation_create"); }
}
