package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.network.chat.Component;

/** A small pop-up over the editor that asks how many blocks to grow or shrink the selection by, or how thick an outline to select, 1 to 64. */
final class ResizeSelectionScreen extends CraftiumScreen {
    private static final int W=260;
    private int H=104;
    private final EditorScreen parent;
    enum Kind {
        EXPAND("Expand selection","EXPAND","Expand by ","Expand","selection_expand"),
        SHRINK("Shrink selection","SHRINK","Shrink by ","Shrink","selection_shrink"),
        OUTLINE("Select outline","OUTLINE","Thickness ","Select","selection_outline");
        final String title,heading,prefix,button,action;
        Kind(String title,String heading,String prefix,String button,String action){this.title=title;this.heading=heading;this.prefix=prefix;this.button=button;this.action=action;}
    }
    private final Kind kind;
    private int left,top;
    ResizeSelectionScreen(EditorScreen parent,boolean expand){this(parent,expand?Kind.EXPAND:Kind.SHRINK);}
    ResizeSelectionScreen(EditorScreen parent,Kind kind){super(Component.literal(kind.title));this.parent=parent;this.kind=kind;}

    @Override protected void init(){
        var o=ClientState.options;
        H=kind==Kind.OUTLINE?128:104;
        left=(width-W)/2;top=(height-H)/2;
        if(kind==Kind.OUTLINE)addRenderableWidget(new FlatCheckbox(left+14,top+60,W-28,16,"Pixel perfect",o.pixelPerfect,()->{o.pixelPerfect=!o.pixelPerfect;rebuildWidgets();}));
        var slider=new FlatSlider(left+14,top+34,W-28,20,Component.empty(),(o.expandAmount-1)/63d){
            protected void updateMessage(){setMessage(Component.literal(kind.prefix+Math.round(1+value*63)+" blocks"));}
            protected void applyValue(){o.expandAmount=(int)Math.round(1+value*63);}
        };
        slider.range(1,64,5);slider.setRaw((o.expandAmount-1)/63d);addRenderableWidget(slider);
        int half=(W-28-6)/2;
        addRenderableWidget(new FlatButton(left+14,top+H-34,half,22,kind.button,()->{
            int amount=o.expandAmount;ClientState.save();ClientCompat.setScreen(minecraft,parent);
            parent.resizeSelection(kind.action,amount);
        }).lit(true));
        addRenderableWidget(new FlatButton(left+20+half,top+H-34,half,22,"Cancel",this::onClose));
    }
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        parent.suspended=true;
        drawUnder(parent,g,-1,-1,delta);
        g.fill(left-8,top-8,left+W+8,top+H+8,0xff172330);g.outline(left-8,top-8,W+16,H+16,ClientState.options.uiBorder);
        g.text(font,kind.heading+" SELECTION",left+14,top+10,0xffb5dcfc,false);
        drawWidgets(g,mouseX,mouseY,delta);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientState.save();ClientCompat.setScreen(minecraft,parent);}
}
