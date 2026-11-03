package dev.craftium.client.mixin;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.craftium.client.ClientState;
import dev.craftium.client.OpacityConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/**
 * Minecraft 1.21.10 version of the liquid-opacity setting. The renderer fixes water's alpha at 1 when it
 * emits vertices, so water is drawn through a consumer that scales alpha by the chosen opacity.
 */
@Mixin(LiquidBlockRenderer.class)
abstract class FluidOpacityMixin {
    @ModifyVariable(method="tesselate",at=@At("HEAD"),argsOnly=true)
    private VertexConsumer craftium$opacity(VertexConsumer consumer,BlockAndTintGetter level,BlockPos pos,VertexConsumer same,BlockState block,FluidState fluid){
        double opacity=ClientState.options.liquidOpacity;
        return fluid.is(FluidTags.WATER)&&opacity<1?new OpacityConsumer(consumer,opacity):consumer;
    }
}
