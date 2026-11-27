package dev.craftium.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.craftium.server.EditorServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game's "moved too quickly" check puts a player back when one tick of movement is longer than about ten blocks. A player flying
 * fast with Craftium, or turning the arcball camera quickly, goes further than that, and on a server was thrown back again and again.
 * The check is skipped for a player Craftium lets fly at will; everyone else is checked as ever. 1.21.1 has no method for the
 * check itself, so this answers its "is this the singleplayer owner" question, which skips it, with yes.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class MovementCheckMixin {
    @Shadow
    public ServerPlayer player;

    @ModifyExpressionValue(
            method = "handleMovePlayer",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
    private boolean craftium$fastFlight(boolean owner) {
        return owner || EditorServer.fastMovement(player);
    }
}
