package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.craftium.core.Geometry;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Checks stairs against their real collision shape: the shape of the transformed state must be the transformed shape. */
class StairTransformTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static boolean occupied(BlockState state, double x, double y, double z) {
        for (var box : state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs())
            if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY && z >= box.minZ && z <= box.maxZ)
                return true;
        return false;
    }

    /** Every octant centre of the block, relative to the block centre. */
    private static void forEachOctant(Octant each) {
        for (double x : new double[] {-.25, .25})
            for (double y : new double[] {-.25, .25}) for (double z : new double[] {-.25, .25}) each.at(x, y, z);
    }

    private interface Octant {
        void at(double x, double y, double z);
    }

    private static void forEachStair(UnaryOperator<BlockState> check) {
        for (var state : Blocks.OAK_STAIRS.getStateDefinition().getPossibleStates()) check.apply(state);
    }

    private static void assertMoved(
            BlockState before,
            BlockState after,
            java.util.function.Function<Geometry.Vector, Geometry.Vector> map,
            String what) {
        forEachOctant((x, y, z) -> {
            var to = map.apply(new Geometry.Vector(x, y, z));
            assertEquals(
                    occupied(before, x + .5, y + .5, z + .5),
                    occupied(after, to.x() + .5, to.y() + .5, to.z() + .5),
                    what + ": " + before + " -> " + after + " at " + x + "," + y + "," + z);
        });
    }

    @Test
    void rotationsFollowTheShape() {
        double[][] turns = {{90, 0, 0}, {180, 0, 0}, {270, 0, 0}, {0, 180, 0}, {0, 0, 180}, {90, 180, 0}};
        for (var t : turns)
            forEachStair(state -> {
                var after = StateTransforms.rotate(state, t[0], t[1], t[2]);
                assertMoved(state, after, v -> Geometry.rotate(v, t[0], t[1], t[2]), java.util.Arrays.toString(t));
                return state;
            });
    }

    @Test
    void flipsFollowTheShape() {
        forEachStair(state -> {
            assertMoved(
                    state, BuilderEdits.turn(state, 0, 0), v -> new Geometry.Vector(-v.x(), v.y(), v.z()), "flip x");
            assertMoved(
                    state, BuilderEdits.turn(state, 0, 1), v -> new Geometry.Vector(v.x(), -v.y(), v.z()), "flip y");
            assertMoved(
                    state, BuilderEdits.turn(state, 0, 2), v -> new Geometry.Vector(v.x(), v.y(), -v.z()), "flip z");
            return state;
        });
    }

    @Test
    void anyStairKeepsItsShapeProperty() {
        assertTrue(Blocks.OAK_STAIRS.defaultBlockState().hasProperty(BlockStateProperties.STAIRS_SHAPE));
    }
}
