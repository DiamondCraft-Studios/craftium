package dev.craftium.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.craftium.runtime.PlayerCapabilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge version: the client removes a broken block through {@code BlockState.onDestroyedByPlayer}, which writes it
 * with flags 11; with no updates it is written with the flags that notify no neighbour and update no shape instead.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class ClientBlockBreakMixin {
    @WrapOperation(
            method = "destroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockState;onDestroyedByPlayer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/level/material/FluidState;)Z"))
    private boolean craftium$flags(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            boolean willHarvest,
            FluidState fluid,
            Operation<Boolean> original) {
        var options = PlayerCapabilities.of(Minecraft.getInstance().player);
        if (options != null && options.noUpdates) return level.setBlock(pos, fluid.createLegacyBlock(), 2 | 16);
        return original.call(state, level, pos, player, willHarvest, fluid);
    }
}
