package dev.craftium.client.interaction;

import dev.craftium.client.LassoSelection;

import java.util.ArrayList;

/**
 * The button held down draws an outline on the screen (up to 1,024 points), and letting go selects what the outline encloses, as seen from the
 * camera (Lasso Select). Nothing is sent while the outline is drawn, so a held button does not repeat the click.
 */
final class LassoInteraction implements Interaction {
    private static final int MAX_PIXELS = 1024;

    @Override public boolean use(EditorContext editor) { return false; }

    @Override public boolean repeatsWhenDragged() { return false; }

    /** The outline starts at the pointer, and no request goes out. */
    @Override public boolean pressed(EditorContext editor, double x, double y) {
        var outline = editor.lasso();
        outline.clear();
        outline.add(new LassoSelection.Pixel(x, y));
        return true;
    }

    @Override public boolean dragged(EditorContext editor, double x, double y) {
        var outline = editor.lasso();
        if (outline.size() < MAX_PIXELS) outline.add(new LassoSelection.Pixel(x, y));
        return true;
    }

    /** The outline is projected into the world and sent as the selection to make. */
    @Override public void released(EditorContext editor) {
        var outline = editor.lasso();
        if (outline.isEmpty()) return;
        var options = editor.options();
        options.selectionPoints = new ArrayList<>(LassoSelection.project(outline));
        editor.request("lasso_select");
        options.selectionPoints.clear();
        outline.clear();
    }
}
