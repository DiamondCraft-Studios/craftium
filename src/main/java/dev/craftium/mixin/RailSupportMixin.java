package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** A rail with no floor or ramp support is removed when shouldBeRemoved says so; with the rule off it is kept and just reshapes. */
@Mixin(BaseRailBlock.class)
abstract class RailSupportMixin {
    @Inject(method="shouldBeRemoved",at=@At("RETURN"),cancellable=true)
    private static void craftium$rail(BlockPos pos,Level level,RailShape shape,CallbackInfoReturnable<Boolean> cir){if(cir.getReturnValueZ()&&level instanceof ServerLevel server&&!WorldRules.get(server).blockSupportBreak)cir.setReturnValue(false);}
}
