package dev.craftium.client.mixin;

import dev.craftium.client.*;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class PickingMixin {
    @Inject(method="pick",at=@At("TAIL"))
    private void craftium$editorReach(float partialTicks,CallbackInfo ci){var mc=(Minecraft)(Object)this;if(mc.player==null||!mc.player.isCreative()||!CraftiumClient.supported())return;var o=ClientState.options;if(o.infiniteReach||o.solidFluids){var hit=CraftiumClient.raycast(mc.getWindow().getGuiScaledWidth()/2d,mc.getWindow().getGuiScaledHeight()/2d);if(hit!=null){mc.hitResult=hit;mc.crosshairPickEntity=null;}}}
}
