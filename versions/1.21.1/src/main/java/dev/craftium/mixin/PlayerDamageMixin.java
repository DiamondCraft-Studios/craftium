package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class PlayerDamageMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true) // 1.21.1: hurt, with no level
    private void craftium$damage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (WorldRules.get(((ServerPlayer) (Object) this).serverLevel()).playerInvulnerability)
            cir.setReturnValue(false);
    }
}
