package dev.craftium.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import dev.craftium.core.StructurePreview;
import dev.craftium.core.StructureRaster;
import dev.craftium.core.StructureSettings;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Exporting a structure: the fields of a vanilla structure block on the left, with Java, Bedrock and Blueprint on their own tabs that
 * share the values they have in common, and a 3D preview on the right that can be turned and zoomed. With several BuildObjects the window
 * has a page for each, with its own name, position and size, and exports them all at once. Opened for Ctrl+P it is only the Blueprint form. The preview is the
 * structure as the file will hold it, so changing a field redraws it. On a short window the fields scroll.
 */
final class StructureExportScreen extends CraftiumScreen {
    private static final int FORM=336,ROW=22,GAP=10,LABELS=84,NUMBER=60,TOP=40,FOOT=40,REQUEST_DELAY=220,REQUEST_TIMEOUT=4000;
    private final Screen parent;
    private final StructureSettings s=ClientState.options.structure;
    /** A BuildObject to export: its id in the Hierarchy and its name. */
    record Target(int id,String name){}
    /** What one page holds of its own: the structure's name and position and size, and the blueprint's name. Everything else is shared by the pages. */
    private static final class Page{
        final int id;final String label;String name,blueprint;final int[] offset=new int[3],size=new int[3];
        Page(int id,String label,String name,String blueprint){this.id=id;this.label=label;this.name=name;this.blueprint=blueprint;}
    }
    private final List<Page> pages=new ArrayList<>();private int page;
    private final boolean blueprintOnly;
    /** What the settings were when the window opened, so what the window changes only for itself can be put back. */
    private final String priorFormat,priorSource,priorName;private final int priorObject;private final int[] priorOffset,priorSize;
    /** Exporting several files: the pages still to do, the folder they go to, the one being waited for, and how it went. */
    private final ArrayDeque<Page> queue=new ArrayDeque<>();private java.nio.file.Path folder;private Page current;private int exported,total;private final List<String> failed=new ArrayList<>();
    private int left,top,panelWidth,panelHeight,viewX,viewY,viewWidth,viewHeight,formTop,formBottom,scroll,maxScroll;
    // The preview: a software-rendered texture of the structure and the camera that renders it.
    private Tex texture;private ReferenceTexture dynamic;private int[] pixels=new int[0];private int textureWidth,textureHeight;
    private float yaw=-35,pitch=28,zoom=1;private boolean dragging,redraw=true;
    private int[] colours=new int[0];private int shownPreviews=-1;
    // Asking the server for a new preview once the player has stopped typing.
    private boolean dirty=true;private long changedAt,sentAt;private boolean waiting;
    /** The status the server had last sent when each request went out, to tell its reply from an older message. */
    private Object previewMark,exportMark;
    private String problem="",result="";
    /** A row's label and where it sits, collected while the widgets are laid out so the two cannot drift apart. */
    private record Label(String text,int x,int y,boolean muted){}
    private final List<Label> labels=new ArrayList<>();

    /** The source size the size fields last showed, so they are filled in again when the structure changes. */
    private int[] shownSource;

    StructureExportScreen(Screen parent){this(parent,List.of(),false);}
    /** Exports these BuildObjects, a page for each. */
    StructureExportScreen(Screen parent,List<Target> objects){this(parent,objects,false);}
    /** The window for Ctrl+P: the clipboard saved as a blueprint, with no tabs. */
    static StructureExportScreen blueprintOnly(Screen parent){return new StructureExportScreen(parent,List.of(),true);}
    /** The window on these BuildObjects with the Blueprint tab chosen: a blueprint is saved from each. */
    static StructureExportScreen blueprintOf(Screen parent,List<Target> objects){var screen=new StructureExportScreen(parent,objects,false);screen.s.format=StructureSettings.BLUEPRINT;return screen;}

