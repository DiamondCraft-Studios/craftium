package dev.craftium.mixin;

import dev.craftium.server.EditorServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class EntityMovementMixin {
    @org.spongepowered.asm.mixin.Unique
    private boolean craftium$forcedNoPhysics;

    @Inject(method = "move", at = @At("HEAD"))
    private void craftium$creativeNoClip(MoverType type, Vec3 delta, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ServerPlayer player) {
            boolean enabled = EditorServer.noClip(player);
            if (enabled) self.noPhysics = true;
            else if (craftium$forcedNoPhysics && !player.isSpectator()) self.noPhysics = false;
            craftium$forcedNoPhysics = enabled;
        }
    }

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void craftium$kill(CallbackInfo ci) { // 1.21.1: kill() takes no level
        if ((Object) this instanceof ServerPlayer player
                && dev.craftium.server.WorldRules.get(player.serverLevel()).playerInvulnerability) ci.cancel();
    }
}
