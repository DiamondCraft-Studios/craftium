package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.BlueprintInfo;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.server.BlueprintFiles;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * Browses the blueprint library like a file explorer: folders (the folders of the files' paths), three views (a grid of pictures, a list of rows, the
 * detailed cards), a selection, a right-click menu and cut, copy and paste between folders. A click selects, a double-click opens a folder or puts a
 * blueprint on the clipboard without closing the screen. A search looks through every folder at once and lists the matches flat.
 * Changing the server's library is for operators; the server says so when anyone else tries.
 */
public final class BlueprintScreen extends CraftiumScreen {
    private static final String[] MODES={"Grid","List","Cards"};
    private static final String[] SORTS={"Name (A-Z)","Name (Z-A)","Most blocks","Fewest blocks","Author"};
    /** Per view: items across, the step from one row to the next, and the height of an item. */
    private static final int[] COLUMNS={4,1,2},STEP={113,24,106},CARD={108,22,98};
    /** The part of the window the items scroll in, from this far below the top of the panel. */
    private static final int VIEW_TOP=32,VIEW_H=340;
    /** Kept while the game runs, so the browser opens in the view and order it was left in. */
    private static int mode,sort;
    /** What Cut or Copy last took: paths in one library (the client's or the server's), waiting for a Paste. */
    private record Clip(boolean cut,boolean client,List<String> paths){}
    private static Clip clip;
    /** Whether the cut items are shown dimmed: Refresh clears the mark, as in Windows, and a paste still moves them. */
    private static boolean cutShown;
    /** A folder to enter ({@code info} is null; {@code path} is the folder's path, {@code count} the files below it) or a blueprint. */
    private record Item(String path,int count,BlueprintInfo info){boolean folder(){return info==null;}String key(){return folder()?path+"/":info.file();}}
    /** A row of the right-click menu; a null label is a divider. */
    private record Entry(String label,boolean enabled,Runnable action){}
    /** A small question over the browser: some text boxes and an OK that hands their text on. */
    private record Dialog(String title,String message,List<String[]> fields,String ok,java.util.function.Consumer<List<String>> action){}
    private final Screen parent;
    private int left,top,canvasW,canvasH,scroll,version,total;
    private boolean dragBar;
    private float scale=1;
    private String query="",folder="";
    private List<Item> items=List.of();
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private String anchor;
    private List<Entry> menu;
    private int menuX,menuY;
    private Dialog dialog;
    private final List<EditBox> boxes=new ArrayList<>();
    /** The client's own files, read off the render thread; shown once {@code listing} is over and the block colours are ready, so opening the screen never stalls on either. */
    private volatile List<BlueprintInfo> local=List.of();
    private boolean listing,warm;
    private int loads,frames,waitTicks,waitVersion;
    public BlueprintScreen(Screen parent){super(Component.literal("Craftium Blueprint Browser"));this.parent=parent;}
    @Override protected void init(){
        scale=Math.min(1f,Math.min(width/700f,height/460f));canvasW=Math.round(width/scale);canvasH=Math.round(height/scale);left=(canvasW-660)/2;top=(canvasH-420)/2;clearWidgets();boxes.clear();
        var search=new EditBox(font,left+8,top+5,420,22,Component.literal("Search blueprints"));search.setHint(Component.literal("Search names, folders, authors and tags…"));search.setValue(query);search.setResponder(value->{query=value;scroll=0;filter();});addRenderableWidget(search);
        button(ClientState.options.libraryClient?"Source: Client":"Source: Server",left+500,top-21,150,20,()->{ClientState.options.libraryClient=!ClientState.options.libraryClient;folder="";scroll=0;selected.clear();reload();init();});
        button("View: "+MODES[mode],left+212,top-21,136,20,()->setMode((mode+1)%MODES.length));button("Sort: "+SORTS[sort],left+354,top-21,140,20,()->{sort=(sort+1)%SORTS.length;scroll=0;init();});
        button("Refresh",left+437,top+5,100,22,this::refresh);button("Import…",left+545,top+5,105,22,this::importFile);
        button("Return",left+530,top+375,120,23,this::onClose);
        if(!folder.isEmpty())button("Up",left+400,top+375,120,23,this::up);
        if(dialog!=null)buildDialog();
        if(loads==0)reload();filter(); // the list is asked for first, so a list that has not arrived is not taken for an empty library
    }
    private void setMode(int next){mode=next;scroll=0;init();}
    private void importFile(){if(ClientState.options.libraryClient)AssetPicker.local(this::reload);else AssetPicker.open(this::reload);}
    /** Asks for the list again: the server's arrives as a reply, the client's is read on another thread. */
    private void reload(){
        int ticket=++loads;listing=true;waitTicks=0;waitVersion=ClientState.libraryVersion;
        version=ClientState.libraryVersion; // this load answers the change: without it tick() would start another every tick, and each would drop the one before it
        if(!ClientState.options.libraryClient){CraftiumClient.send("blueprint_list",null,null);return;}
        java.util.concurrent.CompletableFuture.supplyAsync(ClientAssets::list).whenComplete((found,error)->minecraft.execute(()->{if(ticket!=loads)return;local=error==null?found:List.of();listing=false;filter();}));
    }
    /** What the player asks for with Refresh or F5: the list again, and the cut items are no longer shown as cut. */
    private void refresh(){cutShown=false;reload();}
    private boolean loading(){return listing||!warm;}
    private void enter(String path){folder=path;scroll=0;selected.clear();anchor=null;menu=null;init();}
    private void up(){int slash=folder.lastIndexOf('/');enter(slash<0?"":folder.substring(0,slash));}
    /** The items to show: a search matches every file in every folder; otherwise the folders and files directly inside the open folder. */
    private void filter(){
        var all=ClientState.options.libraryClient?local:ClientState.blueprints;String text=query.toLowerCase(Locale.ROOT).trim();
        var found=new ArrayList<Item>();
        if(!text.isEmpty()){for(var e:all)if(!e.file().endsWith("/")&&(e.file()+" "+e.name()+" "+e.author()+" "+e.tags()).toLowerCase(Locale.ROOT).contains(text))found.add(new Item(null,0,e));found.sort(order());}
        else{
            String prefix=folder.isEmpty()?"":folder+"/";var folders=new TreeMap<String,Integer>(sort==1?Comparator.<String>reverseOrder():Comparator.<String>naturalOrder());var files=new ArrayList<Item>();
            for(var e:all){
                String file=e.file();if(!file.startsWith(prefix))continue;String rest=file.substring(prefix.length());if(rest.isEmpty())continue;
                int slash=rest.indexOf('/');if(slash<0)files.add(new Item(null,0,e));else folders.merge(rest.substring(0,slash),file.endsWith("/")?0:1,Integer::sum); // an entry ending in "/" is an empty folder
            }
            files.sort(order());for(var f:folders.entrySet())found.add(new Item(prefix+f.getKey(),f.getValue(),null));found.addAll(files);
        }
        items=found;total=found.size();scroll=Math.clamp(scroll,0,maxScroll());version=ClientState.libraryVersion;if(!listing)pruneClip(all);
        var keys=new HashSet<String>();for(var i:items)keys.add(i.key());selected.retainAll(keys);if(anchor!=null&&!keys.contains(anchor))anchor=null;
    }
    /** How the files are ordered: by the chosen sort, then by path. */
    private static Comparator<Item> order(){
        Comparator<Item> by=switch(sort){
            case 1->Comparator.comparing((Item i)->i.info().name().toLowerCase(Locale.ROOT)).reversed();
            case 2->Comparator.comparingInt((Item i)->i.info().blocks()).reversed(); // an imported file has no count (-1) and goes last
            case 3->Comparator.comparingInt((Item i)->i.info().blocks()<0?Integer.MAX_VALUE:i.info().blocks());
            case 4->Comparator.comparing((Item i)->i.info().author()==null||i.info().author().isBlank()?"\uffff":i.info().author().toLowerCase(Locale.ROOT));
            default->Comparator.comparing((Item i)->i.info().name().toLowerCase(Locale.ROOT));};
        return by.thenComparing(i->i.info().file());
    }
    /** After a refresh, what was cut or copied that is no longer in the library (moved by the paste, or gone) is dropped from the waiting clipboard. */
    private void pruneClip(List<BlueprintInfo> all){
        if(clip==null||clip.client()!=ClientState.options.libraryClient)return;
        var alive=clip.paths().stream().filter(p->all.stream().anyMatch(e->p.endsWith("/")?e.file().startsWith(p):e.file().equals(p))).toList();
        if(alive.size()!=clip.paths().size())clip=alive.isEmpty()?null:new Clip(clip.cut(),clip.client(),alive);
    }
    private void button(String label,int x,int y,int w,int h,Runnable action){addRenderableWidget(new FlatButton(x,y,w,h,label,action));}
    @Override public void tick(){
        if(listing&&!ClientState.options.libraryClient&&(ClientState.libraryVersion!=waitVersion||++waitTicks>200))listing=false; // the server answered, or never will
        if(!warm&&frames>=2){BlockColours.faces("minecraft:stone");warm=true;} // the first use reads every block's textures, which takes a while: the note is on screen before it starts
        if(version!=ClientState.libraryVersion){if(ClientState.options.libraryClient)reload();else filter();}
    }

