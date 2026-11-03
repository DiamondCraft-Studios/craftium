package dev.craftium.client;

import dev.craftium.client.compat.CraftiumButton;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** A box that shows whether a setting is on, with its name beside it, in the editor's palette. */
final class FlatCheckbox extends CraftiumButton {
    private final boolean checked;
    FlatCheckbox(int x,int y,int width,int height,String label,boolean checked,Runnable action){super(x,y,width,height,Component.literal(label),action);this.checked=checked;}
    @Override protected void paint(Gfx g,int x,int y,float delta){
        var o=ClientState.options;boolean hovered=isHoveredOrFocused();int left=getX(),top=getY(),box=14,boxTop=top+(getHeight()-box)/2;
        g.fill(left,boxTop,left+box,boxTop+box,checked?0xff386e9d:0xff233247);g.outline(left,boxTop,box,box,hovered?o.uiAccent:o.uiBorder);
        if(checked){ToolIcons.line(g,left+3,boxTop+7,left+6,boxTop+10,o.uiText);ToolIcons.line(g,left+6,boxTop+10,left+11,boxTop+3,o.uiText);}
        var font=Minecraft.getInstance().font;g.text(font,font.plainSubstrByWidth(getMessage().getString(),getWidth()-box-10),left+box+7,top+(getHeight()-8)/2,active?o.uiText:o.uiMuted,false);
    }
}
