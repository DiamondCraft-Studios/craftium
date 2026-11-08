package dev.craftium.mixin;

import dev.craftium.server.EditorServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's "moved too quickly" check puts a player back when one tick of movement is longer than about ten blocks. A player flying
 * fast with Craftium, or turning the arcball camera quickly, goes further than that, and on a server was thrown back again and again.
 * The check is skipped for a player Craftium lets fly at will; everyone else is checked as ever.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class MovementCheckMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="shouldCheckPlayerMovement",at=@At("HEAD"),cancellable=true)
    private void craftium$fastFlight(boolean elytra,CallbackInfoReturnable<Boolean> cir){if(EditorServer.fastMovement(player))cir.setReturnValue(false);}
}
