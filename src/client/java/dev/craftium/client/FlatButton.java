package dev.craftium.client;

import dev.craftium.client.compat.CraftiumButton;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Flat workspace controls share the editor's configurable palette. */
final class FlatButton extends CraftiumButton {
    private boolean lit;
    private Runnable back;
    FlatButton(int x,int y,int width,int height,String label,Runnable action){super(x,y,width,height,Component.literal(label),action);}
    /** Shows the button as switched on, like the active tab of a row of tabs. */
    FlatButton lit(boolean lit){this.lit=lit;return this;}
    /** What a right click does, for a button that cycles through values: step back to the previous one. */
    FlatButton back(Runnable back){this.back=back;return this;}
    /** Runs the right-click action when the pointer is over the button and it has one; says whether it did. */
    boolean rightClick(double x,double y){
        if(back==null||!active||!visible||!isMouseOver(x,y))return false;
        back.run();return true;
    }
    @Override protected void paint(Gfx g,int x,int y,float delta){
        var o=ClientState.options;boolean hovered=isHoveredOrFocused();int left=getX(),top=getY();
        g.fill(left,top,left+getWidth(),top+getHeight(),hovered?0xff315473:lit?0xff315b84:0xff223247);g.outline(left,top,getWidth(),getHeight(),hovered||lit?o.uiAccent:o.uiBorder);
        var font=Minecraft.getInstance().font;String text=font.plainSubstrByWidth(getMessage().getString(),getWidth()-10);g.centeredText(font,text,left+getWidth()/2,top+(getHeight()-8)/2,active?o.uiText:o.uiMuted);
    }
}