    // ----- what the menu and the keys do -----
    private String where(){return folder.isEmpty()?"":folder+"/";}
    private List<Item> chosen(){var out=new ArrayList<Item>();for(var i:items)if(selected.contains(i.key()))out.add(i);return out;}
    /** Sends a change to the library being browsed: the client's own is changed here, the server's is asked to. */
    private void run(BlueprintFiles.Op op){
        if(ClientState.options.libraryClient){try{ClientState.notify(ClientAssets.operate(op));}catch(IllegalArgumentException e){ClientState.notify(e.getMessage());}reload();return;}
        var o=ClientState.options;String prior=o.assetData;o.assetData=BlueprintFiles.encode(op);CraftiumClient.send("blueprint_op",null,null);o.assetData=prior; // the reply carries the new list
    }
    private void take(boolean cut){var keys=chosen().stream().map(Item::key).toList();if(keys.isEmpty())return;clip=new Clip(cut,ClientState.options.libraryClient,keys);cutShown=cut;ClientState.notify((cut?"Cut ":"Copied ")+keys.size()+(keys.size()==1?" item":" items")+" · Paste in a folder to "+(cut?"move":"copy")+" "+(keys.size()==1?"it":"them"));}
    private boolean canPaste(){return clip!=null&&clip.client()==ClientState.options.libraryClient;}
    private void paste(String into){if(!canPaste())return;var taken=clip;run(new BlueprintFiles.Op(taken.cut()?"cut":"copy",into,taken.paths(),null,null,null));}
    /** Puts a blueprint on the game clipboard for pasting in the viewport; the browser stays open. */
    private void useAsClipboard(BlueprintInfo info){
        ClientState.options.name=info.file();if(ClientState.options.libraryClient)ClientAssets.load(info.file());else CraftiumClient.send("blueprint_load",null,null);
        ClientState.notify("Copied "+info.name()+" to your clipboard · Ctrl+V in the viewport to begin placement");
    }
    private void activate(Item item){if(item.folder()){query="";enter(item.path());}else useAsClipboard(item.info());}
    private void archive(List<Item> targets){
        if(targets.isEmpty())return;
        String what=targets.size()==1?"\""+leaf(targets.get(0))+"\"":targets.size()+" items";
        open(new Dialog("Archive",targets.stream().anyMatch(Item::folder)?"Archive "+what+" and everything inside? It moves to the blueprints-archive folder, outside the library.":"Archive "+what+"? It moves to the blueprints-archive folder, outside the library.",List.of(),"Archive",v->run(new BlueprintFiles.Op("delete",null,targets.stream().map(Item::key).toList(),null,null,null))));
    }
    private void rename(Item item){open(new Dialog("Rename","",List.<String[]>of(new String[]{"Name",stem(item)}),"Rename",v->run(new BlueprintFiles.Op("rename",item.key(),null,v.get(0),null,null))));}
    private void properties(BlueprintInfo e){
        open(new Dialog("Properties",e.file()+" · "+(e.blocks()<0?"imported schematic":e.blocks()+" blocks · "+e.size().coordinates()),List.of(new String[]{"Name",e.name()},new String[]{"Author",e.author()==null?"":e.author()},new String[]{"Tags",e.tags()==null?"":e.tags()}),"Save",v->run(new BlueprintFiles.Op("edit",e.file(),null,v.get(0),v.get(1),v.get(2)))));
    }
    private void newFolder(){open(new Dialog("New folder","In "+(folder.isEmpty()?"the top level":folder),List.<String[]>of(new String[]{"Name",""}),"Create",v->{if(!v.get(0).isBlank())run(new BlueprintFiles.Op("mkdir",where()+v.get(0).trim(),null,null,null,null));}));}
    private void saveClipboard(){open(new Dialog("Save clipboard here","In "+(folder.isEmpty()?"the top level":folder),List.of(new String[]{"Name",""},new String[]{"Author",ClientState.options.author==null?"":ClientState.options.author},new String[]{"Tags",ClientState.options.tags==null?"":ClientState.options.tags}),"Save",v->{
        if(v.get(0).isBlank())return;var o=ClientState.options;o.name=where()+v.get(0).trim();o.author=v.get(1);o.tags=v.get(2);CraftiumClient.send("blueprint_save",null,null);if(!o.libraryClient)reload();}));}
    private static String leaf(Item item){String path=item.folder()?item.path():item.info().file();return path.substring(path.lastIndexOf('/')+1);}
    private static String stem(Item item){String leaf=leaf(item);return item.folder()?leaf:leaf.replaceFirst("\\.[^.]+$","");}

