package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import dev.craftium.core.EditorControls.Action;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.*;

public final class EditorScreen extends CraftiumScreen {
    private static int BG=0xf510141a,PANEL=0xff10151c,LINE=0xff303b49,ACCENT=0xff5b9fdb,TEXT=0xffdce6ef,MUTED=0xff8595a8;
    private static final int LEFT=182,RIGHT=200;
    private float scale=1;
    private long lastFlightNanos;
    private int w,h,mx,my;
    private boolean builder,help,transform,panels=true,painting;
    private boolean hudHidden,previousHideGui;
    private int menu=-1;
    private long lastUse;
    private double pointerX,pointerY;
    private BlockHitResult lastHit;
    private final List<LassoSelection.Pixel> lasso=new ArrayList<>();
    private static String drawingPanel="";
    private String draggedPanel="";
    private boolean resizePanel,panelScissor,draggingPlacement;
    private Point placementDragOrigin;
    private int selectedNode=-1,nodeAxis;
    private boolean draggingNode;
    private double placementDragX,placementDragY;
    private int dragOriginX,dragOriginY;
    private double dragPointerX,dragPointerY;
    private final List<Control> controls=new ArrayList<>();
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private Slider dragging;
    private String draggingSliderPanel="";
    private record Control(int x,int y,int width,int height,Runnable action,String tip,String panel) {
        Control(int x,int y,int width,int height,Runnable action,String tip){this(x,y,width,height,action,tip,drawingPanel);}
        boolean contains(double px,double py){if(!WorkspaceLayout.visible(panel))return false;var point=WorkspaceLayout.inverse(panel,px,py);return point[0]>=x&&point[1]>=y&&point[0]<x+width&&point[1]<y+height;}
    }
    private record Slider(int x,int width,double min,double max,DoubleConsumer set) {
        void update(double px){set.accept(min+Math.clamp((px-x)/(width-1),0,1)*(max-min));}
    }
    private record MenuItem(String label,String shortcut,Runnable action,List<MenuItem> children) {
        MenuItem(String label,String shortcut,Runnable action){this(label,shortcut,action,List.of());}
    }
    /** The label of the menu entry whose submenu is open, or empty. */
    private String openSub="";
    private final boolean heldContext;
    private enum CameraGesture { NONE,ROTATE,ORBIT,PAN,RADIUS }
    private CameraGesture cameraGesture=CameraGesture.NONE;
    private int cameraButton=-1,initialRadius;private double gestureX,gestureY,orbitDistance;private Vec3 orbitPivot;
    private Tool previousTool=Tool.BOX_SELECT;
    private boolean toolActive=true;
    public boolean toolActive(){return toolActive;}
    private boolean showSelection=true;
    public EditorScreen(boolean builder){this(builder,false);}
    public EditorScreen(boolean builder,boolean heldContext){super(Component.literal("Craftium Building Studio"));this.builder=builder;this.heldContext=heldContext;}
    public boolean showSelection(){return showSelection;}

