package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

final class ReportScreen extends CraftiumScreen {
    private final Screen parent;
    private final String report;
    private List<String> lines;
    private int scroll;
    ReportScreen(Screen parent,String report){super(Component.literal("Craftium Selection Analysis"));this.parent=parent;this.report=report;}
    @Override protected void init(){lines=Arrays.asList(report.split("\n"));addRenderableWidget(new FlatButton(12,height-30,120,22,"Copy report",()->minecraft.keyboardHandler.setClipboard(report)));addRenderableWidget(new FlatButton(width-132,height-30,120,22,"Return",this::onClose));}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int x,int y,float delta){g.fill(0,0,width,height,0xf5101923);g.text(font,"REPORT",12,12,0xffb5dcfc,false);int rows=Math.max(1,(height-78)/12);scroll=Math.clamp(scroll,0,Math.max(0,lines.size()-rows));for(int i=0;i<rows&&i+scroll<lines.size();i++)g.text(font,font.plainSubstrByWidth(lines.get(i+scroll),width-24),12,38+i*12,0xffc9d9e8,false);drawWidgets(g,x,y,delta);}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){scroll-=(int)Math.signum(v)*3;return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
