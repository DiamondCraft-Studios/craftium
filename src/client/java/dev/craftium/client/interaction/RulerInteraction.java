package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/** Each click adds an end point to the rulers, and the second one finishes a ruler; the next starts another. Nothing is sent to the server (Ruler). */
final class RulerInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit != null) {
            try { ClientState.rulerPoints.add(CraftiumClient.point(hit.getBlockPos())); }
            catch (IllegalArgumentException e) { ClientState.notify(e.getMessage()); }
        }
        return true;
    }

    @Override public boolean repeatsWhenDragged() { return false; }

    /** The block a click would put an end on, outlined and filled as a block of a selection is. */
    @Override public void drawWorld(EditorContext editor, Minecraft mc, BlockHitResult hit) {
        if (hit != null && editor.toolActive()) {
            var o = editor.options();
            Overlay.box(new AABB(hit.getBlockPos()), o.selectionColor, (float) o.selectionWidth, o.selectionFill);
        }
        Interaction.super.drawWorld(editor, mc, hit);
    }
}