    private StructureExportScreen(Screen parent,List<Target> objects,boolean blueprintOnly){
        super(Component.literal(blueprintOnly?"Save blueprint":"Export structure"));this.parent=parent;this.blueprintOnly=blueprintOnly;
        priorFormat=s.format;priorSource=s.source;priorName=s.name;priorObject=s.objectId;priorOffset=new int[]{s.offsetX,s.offsetY,s.offsetZ};priorSize=new int[]{s.sizeX,s.sizeY,s.sizeZ};
        s.sizeX=s.sizeY=s.sizeZ=0; // a new selection has a new size, so start from what is detected
        s.objectId=0;
        if(blueprintOnly){s.format=StructureSettings.BLUEPRINT;s.source=StructureSettings.CLIPBOARD;}
        var used=new HashSet<String>();
        for(var target:objects){
            String slug=unique(slug(target.name()),used);
            pages.add(new Page(target.id(),target.name(),slug,slug));
        }
        if(!pages.isEmpty()){s.source=StructureSettings.SELECTION;applyPage(pages.get(0));}
    }

    /** A BuildObject's name as a file name: lower case, with anything a structure name cannot hold turned into an underscore. */
    private static String slug(String name){
        String text=name.toLowerCase(Locale.ROOT).strip().replaceAll("[^a-z0-9_.-]+","_").replaceAll("^_+|_+$","");
        return text.isEmpty()?"buildobject":text.length()>60?text.substring(0,60):text;
    }
    private static String unique(String name,Set<String> used){
        String result=name;for(int n=2;!used.add(result);n++)result=name+"_"+n;return result;
    }
    /** Makes the settings the page's own, so the fields and the preview are of it. */
    private void applyPage(Page p){
        s.name=p.name;s.offsetX=p.offset[0];s.offsetY=p.offset[1];s.offsetZ=p.offset[2];s.sizeX=p.size[0];s.sizeY=p.size[1];s.sizeZ=p.size[2];s.objectId=p.id;
    }
    /** Keeps what has been typed on the page that is shown. */
    private void keepPage(){
        if(pages.isEmpty())return;var p=pages.get(page);
        p.name=s.name;p.offset[0]=s.offsetX;p.offset[1]=s.offsetY;p.offset[2]=s.offsetZ;p.size[0]=s.sizeX;p.size[1]=s.sizeY;p.size[2]=s.sizeZ;
    }
    private void turnPage(int by){
        if(pages.size()<2)return;
        keepPage();page=Math.floorMod(page+by,pages.size());applyPage(pages.get(page));
        result="";scroll=0;changed();rebuildWidgets();
    }
    /** Runs {@code send} with the settings of a page (or as they are, for none) and puts the shown page back after: a request carries the settings as they are when it goes out. */
    private void withPage(Page p,Runnable send){
        var o=ClientState.options;String priorBlueprint=o.name;keepPage();
        if(p!=null){applyPage(p);o.name=p.blueprint;}
        try{send.run();}
        finally{o.name=priorBlueprint;if(!pages.isEmpty())applyPage(pages.get(page));}
    }
    private String blueprintName(){return pages.isEmpty()?ClientState.options.name:pages.get(page).blueprint;}

