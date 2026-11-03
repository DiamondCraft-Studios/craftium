package dev.craftium.client.mixin;
import dev.craftium.client.ClientState;
import net.minecraft.client.renderer.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(FluidRenderer.class)
abstract class FluidOpacityMixin {
    @ModifyVariable(method="tesselate",at=@At("STORE"),ordinal=0)
    private int craftium$opacity(int color,BlockAndTintGetter level,BlockPos pos,FluidRenderer.Output output,BlockState block,FluidState fluid){if(!fluid.is(FluidTags.WATER))return color;int alpha=(int)Math.round((color>>>24)*ClientState.options.liquidOpacity);return (color&0xffffff)|(alpha<<24);}
}
