package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.SpeleothemBlock;
/** Stalactite growth and falling are SpeleothemBlock.tick and randomTick on 26.2. */
@Mixin(SpeleothemBlock.class)
abstract class SpeleothemMixin {
    @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
    private void craftium$rule(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(!WorldRules.get(level).dripstoneAndAmethyst)ci.cancel();}
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void craftium$fall(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(!WorldRules.get(level).dripstoneAndAmethyst)ci.cancel();}
}
