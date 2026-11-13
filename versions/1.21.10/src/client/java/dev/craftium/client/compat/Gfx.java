package dev.craftium.client.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/** Minecraft 1.21.10 implementation of {@link Gfx}; see the 26.2 file in src/client for the contract. */
public final class Gfx implements dev.craftium.client.Canvas {
    final GuiGraphics raw;
    private Gfx(GuiGraphics raw){this.raw=raw;}

    public static Gfx of(GuiGraphics raw){return new Gfx(raw);}

    public Matrix3x2fStack pose(){return raw.pose();}
    public int guiWidth(){return raw.guiWidth();}
    public int guiHeight(){return raw.guiHeight();}
    public void enableScissor(int x1,int y1,int x2,int y2){raw.enableScissor(x1,y1,x2,y2);}
    public void disableScissor(){raw.disableScissor();}

    public void fill(int x1,int y1,int x2,int y2,int color){raw.fill(x1,y1,x2,y2,color);}
    public void fillGradient(int x1,int y1,int x2,int y2,int top,int bottom){raw.fillGradient(x1,y1,x2,y2,top,bottom);}
    /**
     * One-pixel border drawn just inside the rectangle. 1.21.10's own outline call is deferred until the
     * end of the frame, which would change draw order, so the border is built from four immediate fills
     * exactly as the 26.2 implementation does.
     */
    public void outline(int x,int y,int width,int height,int color){
        raw.fill(x,y,x+width,y+1,color);
        raw.fill(x,y+height-1,x+width,y+height,color);
        raw.fill(x,y+1,x+1,y+height-1,color);
        raw.fill(x+width-1,y+1,x+width,y+height-1,color);
    }

    public void text(Font font,String text,int x,int y,int color){if(!dev.craftium.client.CrispText.draw(this,font,text,x,y,color,true,false))raw.drawString(font,text,x,y,color);}
    public void text(Font font,String text,int x,int y,int color,boolean shadow){if(!dev.craftium.client.CrispText.draw(this,font,text,x,y,color,shadow,false))raw.drawString(font,text,x,y,color,shadow);}
    public void text(Font font,Component text,int x,int y,int color){if(!text.getStyle().isEmpty()||!text.getSiblings().isEmpty()||!dev.craftium.client.CrispText.draw(this,font,text.getString(),x,y,color,true,false))raw.drawString(font,text,x,y,color);}
    public void text(Font font,Component text,int x,int y,int color,boolean shadow){if(!text.getStyle().isEmpty()||!text.getSiblings().isEmpty()||!dev.craftium.client.CrispText.draw(this,font,text.getString(),x,y,color,shadow,false))raw.drawString(font,text,x,y,color,shadow);}
    public void text(Font font,FormattedCharSequence text,int x,int y,int color,boolean shadow){raw.drawString(font,text,x,y,color,shadow);}
    public void centeredText(Font font,String text,int x,int y,int color){if(!dev.craftium.client.CrispText.draw(this,font,text,x,y,color,true,true))raw.drawCenteredString(font,text,x,y,color);}
    public void centeredText(Font font,Component text,int x,int y,int color){if(!text.getStyle().isEmpty()||!text.getSiblings().isEmpty()||!dev.craftium.client.CrispText.draw(this,font,text.getString(),x,y,color,true,true))raw.drawCenteredString(font,text,x,y,color);}

    public void item(ItemStack stack,int x,int y){raw.renderItem(stack,x,y);}
    /** The count and durability bar over an item drawn with {@link #item}. */
    public void itemDecorations(Font font,ItemStack stack,int x,int y){raw.renderItemDecorations(font,stack,x,y);}
    /** A piece of a vanilla GUI sprite: the {@code width} by {@code height} area at {@code u}, {@code v} of a sprite that is {@code spriteWidth} by {@code spriteHeight}, drawn at its own size. */
    public void spritePiece(String path,int spriteWidth,int spriteHeight,int u,int v,int x,int y,int width,int height){raw.blitSprite(RenderPipelines.GUI_TEXTURED,ResourceLocation.withDefaultNamespace(path),spriteWidth,spriteHeight,u,v,x,y,width,height);}
    /** A vanilla GUI sprite by path, like {@code hud/hotbar}, drawn at the given size. */
    public void sprite(String path,int x,int y,int width,int height){raw.blitSprite(RenderPipelines.GUI_TEXTURED,ResourceLocation.withDefaultNamespace(path),x,y,width,height);}

    public void blit(Tex texture,int x,int y,float u,float v,int width,int height,int regionWidth,int regionHeight,int textureWidth,int textureHeight,int color){
        raw.blit(RenderPipelines.GUI_TEXTURED,texture.id(),x,y,u,v,width,height,regionWidth,regionHeight,textureWidth,textureHeight,color);
    }

    /** The vanilla crosshair sprite, plain white, with its 15x15 box centred on the given point. */
    public void crosshair(int centerX,int centerY){raw.blitSprite(RenderPipelines.GUI_TEXTURED,ResourceLocation.withDefaultNamespace("hud/crosshair"),centerX-7,centerY-7,15,15);}

    public void widget(AbstractWidget widget,int mouseX,int mouseY,float delta){widget.render(raw,mouseX,mouseY,delta);}
}
