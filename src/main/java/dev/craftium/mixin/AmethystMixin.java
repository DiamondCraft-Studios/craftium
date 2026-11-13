package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.BuddingAmethystBlock;
/** Budding amethyst grows its buds in randomTick. */
@Mixin(BuddingAmethystBlock.class)
abstract class AmethystMixin {
    @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
    private void craftium$rule(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(!WorldRules.get(level).dripstoneAndAmethyst)ci.cancel();}
}
