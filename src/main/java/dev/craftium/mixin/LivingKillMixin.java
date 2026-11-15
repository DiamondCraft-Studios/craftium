package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class LivingKillMixin {
    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void craftium$kill(ServerLevel level, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer && WorldRules.get(level).playerInvulnerability) ci.cancel();
    }
}
