package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.BlueprintInfo;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * Browses the blueprint library as folders (the folders of the files' paths) in one of three views: a grid of pictures, a list of rows, or
 * the detailed cards. A search looks through every folder at once and lists the matches flat, each with its path.
 */
public final class BlueprintScreen extends CraftiumScreen {
    private static final String[] MODES={"Grid","List","Cards"};
    private static final int[] PER_PAGE={12,14,6};
    /** Kept while the game runs, so the browser opens in the view it was left in. */
    private static int mode;
    /** A folder to enter ({@code info} is null; {@code path} is the folder's path, {@code count} the files below it) or a blueprint. */
    private record Item(String path,int count,BlueprintInfo info){boolean folder(){return info==null;}}
    private final net.minecraft.client.gui.screens.Screen parent;
    private int left,top,canvasW,canvasH,page,version,total;
    private float scale=1;
    private String query="",folder="";
    private List<Item> items=List.of();
    /** The client's own files, read off the render thread; shown once {@code listing} is over and the block colours are ready, so opening the screen never stalls on either. */
    private volatile List<BlueprintInfo> local=List.of();
    private boolean listing,warm;
    private int loads,frames,waitTicks,waitVersion;
    public BlueprintScreen(net.minecraft.client.gui.screens.Screen parent){super(Component.literal("Craftium Blueprint Browser"));this.parent=parent;}
    @Override protected void init(){
        scale=Math.min(1f,Math.min(width/700f,height/460f));canvasW=Math.round(width/scale);canvasH=Math.round(height/scale);left=(canvasW-660)/2;top=(canvasH-420)/2;clearWidgets();
        var search=new EditBox(font,left+8,top+5,420,22,Component.literal("Search blueprints"));search.setHint(Component.literal("Search names, folders, authors and tags…"));search.setValue(query);search.setResponder(value->{query=value;page=0;filter();});addRenderableWidget(search);
        button(ClientState.options.libraryClient?"Source: Client":"Source: Server",left+500,top-21,150,20,()->{ClientState.options.libraryClient=!ClientState.options.libraryClient;folder="";reload();init();});
        button("View: "+MODES[mode],left+340,top-21,150,20,()->{mode=(mode+1)%MODES.length;page=0;filter();init();});
        button("Refresh",left+437,top+5,100,22,this::reload);button("Import…",left+545,top+5,105,22,()->{if(ClientState.options.libraryClient)AssetPicker.local(this::reload);else AssetPicker.open(this::reload);});
        button("Previous",left+8,top+375,100,23,()->{page=Math.max(0,page-1);filter();});button("Next",left+116,top+375,100,23,()->{page++;filter();});button("Return",left+530,top+375,120,23,this::onClose);
        if(!folder.isEmpty())button("Up",left+400,top+375,120,23,this::up);
        filter();if(loads==0)reload();
    }
    /** Asks for the list again: the server's arrives as a reply, the client's is read on another thread. */
    private void reload(){
        int ticket=++loads;listing=true;waitTicks=0;waitVersion=ClientState.libraryVersion;
        if(!ClientState.options.libraryClient){CraftiumClient.send("blueprint_list",null,null);return;}
        java.util.concurrent.CompletableFuture.supplyAsync(ClientAssets::list).whenComplete((found,error)->minecraft.execute(()->{if(ticket!=loads)return;local=error==null?found:List.of();listing=false;filter();}));
    }
    private boolean loading(){return listing||!warm;}
    private void enter(String path){folder=path;page=0;init();}
    private void up(){int slash=folder.lastIndexOf('/');enter(slash<0?"":folder.substring(0,slash));}
    private int perPage(){return PER_PAGE[mode];}
    /** The items to show: a search matches every file in every folder; otherwise the folders and files directly inside the open folder. */
    private void filter(){
        var all=ClientState.options.libraryClient?local:ClientState.blueprints;String text=query.toLowerCase(Locale.ROOT).trim();
        var found=new ArrayList<Item>();
        if(!text.isEmpty()){for(var e:all)if((e.file()+" "+e.name()+" "+e.author()+" "+e.tags()).toLowerCase(Locale.ROOT).contains(text))found.add(new Item(null,0,e));}
        else{
            String prefix=folder.isEmpty()?"":folder+"/";var folders=new TreeMap<String,Integer>();var files=new ArrayList<Item>();
            for(var e:all){String file=e.file();if(!file.startsWith(prefix))continue;String rest=file.substring(prefix.length());int slash=rest.indexOf('/');if(slash<0)files.add(new Item(null,0,e));else folders.merge(rest.substring(0,slash),1,Integer::sum);}
            for(var f:folders.entrySet())found.add(new Item(prefix+f.getKey(),f.getValue(),null));found.addAll(files);
        }
        items=found;total=found.size();page=Math.clamp(page,0,Math.max(0,(total-1)/perPage()));version=ClientState.libraryVersion;
    }
    private void button(String label,int x,int y,int w,int h,Runnable action){addRenderableWidget(new FlatButton(x,y,w,h,label,action));}
    @Override public void tick(){
        if(listing&&!ClientState.options.libraryClient&&(ClientState.libraryVersion!=waitVersion||++waitTicks>200))listing=false; // the server answered, or never will
        if(!warm&&frames>=2){BlockColours.faces("minecraft:stone");warm=true;} // the first use reads every block's textures, which takes a while: the note is on screen before it starts
        if(version!=ClientState.libraryVersion){if(ClientState.options.libraryClient)reload();else filter();}
    }
    /** Where the item at this place on the page sits: x, y, width, height. */
    private int[] cell(int index){return switch(mode){
        case 0->new int[]{left+6+(index%4)*164,top+36+(index/4)*113,156,108};
        case 1->new int[]{left+8,top+34+index*24,644,22};
        default->new int[]{left+8+(index%2)*327,top+40+(index/2)*106,315,98};};}
    private static String name(Item item){return item.folder()?item.path().substring(item.path().lastIndexOf('/')+1):item.info().name();}
    private static String count(Item item){return item.count()+(item.count()==1?" file":" files");}
    private static String blocks(BlueprintInfo e){return e.blocks()<0?"Imported schematic":e.blocks()+" blocks";}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    private void folderIcon(Gfx g,int x,int y,int w,int h){g.fill(x,y,x+w*2/5,y+h/4,0xffc9a227);g.fill(x,y+h/6,x+w,y+h,0xffe6bb3a);g.fill(x,y+h/3,x+w,y+h,0xffd8aa2c);}
    /** The 128 x 88 picture of a blueprint: the isometric one when it has blocks, else the flat top-down view. */
    private void picture(Gfx g,BlueprintInfo entry,int px,int py){
        g.fill(px,py,px+128,py+88,0xff2a3441);var picture=BlueprintThumbnails.of(entry);
        if(picture!=null)g.blit(picture,px,py,0,0,AiThumbnails.WIDTH,AiThumbnails.HEIGHT,AiThumbnails.WIDTH,AiThumbnails.HEIGHT,AiThumbnails.WIDTH,AiThumbnails.HEIGHT,0xffffffff); // the same isometric picture the AI build sessions show
        else if(entry.thumbnail()!=null&&entry.thumbnail().size()==256)for(int pixel=0;pixel<256;pixel++){int c=entry.thumbnail().get(pixel);if(c!=0)g.fill(px+40+(pixel%16)*3,py+20+(pixel/16)*3,px+43+(pixel%16)*3,py+23+(pixel/16)*3,c);}
        g.outline(px,py,128,88,0xff3a4a5c);
    }
    private void drawItem(Gfx g,Item item,int[] c,boolean over){
        int bx=c[0],by=c[1],w=c[2],h=c[3];g.fill(bx,by,bx+w,by+h,over?Hover.fill(0xff223247):0xff223247);if(over)g.outline(bx,by,w,h,ClientState.options.uiAccent);
        var e=item.info();
        switch(mode){
            case 0->{
                int px=bx+14,py=by+4;
                if(item.folder()){g.fill(px,py,px+128,py+88,0xff2a3441);folderIcon(g,px+44,py+16,40,32);g.text(font,count(item),px+(128-font.width(count(item)))/2,py+68,0xff9db5cb,false);g.outline(px,py,128,88,0xff3a4a5c);}
                else picture(g,e,px,py);
                g.text(font,font.plainSubstrByWidth(name(item),w-10),bx+5,by+94,0xffdce6ef,false);
            }
            case 1->{
                if(item.folder()){folderIcon(g,bx+6,by+6,14,11);g.text(font,font.plainSubstrByWidth(name(item)+"/",220),bx+26,by+7,0xffffe9a8,false);g.text(font,count(item),bx+258,by+7,0xff9db5cb,false);}
                else{g.fill(bx+6,by+6,bx+18,by+16,0xff5a7b99);
                    g.text(font,font.plainSubstrByWidth(query.isBlank()?e.name():e.file(),220),bx+26,by+7,0xffdce6ef,false);g.text(font,font.plainSubstrByWidth(blocks(e),86),bx+258,by+7,0xff9db5cb,false);
                    g.text(font,font.plainSubstrByWidth(e.blocks()<0?"":e.size().coordinates(),100),bx+350,by+7,0xff9db5cb,false);g.text(font,font.plainSubstrByWidth(e.author().isBlank()?"Unspecified author":e.author(),86),bx+458,by+7,0xff9db5cb,false);g.text(font,font.plainSubstrByWidth(e.tags(),90),bx+552,by+7,0xffa9c9e3,false);}
            }
            default->{
                if(item.folder()){g.fill(bx+5,by+5,bx+133,by+93,0xff2a3441);folderIcon(g,bx+49,by+21,40,32);g.outline(bx+5,by+5,128,88,0xff3a4a5c);
                    g.text(font,font.plainSubstrByWidth(name(item)+"/",166),bx+141,by+10,0xffffe9a8,false);g.text(font,count(item),bx+141,by+27,0xff9db5cb,false);return;}
                picture(g,e,bx+5,by+5);
                g.text(font,font.plainSubstrByWidth(e.name(),166),bx+141,by+10,0xffdce6ef,false);g.text(font,font.plainSubstrByWidth(e.author().isBlank()?"Unspecified author":e.author(),166),bx+141,by+27,0xff9db5cb,false);
                g.text(font,font.plainSubstrByWidth(e.blocks()<0?"Imported schematic":e.blocks()+" blocks · "+e.size().coordinates(),166),bx+141,by+44,0xff9db5cb,false);g.text(font,font.plainSubstrByWidth(e.tags(),166),bx+141,by+61,0xffa9c9e3,false);g.text(font,font.plainSubstrByWidth(e.file(),166),bx+141,by+78,0xff829ab0,false);
            }
        }
    }
    @Override protected void draw(Gfx g,int x,int y,float delta){
        BlueprintThumbnails.newFrame();g.pose().pushMatrix();g.pose().scale(scale,scale);g.fill(0,0,canvasW,canvasH,0xef101923);g.fill(left-12,top-25,left+672,top+414,0xff172330);
        g.text(font,font.plainSubstrByWidth(folder.isEmpty()?"BLUEPRINTS":"BLUEPRINTS / "+folder.replace("/"," / "),330),left,top-17,0xffb5dcfc,false);
        frames++;boolean loading=loading();int per=perPage();
        if(loading){var note="Loading blueprints…";g.text(font,note,left+(660-font.width(note))/2,top+190,0xffb5dcfc,false);}
        else for(int i=page*per;i<Math.min(total,page*per+per);i++){var c=cell(i-page*per);drawItem(g,items.get(i),c,x/scale>=c[0]&&x/scale<c[0]+c[2]&&y/scale>=c[1]&&y/scale<c[1]+c[3]);}
        if(!loading&&items.isEmpty())g.text(font,query.isBlank()&&!folder.isEmpty()?"This folder is empty.":"No blueprints match this search. Save a clipboard in Library or import a schematic.",left+8,top+61,0xff9db5cb,false);
        g.text(font,total+(total==1?" item":" items")+" · Page "+(page+1)+" / "+Math.max(1,(total+per-1)/per),left+240,top+383,0xff9db5cb,false);drawWidgets(g,(int)(x/scale),(int)(y/scale),delta);g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){
        double x=event.x()/scale,y=event.y()/scale;int per=perPage();
        if(!loading())for(int i=page*per;i<Math.min(total,page*per+per);i++){var c=cell(i-page*per);if(x>=c[0]&&x<c[0]+c[2]&&y>=c[1]&&y<c[1]+c[3]){
            var item=items.get(i);
            if(item.folder()){query="";enter(item.path());return true;}
            ClientState.options.name=item.info().file();if(ClientState.options.libraryClient)ClientAssets.load(item.info().file());else CraftiumClient.send("blueprint_load",null,null);ClientState.notify("Loading blueprint · Ctrl+V in the viewport to begin placement");onClose();return true;}}
        return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x,y,event.buttonInfo()),twice);
    }
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event){return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()));}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){BlueprintThumbnails.releaseAll();ClientCompat.setScreen(minecraft,parent);}
}
