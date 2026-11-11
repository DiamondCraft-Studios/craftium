package dev.craftium.server;

import dev.craftium.core.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A level for a feature to place itself in that writes nothing: what the feature sets goes into a map, and what it reads comes from
 * that map first (so it sees the pieces planned before it) and from the real world after that. The methods are recognised by what they
 * take and return, not by name, so this works whatever the game's names are called at run time.
 */
final class RecordingLevel implements InvocationHandler {
    /** A feature that writes more than this is stopped: it is a runaway, not a tree. */
    private static final int LIMIT = 100_000;

    private final ServerLevel real;
    private final Map<Point, BlockSnapshot> blocks;
    private int written;

    private RecordingLevel(ServerLevel real, Map<Point, BlockSnapshot> blocks) {
        this.real = real;
        this.blocks = blocks;
    }

    /** A level over {@code real} whose writes land in {@code blocks}. */
    static WorldGenLevel over(ServerLevel real, Map<Point, BlockSnapshot> blocks) {
        return (WorldGenLevel) Proxy.newProxyInstance(RecordingLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, new RecordingLevel(real, blocks));
    }

    private BlockState stateAt(BlockPos pos) {
        var planned = blocks.get(new Point(pos.getX(), pos.getY(), pos.getZ()));
        return planned != null ? planned.state() : real.getBlockState(pos);
    }

    private boolean write(BlockPos pos, BlockState state) {
        if (++written > LIMIT) throw new IllegalStateException("A feature wrote more than " + LIMIT + " blocks");
        blocks.put(new Point(pos.getX(), pos.getY(), pos.getZ()), new BlockSnapshot(state));
        return true;
    }

    /** Whether the second parameter of a (position, predicate) method is a predicate of the given type, which tells the block test from the fluid test. */
    private static boolean tests(Method method, Class<?> type) {
        return method.getGenericParameterTypes()[1] instanceof ParameterizedType p && p.getActualTypeArguments().length == 1 && p.getActualTypeArguments()[0] == type;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "RecordingLevel";
            };
        }
        var types = method.getParameterTypes();
        var returns = method.getReturnType();
        boolean pos = types.length > 0 && types[0] == BlockPos.class;
        if (pos && types.length == 1 && returns == BlockState.class) return stateAt((BlockPos) args[0]);
        if (pos && types.length == 1 && returns == FluidState.class) return stateAt((BlockPos) args[0]).getFluidState();
        if (pos && types.length == 1 && returns == BlockEntity.class) return null; // features do not leave block entities behind
        if (pos && types.length == 2 && types[1] == Predicate.class && returns == boolean.class) {
            var at = (BlockPos) args[0];
            if (tests(method, BlockState.class)) return ((Predicate<BlockState>) args[1]).test(stateAt(at));
            if (tests(method, FluidState.class)) return ((Predicate<FluidState>) args[1]).test(stateAt(at).getFluidState());
        }
        if (pos && types.length >= 3 && types[1] == BlockState.class && returns == boolean.class) return write((BlockPos) args[0], (BlockState) args[1]); // setBlock
        if (pos && types.length >= 2 && types[1] == boolean.class && returns == boolean.class) { // removeBlock and destroyBlock: the block goes, with no drops
            var at = (BlockPos) args[0];
            return write(at, stateAt(at).getFluidState().createLegacyBlock());
        }
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
