package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.client.ModifyPreview;
import dev.craftium.core.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * The first click anchors the preview where the cursor is, and once it is anchored another click does nothing: the settings change that same
 * preview. Apply or Enter builds it, or confirms an edit that is waiting for it (Modify).
 */
final class ModifyInteraction implements Interaction {
    /** The edit as it would be built, from the anchor once it is down and from the cursor before. */
    @Override public void drawWorld(EditorContext editor, Minecraft mc, BlockHitResult hit) {
        if (editor.toolActive()) ModifyPreview.draw(mc, hit == null ? null : CraftiumClient.point(hit.getBlockPos()));
        Interaction.super.drawWorld(editor, mc, hit);
    }

    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        if (hit != null) ModifyPreview.place(CraftiumClient.point(hit.getBlockPos()));
        return true;
    }

    /** The anchor, while it is a knob the Move gizmo can carry. */
    @Override public List<Point> points(EditorContext editor) { return ModifyPreview.knobNodes(); }

    @Override public String confirmAction() { return "use"; }

    /** An edit that is waiting is confirmed; otherwise the request goes out from the anchor, or from the block under the cursor when there is none. */
    @Override public boolean sendUse(EditorContext editor) {
        if (ClientState.hasPending()) { CraftiumClient.send("pending_confirm", null, null); return true; }
        var hit = editor.target();
        Point at = ModifyPreview.anchored() && ModifyPreview.anchor() != null ? ModifyPreview.anchor() : hit == null ? null : CraftiumClient.point(hit.getBlockPos());
        CraftiumClient.send("use", at, hit == null ? new Point(0, 1, 0) : CraftiumClient.normal(hit));
        ModifyPreview.edit();
        return true;
    }

    /** Keeps its own anchor, and drops a box selection that was half made. */
    @Override public void chosen(EditorContext editor) { ClientState.boxCorners.clear(); }
}
