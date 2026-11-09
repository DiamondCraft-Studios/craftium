package dev.craftium.server;

import dev.craftium.core.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;

/**
 * Lets what an AI build places connect the way it would if it were placed by hand: glass panes and iron bars join their neighbours, fences
 * and walls link up and walls raise their posts. The editor writes every block without updates (see {@link BlockSnapshot#NO_UPDATES}), so a
 * block keeps the connections it was parsed with, which are none; here each one is asked what it would connect to, against the blocks the
 * build has planned next to it as well as the world's.
 * <p>
 * A property the spec wrote on purpose ({@code glass_pane[north=false]}) is a design choice and is left as written.
 */
final class ConnectionResolver {
    private ConnectionResolver() {}

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    static boolean connects(BlockState state) {
        var block = state.getBlock();
        return block instanceof IronBarsBlock || block instanceof FenceBlock || block instanceof WallBlock;
    }

    /**
     * Updates the connections of every planned block that has them.
     * @param pinned the properties the spec wrote at a position, which stay as they were written
     */
    static void resolve(ServerLevel level, EditPlanner plan, Map<Point, Set<String>> pinned) {
        var fixes = new HashMap<Point, BlockState>();
        for (var change : plan.changes.values()) {
            var state = change.after().state();
            if (!connects(state)) continue;
            var keep = pinned.getOrDefault(change.pos(), Set.of());
            var result = state;
            for (var side : SIDES) result = adopt(level, plan, change.pos(), state, result, side, keep);
            if (state.getBlock() instanceof WallBlock) result = adopt(level, plan, change.pos(), state, result, Direction.UP, keep);
            if (result != state) fixes.put(change.pos(), result);
        }
        for (var fix : fixes.entrySet()) {
            var old = plan.changes.get(fix.getKey());
            plan.changes.put(fix.getKey(), new Session.Change(fix.getKey(), old.before(), new BlockSnapshot(fix.getValue(), old.after().data())));
        }
    }

    /** {@code result} with what the block would take from its neighbour on {@code side}: only the connection properties, and not those written. */
    private static BlockState adopt(ServerLevel level, EditPlanner plan, Point at, BlockState state, BlockState result, Direction side, Set<String> keep) {
        BlockPos pos = EditPlanner.pos(at);
        BlockPos beside = pos.relative(side);
        BlockState neighbour = plan.state(EditPlanner.point(beside));
        // Without the water, nothing is scheduled in the real world for a preview that may never be applied; the property itself is not touched.
        var dry = state.hasProperty(BlockStateProperties.WATERLOGGED) ? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
        BlockState updated = dry.updateShape(level, level, pos, side, beside, neighbour, level.getRandom());
        for (Property<?> property : updated.getProperties()) {
            if (property == BlockStateProperties.WATERLOGGED || keep.contains(property.getName()) || !result.hasProperty(property)) continue;
            result = copy(result, updated, property);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }
}
