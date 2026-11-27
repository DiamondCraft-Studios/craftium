package dev.craftium.mixin;

import dev.craftium.Craftium;
import dev.craftium.server.MissingBlockRewrite;
import dev.craftium.server.MissingBlocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft 1.21.1 copy: chunks are read by ChunkSerializer.read there, before the blocks are decoded. */
@Mixin(ChunkSerializer.class)
abstract class MissingBlockChunkMixin {
    @Inject(method = "read", at = @At("HEAD"))
    private static void craftium$keepMissingBlocks(
            ServerLevel level,
            PoiManager poiManager,
            RegionStorageInfo info,
            ChunkPos pos,
            CompoundTag tag,
            CallbackInfoReturnable<ProtoChunk> cir) {
        var table = MissingBlocks.table();
        if (table == null || MissingBlocks.BLOCK == null) return;
        try {
            MissingBlockRewrite.apply(tag, table, MissingBlocks::exists);
        }
        // A chunk that cannot be rewritten loads the way it always did, with its missing blocks as air.
        catch (RuntimeException e) {
            Craftium.LOG.warn("Could not keep the missing blocks of a chunk", e);
        }
    }
}
