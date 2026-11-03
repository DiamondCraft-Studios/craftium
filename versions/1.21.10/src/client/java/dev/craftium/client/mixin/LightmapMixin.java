package dev.craftium.client.mixin;
import dev.craftium.client.ClientState;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.10 version of the minimum-brightness setting. The light texture reads the dimension's
 * ambient light as a single float, so raising it to the chosen minimum gives the same floor that the
 * 26.2 mixin applies to each colour channel.
 */
@Mixin(LightTexture.class)
abstract class LightmapMixin {
    @Shadow private boolean updateLightTexture;
    @Unique private double craftium$lastMinimum;

    @Inject(method="updateLightTexture",at=@At("HEAD"))
    private void craftium$refresh(float partialTick,CallbackInfo ci){
        double amount=ClientState.options.minimumBrightness;
        if(amount!=craftium$lastMinimum){updateLightTexture=true;craftium$lastMinimum=amount;}
    }

    @Redirect(method="updateLightTexture",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/dimension/DimensionType;ambientLight()F"))
    private float craftium$minimum(DimensionType type){
        float ambient=type.ambientLight(),amount=(float)ClientState.options.minimumBrightness;
        return amount>0?Math.max(amount,ambient):ambient;
    }
}
