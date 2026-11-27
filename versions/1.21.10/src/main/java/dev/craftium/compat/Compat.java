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

    /** The data version of this Minecraft, as saved in structure and world files. */
    public static int dataVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** The lowest block layer of a level. */
    public static int minY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMinY();
    }

    /** The highest block layer of a level (inclusive). */
    public static int maxY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMaxY();
    }

    /** The direction closest to a vector. */
    public static net.minecraft.core.Direction nearest(double x, double y, double z) {
        return net.minecraft.core.Direction.getApproximateNearest(x, y, z);
    }

    public static int selectedSlot(net.minecraft.world.entity.player.Inventory inventory) {
        return inventory.getSelectedSlot();
    }

    public static void selectSlot(net.minecraft.world.entity.player.Inventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
    }

    public static String profileName(com.mojang.authlib.GameProfile profile) {
        return profile.name();
    }

    public static void markUnsaved(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        chunk.markUnsaved();
    }

    /** Block properties for a block registered under {@code key}. */
    public static net.minecraft.world.level.block.state.BlockBehaviour.Properties blockProperties(
            ResourceKey<net.minecraft.world.level.block.Block> key) {
        return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                .setId(key);
    }

    /** Whether a block state fills its whole block with an opaque model. */
    public static boolean isSolidRender(net.minecraft.world.level.block.state.BlockState state) {
        return state.isSolidRender();
    }

    /** Placement flag that skips the new block's onPlace; 0 where Minecraft has no such flag. */
    public static final int UPDATE_SKIP_ON_PLACE = net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE;

    /** An entity saved with its id, or null when the entity cannot be saved (a passenger, a removed entity). */
    public static CompoundTag saveEntity(Entity entity, net.minecraft.server.level.ServerLevel level) {
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        return entity.save(out) ? out.buildResult() : null;
    }

    /** The item a creative pick of a block gives, carrying the block's state and its block entity data. */
    public static net.minecraft.world.item.ItemStack pickBlock(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        var stack = state.getCloneItemStack(level, pos, true);
        if (stack.isEmpty()) return stack;
        var properties = net.minecraft.world.item.component.BlockItemStateProperties.EMPTY;
        for (var property : state.getProperties()) properties = properties.with(property, state);
        stack.set(net.minecraft.core.component.DataComponents.BLOCK_STATE, properties);
        var entity = level.getBlockEntity(pos);
        if (entity != null) {
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
            entity.saveCustomOnly(out);
            entity.removeComponentsFromTag(out);
            net.minecraft.world.item.BlockItem.setBlockEntityData(stack, entity.getType(), out);
            stack.applyComponents(entity.collectComponents());
        }
        return stack;
    }

    /** A block state reshaped for a neighbour on {@code side}, without scheduling anything in the level. */
    public static net.minecraft.world.level.block.state.BlockState updateShape(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.core.Direction side,
            net.minecraft.core.BlockPos beside,
            net.minecraft.world.level.block.state.BlockState neighbour) {
        return state.updateShape(level, level, pos, side, beside, neighbour, level.getRandom());
    }

    private static net.minecraft.server.level.TicketType leaseTicket;

    /** Loads one chunk and nothing else: it neither ticks, nor is saved in the world's forced chunks, nor times out. */
    public static void addLeaseTicket(
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        level.getChunkSource().addTicketWithRadius(leaseTicket(), pos, 0);
    }

    public static void removeLeaseTicket(
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        level.getChunkSource().removeTicketWithRadius(leaseTicket(), pos, 0);
    }

    /** Made on first use, since building it needs the game's registries. */
    private static net.minecraft.server.level.TicketType leaseTicket() {
        if (leaseTicket == null)
            leaseTicket = new net.minecraft.server.level.TicketType(
                    net.minecraft.server.level.TicketType.NO_TIMEOUT,
                    net.minecraft.server.level.TicketType.FLAG_LOADING);
        return leaseTicket;
    }

    /** Whether replacing {@code before} with {@code after} at {@code pos} needs a light check. */
    public static boolean lightDiffers(
            net.minecraft.world.level.BlockGetter level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        return net.minecraft.world.level.lighting.LightEngine.hasDifferentLightProperties(before, after);
    }

    /** Tells the chunk source that a section became empty or stopped being empty. */
    public static void sectionEmptinessChanged(
            net.minecraft.server.level.ServerChunkCache chunks, int x, int y, int z, boolean empty) {
        chunks.onSectionEmptinessChanged(x, y, z, empty);
    }

    /** Updates points of interest (beds, workstations, portals) for a block written without setBlock. */
    public static void blockStateChanged(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        level.updatePOIOnBlockStateChange(pos, before, after);
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

    /** The registered block with this id, or air for an id nothing is registered under. */
    public static net.minecraft.world.level.block.Block block(net.minecraft.resources.ResourceLocation id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
    }
}
