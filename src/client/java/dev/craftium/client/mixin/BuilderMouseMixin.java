package dev.craftium.client.mixin;

import dev.craftium.client.BuilderMode;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class BuilderMouseMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void craftium$wheel(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (BuilderMode.scroll(vertical)) ci.cancel();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void craftium$button(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (BuilderMode.mouse(button.button(), action)) ci.cancel();
    }
}
