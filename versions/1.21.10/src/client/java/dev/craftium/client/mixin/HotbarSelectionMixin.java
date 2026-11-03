package dev.craftium.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.craftium.client.BuilderMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** While a builder tool is chosen no hotbar slot is, so the highlight over the held slot is not drawn. */
@Mixin(Gui.class)
abstract class HotbarSelectionMixin {
    @Redirect(method="renderItemHotbar",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/ResourceLocation;IIII)V",ordinal=1))
    private void craftium$noSelection(GuiGraphics graphics,RenderPipeline pipeline,ResourceLocation sprite,int x,int y,int width,int height){
        if(!BuilderMode.active())graphics.blitSprite(pipeline,sprite,x,y,width,height);
    }
}
