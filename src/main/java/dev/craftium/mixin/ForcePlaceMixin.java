package dev.craftium.mixin;
import dev.craftium.runtime.ForcePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/**
 * Force place: while a forced placement is in flight, every block survives. A block of two halves, such as a door or tall
 * plant, places its second half after the first, and that update would otherwise break the first half where it has no support.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class ForcePlaceMixin {
    @Inject(method="canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",at=@At("HEAD"),cancellable=true)
    private void craftium$forced(LevelReader level,BlockPos pos,CallbackInfoReturnable<Boolean> cir){if(ForcePlacement.active())cir.setReturnValue(true);}
}