    @Override protected void init(){
        panelWidth=Math.min(width-20,880);panelHeight=Math.min(height-20,500);left=(width-panelWidth)/2;top=(height-panelHeight)/2;
        int form=Math.clamp(panelWidth*2/5,300,FORM);
        viewX=left+form+GAP+12;viewY=top+TOP;viewWidth=Math.max(60,panelWidth-form-GAP-24);viewHeight=Math.max(60,panelHeight-TOP-132);
        formTop=top+TOP;formBottom=top+panelHeight-FOOT;
        boolean bedrock=s.bedrock(),blueprint=s.blueprint();int x=left+12,fieldX=x+LABELS,fieldWidth=form-LABELS-12,full=form-12,half=full/2-2;labels.clear();

        if(!blueprintOnly){
            addRenderableWidget(new FlatButton(left+12,top+8,110,22,"Java Edition",()->switchFormat(StructureSettings.JAVA)).lit(!bedrock&&!blueprint));
            addRenderableWidget(new FlatButton(left+128,top+8,130,22,"Bedrock Edition",()->switchFormat(StructureSettings.BEDROCK)).lit(bedrock));
            addRenderableWidget(new FlatButton(left+264,top+8,100,22,"Blueprint",()->switchFormat(StructureSettings.BLUEPRINT)).lit(blueprint));
        }

        int y=formTop-scroll;
        if(!pages.isEmpty()){ // one page for each BuildObject: its name is the heading, and the arrows turn the pages
            String heading=(page+1)+" / "+pages.size()+"  "+pages.get(page).label;
            labels.add(new Label(font.plainSubstrByWidth(heading,full-2*26-8),x+26+4,y+6,false));
            if(pages.size()>1){
                field(new FlatButton(x,y,24,20,"‹",()->turnPage(-1)));
                field(new FlatButton(x+full-24,y,24,20,"›",()->turnPage(1)));
            }
            y+=ROW+2;
        }
        else if(!blueprintOnly){
            row("Source",x,y);
            field(new FlatButton(fieldX,y,fieldWidth/2-2,20,"Selection",()->{s.source=StructureSettings.SELECTION;changed();rebuildWidgets();}).lit(s.source.equals(StructureSettings.SELECTION)));
            field(new FlatButton(fieldX+fieldWidth/2+2,y,fieldWidth/2-2,20,"Clipboard",()->{s.source=StructureSettings.CLIPBOARD;changed();rebuildWidgets();}).lit(s.source.equals(StructureSettings.CLIPBOARD)));
            y+=ROW;
        }

        if(blueprint){
            var opt=ClientState.options;
            row("Name",x,y);
            var name=plain(fieldX,y,fieldWidth,blueprintName(),80,value->{if(pages.isEmpty())opt.name=value;else pages.get(page).blueprint=value;});
            name.setHint(Component.literal("my_blueprint"));
            y+=ROW;
            row("Author",x,y);plain(fieldX,y,fieldWidth,opt.author,80,value->opt.author=value);y+=ROW;
            row("Tags",x,y);plain(fieldX,y,fieldWidth,opt.tags,256,value->opt.tags=value);y+=ROW+4;
            field(new FlatCheckbox(x,y,half,16,"Include entities",s.includeEntities,()->{s.includeEntities=!s.includeEntities;changed();rebuildWidgets();}));
            y+=18;
            field(new FlatCheckbox(x,y,half,16,"Show invisible blocks",s.showInvisible,()->{s.showInvisible=!s.showInvisible;redraw=true;rebuildWidgets();}));
            field(new FlatCheckbox(x+half+4,y,half,16,"Show bounding box",s.showBounds,()->{s.showBounds=!s.showBounds;redraw=true;rebuildWidgets();}));
            y+=18;
            field(new FlatCheckbox(x,y,half,16,"Show axes (X Y Z)",s.showAxes,()->{s.showAxes=!s.showAxes;redraw=true;rebuildWidgets();}));
            y+=18;
        }else{
        if(!bedrock){
            row("File",x,y);
            field(new FlatButton(fieldX,y,fieldWidth/2-2,20,"Structure .nbt",()->{s.javaFile=StructureSettings.NBT;changed();rebuildWidgets();}).lit(!s.schematic()));
            field(new FlatButton(fieldX+fieldWidth/2+2,y,fieldWidth/2-2,20,"Schematic .schem",()->{s.javaFile=StructureSettings.SCHEM;changed();rebuildWidgets();}).lit(s.schematic()));
            y+=ROW;
        }

        row("Name",x,y);
        var name=text(fieldX,y,fieldWidth,s.name,120,"A-Za-z0-9_./:\\-",value->s.name=value.toLowerCase(Locale.ROOT));
        name.setHint(Component.literal("minecraft:structure"));
        y+=ROW;

        // Only some files have a place for the structure block's position, so on the others the row stays but cannot be edited.
        row(bedrock||s.schematic()?"Offset":"Position",x,y);
        numbers(fieldX,y,s.storesOffset(),s.offsetX,s.offsetY,s.offsetZ,v->s.offsetX=v,v->s.offsetY=v,v->s.offsetZ=v,true);
        if(!s.storesOffset())note("Not in .nbt",fieldX+3*NUMBER+4,y+6);
        y+=ROW;

        row("Size",x,y);
        var detected=ClientStructures.preview==null?new int[3]:ClientStructures.preview.source();shownSource=detected;
        numbers(fieldX,y,true,s.sizeX>0?s.sizeX:detected[0],s.sizeY>0?s.sizeY:detected[1],s.sizeZ>0?s.sizeZ:detected[2],v->s.sizeX=v,v->s.sizeY=v,v->s.sizeZ=v,false);
        field(new FlatButton(fieldX+3*NUMBER+2,y,fieldWidth-3*NUMBER-2,20,"Detect",()->{s.sizeX=s.sizeY=s.sizeZ=0;changed();rebuildWidgets();}));
        y+=ROW;

        String[] mirrors={"none","left_right","front_back"},mirrorNames={"None","Left ↔ Right","Front ↔ Back"};
        row("Mirror",x,y);
        int mirrorWidth=fieldWidth/2-2;
        field(new FlatButton(fieldX,y,mirrorWidth+14,20,mirrorNames[Math.max(0,Arrays.asList(mirrors).indexOf(s.mirror))],()->{s.mirror=mirrors[(Arrays.asList(mirrors).indexOf(s.mirror)+1)%3];changed();rebuildWidgets();}).back(()->{s.mirror=mirrors[(Arrays.asList(mirrors).indexOf(s.mirror)+2)%3];changed();rebuildWidgets();}));
        field(new FlatButton(fieldX+mirrorWidth+18,y,fieldWidth-mirrorWidth-18,20,"Rotate "+s.rotation+"°",()->{s.rotation=(s.rotation+90)%360;changed();rebuildWidgets();}).back(()->{s.rotation=(s.rotation+270)%360;changed();rebuildWidgets();}));
        y+=ROW;

        row("Integrity %",x,y);
        var integrity=text(fieldX,y,NUMBER-4,Integer.toString(s.integrity),3,"0-9",value->s.integrity=parse(value,100));
        integrity.setHint(Component.literal("100"));
        var seed=text(fieldX+NUMBER+2,y,fieldWidth-NUMBER-2,Long.toString(s.seed),19,"0-9\\-",value->{try{s.seed=Long.parseLong(value);}catch(NumberFormatException e){s.seed=0;}});
        seed.setHint(Component.literal("Seed"));
        y+=ROW+4;

        field(new FlatCheckbox(x,y,half,16,"Include entities",s.includeEntities,()->{s.includeEntities=!s.includeEntities;changed();rebuildWidgets();}));
        if(bedrock)field(new FlatCheckbox(x+half+4,y,half,16,"Include blocks",s.includeBlocks,()->{s.includeBlocks=!s.includeBlocks;changed();rebuildWidgets();}));
        y+=18;
        var voidBox=new FlatCheckbox(x,y,full,16,"Convert air to structure void"+(s.schematic()?" (structures only)":""),s.airToVoid,()->{s.airToVoid=!s.airToVoid;changed();rebuildWidgets();});
        voidBox.active=!s.schematic();field(voidBox);
        y+=18;
        field(new FlatCheckbox(x,y,half,16,"Show invisible blocks",s.showInvisible,()->{s.showInvisible=!s.showInvisible;redraw=true;rebuildWidgets();}));
        field(new FlatCheckbox(x+half+4,y,half,16,"Show bounding box",s.showBounds,()->{s.showBounds=!s.showBounds;redraw=true;rebuildWidgets();}));
        y+=18;
        field(new FlatCheckbox(x,y,half,16,"Show axes (X Y Z)",s.showAxes,()->{s.showAxes=!s.showAxes;redraw=true;rebuildWidgets();}));
        y+=18;
        }
        maxScroll=Math.max(0,y+scroll-formBottom);scroll=Math.min(scroll,maxScroll);

        int bottom=top+panelHeight-32;
        String action=blueprint?(pages.size()>1?"Save all ("+pages.size()+")":"Save blueprint"):pages.size()>1?"Export all ("+pages.size()+")…":"Export…";
        addRenderableWidget(new FlatButton(left+panelWidth-12-130,bottom,130,24,action,this::export));
        addRenderableWidget(new FlatButton(left+panelWidth-12-130-8-90,bottom,90,24,"Close",this::onClose));
        addRenderableWidget(new FlatButton(viewX,bottom,100,24,"Reset view",()->{yaw=-35;pitch=28;zoom=1;redraw=true;}));
        makeTexture();
    }

