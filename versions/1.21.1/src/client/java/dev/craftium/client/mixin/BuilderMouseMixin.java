package dev.craftium.client.mixin;

import dev.craftium.client.BuilderMode;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.21.1 version: a button press is {@code onPress}, with the button as a plain number. */
@Mixin(MouseHandler.class)
abstract class BuilderMouseMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void craftium$wheel(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (BuilderMode.scroll(vertical)) ci.cancel();
    }

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void craftium$button(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (BuilderMode.mouse(button, action)) ci.cancel();
    }
}
