package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class HistoryScreen extends CraftiumScreen {
    private final Screen parent;
    private int scroll;
    HistoryScreen(Screen parent){super(Component.literal("Craftium History"));this.parent=parent;}
    @Override protected void init(){
        addRenderableWidget(new FlatButton(12,height-32,95,22,"Undo",()->send("undo",1)));addRenderableWidget(new FlatButton(115,height-32,95,22,"Redo",()->send("redo",1)));addRenderableWidget(new FlatButton(218,height-32,112,22,"Save history",()->send("history_save",1)));addRenderableWidget(new FlatButton(width-112,height-32,100,22,"Return",this::onClose));
    }
    private void send(String action,int steps){if(!ClientState.status.busy()){ClientState.options.historySteps=steps;CraftiumClient.send(action,null,null);}}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int x,int y,float delta){
        g.fill(0,0,width,height,0xf5101923);g.text(font,"CRAFTIUM  /  HISTORY",12,12,0xffb5dcfc,false);var s=ClientState.status;g.text(font,s.undo()+" undo · "+s.redo()+" redo · saved with this world",12,28,0xff9db5cb,false);int rows=Math.max(1,(height-95)/23);scroll=Math.clamp(scroll,0,Math.max(0,s.history().size()-rows));
        for(int i=0;i<rows&&i+scroll<s.history().size();i++){int index=i+scroll,top=48+i*23;boolean hovered=x>=12&&x<width-12&&y>=top&&y<top+21;g.fill(12,top,width-12,top+21,hovered?0xff315473:0xff223247);g.text(font,font.plainSubstrByWidth("Undo "+(index+1)+": "+s.history().get(index),width-40),20,top+6,0xffdce6ef,false);}
        drawWidgets(g,x,y,delta);g.text(font,font.plainSubstrByWidth(s.message(),width-24),12,height-47,0xffffdca8,false);
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){int row=((int)event.y()-48)/23,rows=Math.max(1,(height-95)/23),index=row+scroll;if(event.y()>=48&&event.x()>=12&&event.x()<width-12&&row>=0&&row<rows&&index<ClientState.status.history().size()){send("history_undo_many",index+1);return true;}return super.mouseClicked(event,twice);}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){scroll-=(int)Math.signum(v)*3;return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
