package dev.craftium.compat;

import com.mojang.math.Transformation;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Minecraft-version boundary. Shared code calls these methods instead of the APIs that changed
 * between releases; each {@code versions/<mc>} module replaces this file with its own implementation.
 * This is the 26.2 implementation.
 */
public final class Compat {
    private Compat() {}

    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static Identifier parse(String id) {
        return Identifier.parse(id);
    }
    /** Like {@link #parse} but returns null for text that is not a valid identifier. */
    public static Identifier tryParse(String id) {
        return Identifier.tryParse(id);
    }

    public static Identifier vanilla(String path) {
        return Identifier.withDefaultNamespace(path);
    }
    /** Namespaced name of a registry or dimension key, e.g. {@code minecraft:overworld}. */
    public static String keyName(ResourceKey<?> key) {
        return key.identifier().toString();
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
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
    /** The player's command source raised to game-master (operator level 2) permission. */
    public static CommandSourceStack gamemasterSource(ServerPlayer player) {
        return player.createCommandSourceStack()
                .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
    }

    public static boolean isGamemaster(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Rebuilds an entity, with passengers, from its saved data. Returns null when the data is invalid. */
    public static Entity loadEntity(CompoundTag tag, Level level) {
        return EntityType.loadEntityRecursive(
                tag,
                level,
                new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.COMMAND, true),
                net.minecraft.world.entity.EntityProcessor.NOP);
    }

    public static Set<String> entityTags(Entity entity) {
        return entity.entityTags();
    }

    public static long chunkKey(int x, int z) {
        return net.minecraft.world.level.ChunkPos.pack(x, z);
    }

    public static Transformation identityTransform() {
        return Transformation.IDENTITY;
    }
    /** A block entity type for one block; the constructor is public here. */
    public static <T extends net.minecraft.world.level.block.entity.BlockEntity>
            net.minecraft.world.level.block.entity.BlockEntityType<T> blockEntityType(
                    net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier<T> factory,
                    net.minecraft.world.level.block.Block block) {
        return new net.minecraft.world.level.block.entity.BlockEntityType<>(factory, Set.of(block));
    }

    /** The registered block with this id, or air for an id nothing is registered under. */
    public static net.minecraft.world.level.block.Block block(net.minecraft.resources.Identifier id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
    }

    /** The block registry, as a lookup that reads saved block states. */
    public static net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blockLookup() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK;
    }
}
