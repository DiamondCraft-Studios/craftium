package dev.craftium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.craftium.compat.Compat;
import dev.craftium.compat.SkipOnPlace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft 1.21.1 only: backports the placement flag that skips the new block's onPlace (which schedules falling,
 * flowing and redstone), so blocks Craftium's tools write without updates stay where they are put. {@code Level.setBlock}
 * notes the flag while it writes into the chunk; {@link SkipOnPlaceChunkMixin} then leaves out the onPlace call.
 */
@Mixin(Level.class)
abstract class SkipOnPlaceMixin {
    @WrapOperation(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState craftium$skipOnPlace(
            LevelChunk chunk,
            BlockPos pos,
            BlockState state,
            boolean moving,
            Operation<BlockState> original,
            @Local(argsOnly = true, ordinal = 0) int flags) {
        boolean was = SkipOnPlace.active;
        SkipOnPlace.active = (flags & Compat.UPDATE_SKIP_ON_PLACE) != 0;
        try {
            return original.call(chunk, pos, state, moving);
        } finally {
            SkipOnPlace.active = was;
        }
    }
}
