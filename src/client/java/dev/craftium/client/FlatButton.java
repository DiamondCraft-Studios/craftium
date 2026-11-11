package dev.craftium.client;

import dev.craftium.client.compat.CraftiumButton;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Flat workspace controls share the editor's configurable palette. */
final class FlatButton extends CraftiumButton {
    private boolean lit;
    private float labelScale = 1;
    private Runnable back;
    FlatButton(int x,int y,int width,int height,String label,Runnable action){super(x,y,width,height,Component.literal(label),action);}
    /** Shows the button as switched on, like the active tab of a row of tabs. */
    FlatButton lit(boolean lit){this.lit=lit;return this;}
    /** Draws the label this many times as large, for a symbol that has to be seen (an arrow that is a link). */
    FlatButton scale(float scale){this.labelScale=scale;return this;}
    /** What a right click does, for a button that cycles through values: step back to the previous one. */
    FlatButton back(Runnable back){this.back=back;return this;}
    /** Runs the right-click action when the pointer is over the button and it has one; says whether it did. */
    boolean rightClick(double x,double y){
        if(back==null||!active||!visible||!isMouseOver(x,y))return false;
        back.run();return true;
    }
    @Override protected void paint(Gfx g,int x,int y,float delta){
        boolean hovered=active&&isHoveredOrFocused();
        if(labelScale==1){face(g,getX(),getY(),getWidth(),getHeight(),getMessage().getString(),lit,active,hovered);return;}
        face(g,getX(),getY(),getWidth(),getHeight(),"",lit,active,hovered); // the button, and then its label at its own size
        var font=Minecraft.getInstance().font;String label=getMessage().getString();
        float textX=getX()+(getWidth()-font.width(label)*labelScale)/2f,textY=getY()+(getHeight()-8*labelScale)/2f;
        g.pose().pushMatrix();g.pose().translate(textX,textY);g.pose().scale(labelScale,labelScale);
        g.text(font,label,0,0,active?ClientState.options.uiText:ClientState.options.uiMuted,false);
        g.pose().popMatrix();
    }
    /** How every button in the editor looks, so the workspace and the screens it opens agree. */
    static void face(Gfx g,int left,int top,int width,int height,String label,boolean lit,boolean enabled,boolean hovered){face(g,left,top,width,height,label,lit,enabled,hovered,false);}
    /** As above; {@code leftAligned} starts the label at the left edge, as the entries of a menu do, instead of centring it. */
    static void face(Gfx g,int left,int top,int width,int height,String label,boolean lit,boolean enabled,boolean hovered,boolean leftAligned){
        var o=ClientState.options;
        g.fill(left,top,left+width,top+height,hovered?Hover.fill(lit?0xff315b84:0xff223247):lit?0xff315b84:0xff223247);g.outline(left,top,width,height,hovered||lit?o.uiAccent:o.uiBorder);
        var font=Minecraft.getInstance().font;String shown=TextCache.fit(font,label,width-10);int textY=top+(height-8)/2,color=enabled?o.uiText:o.uiMuted;
        if(leftAligned)g.text(font,shown,left+8,textY,color,false);else g.text(font,shown,left+width/2-TextCache.width(font,shown)/2,textY,color,false);
    }
}
