package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Turns an AI client's {@link BuildSpec} into an edit inside the player's selection, ready to be held as a ghost until confirmed. */
final class BuildPlanner {
    private BuildPlanner() {}

    static Session.Edit plan(net.minecraft.server.level.ServerPlayer player, Session s, Options o) {
        if (s.selection.size() == 0) throw new IllegalArgumentException("Select the area to build in first");
        var spec = BuildSpec.parse(o.buildSpec);
        var min = s.selection.min();
        var max = s.selection.max();
        var plan = new EditPlanner(player.level(), s, o);
        // Every block is looked up before anything is planned, so an unknown name fails the whole build and not half of it.
        var states = new HashMap<String, BlockState>();
        for (String name : spec.blocks()) states.put(name, plan.parse(name));
        var result = spec.build(new BuildSpec.Bounds(max.x() - min.x() + 1, max.y() - min.y() + 1, max.z() - min.z() + 1));
        // The spec says exactly where blocks go, so the player's brush mask, symmetry and height settings do not apply.
        plan.exact = true;
        for (var entry : result.cells().entrySet()) {
            var at = min.add(entry.getKey().x(), entry.getKey().y(), entry.getKey().z());
            var cell = entry.getValue();
            if (cell.only() != null) {
                var current = plan.state(at);
                var wanted = states.get(cell.only());
                if (!(cell.only().contains("[") ? current == wanted : current.is(wanted.getBlock()))) continue;
            }
            plan.put(at, states.get(cell.block()));
        }
        return plan.finish("AI build");
    }
}
