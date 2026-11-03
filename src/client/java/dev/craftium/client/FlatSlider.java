package dev.craftium.client;

import dev.craftium.client.compat.CraftiumSlider;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

abstract class FlatSlider extends CraftiumSlider {
    FlatSlider(int x,int y,int width,int height,Component label,double value){super(x,y,width,height,label,value);}
    void setRaw(double value){this.value=value;updateMessage();}
    @Override protected void paint(Gfx g,int x,int y,float delta){
        var o=ClientState.options;int left=getX(),top=getY(),filled=(int)Math.round(value*(getWidth()-4));g.fill(left,top,left+getWidth(),top+getHeight(),0xff1b2b40);g.fill(left+2,top+2,left+2+filled,top+getHeight()-2,0xff315473);g.outline(left,top,getWidth(),getHeight(),isHoveredOrFocused()?o.uiAccent:o.uiBorder);
        g.centeredText(Minecraft.getInstance().font,getMessage(),left+getWidth()/2,top+(getHeight()-8)/2,o.uiText);
    }
}
