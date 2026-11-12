package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
/** Every explosion (creeper, TNT, crystal, bed, wind charge) reaches the world through these two steps, so cancelling them leaves entity damage and effects but no broken blocks, no block triggers and no fire. */
@Mixin(ServerExplosion.class)
abstract class ExplosionMixin {
    @Shadow public abstract net.minecraft.server.level.ServerLevel level();
    @Inject(method="interactWithBlocks",at=@At("HEAD"),cancellable=true)
    private void craftium$blocks(List<BlockPos> positions,CallbackInfo ci){if(!WorldRules.get(level()).destructiveExplosions)ci.cancel();}
    @Inject(method="createFire",at=@At("HEAD"),cancellable=true)
    private void craftium$fire(List<BlockPos> positions,CallbackInfo ci){if(!WorldRules.get(level()).destructiveExplosions)ci.cancel();}
}
