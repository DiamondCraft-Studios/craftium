package dev.craftium.client.mixin;

import dev.craftium.client.BuilderMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class BuilderKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void craftium$key(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (dev.craftium.client.CraftiumClient.ingameKey(event, action)
                || BuilderMode.key(event.key(), action, event.hasControlDown())) ci.cancel();
    }
}
