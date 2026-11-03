package dev.craftium.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Block entity data is included so undo and clipboard never silently discard inventories. */
public record BlockSnapshot(BlockState state, CompoundTag data) {
    public static BlockSnapshot capture(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        return new BlockSnapshot(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
    }
    public BlockSnapshot(BlockState state) { this(state,null); }
    /**
     * How the editor writes every block, whatever tool made the edit and whether it is being made, undone or redone: sent to
     * clients, and with nothing updated. No neighbour is told (so a plant beside a removed block stays), no neighbour's shape
     * is recalculated (fences, walls and stairs keep their connections), the block's own {@code onPlace} is skipped (so sand
     * does not fall, water and lava do not flow and redstone does not start), and nothing drops.
     */
    public static final int NO_UPDATES = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_ON_PLACE;

    public void place(ServerLevel level, BlockPos pos, int flags) {
        // Suppress drops: an undoable editor operation must not duplicate container contents as item entities.
        if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
        // Whatever flags the edit carries, the neighbour notification bit is never set and the rest of NO_UPDATES always is.
        level.setBlock(pos,state,(flags & ~Block.UPDATE_NEIGHBORS) | NO_UPDATES);
        if (data != null) {
            var entity = BlockEntity.loadStatic(pos,state,data.copy(),level.registryAccess());
            if (entity != null) { level.setBlockEntity(entity); entity.setChanged(); }
        }
        level.sendBlockUpdated(pos,state,state,2);
    }
    public boolean matches(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos) != state) return false;
        var entity = level.getBlockEntity(pos);
        return data == null ? entity == null : entity != null && data.equals(entity.saveWithFullMetadata(level.registryAccess()));
    }
}
