package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.*;
/** Every copper block that oxidises does so from its own randomTick. */
@Mixin({WeatheringCopperFullBlock.class,WeatheringCopperSlabBlock.class,WeatheringCopperStairBlock.class,WeatheringCopperBulbBlock.class,WeatheringCopperChestBlock.class,WeatheringCopperDoorBlock.class,WeatheringCopperGrateBlock.class,WeatheringCopperTrapDoorBlock.class,WeatheringCopperGolemStatueBlock.class,WeatheringCopperChainBlock.class,WeatheringCopperBarsBlock.class,WeatheringLightningRodBlock.class,WeatheringLanternBlock.class})
abstract class BlockWeatheringMixin {
    @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
    private void craftium$rule(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(!WorldRules.get(level).blockWeathering)ci.cancel();}
}
