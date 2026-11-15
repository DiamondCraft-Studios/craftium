package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.core.EditorControls.Action;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.*;

/** Entity shortcuts use the selected gizmo; block selections retain their own clipboard context. */
final class EntityShortcuts {
    private EntityShortcuts() {}

    static boolean editor(String action, EditorScreen screen) {
        return execute(action, TransformGizmo.selectedEntity(screen));
    }

    private static boolean execute(String action, Entity entity) {
        if (entity == null
                || !java.util.Set.of("copy", "cut", "duplicate", "delete").contains(action)) return false;
        var at = CraftiumClient.point(entity.blockPosition());
        ClientState.options.entityUuid = entity.getUUID().toString();
        if (action.equals("cut") || action.equals("duplicate")) {
            ClientState.duplicatePending = true;
            ClientState.pendingPlacementOrigin = at;
        }
        if (!action.equals("delete")) ClientState.options.pasteEntities = true;
        CraftiumClient.send(
                action.equals("delete") ? "entity_delete" : action.equals("cut") ? "entity_cut" : "entity_copy",
                at,
                null);
        return true;
    }

    private static Entity selected() {
        var mc = Minecraft.getInstance();
        var o = ClientState.options;
        if (mc.level == null || o.gizmoTarget != Options.GizmoTarget.ENTITY || o.entityUuid.isBlank()) return null;
        for (var e : mc.level.entitiesForRendering())
            if (e.getUUID().toString().equals(o.entityUuid) && (e instanceof Display || e instanceof Marker)) return e;
        return null;
    }

    static boolean ingame(KeyEvent event) {
        if (BuilderMode.active()) return false;
        var entity = selected();
        if (entity != null) {
            if (EditorBindings.matches(Action.INGAME_COPY, event)) return execute("copy", entity);
            if (EditorBindings.matches(Action.INGAME_DELETE, event)
                    || event.key() == 259 && (event.modifiers() & 7) == 0) return execute("delete", entity);
        }
        if (ClientState.status.clipboardEntityCount() > 0 && ClientState.status.clipboardSize() == 0) {
            if (EditorBindings.matches(Action.PASTE, event)) {
                var mc = Minecraft.getInstance();
                Point at;
                if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                    var n = CraftiumClient.normal(hit);
                    at = CraftiumClient.point(hit.getBlockPos()).add(n.x(), n.y(), n.z());
                } else
                    at = CraftiumClient.point(net.minecraft.core.BlockPos.containing(mc.player
                            .getEyePosition()
                            .add(mc.player.getLookAngle().scale(5))));
                CraftiumClient.send("paste_at", at, null);
                return true;
            }
            if (EditorBindings.matches(Action.UNDO, event) || EditorBindings.matches(Action.REDO, event)) {
                CraftiumClient.send(EditorBindings.matches(Action.UNDO, event) ? "undo" : "redo", null, null);
                return true;
            }
        }
        return false;
    }
}
