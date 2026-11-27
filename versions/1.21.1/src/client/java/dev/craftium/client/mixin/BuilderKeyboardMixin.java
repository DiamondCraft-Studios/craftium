package dev.craftium.client.mixin;

import dev.craftium.client.BuilderMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.21.1 version: the key arrives as its code, scancode and modifiers, made into the record later versions pass. */
@Mixin(KeyboardHandler.class)
abstract class BuilderKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void craftium$key(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        var event = new KeyEvent(key, scancode, modifiers);
        if (dev.craftium.client.CraftiumClient.ingameKey(event, action)
                || BuilderMode.key(event.key(), action, event.hasControlDown())) ci.cancel();
    }
}
