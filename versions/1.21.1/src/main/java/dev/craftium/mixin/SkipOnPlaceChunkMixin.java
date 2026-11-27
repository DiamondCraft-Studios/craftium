package dev.craftium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Minecraft 1.21.1 only: the chunk half of {@link SkipOnPlaceMixin}. onPlace runs on the server thread only. */
@Mixin(LevelChunk.class)
abstract class SkipOnPlaceChunkMixin {
    @WrapOperation(
            method =
                    "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockState;onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private void craftium$onPlace(
            BlockState state, Level level, BlockPos pos, BlockState old, boolean moving, Operation<Void> original) {
        if (!dev.craftium.compat.SkipOnPlace.active) original.call(state, level, pos, old, moving);
    }
}
