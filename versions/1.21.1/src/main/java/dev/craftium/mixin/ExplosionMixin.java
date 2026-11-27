package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.1 copy. explode() works out the blocks to break and damages entities; finalizeExplosion then breaks
 * those blocks and lights fire only among them. Emptying the list after explode() leaves the damage and effects but
 * no broken blocks, no block triggers and no fire, and the client is sent no blocks to break.
 */
@Mixin(Explosion.class)
abstract class ExplosionMixin {
    @Shadow
    @Final
    private Level level;

    @Shadow
    public abstract void clearToBlow();

    @Inject(method = "explode", at = @At("RETURN"))
    private void craftium$blocks(CallbackInfo ci) {
        if (level instanceof ServerLevel server && !WorldRules.get(server).destructiveExplosions) clearToBlow();
    }
}
