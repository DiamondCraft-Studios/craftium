package dev.craftium.client;

import dev.craftium.client.compat.CraftiumButton;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Flat workspace controls share the editor's configurable palette. */
final class FlatButton extends CraftiumButton {
    FlatButton(int x,int y,int width,int height,String label,Runnable action){super(x,y,width,height,Component.literal(label),action);}
    @Override protected void paint(Gfx g,int x,int y,float delta){
        var o=ClientState.options;boolean hovered=isHoveredOrFocused();int left=getX(),top=getY();
        g.fill(left,top,left+getWidth(),top+getHeight(),hovered?0xff315473:0xff223247);g.outline(left,top,getWidth(),getHeight(),hovered?o.uiAccent:o.uiBorder);
        var font=Minecraft.getInstance().font;String text=font.plainSubstrByWidth(getMessage().getString(),getWidth()-10);g.centeredText(font,text,left+getWidth()/2,top+(getHeight()-8)/2,active?o.uiText:o.uiMuted);
    }
}
