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
        return net.minecraft.SharedConstants.getCurrentVersion()
                .getDataVersion()
                .getVersion();
    }

    /** The lowest block layer of a level. */
    public static int minY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMinBuildHeight();
    }

    /** The highest block layer of a level (inclusive). */
    public static int maxY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMaxBuildHeight() - 1;
    }

    /** The direction closest to a vector. */
    public static net.minecraft.core.Direction nearest(double x, double y, double z) {
        return net.minecraft.core.Direction.getNearest(x, y, z);
    }

    public static int selectedSlot(net.minecraft.world.entity.player.Inventory inventory) {
        return inventory.selected;
    }

    public static void selectSlot(net.minecraft.world.entity.player.Inventory inventory, int slot) {
        inventory.selected = slot;
    }

    public static String profileName(com.mojang.authlib.GameProfile profile) {
        return profile.getName();
    }

    public static void markUnsaved(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        chunk.setUnsaved(true);
    }

    /** Block properties for a block registered under {@code key}; 1.21.1 blocks do not carry their id. */
    public static net.minecraft.world.level.block.state.BlockBehaviour.Properties blockProperties(
            ResourceKey<net.minecraft.world.level.block.Block> key) {
        return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of();
    }

    /** Whether a block state fills its whole block with an opaque model. */
    public static boolean isSolidRender(net.minecraft.world.level.block.state.BlockState state) {
        return state.isSolidRender(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
    }

    /**
     * Placement flag that skips the new block's onPlace. 1.21.1 has no such flag, so Craftium gives the bit later
     * versions use (unused as a flag in 1.21.1) the same meaning, through {@link dev.craftium.mixin.SkipOnPlaceMixin}.
     */
    public static final int UPDATE_SKIP_ON_PLACE = 512;

    /** An entity saved with its id, or null when the entity cannot be saved (a passenger, a removed entity). */
    public static CompoundTag saveEntity(Entity entity, net.minecraft.server.level.ServerLevel level) {
        var out = new CompoundTag();
        return entity.save(out) ? out : null;
    }

    /** The item a creative pick of a block gives, carrying the block's state and its block entity data. */
    public static net.minecraft.world.item.ItemStack pickBlock(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        var stack = state.getBlock().getCloneItemStack(level, pos, state);
        if (stack.isEmpty()) return stack;
        var properties = net.minecraft.world.item.component.BlockItemStateProperties.EMPTY;
        for (var property : state.getProperties()) properties = properties.with(property, state);
        stack.set(net.minecraft.core.component.DataComponents.BLOCK_STATE, properties);
        var entity = level.getBlockEntity(pos);
        if (entity != null) {
            CompoundTag out = entity.saveCustomOnly(level.registryAccess());
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
        return state.updateShape(side, neighbour, level, pos, beside);
    }

    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> LEASE_TICKET =
            net.minecraft.server.level.TicketType.create(
                    "craftium_lease", java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));

    /** Loads one chunk and nothing else: it neither ticks, nor is saved in the world's forced chunks, nor times out. */
    public static void addLeaseTicket(
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        level.getChunkSource().addRegionTicket(LEASE_TICKET, pos, 0, pos);
    }

    public static void removeLeaseTicket(
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        level.getChunkSource().removeRegionTicket(LEASE_TICKET, pos, 0, pos);
    }

    /** Whether replacing {@code before} with {@code after} at {@code pos} needs a light check. */
    public static boolean lightDiffers(
            net.minecraft.world.level.BlockGetter level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        return net.minecraft.world.level.lighting.LightEngine.hasDifferentLightProperties(level, pos, before, after);
    }

    /** 1.21.1 has no chunk-source hook for section emptiness; the light engine's section status is all it tracks. */
    public static void sectionEmptinessChanged(
            net.minecraft.server.level.ServerChunkCache chunks, int x, int y, int z, boolean empty) {}

    /** Updates points of interest (beds, workstations, portals) for a block written without setBlock. */
    public static void blockStateChanged(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        level.onBlockStateChange(pos, before, after);
    }

    /** The level a server player is in. */
    public static net.minecraft.server.level.ServerLevel level(ServerPlayer player) {
        return player.serverLevel();
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
        return EntityType.loadEntityRecursive(tag, level, Function.identity());
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

    /** A block entity type for one block, from vanilla's builder (public in 1.21.1, so no loader help is needed). */
    public static <T extends net.minecraft.world.level.block.entity.BlockEntity>
            net.minecraft.world.level.block.entity.BlockEntityType<T> blockEntityType(
                    java.util.function.BiFunction<
                                    net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState, T>
                            factory,
                    net.minecraft.world.level.block.Block block) {
        return net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(factory::apply, block)
                .build(null);
    }

    /** The registered block with this id, or air for an id nothing is registered under. */
    public static net.minecraft.world.level.block.Block block(net.minecraft.resources.ResourceLocation id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
    }

    /** The block registry, as a lookup that reads saved block states. */
    public static net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blockLookup() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup();
    }
}
