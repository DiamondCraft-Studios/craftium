package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.runtime.ServerPlatform;
import java.util.*;
import net.minecraft.server.level.*;

final class EditProtection {
    private EditProtection() {}

    static List<Point> points(Session.Edit edit) {
        var result = new LinkedHashSet<Point>();
        for (var c : edit.changes()) result.add(c.pos());
        for (var c : edit.entities())
            for (var tag : new net.minecraft.nbt.CompoundTag[] {c.before(), c.after()})
                if (tag != null) {
                    var p = tag.getListOrEmpty("Pos");
                    result.add(new Point(
                            (int) Math.floor(p.getDoubleOr(0, 0)), (int) Math.floor(p.getDoubleOr(1, 0)), (int)
                                    Math.floor(p.getDoubleOr(2, 0))));
                }
        for (var c : edit.annotations())
            for (var a : new Annotation[] {c.before(), c.after()})
                if (a != null) {
                    result.add(a.from());
                    result.add(a.to());
                    if (a.points() != null) result.addAll(a.points());
                }
        return List.copyOf(result);
    }

    static void check(ServerPlayer player, Session.Edit edit) {
        if (!ServerPlatform.requiresProtection()) return;
        ServerPlatform.checkEdit(
                player,
                player.level(),
                points(edit),
                edit.biomes().stream().map(Session.BiomeChange::cell).toList(),
                edit.entities().stream().map(Session.EntityChange::id).toList());
    }

    static boolean allowed(UUID owner, ServerLevel level, Session.Edit edit) {
        if (!ServerPlatform.requiresProtection()) return true;
        for (Point p : points(edit)) if (!ServerPlatform.canChange(owner, level, p)) return false;
        for (var c : edit.biomes())
            for (int x = 0; x < 4; x++)
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 4; z++)
                        if (!ServerPlatform.canChange(
                                owner,
                                level,
                                new Point(
                                        c.cell().x() * 4 + x,
                                        c.cell().y() * 4 + y,
                                        c.cell().z() * 4 + z))) return false;
        return true;
    }
}
