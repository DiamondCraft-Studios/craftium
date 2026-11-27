package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft 1.21.1 copy of the shared mixin: updateShape takes the direction and neighbour first there. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockSupportShapeMixin {
    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void craftium$support(
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos,
            CallbackInfoReturnable<BlockState> cir) {
        if (!cir.getReturnValue().isAir() || !(level instanceof ServerLevel server)) return;
        var self = (BlockState) (Object) this;
        if (self.isAir()
                || self.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || self.hasProperty(BlockStateProperties.BED_PART)
                || self.getBlock() instanceof PistonHeadBlock) return;
        if (!WorldRules.get(server).blockSupportBreak) cir.setReturnValue(self);
    }
}
