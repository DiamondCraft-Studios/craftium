package dev.craftium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.craftium.runtime.PlayerCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge version: a player breaking a block removes it through {@code BlockState.onDestroyedByPlayer}, not
 * {@code Level.removeBlock}, so that is where solid fluids and no-update breaking take over, as on Fabric.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class BlockBreakMixin {
    @Shadow
    @Final
    protected ServerPlayer player;

    @WrapOperation(
            method = "removeBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Z",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockState;onDestroyedByPlayer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/level/material/FluidState;)Z"))
    private boolean craftium$remove(
            BlockState state,
            Level level,
            BlockPos pos,
            Player breaker,
            boolean willHarvest,
            FluidState fluid,
            Operation<Boolean> original) {
        var options = PlayerCapabilities.of(player);
        // Removing a liquid block normally leaves its fluid behind; with solid fluids, source and flowing alike are
        // replaced with air.
        if (options != null
                && options.solidFluids
                && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
            return level.setBlock(
                    pos,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    options.noUpdates ? 2 | 16 : 3);
        // What removing the block does, with the flags that notify no neighbour and update no shape.
        if (options != null && options.noUpdates) return level.setBlock(pos, fluid.createLegacyBlock(), 2 | 16);
        return original.call(state, level, pos, breaker, willHarvest, fluid);
    }
}
