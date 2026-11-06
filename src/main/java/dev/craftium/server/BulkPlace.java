package dev.craftium.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Writes the blocks of one chunk in a single pass. {@code Level.setBlock} brings the chunk's heightmaps and sky light sources up
 * to date after every block, and taking a column's top block out makes it look down the column for the next one, so a large
 * removal spends most of its time there. Here the blocks go straight into the chunk's sections and each of those is brought up to
 * date once for the whole run; what {@code setBlock} still does for every block (the light check, the client update, the point of
 * interest update) is still done for every block. Blocks that need more than a plain write take {@link BlockSnapshot#place}.
 */
public final class BulkPlace {
    /** Below this many blocks in a chunk, the per-block bookkeeping costs less than redoing the whole chunk's. */
    static final int MIN_RUN = 64;
    /** The heightmaps a loaded chunk keeps up to date; the ones {@code LevelChunk.setBlockState} updates. */
    private static final EnumSet<Heightmap.Types> HEIGHTMAPS = EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);
    /** Lets a test turn the chunk write off to compare it with the per-block path. */
    public static boolean enabled = true;
    /** How many blocks have been written by this class; a test reads it to know the chunk write was the one used. */
    public static long blocksWritten;

    private BulkPlace() {}

    /** Whether a run of this size, written with these flags, can be written as a run: the flags must be exactly the editor's own. */
    static boolean applies(int size, int flags) {
        return enabled && size >= MIN_RUN && ((flags & ~Block.UPDATE_NEIGHBORS) | BlockSnapshot.NO_UPDATES) == BlockSnapshot.NO_UPDATES;
    }

    /** Writes every block of {@code run}, all of which are in {@code chunk}, to what the edit wants there. */
    static void place(ServerLevel level, LevelChunk chunk, List<Session.Change> run, boolean backwards, int flags) {
        var sections = chunk.getSections();
        var emptyBefore = new boolean[sections.length];
        var touched = new BlockPos[sections.length]; // a block in each section that was written, once it has been
        var positions = new ArrayList<BlockPos>(run.size());
        var before = new ArrayList<BlockState>(run.size());
        var after = new ArrayList<BlockState>(run.size());
        boolean lightChanged = false;
        for (var change : run) {
            var wanted = backwards ? change.before() : change.after();
            BlockPos pos = EditPlanner.pos(change.pos());
            int index = chunk.getSectionIndex(pos.getY());
            var section = sections[index];
            int x = pos.getX() & 15, y = pos.getY() & 15, z = pos.getZ() & 15;
            var old = section.getBlockState(x, y, z);
            // Block entities are made, kept and removed by more than a section write, so they keep the full path.
            if (wanted.data() != null || wanted.state().hasBlockEntity() || old.hasBlockEntity()) { wanted.place(level, pos, flags); continue; }
            if (old == wanted.state()) continue;
            if (touched[index] == null) { touched[index] = pos; emptyBefore[index] = section.hasOnlyAir(); }
            section.setBlockState(x, y, z, wanted.state());
            positions.add(pos);
            before.add(old);
            after.add(wanted.state());
            if (LightEngine.hasDifferentLightProperties(old, wanted.state())) lightChanged = true;
        }
        if (positions.isEmpty()) return;
        chunk.markUnsaved();blocksWritten += positions.size();
        Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
        var chunkSource = level.getChunkSource();
        var lighting = chunkSource.getLightEngine();
        for (int i = 0; i < sections.length; i++) {
            if (touched[i] == null) continue;
            boolean empty = sections[i].hasOnlyAir();
            if (empty == emptyBefore[i]) continue;
            lighting.updateSectionStatus(touched[i], empty);
            chunkSource.onSectionEmptinessChanged(touched[i].getX() >> 4, touched[i].getY() >> 4, touched[i].getZ() >> 4, empty);
        }
        // The light engine reads these sources when it runs a check, so they are right before any check is queued.
        if (lightChanged) chunk.getSkyLightSources().fillFrom(chunk);
        boolean send = chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            var old = before.get(i);
            var state = after.get(i);
            if (LightEngine.hasDifferentLightProperties(old, state)) lighting.checkBlock(pos);
            level.setBlocksDirty(pos, old, state);
            if (send) level.sendBlockUpdated(pos, old, state, Block.UPDATE_CLIENTS);
            level.updatePOIOnBlockStateChange(pos, old, state);
        }
    }
}