    // ----- the menu -----
    private Entry row(String label,Runnable action){return new Entry(label,true,action);}
    private Entry row(String label,boolean enabled,Runnable action){return new Entry(label,enabled,action);}
    private static final Entry DIVIDER=new Entry(null,false,null);
    private List<Entry> buildMenu(){
        var out=new ArrayList<Entry>();var picked=chosen();
        if(picked.size()==1){
            var item=picked.get(0);
            if(item.folder()){out.add(row("Open",()->activate(item)));out.add(DIVIDER);}
            else{out.add(row("Use as clipboard",()->useAsClipboard(item.info())));out.add(DIVIDER);}
            out.add(row("Cut",()->take(true)));out.add(row("Copy",()->take(false)));
            out.add(row("Paste",item.folder()&&canPaste(),()->paste(item.path())));out.add(DIVIDER);
            out.add(row("Rename",()->rename(item)));out.add(row("Archive",()->archive(picked)));out.add(DIVIDER);
            if(item.folder())out.add(row("Copy path",()->minecraft.keyboardHandler.setClipboard(item.path())));
            else{out.add(row("Copy path",()->minecraft.keyboardHandler.setClipboard(item.info().file())));out.add(row("Properties",()->properties(item.info())));}
        }else if(picked.size()>1){
            out.add(row("Cut "+picked.size()+" items",()->take(true)));out.add(row("Copy "+picked.size()+" items",()->take(false)));out.add(DIVIDER);out.add(row("Archive "+picked.size()+" items",()->archive(picked)));
        }else{
            out.add(row("New folder",this::newFolder));out.add(row("Paste",canPaste(),()->paste(folder)));out.add(DIVIDER);
            out.add(row("Save clipboard here…",this::saveClipboard));out.add(row("Import…",this::importFile));out.add(row("Refresh",this::refresh));out.add(DIVIDER);
            for(int m=0;m<MODES.length;m++){int next=m;out.add(row((m==mode?"• ":"   ")+MODES[m]+" view",()->setMode(next)));}
        }
        return out;
    }
    private int menuWidth(){return 170;}
    private int menuHeight(){int h=4;for(var e:menu)h+=e.label()==null?6:18;return h;}
    private void openMenu(double x,double y){menu=buildMenu();menuX=(int)Math.min(x,canvasW-menuWidth()-2);menuY=(int)Math.min(y,canvasH-menuHeight()-2);}
    /** The entry under a point of the open menu, or null. */
    private Entry menuAt(double x,double y){
        if(menu==null||x<menuX||x>=menuX+menuWidth())return null;int row=menuY+2;
        for(var e:menu){int h=e.label()==null?6:18;if(y>=row&&y<row+h)return e.label()==null?null:e;row+=h;}
        return null;
    }
    private void drawMenu(Gfx g,double mx,double my){
        var o=ClientState.options;int w=menuWidth(),h=menuHeight();g.fill(menuX+2,menuY+2,menuX+w+2,menuY+h+2,0x66000000);g.fill(menuX,menuY,menuX+w,menuY+h,0xff1c2a3a);g.outline(menuX,menuY,w,h,o.uiBorder);
        int row=menuY+2;var over=menuAt(mx,my);
        for(var e:menu){
            if(e.label()==null){g.fill(menuX+6,row+2,menuX+w-6,row+3,0xff3a4a5c);row+=6;continue;}
            if(e==over&&e.enabled())g.fill(menuX+2,row,menuX+w-2,row+18,Hover.fill(0xff223247));
            g.text(font,e.label(),menuX+10,row+5,e.enabled()?o.uiText:o.uiMuted,false);row+=18;
        }
    }

