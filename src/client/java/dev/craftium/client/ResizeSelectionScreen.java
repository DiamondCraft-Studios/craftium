package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.network.chat.Component;

/** A small pop-up over the editor that asks how many blocks to grow or shrink the selection by, 1 to 64. */
final class ResizeSelectionScreen extends CraftiumScreen {
    private static final int W=260,H=104;
    private final EditorScreen parent;
    private final boolean expand;
    private int left,top;
    ResizeSelectionScreen(EditorScreen parent,boolean expand){super(Component.literal(expand?"Expand selection":"Shrink selection"));this.parent=parent;this.expand=expand;}

    @Override protected void init(){
        left=(width-W)/2;top=(height-H)/2;
        var o=ClientState.options;
        var slider=new FlatSlider(left+14,top+34,W-28,20,Component.empty(),(o.expandAmount-1)/63d){
            protected void updateMessage(){setMessage(Component.literal((expand?"Expand by ":"Shrink by ")+Math.round(1+value*63)+" blocks"));}
            protected void applyValue(){o.expandAmount=(int)Math.round(1+value*63);}
        };
        slider.range(1,64,5);slider.setRaw((o.expandAmount-1)/63d);addRenderableWidget(slider);
        int half=(W-28-6)/2;
        addRenderableWidget(new FlatButton(left+14,top+H-34,half,22,expand?"Expand":"Shrink",()->{
            int amount=o.expandAmount;ClientState.save();ClientCompat.setScreen(minecraft,parent);
            parent.resizeSelection(expand?"selection_expand":"selection_shrink",amount);
        }).lit(true));
        addRenderableWidget(new FlatButton(left+20+half,top+H-34,half,22,"Cancel",this::onClose));
    }
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        parent.suspended=true;
        drawUnder(parent,g,-1,-1,delta);
        g.fill(left-8,top-8,left+W+8,top+H+8,0xff172330);g.outline(left-8,top-8,W+16,H+16,ClientState.options.uiBorder);
        g.text(font,"CRAFTIUM  /  "+(expand?"EXPAND":"SHRINK")+" SELECTION",left+14,top+10,0xffb5dcfc,false);
        drawWidgets(g,mouseX,mouseY,delta);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientState.save();ClientCompat.setScreen(minecraft,parent);}
}
