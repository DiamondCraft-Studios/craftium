package dev.craftium.client;

import dev.craftium.client.compat.CraftiumButton;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** A box that shows whether a setting is on, with its name beside it, in the editor's palette. */
final class FlatCheckbox extends CraftiumButton {
    private final boolean checked;
    FlatCheckbox(int x,int y,int width,int height,String label,boolean checked,Runnable action){super(x,y,width,height,Component.literal(label),action);this.checked=checked;}
    /** The tick box on its own, {@code size} pixels square. */
    static void box(Gfx g,int x,int y,int size,boolean checked,boolean hovered){
        var o=ClientState.options;
        g.fill(x,y,x+size,y+size,hovered?Hover.fill(checked?0xff386e9d:0xff233247):checked?0xff386e9d:0xff233247);g.outline(x,y,size,size,hovered?o.uiAccent:o.uiBorder);
        if(checked){int d=(size-12)/2;ToolIcons.line(g,x+2+d,y+6+d,x+5+d,y+9+d,o.uiText);ToolIcons.line(g,x+5+d,y+9+d,x+10+d,y+3+d,o.uiText);}
    }
    @Override protected void paint(Gfx g,int x,int y,float delta){
        var o=ClientState.options;boolean hovered=isHoveredOrFocused();int left=getX(),top=getY(),box=14,boxTop=top+(getHeight()-box)/2;
        box(g,left,boxTop,box,checked,hovered);
        var font=Minecraft.getInstance().font;g.text(font,font.plainSubstrByWidth(getMessage().getString(),getWidth()-box-10),left+box+7,top+(getHeight()-8)/2,active?o.uiText:o.uiMuted,false);
    }
}