    // ----- dialogs -----
    private void open(Dialog next){dialog=next;menu=null;init();}
    private int dialogX(){return left+160;}
    private int dialogY(){return top+110;}
    private int dialogTop(){return dialogY()+(dialog.message().isEmpty()?30:46);}
    private int dialogHeight(){return dialogTop()-dialogY()+dialog.fields().size()*38+34;}
    private void buildDialog(){
        int x=dialogX(),y0=dialogTop(),h=dialogHeight();
        for(int i=0;i<dialog.fields().size();i++){var box=new EditBox(font,x+12,y0+11+i*38,316,20,Component.literal(dialog.fields().get(i)[0]));box.setMaxLength(i==2?256:100);box.setValue(dialog.fields().get(i)[1]);boxes.add(box);addRenderableWidget(box);}
        button(dialog.ok(),x+12,dialogY()+h-28,150,20,this::confirm);button("Cancel",x+178,dialogY()+h-28,150,20,this::closeDialog);
        if(!boxes.isEmpty())setFocused(boxes.get(0));
    }
    private void confirm(){var made=dialog;var values=new ArrayList<String>();for(var b:boxes)values.add(b.getValue());closeDialog();made.action().accept(values);}
    private void closeDialog(){dialog=null;init();}
    private void drawDialog(Gfx g){
        var o=ClientState.options;int x=dialogX(),y=dialogY(),h=dialogHeight();
        g.fill(x-4,y-4,x+344,y+h+4,0xaa000000);g.fill(x,y,x+340,y+h,0xff1c2a3a);g.outline(x,y,340,h,o.uiAccent);
        g.text(font,dialog.title(),x+12,y+10,0xffb5dcfc,false);if(!dialog.message().isEmpty())g.text(font,font.plainSubstrByWidth(dialog.message(),316),x+12,y+26,0xff9db5cb,false);
        for(int i=0;i<dialog.fields().size();i++)g.text(font,dialog.fields().get(i)[0],x+12,dialogTop()+i*38,0xff9db5cb,false);
    }

