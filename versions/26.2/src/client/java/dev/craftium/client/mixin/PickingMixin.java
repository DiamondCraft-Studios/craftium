package dev.craftium.client.mixin;

import dev.craftium.client.*;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class PickingMixin {
    /** The game's own pick aims at the whole of a double slab; only the drawn outline is split in halves. */
    @Inject(method="pick",at=@At("HEAD"))
    private void craftium$wholeSlabs(float partialTicks,CallbackInfo ci){SlabSplit.suppress(true);}
    @Inject(method="pick",at=@At("TAIL"))
    private void craftium$splitSlabs(float partialTicks,CallbackInfo ci){SlabSplit.suppress(false);}
    @Inject(method="pick",at=@At("TAIL"))
    private void craftium$editorReach(float partialTicks,CallbackInfo ci){var mc=(Minecraft)(Object)this;if(mc.player==null||!mc.player.isCreative()||!CraftiumClient.supported())return;var o=ClientState.options;if(o.reach==dev.craftium.core.Options.ReachMode.INFINITE||o.solidFluids||BuilderMode.active()){var hit=CraftiumClient.raycast(mc.getWindow().getGuiScaledWidth()/2d,mc.getWindow().getGuiScaledHeight()/2d);if(hit!=null){mc.hitResult=hit;mc.crosshairPickEntity=null;}}try{FastPlace.afterPick(Minecraft.getInstance());Bulldozer.afterPick(Minecraft.getInstance());}catch(RuntimeException e){/* a failed placement must not break the frame */}}
}