    private void row(String label,int x,int y){if(inForm(y,16))labels.add(new Label(label,x,y+6,false));}
    private void note(String text,int x,int y){if(inForm(y-6,16))labels.add(new Label(text,x,y,true));}
    private boolean inForm(int y,int height){return y>=formTop&&y+height<=formBottom;}
    /** Adds a form widget, hidden when scrolling has moved it out of the form's area. */
    private <T extends AbstractWidget> T field(T widget){
        if(!inForm(widget.getY(),widget.getHeight())){widget.visible=false;widget.active=false;}
        return addRenderableWidget(widget);
    }

    /** Three number fields in a row for X, Y and Z. */
    private void numbers(int x,int y,boolean enabled,int a,int b,int c,IntConsumer ax,IntConsumer bx,IntConsumer cx,boolean negative){
        String allowed=negative?"0-9\\-":"0-9";int[] values={a,b,c};IntConsumer[] sinks={ax,bx,cx};
        for(int i=0;i<3;i++){
            final IntConsumer sink=sinks[i];
            var box=text(x+i*NUMBER,y,NUMBER-4,Integer.toString(values[i]),9,allowed,value->sink.accept(parse(value,0)));
            if(!enabled){box.active=false;box.setEditable(false);}
        }
    }
    /** A text box that only keeps the characters in {@code allowed}, the body of a regular expression character class; not every version's EditBox can filter. */
    private EditBox text(int x,int y,int w,String value,int limit,String allowed,Consumer<String> sink){
        var box=new EditBox(font,x,y,w,20,Component.literal(""));
        boolean[] fixing={false};
        box.setMaxLength(limit);box.setValue(value);
        box.setResponder(t->{
            String clean=t.replaceAll("[^"+allowed+"]","");
            if(!clean.equals(t)){if(!fixing[0]){fixing[0]=true;box.setValue(clean);fixing[0]=false;}return;}
            sink.accept(clean);changed();
        });
        return field(box);
    }
    /** A text box that keeps whatever is typed. */
    private EditBox plain(int x,int y,int w,String value,int limit,Consumer<String> sink){
        var box=new EditBox(font,x,y,w,20,Component.literal(""));
        box.setMaxLength(limit);box.setValue(value);box.setResponder(sink::accept);
        return field(box);
    }
    private static int parse(String value,int fallback){try{return Integer.parseInt(value);}catch(NumberFormatException e){return fallback;}}

