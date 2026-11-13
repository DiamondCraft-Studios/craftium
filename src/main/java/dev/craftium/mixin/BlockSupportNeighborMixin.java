package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Repeaters, comparators and redstone dust check their support in neighborChanged rather than updateShape and remove themselves there. */
@Mixin({DiodeBlock.class,RedStoneWireBlock.class})
abstract class BlockSupportNeighborMixin {
    @Redirect(method="neighborChanged",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean craftium$survive(BlockState state,LevelReader level,BlockPos pos){return state.canSurvive(level,pos)||(level instanceof ServerLevel server&&!WorldRules.get(server).blockSupportBreak);}
}
