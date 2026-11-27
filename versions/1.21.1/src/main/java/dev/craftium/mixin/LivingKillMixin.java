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
    private void craftium$kill(CallbackInfo ci) { // 1.21.1: kill() takes no level
        if ((Object) this instanceof ServerPlayer player && WorldRules.get(player.serverLevel()).playerInvulnerability)
            ci.cancel();
    }
}