    // ----- selection -----
    private int indexOfKey(String key){if(key==null)return -1;for(int i=0;i<items.size();i++)if(items.get(i).key().equals(key))return i;return -1;}
    private void select(int index,boolean ctrl,boolean shift){
        var item=items.get(index);
        if(shift&&indexOfKey(anchor)>=0){int from=indexOfKey(anchor),a=Math.min(from,index),b=Math.max(from,index);if(!ctrl)selected.clear();for(int i=a;i<=b;i++)selected.add(items.get(i).key());return;}
        if(ctrl){if(!selected.remove(item.key()))selected.add(item.key());anchor=item.key();return;}
        selected.clear();selected.add(item.key());anchor=item.key();
    }
    private void step(int delta,boolean shift){
        if(items.isEmpty())return;int from=indexOfKey(anchor),next=from<0?(delta>0?0:items.size()-1):Math.clamp(from+delta,0,items.size()-1);
        if(shift&&from>=0){int keep=from;select(next,false,true);anchor=items.get(keep).key();}else select(next,false,false);
        reveal(next);
    }
    private int itemAt(double x,double y){
        if(loading()||y<top+VIEW_TOP||y>=top+VIEW_TOP+VIEW_H)return -1;
        for(int i=firstShown();i<lastShown();i++){var c=cell(i);if(x>=c[0]&&x<c[0]+c[2]&&y>=c[1]&&y<c[1]+c[3])return i;}
        return -1;
    }
    private boolean inContent(double x,double y){return x>=left&&x<left+655&&y>=top+VIEW_TOP&&y<top+VIEW_TOP+VIEW_H;}
    // ----- scrolling -----
    private int rows(){return (total+COLUMNS[mode]-1)/COLUMNS[mode];}
    private int contentHeight(){return 4+rows()*STEP[mode]-(STEP[mode]-CARD[mode])+4;}
    private int maxScroll(){return Math.max(0,contentHeight()-VIEW_H);}
    private int firstShown(){return Math.max(0,scroll/STEP[mode])*COLUMNS[mode];}
    private int lastShown(){return Math.min(total,((scroll+VIEW_H)/STEP[mode]+1)*COLUMNS[mode]);}
    private void scrollBy(int pixels){scroll=Math.clamp(scroll+pixels,0,maxScroll());}
    /** Scrolls just far enough for this item to be in view. */
    private void reveal(int index){
        int itemTop=4+(index/COLUMNS[mode])*STEP[mode],itemBottom=itemTop+CARD[mode];
        if(itemTop<scroll)scroll=itemTop-4;else if(itemBottom>scroll+VIEW_H)scroll=itemBottom-VIEW_H+4;
        scroll=Math.clamp(scroll,0,maxScroll());
    }
    private int thumbHeight(){return Math.max(24,VIEW_H*VIEW_H/Math.max(VIEW_H,contentHeight()));}
    private int thumbTop(){int free=VIEW_H-thumbHeight(),max=maxScroll();return max==0?0:free*scroll/max;}
    /** Puts the scroll bar's thumb under the pointer. */
    private void dragTo(double y){int free=VIEW_H-thumbHeight();if(free>0)scroll=Math.clamp((int)Math.round((y-top-VIEW_TOP-thumbHeight()/2.0)/free*maxScroll()),0,maxScroll());}