    private void switchFormat(String format){keepPage();s.format=format;scroll=0;result="";changed();rebuildWidgets();}
    private void changed(){dirty=true;changedAt=System.currentTimeMillis();}

    // --- Preview texture ---

    private void makeTexture(){
        releaseTexture();
        double scale=Math.max(1,minecraft.getWindow().getGuiScale());
        int w=(int)Math.max(16,viewWidth*scale),h=(int)Math.max(16,viewHeight*scale);
        while((long)w*h>700_000){w=w*3/4;h=h*3/4;}
        textureWidth=w;textureHeight=h;pixels=new int[w*h];
        var image=new NativeImage(w,h,false);
        dynamic=new ReferenceTexture(image);dynamic.filtering(true);
        texture=Tex.of("craftium","structure_preview/"+UUID.randomUUID());
        minecraft.getTextureManager().register(texture.id(),dynamic);redraw=true;
    }
    private void releaseTexture(){if(texture!=null){minecraft.getTextureManager().release(texture.id());texture=null;dynamic=null;}}
    @Override public void removed(){
        releaseTexture();
        s.objectId=priorObject;
        if(blueprintOnly)s.format=priorFormat;
        if(blueprintOnly||!pages.isEmpty())s.source=priorSource;
        if(!pages.isEmpty()){s.name=priorName;s.offsetX=priorOffset[0];s.offsetY=priorOffset[1];s.offsetZ=priorOffset[2];s.sizeX=priorSize[0];s.sizeY=priorSize[1];s.sizeZ=priorSize[2];}
        ClientState.save();super.removed();
    }

    private void render(){
        var preview=ClientStructures.preview;if(preview==null||dynamic==null)return;
        StructureRaster.render(preview,colours,new StructureRaster.View(yaw,pitch,zoom,s.showBounds,s.showInvisible,s.showAxes),pixels,textureWidth,textureHeight);
        var image=dynamic.getPixels();
        for(int y=0;y<textureHeight;y++)for(int x=0;x<textureWidth;x++)image.setPixel(x,y,pixels[y*textureWidth+x]);
        dynamic.upload();
    }

