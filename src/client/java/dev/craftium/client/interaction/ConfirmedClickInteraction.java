package dev.craftium.client.interaction;

import dev.craftium.client.ClickPreview;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * A click builds what is outlined under the cursor, and a held button does not repeat it. A click that would change as many blocks as a hover
 * preview stops showing asks first, because it cannot be looked over before it is built; with the large-selection warnings off, or a smaller
 * change, the click goes straight on to the server (Extrude, Floodfill).
 */
final class ConfirmedClickInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        var options = editor.options();
        if (!options.largeSelectionWarnings || editor.asking()) return false;
        var hit = editor.target();
        int count = hit == null ? 0 : ClickPreview.measure(Minecraft.getInstance(), hit);
        if (count < options.hoverPreviewLimit) return false;
        Point at = CraftiumClient.point(hit.getBlockPos()), normal = CraftiumClient.normal(hit);
        editor.ask("Change " + String.format(Locale.ROOT, "%,d", count) + (count >= ClickPreview.CAP ? "+" : "") + " blocks, confirm?", "Build it",
            () -> CraftiumClient.send("use", at, normal));
        return true;
    }

    @Override public boolean repeatsWhenDragged() { return false; }
}
