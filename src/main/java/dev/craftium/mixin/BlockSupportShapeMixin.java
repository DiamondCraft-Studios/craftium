package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Torches, plants, carpets, buttons, levers, signs, banners, ladders and most other attached blocks break by turning into air when a neighbour changes. With the rule off the block keeps its state instead. Doors, tall plants, beds and piston heads are left alone because they would otherwise stay as one orphaned half. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockSupportShapeMixin {
    @Inject(method="updateShape",at=@At("RETURN"),cancellable=true)
    private void craftium$support(LevelReader level,ScheduledTickAccess ticks,BlockPos pos,Direction direction,BlockPos neighborPos,BlockState neighborState,RandomSource random,CallbackInfoReturnable<BlockState> cir){
        if(!cir.getReturnValue().isAir()||!(level instanceof ServerLevel server))return;
        var self=(BlockState)(Object)this;
        if(self.isAir()||self.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)||self.hasProperty(BlockStateProperties.BED_PART)||self.getBlock() instanceof PistonHeadBlock)return;
        if(!WorldRules.get(server).blockSupportBreak)cir.setReturnValue(self);
    }
}