    // --- Talking to the server ---

    @Override public void tick(){
        super.tick();
        long now=System.currentTimeMillis();
        if(waiting&&ClientStructures.previews!=shownPreviews)waiting=false;
        if(waiting&&ClientState.status!=previewMark&&ClientState.status.error()){problem=ClientState.status.message();waiting=false;}
        if(exportMark!=null&&ClientState.status!=exportMark){result=(ClientState.status.error()?"× ":"✓ ")+ClientState.status.message();exportMark=null;}
        if(exportMark!=null&&!ClientStructures.exporting())exportMark=null;
        progress();
        if(waiting&&now-sentAt>REQUEST_TIMEOUT){waiting=false;problem="The server did not answer. Is Craftium installed there?";}
        if(dirty&&!waiting&&now-changedAt>=REQUEST_DELAY){
            dirty=false;waiting=true;sentAt=now;previewMark=ClientState.status;problem="";
            CraftiumClient.send("structure_preview",null,null);
        }
    }

    private void export(){
        if(s.blueprint()){saveBlueprints();return;}
        if(ClientStructures.exporting()||current!=null||!queue.isEmpty())return;
        if(pages.size()<2){
            ClientStructures.chooseExportFile(s,path->{
                exportMark=ClientState.status;result="Exporting "+s.fileName()+"…";
                ClientStructures.exportTo(path,s.fileName());
            });
            return;
        }
        // Several BuildObjects: one folder, a file for each, asked for one after the other.
        keepPage();
        ClientStructures.chooseExportFolder(s,chosen->{
            folder=chosen;queue.clear();queue.addAll(pages);exported=0;total=pages.size();failed.clear();result="Exporting "+total+" structures…";
        });
    }

    /** Sends the next file of an export of several when the last has come or failed. */
    private void progress(){
        if(current!=null){
            boolean failedNow=ClientState.status!=exportMark&&ClientState.status.error();
            if(failedNow){failed.add(current.label+": "+ClientState.status.message());ClientStructures.cancelExport();current=null;}
            else if(!ClientStructures.exporting()){exported++;current=null;}
            else return;
        }
        if(!queue.isEmpty()&&!ClientStructures.exporting()){
            current=queue.poll();var send=current;
            withPage(send,()->{exportMark=ClientState.status;ClientStructures.exportTo(folder.resolve(s.fileName()),s.fileName());});
            result="Exporting "+(exported+failed.size()+1)+" of "+total+"…";
        }else if(queue.isEmpty()&&current==null&&total>0){
            result=(failed.isEmpty()?"✓ ":"× ")+"Exported "+exported+" of "+total+" to "+folder+(failed.isEmpty()?"":" · "+String.join("; ",failed));
            total=0;
        }
    }

    /** Saves each page as a blueprint in the library; the server answers each. */
    private void saveBlueprints(){
        var opt=ClientState.options;
        if(pages.isEmpty()){
            if(opt.name.isBlank()){result="× Name the blueprint first";return;}
            CraftiumClient.send("blueprint_save_source",null,null);onClose();return;
        }
        keepPage();
        for(var p:pages)if(p.blueprint.isBlank()){result="× Every blueprint needs a name ("+p.label+")";return;}
        for(var p:pages)withPage(p,()->CraftiumClient.send("blueprint_save_source",null,null));
        ClientState.notify("Saving "+pages.size()+" blueprints…");onClose();
    }

    // --- Drawing ---

    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        var o=ClientState.options;
        g.fill(0,0,width,height,0xe010151c);
        g.fill(left,top,left+panelWidth,top+panelHeight,o.uiPanel);g.outline(left,top,panelWidth,panelHeight,o.uiBorder);
        if(ClientStructures.previews!=shownPreviews){
            shownPreviews=ClientStructures.previews;var preview=ClientStructures.preview;
            if(preview!=null)colours=ClientStructures.colours(preview);
            redraw=true;
            // Fields left on "detect" show the detected size, so they follow the structure when it changes.
            if(preview!=null&&!Arrays.equals(preview.source(),shownSource)&&(s.sizeX==0||s.sizeY==0||s.sizeZ==0))rebuildWidgets();
        }
        if(redraw){redraw=false;render();}

