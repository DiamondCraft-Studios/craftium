package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Water and lava flow only through FlowingFluid.tick, so cancelling it freezes every flowing fluid where it is; placed fluid stays put and nothing new spreads. */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void craftium$spread(ServerLevel level,BlockPos pos,BlockState state,FluidState fluid,CallbackInfo ci){if(!WorldRules.get(level).fluidSpread)ci.cancel();}
    /** Flowing into a torch, rail, carpet or plant breaks it and drops the item; with the rule off the fluid stops at anything that is not air, fluid or a block that can hold it (waterlogging, kelp). */
    @Inject(method="spreadTo",at=@At("HEAD"),cancellable=true)
    private void craftium$wash(LevelAccessor level,BlockPos pos,BlockState state,Direction direction,FluidState fluid,CallbackInfo ci){if(level instanceof ServerLevel server&&!WorldRules.get(server).fluidInteractions&&!state.isAir()&&!(state.getBlock() instanceof LiquidBlock)&&!(state.getBlock() instanceof LiquidBlockContainer))ci.cancel();}
}
