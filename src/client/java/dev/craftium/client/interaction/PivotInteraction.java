package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;

/** A click sets the pivot that Rotate and Scale work around, at the middle of the block clicked; a held button keeps moving it (Pivot). */
final class PivotInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit != null) {
            var at = hit.getBlockPos();
            var options = editor.options();
            options.pivotSet = true;
            options.pivotX = at.getX() + .5;
            options.pivotY = at.getY() + .5;
            options.pivotZ = at.getZ() + .5;
            ClientState.notify("Pivot set");
        }
        return true;
    }
}
