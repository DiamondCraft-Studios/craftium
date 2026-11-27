package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.21.1 copy: the copper blocks that oxidise in 1.21.1, each from its own randomTick. */
@Mixin({
    WeatheringCopperFullBlock.class,
    WeatheringCopperSlabBlock.class,
    WeatheringCopperStairBlock.class,
    WeatheringCopperBulbBlock.class,
    WeatheringCopperDoorBlock.class,
    WeatheringCopperGrateBlock.class,
    WeatheringCopperTrapDoorBlock.class
})
abstract class BlockWeatheringMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void craftium$rule(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (!WorldRules.get(level).blockWeathering) ci.cancel();
    }
}