        if(blueprintOnly)g.text(font,"SAVE BLUEPRINT",left+12,top+14,o.uiText,false);
        for(var label:labels)g.text(font,label.text(),label.x(),label.y(),label.muted()?o.uiMuted:o.uiText,false);
        if(maxScroll>0){
            int track=formBottom-formTop,thumb=Math.max(16,track*track/(track+maxScroll)),at=formTop+(track-thumb)*scroll/maxScroll,x=viewX-GAP-8;
            g.fill(x,formTop,x+3,formBottom,0xff1b2838);g.fill(x,at,x+3,at+thumb,o.uiAccent);
        }

        // The preview.
        g.fill(viewX,viewY,viewX+viewWidth,viewY+viewHeight,StructureRaster.BACKGROUND);
        var preview=ClientStructures.preview;
        if(preview!=null&&texture!=null)g.blit(texture,viewX,viewY,0,0,viewWidth,viewHeight,textureWidth,textureHeight,textureWidth,textureHeight,0xffffffff);
        else g.centeredText(font,waiting?"Building the preview…":(problem.isEmpty()?"No preview yet":problem),viewX+viewWidth/2,viewY+viewHeight/2-4,o.uiMuted);
        g.outline(viewX,viewY,viewWidth,viewHeight,dragging?o.uiAccent:o.uiBorder);
        g.text(font,"Drag to rotate · scroll to zoom",viewX+6,viewY+viewHeight-12,o.uiMuted,false);

        int lineY=viewY+viewHeight+6,limit=top+panelHeight-36;
        if(preview!=null)g.text(font,preview.sizeX()+" × "+preview.sizeY()+" × "+preview.sizeZ()+" · "+preview.blocks()+" blocks"+(waiting?" · updating…":""),viewX,lineY,o.uiText,false);
        lineY+=12;
        if(!problem.isEmpty()){for(var line:font.split(Component.literal(problem),viewWidth)){if(lineY>limit)break;g.text(font,line,viewX,lineY,0xffe08a7a,false);lineY+=10;}}
        else if(preview!=null)for(String note:preview.notes()){for(var line:font.split(Component.literal(note),viewWidth)){if(lineY>limit)break;g.text(font,line,viewX,lineY,0xffe0c27a,false);lineY+=10;}}

        // What the last export did, and what the format can hold, under the fields.
        int bottom=top+panelHeight-FOOT+6;
        if(!result.isEmpty())g.text(font,font.plainSubstrByWidth(result,viewX-left-GAP-24),left+12,bottom,result.startsWith("×")?0xffe08a7a:o.uiText,false);
        else if(s.blueprint())g.text(font,"Saved to the blueprint library on the server.",left+12,bottom,o.uiMuted,false);
        else g.text(font,s.bedrock()?"Bedrock structure blocks save up to 64 × 256 × 64":"Java structure blocks load up to 48 × 48 × 48",left+12,bottom,o.uiMuted,false);
        g.text(font,s.blueprint()?"A blueprint keeps the blocks as they are.":"Mirror, rotation and integrity are applied to the exported blocks.",left+12,bottom+11,o.uiMuted,false);
        drawWidgets(g,mouseX,mouseY,delta);
    }

    // --- Preview controls ---

    private boolean inView(double x,double y){return x>=viewX&&x<viewX+viewWidth&&y>=viewY&&y<viewY+viewHeight;}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(event.button()==0&&inView(event.x(),event.y())){dragging=true;setFocused(null);return true;}
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(dragging){yaw+=(float)dx*.6f;pitch=Math.clamp(pitch+(float)dy*.6f,-89f,89f);redraw=true;return true;}
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){dragging=false;return super.mouseReleased(event);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(inView(x,y)){zoom=Math.clamp(zoom*(float)Math.pow(1.15,vertical),.1f,40f);redraw=true;return true;}
        if(maxScroll>0&&x<viewX&&y>=formTop&&y<formBottom){int next=Math.clamp(scroll-(int)Math.signum(vertical)*ROW,0,maxScroll);if(next!=scroll){scroll=next;rebuildWidgets();}return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
    @Override public boolean isPauseScreen(){return false;}
}
