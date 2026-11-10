package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.BlueprintInfo;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Search includes nested folder paths, names, authors and tags. */
public final class BlueprintScreen extends CraftiumScreen {
    private final net.minecraft.client.gui.screens.Screen parent;
    private int left,top,canvasW,canvasH,page,version;
    private float scale=1;
    private String query="";
    private List<BlueprintInfo> matches=List.of();
    /** The client's own files, read off the render thread; shown once {@code listing} is over and the block colours are ready, so opening the screen never stalls on either. */
    private volatile List<BlueprintInfo> local=List.of();
    private boolean listing,warm;
    private int loads,frames,waitTicks,waitVersion;
    public BlueprintScreen(net.minecraft.client.gui.screens.Screen parent){super(Component.literal("Craftium Blueprint Browser"));this.parent=parent;}
    @Override protected void init(){
        scale=Math.min(1f,Math.min(width/700f,height/460f));canvasW=Math.round(width/scale);canvasH=Math.round(height/scale);left=(canvasW-660)/2;top=(canvasH-420)/2;clearWidgets();
        var search=new EditBox(font,left+8,top+5,420,22,Component.literal("Search blueprints"));search.setHint(Component.literal("Search names, folders, authors and tags…"));search.setValue(query);search.setResponder(value->{query=value;page=0;filter();});addRenderableWidget(search);
        button(ClientState.options.libraryClient?"Source: Client":"Source: Server",left+500,top-21,150,20,()->{ClientState.options.libraryClient=!ClientState.options.libraryClient;reload();init();});
        button("Refresh",left+437,top+5,100,22,this::reload);button("Import…",left+545,top+5,105,22,()->{if(ClientState.options.libraryClient)AssetPicker.local(this::reload);else AssetPicker.open(this::reload);});
        button("Previous",left+8,top+375,100,23,()->{page=Math.max(0,page-1);filter();});button("Next",left+116,top+375,100,23,()->{page++;filter();});button("Return",left+530,top+375,120,23,this::onClose);filter();if(loads==0)reload();
    }
    /** Asks for the list again: the server's arrives as a reply, the client's is read on another thread. */
    private void reload(){
        int ticket=++loads;listing=true;waitTicks=0;waitVersion=ClientState.libraryVersion;
        if(!ClientState.options.libraryClient){CraftiumClient.send("blueprint_list",null,null);return;}
        java.util.concurrent.CompletableFuture.supplyAsync(ClientAssets::list).whenComplete((found,error)->minecraft.execute(()->{if(ticket!=loads)return;local=error==null?found:List.of();listing=false;filter();}));
    }
    private boolean loading(){return listing||!warm;}
    private void filter(){String text=query.toLowerCase(Locale.ROOT);matches=(ClientState.options.libraryClient?local:ClientState.blueprints).stream().filter(e->(e.file()+" "+e.name()+" "+e.author()+" "+e.tags()).toLowerCase(Locale.ROOT).contains(text)).toList();page=Math.clamp(page,0,Math.max(0,(matches.size()-1)/6));version=ClientState.libraryVersion;}
    private void button(String label,int x,int y,int w,int h,Runnable action){addRenderableWidget(new FlatButton(x,y,w,h,label,action));}
    @Override public void tick(){
        if(listing&&!ClientState.options.libraryClient&&(ClientState.libraryVersion!=waitVersion||++waitTicks>200))listing=false; // the server answered, or never will
        if(!warm&&frames>=2){BlockColours.faces("minecraft:stone");warm=true;} // the first use reads every block's textures, which takes a while: the note is on screen before it starts
        if(version!=ClientState.libraryVersion){if(ClientState.options.libraryClient)reload();else filter();}
    }
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int x,int y,float delta){
        BlueprintThumbnails.newFrame();g.pose().pushMatrix();g.pose().scale(scale,scale);g.fill(0,0,canvasW,canvasH,0xef101923);g.fill(left-12,top-25,left+672,top+414,0xff172330);g.text(font,"BLUEPRINTS",left,top-17,0xffb5dcfc,false);
        frames++;boolean loading=loading();
        if(loading){var note="Loading blueprints…";g.text(font,note,left+(660-font.width(note))/2,top+190,0xffb5dcfc,false);}
        else for(int i=page*6;i<Math.min(matches.size(),page*6+6);i++){
            int index=i-page*6,bx=left+8+(index%2)*327,by=top+40+(index/2)*106;var entry=matches.get(i);boolean over=x/scale>=bx&&x/scale<bx+315&&y/scale>=by&&y/scale<by+98;g.fill(bx,by,bx+315,by+98,over?Hover.fill(0xff223247):0xff223247);if(over)g.outline(bx,by,315,98,ClientState.options.uiAccent);
            g.fill(bx+5,by+5,bx+133,by+93,0xff2a3441);var picture=BlueprintThumbnails.of(entry);
            if(picture!=null)g.blit(picture,bx+5,by+5,0,0,AiThumbnails.WIDTH,AiThumbnails.HEIGHT,AiThumbnails.WIDTH,AiThumbnails.HEIGHT,AiThumbnails.WIDTH,AiThumbnails.HEIGHT,0xffffffff); // the same isometric picture the AI build sessions show
            else if(entry.thumbnail()!=null&&entry.thumbnail().size()==256)for(int pixel=0;pixel<256;pixel++){int c=entry.thumbnail().get(pixel);if(c!=0)g.fill(bx+45+(pixel%16)*3,by+25+(pixel/16)*3,bx+48+(pixel%16)*3,by+28+(pixel/16)*3,c);} // listed without blocks: the flat top-down view
            g.outline(bx+5,by+5,128,88,0xff3a4a5c);
            g.text(font,font.plainSubstrByWidth(entry.name(),166),bx+141,by+10,0xffdce6ef,false);g.text(font,font.plainSubstrByWidth(entry.author().isBlank()?"Unspecified author":entry.author(),166),bx+141,by+27,0xff9db5cb,false);
            g.text(font,font.plainSubstrByWidth(entry.blocks()<0?"Imported schematic":entry.blocks()+" blocks · "+entry.size().coordinates(),166),bx+141,by+44,0xff9db5cb,false);g.text(font,font.plainSubstrByWidth(entry.tags(),166),bx+141,by+61,0xffa9c9e3,false);g.text(font,font.plainSubstrByWidth(entry.file(),166),bx+141,by+78,0xff829ab0,false);
        }
        if(!loading&&matches.isEmpty())g.text(font,"No blueprints match this search. Save a clipboard in Library or import a schematic.",left+8,top+61,0xff9db5cb,false);
        g.text(font,matches.size()+" files · Page "+(page+1)+" / "+Math.max(1,(matches.size()+5)/6),left+240,top+383,0xff9db5cb,false);drawWidgets(g,(int)(x/scale),(int)(y/scale),delta);g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){
        double x=event.x()/scale,y=event.y()/scale;
        if(!loading())for(int i=page*6;i<Math.min(matches.size(),page*6+6);i++){int index=i-page*6,bx=left+8+(index%2)*327,by=top+40+(index/2)*106;if(x>=bx&&x<bx+315&&y>=by&&y<by+98){ClientState.options.name=matches.get(i).file();if(ClientState.options.libraryClient)ClientAssets.load(matches.get(i).file());else CraftiumClient.send("blueprint_load",null,null);ClientState.notify("Loading blueprint · Ctrl+V in the viewport to begin placement");onClose();return true;}}
        return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x,y,event.buttonInfo()),twice);
    }
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event){return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()));}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){BlueprintThumbnails.releaseAll();ClientCompat.setScreen(minecraft,parent);}
}
