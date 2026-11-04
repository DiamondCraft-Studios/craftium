package dev.craftium.client.mixin;
import dev.craftium.client.FastPlace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Fast place places on its own, so the game's repeat of the use key, which waits four ticks between blocks, is skipped. */
@Mixin(Minecraft.class)
abstract class FastPlaceMixin {
    @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
    private void craftium$fastPlace(CallbackInfo ci){if(FastPlace.cancelVanilla())ci.cancel();}
}