    // ----- drawing -----
    /** Where an item sits in the window, scrolled: x, y, width, height. */
    private int[] cell(int index){int row=index/COLUMNS[mode],y=top+VIEW_TOP+4+row*STEP[mode]-scroll;return switch(mode){
        case 0->new int[]{left+6+(index%4)*164,y,156,CARD[0]};
        case 1->new int[]{left+8,y,644,CARD[1]};
        default->new int[]{left+8+(index%2)*327,y,315,CARD[2]};};}
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
        int bx=c[0],by=c[1],w=c[2],h=c[3];boolean chosen=selected.contains(item.key());
        int base=chosen?0xff2b4a6b:0xff223247;g.fill(bx,by,bx+w,by+h,over?Hover.fill(base):base);if(over||chosen)g.outline(bx,by,w,h,ClientState.options.uiAccent);
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
                    g.text(font,font.plainSubstrByWidth(name(item)+"/",166),bx+141,by+10,0xffffe9a8,false);g.text(font,count(item),bx+141,by+27,0xff9db5cb,false);}
                else{picture(g,e,bx+5,by+5);
                    g.text(font,font.plainSubstrByWidth(e.name(),166),bx+141,by+10,0xffdce6ef,false);g.text(font,font.plainSubstrByWidth(e.author().isBlank()?"Unspecified author":e.author(),166),bx+141,by+27,0xff9db5cb,false);
                    g.text(font,font.plainSubstrByWidth(e.blocks()<0?"Imported schematic":e.blocks()+" blocks · "+e.size().coordinates(),166),bx+141,by+44,0xff9db5cb,false);g.text(font,font.plainSubstrByWidth(e.tags(),166),bx+141,by+61,0xffa9c9e3,false);g.text(font,font.plainSubstrByWidth(e.file(),166),bx+141,by+78,0xff829ab0,false);}
            }
        }
        if(cutShown&&clip!=null&&clip.cut()&&clip.client()==ClientState.options.libraryClient&&clip.paths().contains(item.key()))g.fill(bx,by,bx+w,by+h,0x88101923); // waiting to be moved
    }
    @Override protected void draw(Gfx g,int x,int y,float delta){
        BlueprintThumbnails.newFrame();g.pose().pushMatrix();g.pose().scale(scale,scale);g.fill(0,0,canvasW,canvasH,0xef101923);g.fill(left-12,top-25,left+672,top+414,0xff172330);
        double mx=x/scale,my=y/scale;boolean quiet=dialog!=null||menu!=null;
        g.text(font,font.plainSubstrByWidth(folder.isEmpty()?"BLUEPRINTS":"BLUEPRINTS / "+folder.replace("/"," / "),200),left,top-17,0xffb5dcfc,false);
        frames++;boolean loading=loading();
        if(loading){var note="Loading blueprints…";g.text(font,note,left+(660-font.width(note))/2,top+190,0xffb5dcfc,false);}
        else{
            g.enableScissor(left-4,top+VIEW_TOP,left+664,top+VIEW_TOP+VIEW_H);
            for(int i=firstShown();i<lastShown();i++){var c=cell(i);drawItem(g,items.get(i),c,!quiet&&my>=top+VIEW_TOP&&my<top+VIEW_TOP+VIEW_H&&mx>=c[0]&&mx<c[0]+c[2]&&my>=c[1]&&my<c[1]+c[3]);}
            g.disableScissor();
            if(maxScroll()>0){g.fill(left+657,top+VIEW_TOP,left+661,top+VIEW_TOP+VIEW_H,0xff223247);g.fill(left+657,top+VIEW_TOP+thumbTop(),left+661,top+VIEW_TOP+thumbTop()+thumbHeight(),dragBar?ClientState.options.uiAccent:0xff4a6a8a);}
        }
        if(!loading&&items.isEmpty())g.text(font,query.isBlank()&&!folder.isEmpty()?"This folder is empty. Right-click for a menu.":query.isBlank()?"Nothing here yet. Right-click to make a folder, save your clipboard or import a schematic.":"No blueprints match this search.",left+8,top+61,0xff9db5cb,false);
        String picked=selected.isEmpty()?"":" · "+selected.size()+" selected";
        g.text(font,total+(total==1?" item":" items")+picked+" · Sorted by "+SORTS[sort].toLowerCase(Locale.ROOT),left+8,top+383,0xff9db5cb,false);
        if(dialog!=null)drawDialog(g);
        drawWidgets(g,(int)mx,(int)my,delta);
        if(menu!=null)drawMenu(g,mx,my);
        g.pose().popMatrix();
    }

    // ----- input -----
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){
        double x=event.x()/scale,y=event.y()/scale;int button=event.button();
        var scaled=new net.minecraft.client.input.MouseButtonEvent(x,y,event.buttonInfo());
        if(dialog!=null){boolean inside=x>=dialogX()&&x<dialogX()+340&&y>=dialogY()&&y<dialogY()+dialogHeight();return inside&&super.mouseClicked(scaled,twice)||true;}
        if(menu!=null){var hit=menuAt(x,y);menu=null;if(hit!=null){if(hit.enabled())hit.action().run();return true;}if(button!=1)return true;}
        if(button==0&&!loading()&&maxScroll()>0&&x>=left+655&&x<left+664&&y>=top+VIEW_TOP&&y<top+VIEW_TOP+VIEW_H){dragBar=true;dragTo(y);return true;}
        if(inContent(x,y)&&!loading()){
            int index=itemAt(x,y);
            if(button==1){
                if(index>=0){if(!selected.contains(items.get(index).key()))select(index,false,false);}else selected.clear();
                openMenu(x,y);return true;
            }
            if(button==0){
                if(index<0){if(!event.hasControlDown()&&!event.hasShiftDown())selected.clear();return true;}
                if(twice&&!event.hasControlDown()&&!event.hasShiftDown()){select(index,false,false);activate(items.get(index));return true;}
                select(index,event.hasControlDown(),event.hasShiftDown());return true;
            }
        }
        return super.mouseClicked(scaled,twice);
    }
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,double dx,double dy){
        if(dragBar){dragTo(event.y()/scale);return true;}
        return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()),dx/scale,dy/scale);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(dialog!=null)return true;menu=null;scrollBy((int)Math.round(-vertical*STEP[mode]*0.75));return true;
    }
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event){dragBar=false;return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()));}
    @Override public boolean keyPressed(KeyEvent event){
        int key=event.key();
        if(dialog!=null){
            if(key==GLFW.GLFW_KEY_ESCAPE){closeDialog();return true;}
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){confirm();return true;}
            return super.keyPressed(event);
        }
        if(menu!=null&&key==GLFW.GLFW_KEY_ESCAPE){menu=null;return true;}
        if(getFocused() instanceof EditBox box&&box.isFocused())return super.keyPressed(event); // typing in the search box
        boolean ctrl=event.hasControlDown(),shift=event.hasShiftDown();
        if(ctrl&&key==GLFW.GLFW_KEY_A){for(var i:items)selected.add(i.key());return true;}
        if(ctrl&&key==GLFW.GLFW_KEY_C){take(false);return true;}
        if(ctrl&&key==GLFW.GLFW_KEY_X){take(true);return true;}
        if(ctrl&&key==GLFW.GLFW_KEY_V){paste(selected.size()==1&&chosen().size()==1&&chosen().get(0).folder()?chosen().get(0).path():folder);return true;}
        switch(key){
            case GLFW.GLFW_KEY_ENTER,GLFW.GLFW_KEY_KP_ENTER->{var c=chosen();if(c.size()==1){activate(c.get(0));return true;}}
            case GLFW.GLFW_KEY_DELETE->{archive(chosen());return true;}
            case GLFW.GLFW_KEY_F2->{var c=chosen();if(c.size()==1){rename(c.get(0));return true;}}
            case GLFW.GLFW_KEY_BACKSPACE->{if(!folder.isEmpty()){up();return true;}}
            case GLFW.GLFW_KEY_F5->{refresh();return true;}
            case GLFW.GLFW_KEY_PAGE_DOWN->{scrollBy(VIEW_H-STEP[mode]);return true;}
            case GLFW.GLFW_KEY_PAGE_UP->{scrollBy(STEP[mode]-VIEW_H);return true;}
            case GLFW.GLFW_KEY_HOME->{scroll=0;return true;}
            case GLFW.GLFW_KEY_END->{scroll=maxScroll();return true;}
            case GLFW.GLFW_KEY_RIGHT->{step(1,shift);return true;}
            case GLFW.GLFW_KEY_LEFT->{step(-1,shift);return true;}
            case GLFW.GLFW_KEY_DOWN->{step(COLUMNS[mode],shift);return true;}
            case GLFW.GLFW_KEY_UP->{step(-COLUMNS[mode],shift);return true;}
            default->{}
        }
        return super.keyPressed(event);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){BlueprintThumbnails.releaseAll();ClientCompat.setScreen(minecraft,parent);}
}
