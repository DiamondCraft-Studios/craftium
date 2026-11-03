package dev.craftium.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.craftium.client.BuilderMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** While a builder tool is chosen no hotbar slot is, so the highlight over the held slot is not drawn. */
@Mixin(Hud.class)
abstract class HotbarSelectionMixin {
    @Redirect(method="extractItemHotbar",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",ordinal=1))
    private void craftium$noSelection(GuiGraphicsExtractor graphics,RenderPipeline pipeline,Identifier sprite,int x,int y,int width,int height){
        if(!BuilderMode.active())graphics.blitSprite(pipeline,sprite,x,y,width,height);
    }
}
