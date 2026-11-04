package dev.craftium.mixin;
import dev.craftium.runtime.PlayerCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
/** No updates: a block the player breaks leaves its neighbours alone, so the plant on top of it stays. */
@Mixin(ServerPlayerGameMode.class)
abstract class BlockBreakMixin {
    @Shadow @Final protected ServerPlayer player;
    @Redirect(method="destroyBlock",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean craftium$remove(ServerLevel level,BlockPos pos,boolean movedByPiston){
        var options=PlayerCapabilities.of(player);
        // Removing a liquid block normally leaves its fluid behind; with solid fluids, source and flowing alike are replaced with air.
        if(options!=null&&options.solidFluids&&level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)return level.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),options.noUpdates?2|16:3);
        // What removeBlock does, with the flags that notify no neighbour and update no shape.
        if(options!=null&&options.noUpdates)return level.setBlock(pos,level.getFluidState(pos).createLegacyBlock(),2|16);
        return level.removeBlock(pos,movedByPiston);
    }
}
