package dev.craftium.server;

import dev.craftium.compat.Compat;
import dev.craftium.core.Geometry;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Preserves representable orientation properties; blocks without vertical variants use the nearest legal state. */
final class StateTransforms {
    private StateTransforms() {}

    static BlockState rotate(BlockState original, double yaw, double pitch, double roll) {
        int turns = Math.floorMod((int) Math.round(yaw / 90), 4);
        BlockState state = original.rotate(Rotation.values()[turns]), from = state;
        double remainingYaw = yaw - turns * 90;
        for (var property : from.getProperties()) {
            var value = from.getValue(property);
            if (value instanceof Direction direction) {
                var v = rotate(direction, remainingYaw, pitch, roll);
                Direction best = direction;
                double score = -Double.MAX_VALUE;
                for (var allowed : property.getPossibleValues())
                    if (allowed instanceof Direction candidate) {
                        double dot = v.x() * candidate.getStepX()
                                + v.y() * candidate.getStepY()
                                + v.z() * candidate.getStepZ();
                        if (dot > score) {
                            score = dot;
                            best = candidate;
                        }
                    }
                state = set(state, property, best);
            } else if (value instanceof Direction.Axis axis) {
                var v = Geometry.rotate(
                        axis == Direction.Axis.X
                                ? new Geometry.Vector(1, 0, 0)
                                : axis == Direction.Axis.Y
                                        ? new Geometry.Vector(0, 1, 0)
                                        : new Geometry.Vector(0, 0, 1),
                        remainingYaw,
                        pitch,
                        roll);
                Direction.Axis best = Math.abs(v.x()) >= Math.abs(v.y()) && Math.abs(v.x()) >= Math.abs(v.z())
                        ? Direction.Axis.X
                        : Math.abs(v.y()) >= Math.abs(v.z()) ? Direction.Axis.Y : Direction.Axis.Z;
                if (property.getPossibleValues().contains(best)) state = set(state, property, best);
            }
        }
        if (state.hasProperty(BlockStateProperties.ROTATION_16))
            state = state.setValue(
                    BlockStateProperties.ROTATION_16,
                    Math.floorMod(
                            original.getValue(BlockStateProperties.ROTATION_16) + (int) Math.round(yaw * 16 / 360),
                            16));
        if (Geometry.rotate(new Geometry.Vector(0, 1, 0), remainingYaw, pitch, roll)
                        .y()
                < -.5) {
            state = flipHalf(state);
            // Turning a stair upside down by pitch or roll reflects its footprint in the horizontal plane, so its
            // inner and outer corners change sides. (A flip along Y keeps the footprint and so keeps the shape.)
            if (state.hasProperty(BlockStateProperties.STAIRS_SHAPE))
                state = state.setValue(
                        BlockStateProperties.STAIRS_SHAPE,
                        swapSides(state.getValue(BlockStateProperties.STAIRS_SHAPE)));
        }
        if (Math.abs(pitch % 90) < .0001 && Math.abs(roll % 90) < .0001)
            for (var property : from.getProperties()) {
                Direction direction = direction(property.getName());
                if (direction == null) continue;
                var rotated = rotate(direction, remainingYaw, pitch, roll);
                Direction destination = Compat.nearest(rotated.x(), rotated.y(), rotated.z());
                var target = state.getBlock().getStateDefinition().getProperty(destination.getName());
                var value = from.getValue(property);
                if (target != null && target.getPossibleValues().contains(value)) state = set(state, target, value);
            }
        return state;
    }

    private static Geometry.Vector rotate(Direction direction, double yaw, double pitch, double roll) {
        return Geometry.rotate(
                new Geometry.Vector(direction.getStepX(), direction.getStepY(), direction.getStepZ()),
                yaw,
                pitch,
                roll);
    }

    private static Direction direction(String name) {
        for (var direction : Direction.values()) if (direction.getName().equals(name)) return direction;
        return null;
    }

    /**
     * The game's mirror, with stairs done by hand: the game's own handling leaves some stair corners on the wrong side after a
     * reflection. A reflection turns the facing across the mirrored axis and always puts inner and outer corners on the other side.
     */
    static BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        if (state.hasProperty(BlockStateProperties.STAIRS_SHAPE)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var along = mirror == net.minecraft.world.level.block.Mirror.FRONT_BACK
                    ? Direction.Axis.X
                    : mirror == net.minecraft.world.level.block.Mirror.LEFT_RIGHT ? Direction.Axis.Z : null;
            if (along == null) return state;
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return state.setValue(
                            BlockStateProperties.HORIZONTAL_FACING,
                            facing.getAxis() == along ? facing.getOpposite() : facing)
                    .setValue(
                            BlockStateProperties.STAIRS_SHAPE,
                            swapSides(state.getValue(BlockStateProperties.STAIRS_SHAPE)));
        }
        return state.mirror(mirror);
    }

    private static StairsShape swapSides(StairsShape shape) {
        return switch (shape) {
            case INNER_LEFT -> StairsShape.INNER_RIGHT;
            case INNER_RIGHT -> StairsShape.INNER_LEFT;
            case OUTER_LEFT -> StairsShape.OUTER_RIGHT;
            case OUTER_RIGHT -> StairsShape.OUTER_LEFT;
            default -> shape;
        };
    }

    static BlockState flipHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HALF))
            state = state.setValue(
                    BlockStateProperties.HALF,
                    state.getValue(BlockStateProperties.HALF) == Half.TOP ? Half.BOTTOM : Half.TOP);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            state = state.setValue(
                    BlockStateProperties.DOUBLE_BLOCK_HALF,
                    state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                            ? DoubleBlockHalf.LOWER
                            : DoubleBlockHalf.UPPER);
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE)
            state = state.setValue(
                    BlockStateProperties.SLAB_TYPE,
                    state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP);
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState set(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
    }
}
