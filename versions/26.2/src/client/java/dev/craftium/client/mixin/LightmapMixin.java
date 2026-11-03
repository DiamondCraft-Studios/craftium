package dev.craftium.client.mixin;
import dev.craftium.client.ClientState;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Lightmap.class)
abstract class LightmapMixin {
    @Unique private Vector3fc craftium$ambient;
    @Unique private boolean craftium$needsUpdate;
    @Unique private double craftium$lastMinimum;
    @Inject(method="render",at=@At("HEAD"))
    private void craftium$minimum(LightmapRenderState state,CallbackInfo ci){float amount=(float)ClientState.options.minimumBrightness;craftium$ambient=state.ambientColor;craftium$needsUpdate=state.needsUpdate;if(amount>0){state.ambientColor=new Vector3f(Math.max(amount,craftium$ambient.x()),Math.max(amount,craftium$ambient.y()),Math.max(amount,craftium$ambient.z()));state.needsUpdate=true;}else if(craftium$lastMinimum!=0)state.needsUpdate=true;craftium$lastMinimum=amount;}
    @Inject(method="render",at=@At("TAIL"))
    private void craftium$restore(LightmapRenderState state,CallbackInfo ci){state.ambientColor=craftium$ambient;state.needsUpdate=craftium$needsUpdate;}
}