    private Options o(){return ClientState.options;}
    @Override protected void init() {
        lastFlightNanos=0;
        if(!hudHidden){previousHideGui=ClientCompat.hudHidden(minecraft);hudHidden=true;if(!previousHideGui)ClientCompat.toggleHud(minecraft);}
        scale=Math.min(1f,Math.min(width/1000f,height/562f));w=Math.round(width/scale);h=Math.round(height/scale);
        fields.clear();
        field("block",w-RIGHT+10,244,RIGHT-20,o().block,s->ClientState.pick(s));
        field("mask",w-RIGHT+10,h-95,RIGHT-20,o().mask,s->o().mask=s);
        field("minY",w-RIGHT+10,h-64,80,Integer.toString(o().minY),s->{try{o().minY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
        field("maxY",w-90,h-64,80,Integer.toString(o().maxY),s->{try{o().maxY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
        if(!builder) {
            if(o().tool==Tool.NOISE_PAINTER) {
                field("secondary",10,398,LEFT-20,o().secondary,s->o().secondary=s);
                field("seed",10,439,LEFT-20,Long.toString(o().seed),s->{try{o().seed=Long.parseLong(s);}catch(NumberFormatException ignored){}});
            } else if(o().tool==Tool.BIOME_PAINTER || o().tool==Tool.CLENTAMINATOR)
                field("biome",10,382,LEFT-20,o().biome,s->o().biome=s);
            else if(o().tool==Tool.TEXT)field("text",10,326,LEFT-20,o().text,s->o().text=s);
            else if(o().tool==Tool.ROCK || o().tool==Tool.DISTORT || o().tool==Tool.ROUGHEN || o().tool==Tool.SHATTER)
                field("seed",10,418,LEFT-20,Long.toString(o().seed),s->{try{o().seed=Long.parseLong(s);}catch(NumberFormatException ignored){}});
        }
        if(transform){
            int x=w/2-210,y=h/2-92;
            field("offsetX",x+20,y+61,114,Integer.toString(o().offsetX),s->{try{o().offsetX=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
            field("offsetY",x+152,y+61,114,Integer.toString(o().offsetY),s->{try{o().offsetY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
            field("offsetZ",x+284,y+61,114,Integer.toString(o().offsetZ),s->{try{o().offsetZ=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
            field("copies",x+20,y+109,114,Integer.toString(o().copies),s->{try{o().copies=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
        }
    }
    private void field(String id,int x,int y,int width,String value,Consumer<String> set) {
        var box=new EditBox(font,x,y,width,18,Component.literal(id));box.setMaxLength(id.equals("text")?48:256);box.setValue(value);
        box.setResponder(set);box.setBordered(false);box.setTextColor(TEXT);fields.put(id,box);
    }
    private void select(Tool tool){
        if(tool.transform()&&tool!=Tool.PIVOT&&ClientState.status.selectionSize()==0)ClientState.notify("Select blocks first, then use "+tool.label);
        toolActive=true;previousTool=o().tool;selectedNode=-1;if(tool!=Tool.BOX_SELECT)ClientState.boxCorners.clear();o().tool=tool;o().gizmoTarget=Options.GizmoTarget.AUTO;builder=false;init();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean isInGameUi(){return true;}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta) {
        fly();
        BG=o().uiBackground;PANEL=o().uiPanel;LINE=o().uiBorder;ACCENT=o().uiAccent;TEXT=o().uiText;MUTED=o().uiMuted;
        pointerX=mouseX;pointerY=mouseY;mx=(int)(mouseX/scale);my=(int)(mouseY/scale);controls.clear();
        g.pose().pushMatrix();g.pose().scale(scale,scale);
        topbar(g);
        if(panels){tools(g);options(g);targetInfo(g);clipboard(g);palette(g);history(g);masks(g);}
        finishPanel(g);
        viewport(g);
        for(int i=1;i<lasso.size();i++)ToolIcons.line(g,(int)(lasso.get(i-1).x()/scale),(int)(lasso.get(i-1).y()/scale),(int)(lasso.get(i).x()/scale),(int)(lasso.get(i).y()/scale),0xffffd66d);
        statusbar(g);
        if(menu>=0)dropdown(g);
        if(help)help(g);
        if(transform)transform(g);
        if((panels||transform) && menu<0 && !help)for(var field:fields.entrySet()) {
            if(transform != (field.getKey().startsWith("offset")||field.getKey().equals("copies")))continue;
            String panel=WorkspaceLayout.fieldPanel(field.getKey());if(!WorkspaceLayout.visible(panel))continue;g.pose().pushMatrix();WorkspaceLayout.apply(g,panel);var point=WorkspaceLayout.inverse(panel,mx,my);
            var f=field.getValue();g.fill(f.getX()-3,f.getY()-4,f.getX()+f.getWidth()+3,f.getY()+14,0xff1b2838);
            if(f.isFocused())g.outline(f.getX()-4,f.getY()-5,f.getWidth()+8,20,ACCENT);
            g.widget(f,(int)point[0],(int)point[1],delta);g.pose().popMatrix();
        }
        if(!help&&menu<0) {
            String tooltip="";for(var c:controls)if(c.contains(mx,my)&&!c.tip.isEmpty())tooltip=c.tip;
            if(!tooltip.isEmpty()) {
                int tw=Math.min(320,font.width(tooltip)+14),tx=Math.min(mx+14,w-tw-4),ty=Math.min(my+18,h-34);
                g.fill(tx,ty,tx+tw,ty+22,0xf5222d3b);g.outline(tx,ty,tw,22,LINE);
                g.text(font,shorten(tooltip,tw-12),tx+6,ty+7,TEXT,false);
            }
        }
        g.pose().popMatrix();ReferenceImages.draw(g,true,mouseX,mouseY);
    }
    private void topbar(Gfx g) {
        g.fill(0,0,w,23,0xff1b222c);g.fill(0,22,w,23,LINE);
        CraftiumBrand.draw(g,6,3,18);text(g,"CRAFTIUM",26,8,TEXT);
        String[] names={"File","Edit","Select","View","Create","Operations","Masks","Window","Help"};int x=108;
        for(int i=0;i<names.length;i++) {
            int index=i,bw=font.width(names[i])+16;
            button(g,x,2,bw,19,names[i],menu==i,()->{menu=menu==index?-1:index;openSub="";},"");x+=bw;
        }
        button(g,w-58,3,52,17,"Exit  ×",false,this::onClose,"Return to building · Right Shift");
    }
    private void panel(Gfx g,int x,int y,int width,int height,String title) {
        finishPanel(g);String id=WorkspaceLayout.key(title);if(!id.isBlank()){
            WorkspaceLayout.prepare(id,x,y,width,height);drawingPanel=id;g.pose().pushMatrix();WorkspaceLayout.apply(g,id);
            if(!WorkspaceLayout.visible(id)){g.enableScissor(0,0,0,0);panelScissor=true;}
        }
        g.fill(x,y,x+width,y+height,BG);g.outline(x,y,width,height,LINE);
        g.fill(x+1,y+1,x+width-1,y+18,0xff202a37);g.fill(x+1,y+1,x+3,y+18,ACCENT);
        text(g,"▾",x+7,y+6,MUTED);text(g,title,x+17,y+6,TEXT);
        if(!id.isBlank()){text(g,"×",x+width-12,y+5,MUTED);controls.add(new Control(x+width-17,y+2,15,14,()->WorkspaceLayout.toggle(id),"Close panel · reopen from Window"));g.fill(x+width-5,y+height-5,x+width-2,y+height-2,LINE);}
    }
    private void finishPanel(Gfx g){if(!drawingPanel.isBlank()){if(panelScissor){g.disableScissor();panelScissor=false;}g.pose().popMatrix();drawingPanel="";}}
    private void tools(Gfx g) {
        panel(g,0,27,LEFT,166,"Tools");int i=0;
        for(Tool tool:Tool.values()) {
            if(tool.transform())continue; // these are pinned above the notice instead
            int x=9+(i%7)*23,y=50+(i/7)*23;i++;
            boolean selected=toolActive&&o().tool==tool;
            g.fill(x,y,x+21,y+21,selected?0xff315b84:0xff223247);
            if(mx>=x&&mx<x+21&&my>=y&&my<y+21)g.fill(x,y,x+21,y+21,0xff3b5875);
            if(selected){g.outline(x,y,21,21,0xff8bc7ff);g.fill(x,y+19,x+21,y+21,ACCENT);}
            g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(.82f,.82f);ToolIcons.draw(g,tool,0,0,selected?0xffffffff:0xffd0deeb);g.pose().popMatrix();
            controls.add(new Control(x,y,21,21,()->{if(toolActive&&o().tool==tool)toolActive=false;else select(tool);},tool.label+" · "+tool.description));
        }
    }
    private void options(Gfx g) {
        panel(g,0,199,LEFT,h-224,builder?"Builder controls":"Tool options");
        button(g,10,222,79,19,"Tool options",!builder,()->{builder=false;init();},"");
        button(g,93,222,79,19,"Builder",builder,()->{builder=true;init();},"");
        if(builder){builderOptions(g);return;}
        text(g,o().tool.label,10,252,0xffa5cef2);wrap(g,o().tool.description,10,268,LEFT-20,MUTED,3);
        button(g,131,201,25,15,"…",false,()->ClientCompat.setScreen(minecraft,new StudioScreen(this)),"Advanced settings · View → Studio inspector");
        if(o().tool==Tool.BOX_SELECT||o().tool==Tool.MAGIC_SELECT||o().tool==Tool.RULER) {
            var s=ClientState.status;
            if(o().tool==Tool.RULER) {
                text(g,"MEASUREMENT",10,311,MUTED);
                var points=ClientState.rulerPoints;
                if(!points.isEmpty())text(g,"Start: "+points.getFirst().coordinates(),10,332,TEXT);
                if(points.size()>1) {
                    text(g,"End: "+points.getLast().coordinates(),10,353,TEXT);double length=0;int manhattan=0;
                    for(int i=1;i<points.size();i++){Point a=points.get(i-1),b=points.get(i);length+=Math.sqrt(a.distanceSquared(b));manhattan+=Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z());}
                    text(g,String.format(Locale.ROOT,"Length: %.2f",length),10,377,0xffffd783);text(g,"Manhattan: "+manhattan,10,395,MUTED);
                }
                button(g,10,423,LEFT-20,20,"Clear measurement",false,()->{ClientState.rulerPoints.clear();ClientState.rulerStart=null;ClientState.rulerEnd=null;action("ruler_clear");},"");
                button(g,10,449,LEFT-20,20,"Mode: "+o().rulerMode,false,()->o().rulerMode=Options.RulerMode.values()[(o().rulerMode.ordinal()+1)%2],"Inspector → Ruler to split segments and select center");
            } else {
                text(g,"SELECTION BOUNDS",10,311,MUTED);
                text(g,"Min  "+s.min().coordinates(),10,333,TEXT);text(g,"Max  "+s.max().coordinates(),10,353,TEXT);
                text(g,s.selectionSize()+" blocks",10,379,0xffa5cef2);
                modeButtons(g,394);
                if(o().tool==Tool.MAGIC_SELECT)toggle(g,10,444,"Surface only",o().surfaceOnly,()->o().surfaceOnly=!o().surfaceOnly);
                if(o().tool==Tool.MAGIC_SELECT)toggle(g,10,468,"Hover preview (under "+o().magicPreviewLimit+")",o().magicPreview,()->o().magicPreview=!o().magicPreview);
                button(g,10,h-52,78,20,"Fill",false,()->action("fill"),"Fill the selection with the active block");button(g,94,h-52,78,20,"Clear",false,()->action("clear"),"Clear the selection");
            }
            return;
        }
        if(o().tool.transform()){
            text(g,"PIVOT",10,311,MUTED);
            text(g,o().pivotSet?String.format(Locale.ROOT,"%.1f, %.1f, %.1f",o().pivotX,o().pivotY,o().pivotZ):"Centre of the selection",10,327,TEXT);
            button(g,10,350,LEFT-20,20,"Reset pivot",false,()->o().pivotSet=false,"Go back to the centre of the selection");
            wrap(g,"Drag a handle, then press Enter to apply. Esc cancels.",10,382,LEFT-20,MUTED,3);
            return;
        }
        if(o().tool!=Tool.TEXT) {
            button(g,10,305,LEFT-20,19,"Shape: "+nice(o().shape.name())+"  ▾",false,()->o().shape=BrushMath.Shape.values()[(o().shape.ordinal()+1)%BrushMath.Shape.values().length],"Cycle sphere, cube, octahedron and cylinder");
            slider(g,10,347,LEFT-20,"Radius",o().radius,1,24,v->o().radius=(int)Math.round(v),true);
            if(EnumSet.of(Tool.SCULPT_DRAW,Tool.ROCK,Tool.ELEVATION,Tool.FLATTEN,Tool.SLOPE,Tool.DISTORT,Tool.SHATTER,Tool.EXTRUDE).contains(o().tool))
                slider(g,10,375,LEFT-20,"Strength",o().strength,.1,5,v->o().strength=v,false);
        }
        // Tool-specific controls occupy the same familiar options area.
        switch(o().tool) {
            case NOISE_PAINTER -> {
                text(g,"Secondary block",10,382,MUTED);text(g,"Noise seed",10,423,MUTED);
                slider(g,10,480,LEFT-20,"Scale",o().noiseScale,1,64,v->o().noiseScale=v,false);
                slider(g,10,508,LEFT-20,"Blend",o().density,0,1,v->o().density=v,false);
            }
            case BIOME_PAINTER,CLENTAMINATOR -> text(g,"Biome identifier",10,369,MUTED);
            case TEXT -> {text(g,"Text · A–Z, 0–9, ! ? - .",10,309,MUTED);wrap(g,"Place upright letters on the clicked face. Each character is 5 by 7 blocks.",10,354,LEFT-20,MUTED,4);}
            case ROCK,DISTORT,ROUGHEN,SHATTER -> {
                text(g,"Noise seed",10,405,MUTED);
                slider(g,10,460,LEFT-20,"Noise scale",o().noiseScale,1,64,v->o().noiseScale=v,false);
                toggle(g,10,489,"Invert / carve",o().invert,()->o().invert=!o().invert);
            }
            default -> {
                toggle(g,10,403,"Invert / carve",o().invert,()->o().invert=!o().invert);
                toggle(g,10,425,"Hollow shape",o().hollow,()->o().hollow=!o().hollow);
                toggle(g,10,447,"Surface only",o().surfaceOnly,()->o().surfaceOnly=!o().surfaceOnly);
                if(o().tool.selection())modeButtons(g,472);
            }
        }
        if(o().tool==Tool.TEXT||o().tool==Tool.BIOME_PAINTER||o().tool==Tool.CLENTAMINATOR) {
            toggle(g,10,416,"Keep existing",o().keepExisting,()->o().keepExisting=!o().keepExisting);
        }
        if(o().tool.selection()){button(g,10,h-52,78,20,"Fill",false,()->action("fill"),"Fill the selection with the active block");button(g,94,h-52,78,20,"Clear",false,()->action("clear"),"Clear the selection");}
    }
    private void builderOptions(Gfx g) {
        text(g,"CAPABILITIES",10,254,MUTED);
        toggle(g,10,273,"Enhanced flight",o().enhancedFlight,()->o().enhancedFlight=!o().enhancedFlight);
        toggle(g,10,296,"Bulldozer",o().bulldozer,()->o().bulldozer=!o().bulldozer);
        toggle(g,10,319,"Replace mode",o().replaceMode,()->o().replaceMode=!o().replaceMode);
        toggle(g,10,342,"Force place",o().forcePlace,()->o().forcePlace=!o().forcePlace);
        toggle(g,10,365,"No updates",o().noUpdates,()->o().noUpdates=!o().noUpdates);
        toggle(g,10,388,"Tinker",o().tinker,()->o().tinker=!o().tinker);
        slider(g,10,428,LEFT-20,"Fly speed",o().flySpeed,.1,10,v->o().flySpeed=(float)v,false);
        button(g,10,452,LEFT-20,20,"Apply flight speed",false,()->action("flight"),"Apply the selected flight speed");
        button(g,10,478,78,20,"Hotbar ↔",false,()->action("hotbar"),"Swap the hotbar with inventory row 1 · Z");
        button(g,94,478,78,20,"Game mode",false,()->action("gamemode"),"Switch between Creative and Spectator");
        button(g,10,506,LEFT-20,20,"Builder tool: "+o().builderTool.name(),false,()->{o().builderTool=Options.BuilderTool.values()[(o().builderTool.ordinal()+1)%Options.BuilderTool.values().length];},"Press 0 in the world · Alt + wheel changes tools");
    }
    private void targetInfo(Gfx g) {
        int x=w-RIGHT;panel(g,x,27,RIGHT,82,"Target info");var hit=target();
        if(hit!=null&&minecraft.level!=null){var p=hit.getBlockPos();text(g,p.getX()+"  /  "+p.getY()+"  /  "+p.getZ(),x+10,53,TEXT);text(g,shorten(BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(p).getBlock()).toString(),RIGHT-20),x+10,69,MUTED);}
        else text(g,"Point at a block",x+10,53,MUTED);
        text(g,ClientState.status.selectionSize()+" blocks selected",x+10,89,0xffa5cef2);
    }
    private void clipboard(Gfx g) {
        int x=w-RIGHT;panel(g,x,115,RIGHT,101,"Clipboard");
        text(g,ClientState.status.clipboardSize()==0?"No copied blocks":ClientState.status.clipboardSize()+" blocks ready to place",x+10,141,MUTED);
        button(g,x+10,159,56,20,"Copy",false,()->action("copy"),"Copy selection · Ctrl+C");
        button(g,x+72,159,56,20,"Cut",false,()->action("cut"),"Cut selection · Ctrl+X");
        button(g,x+134,159,56,20,"Paste",false,()->action("paste"),"Paste at cursor · Ctrl+V");
        button(g,x+10,186,44,20,"Rotate",false,()->action("rotate"),"Rotate clipboard 90° · Ctrl+R");
        button(g,x+58,186,44,20,"Mirror",false,()->action("flip"),"Mirror clipboard on X · Ctrl+F");
        button(g,x+106,186,84,20,"Paste in place",false,()->CraftiumClient.send("paste_in_place",null,null),"Paste at the coordinates the blocks were copied from");
    }
    private void palette(Gfx g) {
        int x=w-RIGHT;panel(g,x,222,RIGHT,105,"Active block / Palette");
        var blocks=ClientState.palette;int i=0;
        for(String block:blocks.subList(0,Math.min(14,blocks.size()))) {
            int bx=x+10+(i%7)*26,by=270+(i/7)*26;i++;
            boolean active=o().block.equals(block);g.fill(bx,by,bx+23,by+23,active?0xff315b84:0xff1b2838);if(active)g.outline(bx,by,23,23,ACCENT);
            var id=dev.craftium.compat.Compat.tryParse(block);if(id!=null)BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b->g.item(b.asItem().getDefaultInstance(),bx+3,by+3));
            controls.add(new Control(bx,by,23,23,()->{ClientState.pick(block);fields.get("block").setValue(block);},block));
        }
    }
    private void history(Gfx g) {
        int x=w-RIGHT,y=333,height=h-477;
        panel(g,x,y,RIGHT,Math.max(50,height),"History");
        button(g,x+10,y+24,85,19,"Undo "+ClientState.status.undo(),false,()->action("undo"),"Undo · Ctrl+Z");
        button(g,x+104,y+24,86,19,"Redo "+ClientState.status.redo(),false,()->action("redo"),"Redo · Ctrl+Y");
        int row=y+51,index=0;for(String event:ClientState.status.history()){if(row>y+height-12)break;int steps=++index;text(g,shorten(event,RIGHT-20),x+10,row,MUTED);controls.add(new Control(x+7,row-3,RIGHT-14,14,()->{o().historySteps=steps;action("history_undo_many");},"Undo through this edit"));row+=15;}
    }
    private void masks(Gfx g) {
        int x=w-RIGHT;panel(g,x,h-138,RIGHT,113,"Tool masks");
        text(g,"Only replace block (optional)",x+10,h-112,MUTED);
        text(g,"Min Y",x+10,h-78,MUTED);text(g,"Max Y",w-90,h-78,MUTED);
        toggle(g,x+10,h-43,"Only in selection",o().inSelection,()->o().inSelection=!o().inSelection);
    }
    private void viewport(Gfx g) {
        int l=panels?LEFT+6:6,r=panels?w-RIGHT-6:w-6;
        g.fill(l,27,l+100,45,0xd9212c3a);text(g,"Main viewport",l+8,33,TEXT);
        text(g,"+",l+110,33,MUTED);
        int cy=h-66;
        if(ClientState.status.selectionSize()>0){g.fill(l+8,cy,l+220,cy+29,0xdd131d29);text(g,"SELECTION  /  "+ClientState.status.selectionSize()+" blocks",l+17,cy+6,0xffffd783);text(g,ClientState.status.min().coordinates()+" → "+ClientState.status.max().coordinates(),l+17,cy+18,MUTED);}
        String notice=System.currentTimeMillis()<ClientState.noticeUntil?ClientState.notice:ClientState.status.message();
        if(!CraftiumClient.supported())notice="Craftium must also be installed on the server";
        int tw=Math.min(r-l-24,font.width(notice)+20);
        int noticeY=pinnedBounds()[1]+pinnedBounds()[3]+4;
        if(tw>40){g.fill(l+8,noticeY,l+8+tw,noticeY+23,ClientState.status.error()?0xe14b252a:0xd916202d);text(g,shorten(notice,tw-16),l+16,noticeY+8,ClientState.status.error()?0xffffb6ae:0xffbfd4e8);}
        pinnedTools(g);
        largeSelectionPopup(g);
        outlineSettingsPopup(g);
        if(minecraft.level==null){g.fill(l,80,r,h-28,0xff14202e);text(g,"Open a Creative world to start building",l+30,105,TEXT);}
    }
    /** Where the large-selection popup is, or null when it is not showing. It sits in the middle of the viewport. */
    private int[] popupBounds(){
        if(ClientState.largeSelection<=0)return null;
        int left=panels?LEFT+6:6,right=panels?w-RIGHT-6:w-6;
        return new int[]{(left+right-250)/2,h/2-70,250,62};
    }
    /** A small popup beside the work, not over it: the editor stays visible and nothing is dimmed. */
    private void largeSelectionPopup(Gfx g){
        int[] b=popupBounds();
        if(b==null)return;
        g.fill(b[0],b[1],b[0]+b[2],b[1]+b[3],0xf0141d29);g.outline(b[0],b[1],b[2],b[3],ACCENT);
        text(g,"Selected "+ClientState.largeSelection+" blocks, confirm?",b[0]+10,b[1]+9,TEXT);
        button(g,b[0]+10,b[1]+30,112,22,"Confirm",true,this::confirmLargeSelection,"Keep the selection · Enter");
        button(g,b[0]+128,b[1]+30,112,22,"Cancel",false,this::cancelLargeSelection,"Go back to the selection you had · Esc");
    }
    private void confirmLargeSelection(){ClientState.largeSelection=0;SelectionOverlay.update(ClientState.heldSelection);}
    private void cancelLargeSelection(){
        ClientState.largeSelection=0;ClientState.boxCorners.clear();
        CraftiumClient.send("selection_restore",null,null);
    }
    private final ColourPicker outlinePicker=new ColourPicker(colour->ClientState.options.selectionColor=colour);
    /** Where the selection outline settings are, or null when they are not showing: the top right of the viewport. */
    private int[] outlineBounds(){
        if(!ClientState.outlineSettings)return null;
        int right=panels?w-RIGHT-6:w-6;
        return new int[]{right-172,49,172,ColourPicker.height()+100};
    }
    /** The colour and width of the selection outline: a picker for the colour and a slider for the width. */
    private void outlineSettingsPopup(Gfx g){
        int[] b=outlineBounds();
        if(b==null)return;
        g.fill(b[0],b[1],b[0]+b[2],b[1]+b[3],0xf0141d29);g.outline(b[0],b[1],b[2],b[3],ACCENT);
        text(g,"Selection outline",b[0]+10,b[1]+8,TEXT);
        button(g,b[0]+b[2]-24,b[1]+4,18,16,"x",false,()->ClientState.outlineSettings=false,"Close");
        if(!outlinePicker.active())outlinePicker.set(o().selectionColor);
        int px=b[0]+16,py=b[1]+26;
        outlinePicker.draw(g,font,px,py);
        controls.add(new Control(px,outlinePicker.squareY(),ColourPicker.WIDTH,ColourPicker.SQUARE_HEIGHT,()->outlinePicker.pressSquare(mx,my),"Saturation and brightness"));
        controls.add(new Control(px,outlinePicker.barY(),ColourPicker.WIDTH,ColourPicker.BAR_HEIGHT,()->outlinePicker.pressHue(mx,my),"Hue"));
        slider(g,px,py+ColourPicker.height()+18,ColourPicker.WIDTH,"Width",o().selectionWidth,.1,10,v->o().selectionWidth=Math.round(v*100)/100d,false);
        button(g,px,b[1]+b[3]-28,ColourPicker.WIDTH,20,"Reset",false,()->{o().selectionColor=0xffffd66d;o().selectionWidth=1;},"Back to the default colour and width");
    }
    /** Picks the tool, or puts it away when it is already the one in use, as clicking it in the pinned bar does. */
    private void toggleTool(Tool tool){if(toolActive&&o().tool==tool)toolActive=false;else select(tool);}
    private static final Tool[] PINNED={Tool.MOVE,Tool.ROTATE,Tool.SCALE,Tool.PIVOT};
    /** The four transform tools stay pinned at the top of the viewport, just above the notice message, which sits below them. */
    private int[] pinnedBounds(){
        int size=26,gap=4,total=PINNED.length*size+(PINNED.length-1)*gap;
        int left=panels?LEFT+6:6;
        return new int[]{left+8,49,total+8,size+8};
    }
    private void pinnedTools(Gfx g){
        int[] b=pinnedBounds();
        g.fill(b[0],b[1],b[0]+b[2],b[1]+b[3],0xdd131d29);g.outline(b[0],b[1],b[2],b[3],LINE);
        for(int i=0;i<PINNED.length;i++){
            final Tool tool=PINNED[i];final int slot=i;int x=b[0]+4+i*30,y=b[1]+4;
            boolean selected=toolActive&&o().tool==tool;
            g.fill(x,y,x+26,y+26,selected?0xff315b84:0xff223247);
            if(mx>=x&&mx<x+26&&my>=y&&my<y+26)g.fill(x,y,x+26,y+26,0xff3b5875);
            if(selected){g.outline(x,y,26,26,0xff8bc7ff);g.fill(x,y+24,x+26,y+26,ACCENT);}
            g.pose().pushMatrix();g.pose().translate(x+1,y+1);g.pose().scale(1f,1f);ToolIcons.draw(g,tool,0,0,selected?0xffffffff:0xffd0deeb);g.pose().popMatrix();
            int key=EditorControls.toolDefault(tool);
            controls.add(new Control(x,y,26,26,()->{if(toolActive&&o().tool==tool)toolActive=false;else select(tool);},tool.label+(key>0?" · "+(char)key+" / "+(slot+1):" · "+(slot+1))+" · "+tool.description));
        }
    }
    private void statusbar(Gfx g) {
        g.fill(0,h-20,w,h,0xff1b222c);g.fill(0,h-21,w,h-20,LINE);
        text(g,"LMB Drag: Camera     MMB: Pick block     RMB: Use tool     WASD / Space Shift: Fly",9,h-13,MUTED);
        text(g,(ClientState.status.busy()?"APPLYING…":"● READY")+"   "+String.format(Locale.ROOT,"%.1f×",o().flySpeed),w-132,h-13,ClientState.status.busy()?0xffffd783:0xff89c9ac);
    }
    private void dropdown(Gfx g) {
        List<MenuItem> items=menuItems(menu);int x=108;String[] names={"File","Edit","Select","View","Create","Operations","Masks","Window","Help"};for(int i=0;i<menu;i++)x+=font.width(names[i])+16;
        int height=4;for(MenuItem item:items)height+=item.action==null?9:25;
        g.fill(x,24,x+226,24+height,0xff1c2735);g.outline(x,24,226,height,LINE);
        int y=27;
        for(MenuItem item:items){
            if(item.action==null){g.fill(x+8,y+4,x+218,y+5,LINE);y+=9;continue;}
            if(!item.children.isEmpty()){
                boolean open=openSub.equals(item.label);
                button(g,x+3,y,220,24,item.label,open,()->openSub=open?"":item.label,"");
                text(g,">",x+212,y+8,MUTED);
                if(open){
                    int sx=x+226;g.fill(sx,y-3,sx+226,y-3+4+item.children.size()*25,0xff1c2735);g.outline(sx,y-3,226,4+item.children.size()*25,LINE);
                    int sy=y;
                    for(MenuItem child:item.children){final var run=child.action;button(g,sx+3,sy,220,24,child.label,false,()->{menu=-1;openSub="";run.run();},"");sy+=25;}
                }
                y+=25;continue;
            }
            final var action=item.action;button(g,x+3,y,220,24,item.label,false,()->{menu=-1;action.run();},"");
            if(!item.shortcut.isEmpty())text(g,item.shortcut,x+212-font.width(item.shortcut),y+8,MUTED);y+=25;
        }
    }
    private List<MenuItem> menuItems(int which) {
        return switch(which) {
            case 0 -> List.of(item("Open reference image…","",ReferenceImages::choose),item("Save tool settings","",()->{ClientState.save();ClientState.notify("Tool settings saved");}),item("Reset tool settings","",()->{ClientState.options=new Options();init();}),submenu("Preferences",item("Keyboard Shortcuts…","",()->ClientCompat.setScreen(minecraft,new KeyboardShortcutsScreen(this)))),item("Exit editor","RShift",this::onClose));
            case 1 -> List.of(item("Undo","Ctrl+Z",()->action("undo")),item("Redo","Ctrl+Y",()->action("redo")),divider(),
                item("Copy","Ctrl+C",()->action("copy")),item("Cut","Ctrl+X",()->action("cut")),item("Paste","Ctrl+V",()->action("paste")),
                item("Paste at cursor","",this::pasteAtCursor),item("Paste in place","",()->CraftiumClient.send("paste_in_place",null,null)),divider(),
                item("Edits history","",()->ClientCompat.setScreen(minecraft,new HistoryScreen(this))));
            case 2 -> List.of(item("Deselect","",()->action("clear")),item("Inverse","",()->action("selection_invert")),
                item(showSelection?"Hide selection":"Show selection","",()->showSelection=!showSelection),item("Clear selection","Del",()->action("delete")),divider(),
                item("Expand","by "+o().expandAmount,()->resizeSelection("selection_expand",o().expandAmount)),item("Shrink","by "+o().expandAmount,()->resizeSelection("selection_shrink",o().expandAmount)),
                item("Expand by 1","",()->resizeSelection("selection_expand",1)),item("Shrink by 1","",()->resizeSelection("selection_shrink",1)));
            case 3 -> List.of(item("Toggle side panels","",()->panels=!panels),item("Tool options","",()->{builder=false;init();}),item("Builder controls","Alt",()->{builder=true;init();}),item("Studio inspector","",()->ClientCompat.setScreen(minecraft,new StudioScreen(this))),item("Colour picker / gradient","",()->ClientCompat.setScreen(minecraft,new ColourScreen(this))),item("Keyboard shortcuts","",()->ClientCompat.setScreen(minecraft,new KeyboardShortcutsScreen(this))),item(o().selectionAllEdges?"Selection: outline only":"Selection: all edges","",()->o().selectionAllEdges=!o().selectionAllEdges),item("Selection outline…","",()->ClientState.outlineSettings=!ClientState.outlineSettings),item("Show controls","",()->help=true));
            case 7 -> {var items=new ArrayList<MenuItem>();for(String id:WorkspaceLayout.windows.keySet())items.add(item("Toggle "+id,"",()->WorkspaceLayout.toggle(id)));items.add(item("Save layout","",WorkspaceLayout::save));items.add(item("Reset layout","",WorkspaceLayout::reset));items.add(item("Copy layout","",()->minecraft.keyboardHandler.setClipboard(WorkspaceLayout.json())));items.add(item("Paste layout","",()->{try{WorkspaceLayout.importJson(minecraft.keyboardHandler.getClipboard());WorkspaceLayout.save();}catch(Exception e){ClientState.notify("Invalid layout JSON");}}));yield items;}
            case 4 -> List.of(item("Shape","",()->select(Tool.SHAPE)),item("Block text","",()->select(Tool.TEXT)),item("Line","",()->select(Tool.LINE)),item("Rock","",()->select(Tool.ROCK)));
            case 5 -> List.of(item("Fill selection","Ctrl+F",()->action("fill")),item("Delete selection","Del",()->action("delete")),item("Move / clone / stack…","",()->{transform=true;init();}),item("Smooth brush","",()->select(Tool.SMOOTH)),item("Flatten brush","",()->select(Tool.FLATTEN)));
            case 6 -> List.of(item("Restrict to selection","",()->o().inSelection=!o().inSelection),item("Clear block mask","",()->{o().mask="";fields.get("mask").setValue("");}),item("Toggle surface painting","",()->o().surfaceOnly=!o().surfaceOnly));
            default -> List.of(item("Editor controls","",()->help=true),item("About Craftium","",()->help=true));
        };
    }
    /** How a new selection combines with the current one, as four buttons in a grid with the active one lit. */
    private void modeButtons(Gfx g,int y){
        int half=(LEFT-24)/2;var modes=Selection.Mode.values();
        String[] tips={"Replace the selection","Add to the selection","Subtract from the selection","Keep only what overlaps the selection"};
        for(int i=0;i<modes.length;i++){
            final var mode=modes[i];
            button(g,10+(i%2)*(half+4),y+(i/2)*22,half,19,nice(mode.name()),o().mode==mode,()->o().mode=mode,tips[i]);
        }
    }
    private MenuItem item(String label,String shortcut,Runnable action){return new MenuItem(label,shortcut,action);}
    /** A menu entry that opens a submenu beside it instead of doing something. */
    private MenuItem submenu(String label,MenuItem... children){return new MenuItem(label,"",()->{},List.of(children));}
    /** A thin line between groups of menu entries; it has no action. */
    private MenuItem divider(){return new MenuItem("","",null);}
    /** Grows or shrinks the selection by {@code amount} blocks all round, the server repeating one block at a time. */
    private void resizeSelection(String action,int amount){
        o().selectionResize=amount;
        try{action(action);}finally{o().selectionResize=0;}
    }
    /** Pastes straight away at the block last under the cursor, without the placement preview. */
    private void pasteAtCursor(){
        var hit=target();
        if(hit==null){ClientState.notify("Point at a block to paste on");return;}
        CraftiumClient.send("paste",CraftiumClient.point(hit.getBlockPos()),CraftiumClient.normal(hit));
    }
    private void transform(Gfx g) {
        controls.clear();
        g.fill(0,23,w,h-21,0x9910151c);int x=w/2-210,y=h/2-92;
        panel(g,x,y,420,184,"Move / clone / stack selection");
        text(g,"Offset X",x+20,y+41,MUTED);text(g,"Offset Y",x+152,y+41,MUTED);text(g,"Offset Z",x+284,y+41,MUTED);
        text(g,"Stack copies (1–16)",x+20,y+89,MUTED);text(g,"Offset range: -128 to 128 blocks",x+152,y+111,MUTED);
        button(g,x+20,y+145,85,22,"Move",false,()->{action("move");transform=false;init();},"");
        button(g,x+115,y+145,85,22,"Clone",false,()->{action("clone");transform=false;init();},"");
        button(g,x+210,y+145,85,22,"Stack",false,()->{action("stack");transform=false;init();},"");
        button(g,x+305,y+145,95,22,"Cancel",false,()->{transform=false;init();},"");
    }
    private void help(Gfx g) {
        controls.clear();
        g.fill(0,23,w,h-21,0x9910151c);int x=w/2-230,y=h/2-145;
        panel(g,x,y,460,290,"Craftium / Building Studio");
        String[] lines={"A familiar workspace for building, painting and sculpting.","","Right Shift          Open / close the editor","Left drag            Look around the world","W A S D / Space Shift Fly horizontally / up and down","Right click / drag   Use the selected tool","Middle click         Pick the block under the cursor","Ctrl + middle drag   Adjust brush radius","Ctrl Z / Ctrl Y      Undo / redo","Ctrl C / X / V       Copy / cut / paste at cursor","Ctrl R / Ctrl F      Rotate / mirror pending placement","Ctrl F / Ctrl R      Fill / replace the selection","Enter / Delete       Clear / delete selection","Ctrl J               Duplicate selection as a placement","Ctrl P / Ctrl B      Save blueprint / show biomes","View > Keybinds      Edit shortcuts; inspector via View","","Creative mode + cheats / operator level 2 required.","Biome edits use Minecraft's 4 × 4 × 4 biome cells."};
        int row=y+30;for(String line:lines){text(g,line,x+18,row,MUTED);row+=14;}
        button(g,x+350,y+258,92,22,"Back to editor",false,()->help=false,"");
    }
    private void button(Gfx g,int x,int y,int width,int height,String label,boolean active,Runnable action,String tip) {
        var pointer=WorkspaceLayout.inverse(drawingPanel,mx,my);boolean hover=pointer[0]>=x&&pointer[0]<x+width&&pointer[1]>=y&&pointer[1]<y+height;
        g.fill(x,y,x+width,y+height,active?0xff315b84:hover?0xff34485e:0xff243246);
        if(active)g.fill(x,y+height-2,x+width,y+height,ACCENT);
        text(g,label,x+6,y+(height-8)/2,active?0xffffffff:TEXT);
        controls.add(new Control(x,y,width,height,action,tip));
    }
    private void toggle(Gfx g,int x,int y,String label,boolean value,Runnable action) {
        g.fill(x,y,x+12,y+12,value?0xff386e9d:0xff233247);g.outline(x,y,12,12,LINE);
        if(value){ToolIcons.line(g,x+2,y+6,x+5,y+9,TEXT);ToolIcons.line(g,x+5,y+9,x+10,y+3,TEXT);}
        text(g,label,x+19,y+3,TEXT);controls.add(new Control(x,y,Math.min(LEFT-20,font.width(label)+24),15,action,""));
    }
    private void slider(Gfx g,int x,int y,int width,String label,double value,double min,double max,DoubleConsumer set,boolean integer) {
        text(g,label,x,y-10,MUTED);g.fill(x,y,x+width,y+17,0xff1b2b40);
        int fill=(int)((value-min)/(max-min)*(width-4));g.fill(x+2,y+2,x+Math.clamp(fill,3,width-2),y+15,0xff2c5074);
        String display=integer?Integer.toString((int)value):String.format(Locale.ROOT,"%.2f",value);g.centeredText(font,display,x+width/2,y+5,TEXT);
        var slider=new Slider(x,width,min,max,set);String panel=drawingPanel;controls.add(new Control(x,y,width,17,()->{dragging=slider;draggingSliderPanel=panel;slider.update(WorkspaceLayout.inverse(panel,mx,my)[0]);},"Drag to change "+label.toLowerCase(Locale.ROOT)));
    }
    private void text(Gfx g,String text,int x,int y,int color){g.text(font,text,x,y,color,false);}
    private String shorten(String text,int pixels){if(font.width(text)<=pixels)return text;return font.plainSubstrByWidth(text,Math.max(0,pixels-9))+"…";}
    private void wrap(Gfx g,String text,int x,int y,int width,int color,int maxLines){int line=0;for(var row:font.split(Component.literal(text),width)){if(line>=maxLines)break;g.text(font,row,x,y+line*11,color,false);line++;}}
    private String nice(String name){String s=name.toLowerCase(Locale.ROOT).replace('_',' ');return Character.toUpperCase(s.charAt(0))+s.substring(1);}
    public BlockHitResult target(){
        if(inViewport(pointerX/scale,pointerY/scale))lastHit=CraftiumClient.raycast(pointerX,pointerY);
        return lastHit;
    }
    private void action(String action){
        if(EntityShortcuts.editor(action,this))return;
        var hit=target();
        if(action.equals("clear")){ClientState.boxCorners.clear();o().pivotSet=false;}
        if(action.equals("duplicate")||action.equals("cut")){ClientState.duplicatePending=true;CraftiumClient.send(action.equals("cut")?"cut":"copy",null,null);return;}
        if(action.equals("paste")){
            if(PlacementPreview.active()){PlacementPreview.confirm();return;}
            if(hit!=null){Point p=CraftiumClient.point(hit.getBlockPos()),n=CraftiumClient.normal(hit);PlacementPreview.begin(p.add(n.x(),n.y(),n.z()));}
            else ClientState.notify("Point at a block to position the clipboard");
            return;
        }
        CraftiumClient.send(action,hit==null?null:CraftiumClient.point(hit.getBlockPos()),hit==null?null:CraftiumClient.normal(hit));
    }
    private boolean inViewport(double x,double y){
        int[] pin=pinnedBounds();
        if(x>=pin[0]&&x<pin[0]+pin[2]&&y>=pin[1]&&y<pin[1]+pin[3])return false; // the pinned tools are not the world
        int[] popup=popupBounds();
        if(popup!=null&&x>=popup[0]&&x<popup[0]+popup[2]&&y>=popup[1]&&y<popup[1]+popup[3])return false;
        int[] outline=outlineBounds();
        if(outline!=null&&x>=outline[0]&&x<outline[0]+outline[2]&&y>=outline[1]&&y<outline[1]+outline[3])return false;
        return !ReferenceImages.hit(x*scale,y*scale)&&y>27&&y<(height/scale)-23&&(!panels||WorkspaceLayout.hit(x,y,false).isBlank());
    }
    private void use(){
        if(o().tool==Tool.ANNOTATION){var hit=target();if(hit!=null){o().annotationNormal=CraftiumClient.normal(hit);if(o().annotationMode.equals("DRAW")){Point p=CraftiumClient.point(hit.getBlockPos());if(o().annotationPoints.size()<256&&(o().annotationPoints.isEmpty()||!o().annotationPoints.getLast().equals(p)))o().annotationPoints.add(p);return;}}}
        long now=System.currentTimeMillis();if(now-lastUse<140||ClientState.status.busy())return;lastUse=now;
        if(o().tool==Tool.PATH||o().tool==Tool.MODELLING||(o().tool==Tool.GRADIENT_PAINTER&&minecraft.hasShiftDown())){var hit=target();if(hit!=null&&o().nodes.size()<64){o().nodes.add(CraftiumClient.point(hit.getBlockPos()));ClientState.notify("Added point "+o().nodes.size()+" · Enter to build · View → Studio inspector for settings");}return;}
        if(o().tool==Tool.SLOPE){var hit=target();if(hit!=null){Point p=CraftiumClient.point(hit.getBlockPos());if(o().nodes.isEmpty()){o().nodes.add(p);ClientState.notify("Slope anchor set · drag from the second point");return;}if(o().nodes.size()==1)o().nodes.add(p);else o().nodes.set(1,p);}}
        if(o().tool==Tool.RULER) {
            var hit=target();if(hit!=null) {
                Point p=CraftiumClient.point(hit.getBlockPos());if(ClientState.rulerPoints.size()<256)ClientState.rulerPoints.add(p);ClientState.rulerStart=ClientState.rulerPoints.getFirst();ClientState.rulerEnd=p;
            }
            return;
        }
        if(o().tool==Tool.PIVOT){
            var hit=target();
            if(hit!=null){var p=hit.getBlockPos();o().pivotSet=true;o().pivotX=p.getX()+.5;o().pivotY=p.getY()+.5;o().pivotZ=p.getZ()+.5;ClientState.notify("Pivot set");}
            return;
        }
        if(o().tool.transform())return; // these work through their handles, not by clicking
        if(o().tool==Tool.BOX_SELECT){
            var hit=target();
            if(hit!=null){
                Point p=CraftiumClient.point(hit.getBlockPos());var corners=ClientState.boxCorners;
                // The first click leaves one handle, the second a second one; each can be dragged afterwards.
                if(corners.size()==1){corners.add(p);selectedNode=1;}else{corners.clear();corners.add(p);selectedNode=0;}
            }
        }
        action("use");
    }
    /** Called when a handle has been let go of: the box follows the handles, replacing the box it made before. */
    void handlesMoved(){
        if(o().tool!=Tool.BOX_SELECT||ClientState.boxCorners.size()!=2||ClientState.status.busy())return;
        o().position1=ClientState.boxCorners.get(0);o().position2=ClientState.boxCorners.get(1);
        CraftiumClient.send("select_box_adjust",null,null);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        if(ReferenceImages.click(event.x(),event.y(),event.button()))return true;
        double x=event.x()/scale,y=event.y()/scale;mx=(int)x;my=(int)y;pointerX=event.x();pointerY=event.y();
        if(event.button()==0) {
            if((panels||transform)&&menu<0&&!help){
                EditBox selected=null;
                for(var entry:fields.entrySet()){
                    EditBox field=entry.getValue();field.setFocused(false);
                    if(transform != (entry.getKey().startsWith("offset")||entry.getKey().equals("copies")))continue;
                    String panel=WorkspaceLayout.fieldPanel(entry.getKey());if(!WorkspaceLayout.visible(panel))continue;var point=WorkspaceLayout.inverse(panel,x,y);
                    if(point[0]>=field.getX()-3&&point[0]<field.getX()+field.getWidth()+3&&point[1]>=field.getY()-4&&point[1]<field.getY()+15)selected=field;
                }
                if(selected!=null){selected.setFocused(true);String id="";for(var entry:fields.entrySet())if(entry.getValue()==selected)id=entry.getKey();var point=WorkspaceLayout.inverse(WorkspaceLayout.fieldPanel(id),x,y);selected.mouseClicked(new MouseButtonEvent(point[0],point[1],event.buttonInfo()),doubleClick);return true;}
            }
            for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(c.contains(x,y)){c.action.run();return true;}}
            if(help||transform)return true;if(menu>=0){menu=-1;return true;}
            if(panels){String id=WorkspaceLayout.hit(x,y,true);if(!id.isBlank()){var r=WorkspaceLayout.windows.get(id);draggedPanel=id;resizePanel=false;dragOriginX=r.x;dragOriginY=r.y;dragPointerX=x;dragPointerY=y;return true;}
                id=WorkspaceLayout.hit(x,y,false);if(!id.isBlank()){var r=WorkspaceLayout.windows.get(id);if(x>r.x+r.w-12&&y>r.y+r.h-12){draggedPanel=id;resizePanel=true;dragOriginX=r.w;dragOriginY=r.h;dragPointerX=x;dragPointerY=y;return true;}}
            }
        }
        if(help||transform||menu>=0)return true;
        if(!inViewport(x,y))return true;
        if(PlacementPreview.active()&&EditorBindings.matches(Action.ROTATE_PLACEMENT,event)){perform(Action.ROTATE_PLACEMENT);return true;}
        if(PlacementPreview.active()&&EditorBindings.matches(Action.FLIP_PLACEMENT,event)){perform(Action.FLIP_PLACEMENT);return true;}
        if(EditorBindings.matches(Action.EXTRUDE_POINT,event)&&perform(Action.EXTRUDE_POINT))return true;
        for(Action binding:Action.values())if(EditorBindings.get(binding).mouse()&&EditorBindings.matches(binding,event)&&perform(binding))return true;
        if(EditorBindings.matches(Action.ARCBALL,event)||EditorBindings.matches(Action.PAN_CAMERA,event)||EditorBindings.matches(Action.ADJUST_RADIUS,event)){
            cameraGesture=EditorBindings.matches(Action.ARCBALL,event)?CameraGesture.ORBIT:EditorBindings.matches(Action.PAN_CAMERA,event)?CameraGesture.PAN:CameraGesture.RADIUS;cameraButton=event.button();gestureX=event.x();gestureY=event.y();initialRadius=o().radius;
            var hit=target();orbitPivot=hit==null?minecraft.player.getEyePosition().add(minecraft.player.getLookAngle().scale(12)):hit.getLocation();orbitDistance=Math.max(1,orbitPivot.distanceTo(minecraft.player.getEyePosition()));return true;
        }
        // Both corners of a box selection show a gizmo; grabbing the one that is not selected selects it and starts the drag.
        if(event.button()==0&&o().tool==Tool.BOX_SELECT&&ClientState.boxCorners.size()==2){
            for(int i=0;i<2;i++)if(i!=selectedNode&&TransformGizmo.hitsPointHandles(ClientState.boxCorners.get(i),event.x(),event.y())){selectedNode=i;break;}
        }
        if(event.button()==0&&TransformGizmo.click(this,event.x(),event.y()))return true;
        if(event.button()==0&&!PlacementPreview.active()){
            var points=handleNodes();double best=144;int index=-1;for(int i=0;i<points.size();i++){var pixel=project(points.get(i));if(pixel==null)continue;double distance=Math.pow(event.x()-pixel[0],2)+Math.pow(event.y()-pixel[1],2);if(distance<best){best=distance;index=i;}}
            if(index>=0){selectedNode=index;draggingNode=true;placementDragOrigin=points.get(index);placementDragX=event.x();placementDragY=event.y();return true;}
        }
        if(PlacementPreview.active()){
            if(event.button()==1){var hit=target();if(hit!=null){Point p=CraftiumClient.point(hit.getBlockPos()),n=CraftiumClient.normal(hit);PlacementPreview.origin=p.add(n.x(),n.y(),n.z());}return true;}
            if(event.button()==0&&pickPlacementAxis(event.x(),event.y())){draggingPlacement=true;placementDragOrigin=PlacementPreview.origin;placementDragX=event.x();placementDragY=event.y();return true;}
        }
        if(!toolActive&&EditorBindings.matches(Action.CROSSHAIR,event)){cameraGesture=CameraGesture.ROTATE;cameraButton=event.button();return true;}
        if(EditorBindings.matches(Action.ROTATE_CAMERA,event)){cameraGesture=CameraGesture.ROTATE;cameraButton=event.button();return true;}
        if(toolActive&&EditorBindings.matches(Action.USE_TOOL,event)){if(o().tool==Tool.ANNOTATION&&o().annotationMode.equals("DRAW"))o().annotationPoints.clear();painting=true;ClientState.strokeId=System.nanoTime();if(o().tool==Tool.LASSO_SELECT){lasso.clear();lasso.add(new LassoSelection.Pixel(event.x(),event.y()));}else use();}
        if(EditorBindings.matches(Action.PICK_BLOCK,event)){
            // Dragging pans the camera; letting go without having dragged picks the block, as a click always did.
            var hit=target();pickedHit=hit;pendingPick=true;middleDragged=false;
            cameraGesture=CameraGesture.PAN;cameraButton=event.button();gestureX=event.x();gestureY=event.y();
            orbitPivot=hit==null?minecraft.player.getEyePosition().add(minecraft.player.getLookAngle().scale(12)):hit.getLocation();orbitDistance=Math.max(1,orbitPivot.distanceTo(minecraft.player.getEyePosition()));
        }
        return true;
    }
    /**
     * Moves the camera toward, or away from, whatever is under the cursor. The step is a share of the distance to it, so
     * the zoom is quick when far from a thing and fine when close, and it goes along the cursor's ray, so what is under
     * the cursor stays under it.
     */
    private void zoomToCursor(double x,double y,double wheel){
        if(minecraft.player==null||wheel==0||!inViewport(x/scale,y/scale))return;
        var camera=ClientCompat.camera(minecraft);var near=ClientCompat.nearPlane(camera);
        Vec3 eye=camera.position();
        Vec3 ray=near.getPointOnPlane((float)(x/minecraft.getWindow().getGuiScaledWidth()*2-1),(float)(1-y/minecraft.getWindow().getGuiScaledHeight()*2)).normalize();
        var hit=CraftiumClient.raycast(x,y);
        double distance=hit==null?12:eye.distanceTo(hit.getLocation());
        double step=Math.clamp(distance*.15,.4,24)*Math.signum(wheel)*Math.min(3,Math.abs(wheel));
        if(wheel>0&&hit!=null)step=Math.min(step,Math.max(0,distance-1)); // zooming in stops short of the block
        Vec3 moved=eye.add(ray.scale(step));
        minecraft.player.setPos(moved.x,moved.y-minecraft.player.getEyeHeight(),moved.z);
        minecraft.player.setDeltaMovement(Vec3.ZERO);minecraft.player.setOldPosAndRot();
    }
    private BlockHitResult pickedHit;private boolean pendingPick,middleDragged;
    private void pickBlock(BlockHitResult hit){
        if(hit==null||minecraft.level==null)return;
        String block=BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(hit.getBlockPos()).getBlock()).toString();
        ClientState.pick(block);fields.get("block").setValue(block);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){outlinePicker.release();if(cameraGesture!=CameraGesture.NONE&&event.button()==cameraButton){if(pendingPick&&!middleDragged)pickBlock(pickedHit);pendingPick=false;cameraGesture=CameraGesture.NONE;cameraButton=-1;return true;}if(event.button()==0&&TransformGizmo.release(this))return true;ReferenceImages.release();if(draggingNode)handlesMoved();draggingNode=false;draggingPlacement=false;dragging=null;if(!draggedPanel.isBlank()){WorkspaceLayout.snap(draggedPanel,w,h);WorkspaceLayout.save();draggedPanel="";}if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&EditorBindings.get(Action.USE_TOOL).mouse()){if(painting&&o().tool==Tool.ANNOTATION&&o().annotationMode.equals("DRAW")&&o().annotationPoints.size()>1)action("annotation_create");painting=false;ClientState.strokeId=0;if(o().tool==Tool.LASSO_SELECT&&!lasso.isEmpty()){o().selectionPoints=new ArrayList<>(LassoSelection.project(lasso));action("lasso_select");o().selectionPoints.clear();lasso.clear();}}return true;}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {
        if(outlinePicker.active()){outlinePicker.drag(event.x()/scale,event.y()/scale);return true;}
        if(ReferenceImages.drag(event.x(),event.y()))return true;
        pointerX=event.x();pointerY=event.y();
        if(cameraGesture!=CameraGesture.NONE){
            // A few pixels of wobble on a middle click is still a click, not a pan.
            if(pendingPick&&!middleDragged){if(Math.hypot(event.x()-gestureX,event.y()-gestureY)<=3)return true;middleDragged=true;}
            if(cameraGesture==CameraGesture.RADIUS)o().radius=Math.clamp(initialRadius+(int)Math.round(((event.x()-gestureX)-(event.y()-gestureY))/8),1,24);
            else if(cameraGesture==CameraGesture.ROTATE)minecraft.player.turn(dx*2,dy*2);
            else if(cameraGesture==CameraGesture.ORBIT){minecraft.player.turn(dx*2,dy*2);Vec3 eye=orbitPivot.subtract(minecraft.player.getLookAngle().scale(orbitDistance));minecraft.player.setPos(eye.x,eye.y-minecraft.player.getEyeHeight(),eye.z);}
            else{var camera=ClientCompat.camera(minecraft);var near=ClientCompat.nearPlane(camera);Vec3 center=near.getPointOnPlane(0,0),right=near.getPointOnPlane(1,0).subtract(center).normalize(),up=near.getPointOnPlane(0,1).subtract(center).normalize();double units=2*orbitDistance*Math.tan(Math.toRadians(ClientCompat.fov(minecraft,camera))/2)/height;minecraft.player.setPos(minecraft.player.position().add(right.scale(-dx*units)).add(up.scale(dy*units)));}
            minecraft.player.setDeltaMovement(Vec3.ZERO);minecraft.player.setOldPosAndRot();return true;
        }
        if(TransformGizmo.drag(event.x(),event.y()))return true;
        if(draggingNode&&selectedNode>=0&&selectedNode<handleNodes().size()){
            var a=project(placementDragOrigin);var b=project(placementDragOrigin.add(nodeAxis==0?1:0,nodeAxis==1?1:0,nodeAxis==2?1:0));
            if(a!=null&&b!=null){double vx=b[0]-a[0],vy=b[1]-a[1],length=vx*vx+vy*vy;int steps=length<.01?0:(int)Math.round(((event.x()-placementDragX)*vx+(event.y()-placementDragY)*vy)/length);handleNodes().set(selectedNode,placementDragOrigin.add(nodeAxis==0?steps:0,nodeAxis==1?steps:0,nodeAxis==2?steps:0));}return true;
        }
        if(draggingPlacement&&PlacementPreview.active()){
            var a=project(placementDragOrigin);var b=project(placementDragOrigin.add(PlacementPreview.axis==0?1:0,PlacementPreview.axis==1?1:0,PlacementPreview.axis==2?1:0));
            if(a!=null&&b!=null){double vx=b[0]-a[0],vy=b[1]-a[1],length=vx*vx+vy*vy;int steps=length<.01?0:(int)Math.round(((event.x()-placementDragX)*vx+(event.y()-placementDragY)*vy)/length);PlacementPreview.origin=placementDragOrigin.add(PlacementPreview.axis==0?steps:0,PlacementPreview.axis==1?steps:0,PlacementPreview.axis==2?steps:0);}return true;
        }
        if(!draggedPanel.isBlank()){var r=WorkspaceLayout.windows.get(draggedPanel);if(resizePanel){r.w=Math.max(110,dragOriginX+(int)(event.x()/scale-dragPointerX));r.h=Math.max(60,dragOriginY+(int)(event.y()/scale-dragPointerY));}else{r.x=Math.clamp(dragOriginX+(int)(event.x()/scale-dragPointerX),0,w-50);r.y=Math.clamp(dragOriginY+(int)(event.y()/scale-dragPointerY),24,h-40);}return true;}
        if(dragging!=null){dragging.update(WorkspaceLayout.inverse(draggingSliderPanel,event.x()/scale,event.y()/scale)[0]);return true;}
        if(help||transform||menu>=0)return true;
        if(inViewport(event.x()/scale,event.y()/scale)) {
            if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&o().tool==Tool.LASSO_SELECT){if(lasso.size()<1024)lasso.add(new LassoSelection.Pixel(event.x(),event.y()));return true;}
            if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&painting && o().tool!=Tool.BOX_SELECT&&o().tool!=Tool.RULER&&o().tool!=Tool.LINE&&o().tool!=Tool.PATH&&o().tool!=Tool.MODELLING)use();
        }
        return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(help||transform||menu>=0)return true;
        if(builder){action(vertical>0?"hotbar_previous":"hotbar_next");return true;}
        if(EditorBindings.held(Action.ADJUST_SPEED)){o().flySpeed=Math.clamp(o().flySpeed+(float)vertical*.2f,.1f,10f);action("flight");return true;}
        if(EditorBindings.held(Action.ADJUST_RADIUS)){o().radius=Math.clamp(o().radius+(int)Math.signum(vertical),1,24);return true;}
        if(!toolActive){zoomToCursor(x,y,vertical);return true;} // no tool chosen: the wheel is the camera's
        if(TransformGizmo.available(this)){TransformGizmo.nudge(this,EditorControls.nudge(vertical>0?Action.NUDGE_FORWARD:Action.NUDGE_BACK,minecraft.player.getYRot()));}
        return true;
    }
    @Override public boolean charTyped(CharacterEvent event){for(EditBox field:fields.values())if(field.isFocused())return field.charTyped(event);return false;}
    @Override public boolean keyPressed(KeyEvent event) {
        for(EditBox field:fields.values())if(field.isFocused()){
            if(event.key()==GLFW.GLFW_KEY_ESCAPE||event.key()==GLFW.GLFW_KEY_ENTER){field.setFocused(false);return true;}
            return field.keyPressed(event);
        }
        if(ClientState.largeSelection>0){
            if(event.key()==GLFW.GLFW_KEY_ENTER){confirmLargeSelection();return true;}
            if(event.key()==GLFW.GLFW_KEY_ESCAPE){cancelLargeSelection();return true;}
        }
        if(CraftiumClient.editorKey.matches(event)){onClose();return true;}
        if(event.key()==GLFW.GLFW_KEY_ESCAPE){if(ClientState.hasPending())CraftiumClient.send("pending_cancel",null,null);else if(PlacementPreview.active())PlacementPreview.cancel();else if(help)help=false;else if(transform){transform=false;init();}else if(menu>=0)menu=-1;else onClose();return true;}
        if(EditorBindings.matches(Action.HELP,event)){perform(Action.HELP);return true;}
        if(help||transform)return true;
        // Shared chords are resolved by the active context, before ordinary tool letters.
        if(PlacementPreview.active()){
            if(EditorBindings.matches(Action.ROTATE_PLACEMENT,event)){action("rotate");return true;}
            if(EditorBindings.matches(Action.FLIP_PLACEMENT,event)){action("flip_facing");return true;}
        }
        if((o().tool==Tool.PATH||o().tool==Tool.MODELLING)&&EditorBindings.matches(Action.EXTRUDE_POINT,event)&&!o().nodes.isEmpty()){
            if(o().nodes.size()>=64){ClientState.notify("Maximum 64 control points");return true;}o().nodes.add(o().nodes.getLast());selectedNode=o().nodes.size()-1;return true;
        }
        for(Action binding:Action.values())if(!EditorBindings.get(binding).mouse()&&EditorBindings.matches(binding,event)&&perform(binding))return true;
        if(event.key()==GLFW.GLFW_KEY_BACKSPACE&&(event.modifiers()&7)==0){perform(Action.DELETE);return true;}
        if((event.modifiers()&7)==0)for(var entry:CraftiumClient.toolKeys.entrySet())if(entry.getValue().matches(event)){select(entry.getKey());return true;}
        return true;
    }
    private boolean perform(Action binding){
        switch(binding){
            case CONFIRM -> {if(ClientState.hasPending())CraftiumClient.send("pending_confirm",null,null);else if(PlacementPreview.active())PlacementPreview.confirm();else action(o().tool==Tool.PATH||o().tool==Tool.MODELLING||o().tool==Tool.MODIFY?"use":"clear");}
            case UNDO,UNDO_ALT -> action("undo");
            case TOGGLE_MOVE -> toggleTool(Tool.MOVE);case TOGGLE_ROTATE -> toggleTool(Tool.ROTATE);case TOGGLE_SCALE -> toggleTool(Tool.SCALE);case TOGGLE_PIVOT -> toggleTool(Tool.PIVOT);
case REDO -> action("redo");case COPY -> action("copy");case CUT -> action("cut");case DUPLICATE -> action("duplicate");case PASTE -> action("paste");
            case COPY_AIR -> {boolean old=o().pasteAir;o().pasteAir=true;action("copy");o().pasteAir=old;}
            case DELETE -> {if(PlacementPreview.active())PlacementPreview.cancel();else action("delete");}
            case QUICK_FILL -> action("fill");case QUICK_REPLACE -> action("replace");
            case ROTATE_PLACEMENT -> {if(!PlacementPreview.active())return false;action("rotate");}
            case FLIP_PLACEMENT -> {if(!PlacementPreview.active())return false;action("flip_facing");}
            case EXTRUDE_POINT -> {if((o().tool!=Tool.PATH&&o().tool!=Tool.MODELLING)||o().nodes.isEmpty())return false;if(o().nodes.size()>=64)ClientState.notify("Maximum 64 control points");else{o().nodes.add(o().nodes.getLast());selectedNode=o().nodes.size()-1;}}
            case SAVE_BLUEPRINT -> {if(ClientState.status.clipboardSize()==0&&ClientState.status.clipboardEntityCount()==0)ClientState.notify("Copy a selection first");else ClientCompat.setScreen(minecraft,new SaveBlueprintScreen(this));}
            case SHOW_BIOMES -> o().showBiomes=!o().showBiomes;
            case SHOW_SELECTION -> showSelection=!showSelection;case SHOW_ANNOTATIONS -> o().showAnnotations=!o().showAnnotations;
            case SHOW_DISPLAYS -> o().showDisplayGizmos=!o().showDisplayGizmos;case SHOW_MARKERS -> o().showMarkerGizmos=!o().showMarkerGizmos;
            case SHOW_COLLISION -> o().showCollisionMesh=!o().showCollisionMesh;case SHOW_LIGHTS -> o().showLightBlocks=!o().showLightBlocks;case SHOW_VOID -> o().showStructureVoid=!o().showStructureVoid;
            case NUDGE_FORWARD,NUDGE_BACK,NUDGE_LEFT,NUDGE_RIGHT,NUDGE_UP,NUDGE_DOWN -> TransformGizmo.nudge(this,EditorControls.nudge(binding,minecraft.player.getYRot()));
            case PASTE_SELECT -> PlacementPreview.pasteAndSelect();case LAST_TOOL -> select(previousTool);
            case INSPECTOR -> ClientCompat.setScreen(minecraft,new StudioScreen(this));case HELP -> help=!help;case PANELS -> panels=!panels;
            case GIZMO_MOVE -> o().gizmoMode=Options.GizmoMode.MOVE;case GIZMO_ROTATE -> o().gizmoMode=Options.GizmoMode.ROTATE;case GIZMO_SCALE -> o().gizmoMode=Options.GizmoMode.SCALE;case LOCAL_AXES -> o().gizmoLocal=!o().gizmoLocal;
            default -> {return false;}
        }
        return true;
    }
    /** Replaces the keyboard as the source of flight input; set only by tests, which cannot fake polled key state. */
    static java.util.function.Supplier<double[]> flightInput;
    /** Flight input as {forward, side, up}, each from -1 to 1. */
    private double[] flightAxes(){
        if(flightInput!=null)return flightInput.get();
        return new double[]{
            (CraftiumClient.held(minecraft.options.keyUp)?1:0)-(CraftiumClient.held(minecraft.options.keyDown)?1:0),
            (CraftiumClient.held(minecraft.options.keyRight)?1:0)-(CraftiumClient.held(minecraft.options.keyLeft)?1:0),
            (CraftiumClient.held(minecraft.options.keyJump)?1:0)-(CraftiumClient.held(minecraft.options.keyShift)?1:0)};
    }
    @Override public void tick() {
        if(heldContext){if(!CraftiumClient.held(CraftiumClient.contextKey))onClose();return;}
    }
    /**
     * Flies the camera for the time since the last frame. Moving on game ticks would update the viewport
     * only 20 times a second whatever the Max Framerate setting is, so this runs on every rendered frame
     * at the same 7 blocks per second (0.35 per tick) the tick-based version covered.
     */
    private void fly() {
        long now=System.nanoTime();
        double seconds=lastFlightNanos==0?0:Math.min((now-lastFlightNanos)/1e9,.1);
        lastFlightNanos=now;
        var player=minecraft.player;
        if(heldContext||player==null||help||transform||menu>=0||!(player.isCreative()||player.isSpectator()))return;
        for(EditBox field:fields.values())if(field.isFocused())return;
        double[] axes=flightAxes();double forward=axes[0],side=axes[1],up=axes[2];
        if(forward==0&&side==0&&up==0)return;
        double yaw=Math.toRadians(player.getYRot());
        Vec3 delta=new Vec3(-Math.sin(yaw)*forward-Math.cos(yaw)*side,up,Math.cos(yaw)*forward-Math.sin(yaw)*side).normalize().scale(.35*20*o().flySpeed*seconds);
        if(!player.getAbilities().flying){player.getAbilities().flying=true;player.onUpdateAbilities();}
        player.setDeltaMovement(Vec3.ZERO);player.setPos(player.position().add(delta));
        player.setOldPosAndRot(); // the renderer interpolates from the previous position, so make it the current one
    }
    private List<Point> handleNodes(){return o().tool==Tool.RULER?ClientState.rulerPoints:o().tool==Tool.BOX_SELECT?ClientState.boxCorners:EnumSet.of(Tool.PATH,Tool.MODELLING,Tool.GRADIENT_PAINTER,Tool.SLOPE).contains(o().tool)?o().nodes:new ArrayList<>();}
    double pointerX(){return pointerX;}
    double pointerY(){return pointerY;}
    boolean pointerInViewport(){return inViewport(pointerX/scale,pointerY/scale);}
    public Point handlePoint(){return selectedNode>=0&&selectedNode<handleNodes().size()?handleNodes().get(selectedNode):null;}
    public int handleAxis(){return nodeAxis;}
    public void moveHandle(Point point){if(selectedNode>=0&&selectedNode<handleNodes().size())handleNodes().set(selectedNode,point);}
    private double[] project(Point p){
        var camera=ClientCompat.camera(minecraft);Vec3 v=new Vec3(p.x(),p.y(),p.z()).subtract(camera.position());
        var plane=ClientCompat.nearPlane(camera);Vec3 center=plane.getPointOnPlane(0,0);
        Vec3 right=plane.getPointOnPlane(1,0).subtract(center).normalize(),up=plane.getPointOnPlane(0,1).subtract(center).normalize(),forward=center.normalize();
        double z=v.dot(forward);if(z<=.05)return null;
        double focal=height/(2*Math.tan(Math.toRadians(ClientCompat.fov(minecraft,camera))/2));return new double[]{width/2d+v.dot(right)*focal/z,height/2d-v.dot(up)*focal/z};
    }
    private boolean pickPlacementAxis(double x,double y){
        Point p=PlacementPreview.origin;var a=project(p);if(a==null)return false;double best=100;
        for(int axis=0;axis<3;axis++){var b=project(p.add(axis==0?5:0,axis==1?5:0,axis==2?5:0));if(b==null)continue;double dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy;if(length<1)continue;double t=Math.clamp(((x-a[0])*dx+(y-a[1])*dy)/length,0,1);double distance=Math.pow(x-a[0]-t*dx,2)+Math.pow(y-a[1]-t*dy,2);if(distance<best){best=distance;PlacementPreview.axis=axis;}}
        return best<100;
    }
    private int key(long handle,int key){return GLFW.glfwGetKey(handle,key)==GLFW.GLFW_PRESS?1:0;}
    @Override public void removed(){TransformGizmo.cancel();if(hudHidden){if(ClientCompat.hudHidden(minecraft)!=previousHideGui)ClientCompat.toggleHud(minecraft);hudHidden=false;}ClientState.strokeId=0;ClientState.save();super.removed();}
    @Override public void onClose(){if(ClientState.largeSelection>0)confirmLargeSelection();ClientCompat.setScreen(minecraft,null);if(ClientState.hasPending()&&CraftiumClient.supported())CraftiumClient.send("pending_cancel",null,null);if(CraftiumClient.supported()&&minecraft.player!=null&&minecraft.player.isSpectator())CraftiumClient.send("gamemode_restore",null,null);}
}
