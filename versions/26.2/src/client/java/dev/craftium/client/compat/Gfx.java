package dev.craftium.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Version-neutral view of the GUI drawing surface. Method names follow the newest API;
 * each {@code versions/<mc>} module supplies the implementation for its release.
 * This is the 26.2 implementation, where every call maps straight onto {@link GuiGraphicsExtractor}.
 */
public final class Gfx {
    final GuiGraphicsExtractor raw;
    private Gfx(GuiGraphicsExtractor raw){this.raw=raw;}

    public static Gfx of(GuiGraphicsExtractor raw){return new Gfx(raw);}

    public Matrix3x2fStack pose(){return raw.pose();}
    public int guiWidth(){return raw.guiWidth();}
    public int guiHeight(){return raw.guiHeight();}
    public void enableScissor(int x1,int y1,int x2,int y2){raw.enableScissor(x1,y1,x2,y2);}
    public void disableScissor(){raw.disableScissor();}

    public void fill(int x1,int y1,int x2,int y2,int color){raw.fill(x1,y1,x2,y2,color);}
    public void fillGradient(int x1,int y1,int x2,int y2,int top,int bottom){raw.fillGradient(x1,y1,x2,y2,top,bottom);}
    /** One-pixel border drawn just inside the given rectangle. */
    public void outline(int x,int y,int width,int height,int color){raw.outline(x,y,width,height,color);}

    public void text(Font font,String text,int x,int y,int color){raw.text(font,text,x,y,color);}
    public void text(Font font,String text,int x,int y,int color,boolean shadow){raw.text(font,text,x,y,color,shadow);}
    public void text(Font font,Component text,int x,int y,int color){raw.text(font,text,x,y,color);}
    public void text(Font font,Component text,int x,int y,int color,boolean shadow){raw.text(font,text,x,y,color,shadow);}
    public void text(Font font,FormattedCharSequence text,int x,int y,int color,boolean shadow){raw.text(font,text,x,y,color,shadow);}
    public void centeredText(Font font,String text,int x,int y,int color){raw.centeredText(font,text,x,y,color);}
    public void centeredText(Font font,Component text,int x,int y,int color){raw.centeredText(font,text,x,y,color);}

    public void item(ItemStack stack,int x,int y){raw.item(stack,x,y);}
    /** The count and durability bar over an item drawn with {@link #item}. */
    public void itemDecorations(Font font,ItemStack stack,int x,int y){raw.itemDecorations(font,stack,x,y);}
    /** A piece of a vanilla GUI sprite: the {@code width} by {@code height} area at {@code u}, {@code v} of a sprite that is {@code spriteWidth} by {@code spriteHeight}, drawn at its own size. */
    public void spritePiece(String path,int spriteWidth,int spriteHeight,int u,int v,int x,int y,int width,int height){raw.blitSprite(RenderPipelines.GUI_TEXTURED,Identifier.withDefaultNamespace(path),spriteWidth,spriteHeight,u,v,x,y,width,height);}
    /** A vanilla GUI sprite by path, like {@code hud/hotbar}, drawn at the given size. */
    public void sprite(String path,int x,int y,int width,int height){raw.blitSprite(RenderPipelines.GUI_TEXTURED,Identifier.withDefaultNamespace(path),x,y,width,height);}

    /** Draws a region of a GUI texture, tinted by {@code color}. */
    public void blit(Tex texture,int x,int y,float u,float v,int width,int height,int regionWidth,int regionHeight,int textureWidth,int textureHeight,int color){
        raw.blit(RenderPipelines.GUI_TEXTURED,texture.id(),x,y,u,v,width,height,regionWidth,regionHeight,textureWidth,textureHeight,color);
    }

    /** The vanilla crosshair sprite, plain white, with its 15x15 box centred on the given point. */
    public void crosshair(int centerX,int centerY){raw.blitSprite(RenderPipelines.GUI_TEXTURED,Identifier.withDefaultNamespace("hud/crosshair"),centerX-7,centerY-7,15,15);}

    /** Renders a vanilla widget (text box, button) onto this surface. */
    public void widget(AbstractWidget widget,int mouseX,int mouseY,float delta){widget.extractRenderState(raw,mouseX,mouseY,delta);}
}
