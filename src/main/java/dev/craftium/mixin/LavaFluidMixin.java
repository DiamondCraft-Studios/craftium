package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Lava flowing down onto water makes stone in its own spreadTo, before the shared FlowingFluid code runs. */
@Mixin(LavaFluid.class)
abstract class LavaFluidMixin {
    @Inject(method="spreadTo",at=@At("HEAD"),cancellable=true)
    private void craftium$stone(LevelAccessor level,BlockPos pos,BlockState state,Direction direction,FluidState fluid,CallbackInfo ci){if(level instanceof ServerLevel server&&!WorldRules.get(server).fluidInteractions&&level.getFluidState(pos).is(FluidTags.WATER))ci.cancel();}
}
