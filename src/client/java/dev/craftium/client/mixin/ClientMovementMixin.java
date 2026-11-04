package dev.craftium.client.mixin;

import dev.craftium.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class ClientMovementMixin {
    @org.spongepowered.asm.mixin.Unique private boolean craftium$forcedNoPhysics;
    @Inject(method="move",at=@At("HEAD"))
    private void craftium$creativeNoClip(MoverType type,Vec3 delta,CallbackInfo ci){Entity self=(Entity)(Object)this;if(self instanceof LocalPlayer player){boolean enabled=player.isCreative()&&player.getAbilities().flying&&CraftiumClient.supported()&&ClientState.options.noClip; // only while flyingif(enabled)self.noPhysics=true;else if(craftium$forcedNoPhysics&&!player.isSpectator())self.noPhysics=false;craftium$forcedNoPhysics=enabled;}}
}
