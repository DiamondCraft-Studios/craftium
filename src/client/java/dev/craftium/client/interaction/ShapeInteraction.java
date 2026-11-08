package dev.craftium.client.interaction;

import dev.craftium.client.BrushGhost;
import dev.craftium.client.CraftiumClient;
import dev.craftium.client.ShapeObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A click puts the shape down rather than building it, and a held button carries it along; Move, Rotate and Scale then change it, Enter builds it
 * and Escape takes it away (Shape). The turn keys work on the hover preview before it is down, as they do on a shape that is.
 */
final class ShapeInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit != null) ShapeObject.place(CraftiumClient.point(hit.getBlockPos()));
        return true;
    }

    @Override public boolean turnsPlacement() { return true; }

    /** The shape as ghost blocks where a click would put it, or the outline of the brush when the ghost cannot be drawn. */
    @Override public void drawWorld(EditorContext editor, Minecraft mc, BlockHitResult hit) {
        if (Interaction.showsBrush(editor, hit) && BrushGhost.draw(mc, hit.getBlockPos(), editor.options())) return;
        Interaction.super.drawWorld(editor, mc, hit);
    }
}
