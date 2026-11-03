package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(FarmlandBlock.class)
abstract class FarmlandMixin {
    @Inject(method="fallOn",at=@At("HEAD"),cancellable=true)
    private void craftium$farmland(Level level,BlockState state,BlockPos pos,Entity entity,double distance,CallbackInfo ci){if(level instanceof ServerLevel server&&!WorldRules.get(server).trampleFarmland)ci.cancel();}
}
