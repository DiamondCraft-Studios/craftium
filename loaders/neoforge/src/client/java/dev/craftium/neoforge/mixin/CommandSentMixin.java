package dev.craftium.neoforge.mixin;

import dev.craftium.client.platform.ClientPlatform;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge has no event for a command the player sends, so this tells {@link ClientPlatform} of each one. */
@Mixin(ClientPacketListener.class)
abstract class CommandSentMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void craftium$sent(String command, CallbackInfo ci) {
        ClientPlatform.commandSent(command);
    }
}
