package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The colour picker as a window, made like the editor's panels: the same frame and title bar, dragged by its title, folded up to the
 * title bar with the arrow and closed with the cross. It edits whatever the getter and the setter stand for, live, floats over the
 * screen it came from (never full screen) and gives that screen back when it closes. Where it was left, and whether it was folded, is
 * remembered for the next time it opens.
 */
public final class ColourPickerScreen extends CraftiumScreen {
    private static final int PAD=8,TITLE=WorkspaceLayout.TITLE,BODY=26,W=ColourPicker.WIDTH+2*PAD,H=BODY+ColourPicker.HEIGHT+PAD;
    private static int windowX=Integer.MIN_VALUE,windowY;
    private static boolean folded;

    private final Screen parent;
    private final String title;
    private final IntSupplier get,fallback;
    private final IntConsumer set;
    private ColourPicker picker;
    private boolean dragging;
    private double grabX,grabY;

    /** Opens the picker over {@code parent}. {@code fallback} is what the Reset button puts back. */
    static void open(Screen parent,String title,IntSupplier get,IntConsumer set,IntSupplier fallback){
        ClientCompat.setScreen(net.minecraft.client.Minecraft.getInstance(),new ColourPickerScreen(parent,title,get,set,fallback));
    }
    private ColourPickerScreen(Screen parent,String title,IntSupplier get,IntConsumer set,IntSupplier fallback){
        super(Component.literal(title));this.parent=parent;this.title=title;this.get=get;this.set=set;this.fallback=fallback;
    }

    private void place(){
        if(windowX==Integer.MIN_VALUE){windowX=(width-W)/2;windowY=(height-H)/2;}
        windowX=Math.clamp(windowX,0,Math.max(0,width-W));windowY=Math.clamp(windowY,0,Math.max(0,height-(folded?TITLE:H)));
    }
    @Override protected void init(){
        place();clearWidgets();picker=null;
        if(folded)return;
        picker=new ColourPicker(windowX+PAD,windowY+BODY,get,set,fallback);
        picker.init(font,w->addRenderableWidget(w));
    }

    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        var o=ClientState.options;int x=windowX,y=windowY,shown=folded?TITLE:H;
        drawUnder(parent,g,-1,-1,delta); // a window over the screen it came from, not a screen of its own
        g.fill(x,y,x+W,y+shown,o.uiBackground);g.outline(x,y,W,shown,o.uiBorder);
        g.fill(x+1,y+1,x+W-1,y+18,0xff202a37);g.fill(x+1,y+1,x+3,y+18,o.uiAccent);
        g.text(font,folded?"▸":"▾",x+7,y+6,o.uiMuted,false);g.text(font,title,x+17,y+6,o.uiText,false);g.text(font,"×",x+W-12,y+5,o.uiMuted,false);
        if(picker!=null){picker.draw(g,font);drawWidgets(g,mouseX,mouseY,delta);}
    }

    private static boolean in(double mx,double my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
        double mx=event.x(),my=event.y();
        if(event.button()==0){
            if(in(mx,my,windowX+W-17,windowY+2,15,14)){onClose();return true;}
            if(in(mx,my,windowX+3,windowY+2,14,14)){folded=!folded;init();return true;}
            if(in(mx,my,windowX,windowY,W,18)){dragging=true;grabX=mx-windowX;grabY=my-windowY;return true;}
            if(picker!=null&&picker.mouseClicked(mx,my,0))return true;
        }
        return super.mouseClicked(event,twice)||in(mx,my,windowX,windowY,W,folded?TITLE:H); // a click on the window is its own; elsewhere it is nobody's
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(dragging&&event.button()==0){windowX=(int)Math.round(event.x()-grabX);windowY=(int)Math.round(event.y()-grabY);init();return true;}
        if(picker!=null&&picker.mouseDragged(event.x(),event.y(),event.button()))return true;
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){
        if(dragging&&event.button()==0){dragging=false;return true;}
        if(picker!=null&&picker.mouseReleased(event.button()))return true;
        return super.mouseReleased(event);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientState.save();ClientCompat.setScreen(minecraft,parent);}
}
