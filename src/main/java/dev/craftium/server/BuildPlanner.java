package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Turns an AI client's {@link BuildSpec} into an edit at the world coordinates the spec names, ready to be held as a ghost until it
 * is applied. It needs no selection and no player nearby: the chunks it reaches are loaded for it.
 */
final class BuildPlanner {
    private BuildPlanner() {}

    static Session.Edit plan(net.minecraft.server.level.ServerPlayer player, Session ai, Options o) {
        var spec = BuildSpec.parse(o.buildSpec);
        var place = spec.place();
        var min = place.min();
        var level = (net.minecraft.server.level.ServerLevel) player.level();
        AiWorld.load(level, min.x(), min.z(), min.x() + place.bounds().sx() - 1, min.z() + place.bounds().sz() - 1);
        var plan = new EditPlanner(level, ai, o);
        // Every block is looked up before anything is planned, so an unknown name fails the whole build and not half of it.
        var states = new HashMap<String, BlockState>();
        for (String name : spec.blocks()) states.put(name, plan.parse(name));
        var result = spec.build(place);
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
