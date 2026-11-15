package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class PlayerDamageMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void craftium$damage(
            ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (WorldRules.get(level).playerInvulnerability) cir.setReturnValue(false);
    }
}
