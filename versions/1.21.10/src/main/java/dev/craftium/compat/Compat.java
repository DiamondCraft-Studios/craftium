package dev.craftium.compat;

import com.mojang.math.Transformation;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Minecraft 1.21.10 implementation of the version boundary; see the 26.2 file in src/main for the contract. */
public final class Compat {
    private Compat() {}

    public static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    public static ResourceLocation parse(String id) {
        return ResourceLocation.parse(id);
    }

    public static ResourceLocation vanilla(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    public static ResourceLocation tryParse(String id) {
        return ResourceLocation.tryParse(id);
    }

    public static String keyName(ResourceKey<?> key) {
        return key.location().toString();
    }
    /** Namespaced name of a tag, e.g. {@code minecraft:infiniburn_overworld}. */
    public static String tagName(net.minecraft.tags.TagKey<?> tag) {
        return tag.location().toString();
    }

    /** The level a server player is in. */
    public static net.minecraft.server.level.ServerLevel level(ServerPlayer player) {
        return player.level();
    }

    public static boolean isGamemaster(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    public static CommandSourceStack gamemasterSource(ServerPlayer player) {
        return player.createCommandSourceStack().withPermission(2);
    }

    public static boolean isGamemaster(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    public static Entity loadEntity(CompoundTag tag, Level level) {
        return EntityType.loadEntityRecursive(tag, level, EntitySpawnReason.COMMAND, Function.identity());
    }

    public static Set<String> entityTags(Entity entity) {
        return entity.getTags();
    }

    public static long chunkKey(int x, int z) {
        return net.minecraft.world.level.ChunkPos.asLong(x, z);
    }

    public static Transformation identityTransform() {
        return Transformation.identity();
    }

    /**
     * A block entity type for one block. The constructor is private in 1.21.10, so this goes through Fabric API's
     * builder, found by name: the Paper plugin compiles this file without Fabric API and never calls this.
     */
    @SuppressWarnings("unchecked")
    public static <T extends net.minecraft.world.level.block.entity.BlockEntity>
            net.minecraft.world.level.block.entity.BlockEntityType<T> blockEntityType(
                    java.util.function.BiFunction<
                                    net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState, T>
                            factory,
                    net.minecraft.world.level.block.Block block) {
        try {
            String name = "net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder";
            Class<?> builder = Class.forName(name);
            Class<?> supplier = Class.forName(name + "$Factory");
            Object made = java.lang.reflect.Proxy.newProxyInstance(
                    supplier.getClassLoader(), new Class<?>[] {supplier}, (proxy, method, args) -> {
                        if (method.getName().equals("create"))
                            return factory.apply(
                                    (net.minecraft.core.BlockPos) args[0],
                                    (net.minecraft.world.level.block.state.BlockState) args[1]);
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "Craftium block entity factory";
                        };
                    });
            Object type = builder.getMethod("create", supplier, net.minecraft.world.level.block.Block[].class)
                    .invoke(null, made, new net.minecraft.world.level.block.Block[] {block});
            return (net.minecraft.world.level.block.entity.BlockEntityType<T>)
                    builder.getMethod("build").invoke(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot make a block entity type without Fabric API", e);
        }
    }
}
