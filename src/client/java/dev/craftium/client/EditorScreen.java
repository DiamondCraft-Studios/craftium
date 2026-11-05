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
    private boolean builder,help,transform,panels=true,painting,pinnedTab;
    private boolean hudHidden,previousHideGui;
    private int menu=-1;
    /** The menu bar in order; {@link #menuItems} numbers its cases the same way. */
    private static final String[] MENUS={"File","Edit","Select","View","Create","Structure","Operations","Masks","AI","Window","Help"};
    /** A menu's width in the bar: its name with padding, and room for the status square beside AI while its bridge is on. */
    private int menuWidth(int index){return font.width(MENUS[index])+16+(MENUS[index].equals("AI")&&AiBridge.running()?12:0);}
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
    /** A clickable area. {@code back} is what a right click does, for a control that cycles through values; null for the others. */
    private record Control(int x,int y,int width,int height,Runnable action,Runnable back,String tip,String panel,int clipTop,int clipBottom) {
        Control(int x,int y,int width,int height,Runnable action,String tip){this(x,placed(y,height),width,height,action,null,tip,drawingPanel,scrollClipTop,scrollClipBottom);}
        Control(int x,int y,int width,int height,Runnable action,Runnable back,String tip){this(x,placed(y,height),width,height,action,back,tip,drawingPanel,scrollClipTop,scrollClipBottom);}
        boolean contains(double px,double py){if(!WorkspaceLayout.visible(panel))return false;var point=WorkspaceLayout.inverse(panel,px,py);return point[0]>=x&&point[1]>=y&&point[0]<x+width&&point[1]<y+height&&point[1]>=clipTop&&point[1]<clipBottom;}
    }
    // While a panel's contents scroll, controls are made where they are drawn (shifted) and only answer inside the visible part.
    private static boolean scrollActive;
    private static int scrollShift,scrollContent,scrollClipTop=Integer.MIN_VALUE,scrollClipBottom=Integer.MAX_VALUE;
    /** Where a control drawn at {@code y} really is, noting how far down the scrolling contents reach. */
    private static int placed(int y,int height){if(scrollActive)scrollContent=Math.max(scrollContent,y+height);return y+scrollShift;}
    /** {@code typedMax} is how far a typed value may go; the track itself stops at {@code max}. */
    private record Slider(int x,int width,double min,double max,double typedMax,double def,DoubleConsumer set) {
        /** Sets the value for a pointer at {@code px}; Ctrl steps it by 1 and Shift by 0.01. */
        void update(double px,boolean ctrl,boolean shift){
            set.accept(Math.clamp(FlatSlider.step(min+FlatSlider.fractionAt(px,x,width)*(max-min),ctrl,shift),min,max));
        }
        void reset(){set.accept(def);}
    }
    /** A slider's clickable area with the slider itself and the key that tells it apart from the others, rebuilt every frame. */
    private record SliderArea(Control control,Slider slider,String key,boolean integer) {}
    private final List<SliderArea> sliderAreas=new ArrayList<>();
    /** The slider whose value is being typed, with the text typed so far; null when none is. */
    private SliderArea typing;private String typed="";
    private static final Options DEFAULTS=new Options();
    private record MenuItem(String label,String shortcut,Runnable action,List<MenuItem> children) {
        MenuItem(String label,String shortcut,Runnable action){this(label,shortcut,action,List.of());}
    }
    /** The label of the menu entry whose submenu is open, or empty. */
    private String openSub="",openSub2="";
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
        fields.clear();suspended=false;
        int masksW=WorkspaceLayout.width("masks",RIGHT),masksH=WorkspaceLayout.height("masks",187),masksY=h-212;
        layoutKey=layoutKey();
        field("minY",w-RIGHT+10,masksY+masksH-39,80,Integer.toString(o().minY),s->{try{o().minY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
        field("maxY",w-RIGHT+masksW-90,masksY+masksH-39,80,Integer.toString(o().maxY),s->{try{o().maxY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
        if(!builder) {
            if(pinnedTab)pinnedFields();
            else if(o().tool==Tool.NOISE_PAINTER) {
                field("secondary",10,398,optionsWidth()-20,o().secondary,s->o().secondary=s);
                field("seed",10,439,optionsWidth()-20,Long.toString(o().seed),s->{try{o().seed=Long.parseLong(s);}catch(NumberFormatException ignored){}});
            } else if(o().tool==Tool.BIOME_PAINTER || o().tool==Tool.CLENTAMINATOR)
                field("biome",10,382,optionsWidth()-20,o().biome,s->o().biome=s);
            else if(o().tool==Tool.TEXT)field("text",10,326,optionsWidth()-20,o().text,s->o().text=s);
            else if(o().tool==Tool.ROCK)
                field("seed",10,418,optionsWidth()-20,Long.toString(o().seed),s->{try{o().seed=Long.parseLong(s);}catch(NumberFormatException ignored){}});
            else if(o().tool==Tool.DISTORT || o().tool==Tool.ROUGHEN || o().tool==Tool.SHATTER)
                field("seed",10,383,optionsWidth()-20,Long.toString(o().seed),s->{try{o().seed=Long.parseLong(s);}catch(NumberFormatException ignored){}});
        }
        if(transform){
            int x=w/2-210,y=h/2-92;
            field("offsetX",x+20,y+61,114,Integer.toString(o().offsetX),s->{try{o().offsetX=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
            field("offsetY",x+152,y+61,114,Integer.toString(o().offsetY),s->{try{o().offsetY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
            field("offsetZ",x+284,y+61,114,Integer.toString(o().offsetZ),s->{try{o().offsetZ=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
            field("copies",x+20,y+109,114,Integer.toString(o().copies),s->{try{o().copies=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
        }
    }
    /** The options panel's real width and bottom edge, which its text inputs are placed by; they are rebuilt when the panels change size. */
    private int optionsWidth(){return WorkspaceLayout.width("options",LEFT);}
    private int optionsBottom(){return 199+WorkspaceLayout.height("options",h-224);}
    private int layoutKey(){return java.util.Objects.hash(optionsWidth(),optionsBottom(),WorkspaceLayout.width("masks",RIGHT),WorkspaceLayout.height("masks",187));}
    private int layoutKey;
    private final Map<String,Integer> fieldBaseY=new HashMap<>();
    /** Whether a text input of the scrolling options is in the visible part, moving it to where the scrolling contents put it. */
    private boolean fieldShown(String id,EditBox box){
        if(!"options".equals(WorkspaceLayout.fieldPanel(id))||id.startsWith("pin:")||builder||pinnedTab||o().tool==Tool.MODIFY)return true;
        box.setY(fieldBaseY.getOrDefault(id,box.getY())+fieldShift);
        return box.getY()-4>=viewTop&&box.getY()+14<=viewBottom;
    }
    private void field(String id,int x,int y,int width,String value,Consumer<String> set) {
        fieldBaseY.put(id,y);var box=new EditBox(font,x,y,width,18,Component.literal(id));box.setMaxLength(id.equals("text")?48:256);box.setValue(value);
        box.setResponder(set);box.setBordered(false);box.setTextColor(TEXT);fields.put(id,box);
    }
    /**
     * Drops every selection job still in flight: the large-selection popup goes back to the previous selection, and a
     * selection still being built is forgotten, so its result is ignored when it arrives and no popup follows.
     */
    private void cancelSelectionJobs(){
        if(ClientState.largeSelection>0)cancelLargeSelection();
        ClientState.selectionPending=false;ClientState.selectingSince=0;
    }
    private void deselectTool(){cancelSelectionJobs();toolActive=false;selectedNode=-1;}
    private void select(Tool tool){
        cancelSelectionJobs();
        if(tool.transform()&&tool!=Tool.PIVOT&&ClientState.status.selectionSize()==0&&!ShapeObject.active())ClientState.notify("Select blocks first, then use "+tool.label);
        if(tool!=Tool.MODIFY)ModifyPreview.edit();
        toolActive=true;previousTool=o().tool;selectedNode=-1;if(tool!=Tool.BOX_SELECT)ClientState.boxCorners.clear();o().tool=tool;o().gizmoTarget=Options.GizmoTarget.AUTO;builder=false;ClientState.save();init();}
    /** Set while a pop-up such as the block picker draws this screen as its backdrop: no flight or cursor handling then. */
    boolean suspended;
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean isInGameUi(){return true;}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta) {
        if(!suspended){fly();syncCursor();}
        BG=o().uiBackground;PANEL=o().uiPanel;LINE=o().uiBorder;ACCENT=o().uiAccent;TEXT=o().uiText;MUTED=o().uiMuted;
        if(!suspended&&layoutKey!=layoutKey())init();
        pointerX=mouseX;pointerY=mouseY;mx=(int)(mouseX/scale);my=(int)(mouseY/scale);controls.clear();sliderAreas.clear();dragSources.clear();
        syncCrosshair();
        if(cameraGesture==CameraGesture.ROTATE&&cameraButton==GLFW.GLFW_MOUSE_BUTTON_RIGHT){g.crosshair(width/2,height/2);} // the first-person look aims with the vanilla crosshair
        g.pose().pushMatrix();g.pose().scale(scale,scale);
        topbar(g);
        if(panels){tools(g);options(g);targetInfo(g);clipboard(g);palette(g);history(g);masks(g);rulerPanel(g);}
        finishPanel(g);
        viewport(g);
        for(int i=1;i<lasso.size();i++)ToolIcons.line(g,(int)(lasso.get(i-1).x()/scale),(int)(lasso.get(i-1).y()/scale),(int)(lasso.get(i).x()/scale),(int)(lasso.get(i).y()/scale),0xffffd66d);
        statusbar(g);
        if(menu>=0)dropdown(g);
        if(ctxCell>=0)maskMenu(g);
        if(shapeMenu)shapeMenu(g);
        if(help)help(g);
        if(transform)transform(g);
        if((panels||transform) && menu<0 && !help)for(var field:fields.entrySet()) {
            if(transform != (field.getKey().startsWith("offset")||field.getKey().equals("copies")))continue;
            String panel=WorkspaceLayout.fieldPanel(field.getKey());if(!WorkspaceLayout.visible(panel))continue;g.pose().pushMatrix();WorkspaceLayout.apply(g,panel);var point=WorkspaceLayout.inverse(panel,mx,my);
            var f=field.getValue();if(!fieldShown(field.getKey(),f)){g.pose().popMatrix();continue;}
            g.fill(f.getX()-3,f.getY()-4,f.getX()+f.getWidth()+3,f.getY()+14,0xff1b2838);
            if(f.isFocused())g.outline(f.getX()-4,f.getY()-5,f.getWidth()+8,20,ACCENT);
            g.widget(f,(int)point[0],(int)point[1],delta);g.pose().popMatrix();
        }
        hoverFeedback(g);
        pipettePreview(g);
        if(!help&&menu<0&&ctxCell<0&&!shapeMenu&&confirm==null) { // an open menu or pop-up is what the pointer is for, so no tooltip over it
            String tooltip="";for(var c:controls)if(c.contains(mx,my)&&!c.tip.isEmpty())tooltip=c.tip;
            if(!tooltip.isEmpty()) {
                // A new line starts a new paragraph; each is wrapped to the pane, and a pane with a title shows it brighter than what follows.
                String[] paragraphs=tooltip.split("\n");var rows=new ArrayList<net.minecraft.util.FormattedCharSequence>();var firstRows=0;int widest=0;
                for(int i=0;i<paragraphs.length;i++){
                    var split=font.split(Component.literal(paragraphs[i]),306);
                    if(i==0)firstRows=split.size();
                    for(var row:split){rows.add(row);widest=Math.max(widest,font.width(row));}
                }
                int tw=Math.min(320,widest+14),th=rows.size()*10+12,tx=Math.min(mx+14,w-tw-4),ty=Math.min(my+18,h-th-12);
                g.fill(tx,ty,tx+tw,ty+th,0xf5222d3b);g.outline(tx,ty,tw,th,LINE);
                for(int i=0;i<rows.size();i++)g.text(font,rows.get(i),tx+6,ty+6+i*10,paragraphs.length>1&&i>=firstRows?MUTED:TEXT,false);
            }
        }
        if(dragBlock!=null&&dragMoved){g.fill(mx-13,my-13,mx+13,my+13,0xdd1b2838);g.outline(mx-13,my-13,26,26,ACCENT);BlockPickerScreen.icon(g,font,dragBlock,mx-8,my-8);}
        g.pose().popMatrix();ReferenceImages.draw(g,true,mouseX,mouseY);
    }
    /** Whether the pointer is free to act on the controls, not held by a drag or covered by a pop-up. */
    private boolean pointerFree(){return ctxCell<0&&!shapeMenu&&dragging==null&&draggedPanel.isBlank()&&cameraGesture==CameraGesture.NONE&&!(dragBlock!=null&&dragMoved);}
    /**
     * The hover look for every control in the workspace: the one the pointer is on, the topmost as for a click, is lightened and
     * outlined. Controls only register themselves, so none of them draws its own and a new one cannot forget to.
     */
    private void hoverFeedback(Gfx g){
        if(!pointerFree())return;
        for(int i=controls.size()-1;i>=0;i--){
            var c=controls.get(i);
            if(!c.contains(mx,my))continue;
            g.pose().pushMatrix();WorkspaceLayout.apply(g,c.panel());
            boolean clipped=c.clipTop()!=Integer.MIN_VALUE;if(clipped)g.enableScissor(0,c.clipTop(),w,c.clipBottom()); // a control half scrolled out of view stays inside its area
            g.fill(c.x(),c.y(),c.x()+c.width(),c.y()+c.height(),Hover.OVERLAY);g.outline(c.x(),c.y(),c.width(),c.height(),ACCENT);
            if(clipped)g.disableScissor();
            g.pose().popMatrix();return;
        }
    }
    private void topbar(Gfx g) {
        g.fill(0,0,w,23,0xff1b222c);g.fill(0,22,w,23,LINE);
        CraftiumBrand.draw(g,6,3,18);text(g,"CRAFTIUM",26,8,TEXT);
        int x=108;
        for(int i=0;i<MENUS.length;i++) {
            int index=i,bw=menuWidth(i);
            button(g,x,2,bw,19,MENUS[i],menu==i,()->{menu=menu==index?-1:index;openSub="";openSub2="";},"");
            if(MENUS[i].equals("AI")&&AiBridge.running())g.fill(x+bw-12,8,x+bw-6,14,AiBridge.colour(AiBridge.indicator())); // the AI bridge is on: the same colours as the corner marker
            x+=bw;
        }
        button(g,w-58,3,52,17,"Exit  ×",false,this::onClose,"");
    }
    /** Draws a panel's frame at its real size, and sets {@link #pw}, {@link #ph} and {@link #pb} for the contents to lay themselves out in. */
    private void panel(Gfx g,int x,int y,int width,int height,String title) {
        finishPanel(g);String id=WorkspaceLayout.key(title);if(!id.isBlank()){
            WorkspaceLayout.prepare(id,x,y,width,height);drawingPanel=id;g.pose().pushMatrix();WorkspaceLayout.apply(g,id);
            if(!WorkspaceLayout.visible(id)){g.enableScissor(0,0,0,0);panelScissor=true;}
            width=WorkspaceLayout.width(id,width);height=WorkspaceLayout.height(id,height);
        }
        pw=width;ph=height;pb=y+height;
        g.fill(x,y,x+width,y+height,BG);g.outline(x,y,width,height,LINE);
        g.fill(x+1,y+1,x+width-1,y+18,0xff202a37);g.fill(x+1,y+1,x+3,y+18,ACCENT);
        text(g,"▾",x+7,y+6,MUTED);text(g,title,x+17,y+6,TEXT);
        if(!id.isBlank()){text(g,"×",x+width-12,y+5,MUTED);controls.add(new Control(x+width-17,y+2,15,14,()->WorkspaceLayout.toggle(id),""));boolean grip=resizeGripHovered(id);g.fill(x+width-6,y+height-6,x+width-2,y+height-2,grip||id.equals(draggedPanel)&&resizePanel?ACCENT:LINE);}
    }
    /** The size of the panel being drawn, and where its bottom edge is; contents lay out against these, never against fixed sizes. */
    private int pw,ph,pb;
    private boolean resizeGripHovered(String id){var r=WorkspaceLayout.windows.get(id);return r!=null&&r.visible&&draggedPanel.isBlank()&&mx>r.x+r.w-12&&mx<r.x+r.w&&my>r.y+r.h-12&&my<r.y+r.h;}
    /** Splits {@code total} into one width per weight with {@code gap} between them: a flex row. The last takes the rounding. */
    private static int[] flex(int total,int gap,int... weights){
        int sum=0;for(int weight:weights)sum+=weight;
        int free=total-gap*(weights.length-1),used=0;int[] widths=new int[weights.length];
        for(int i=0;i<weights.length;i++){widths[i]=i==weights.length-1?free-used:free*weights[i]/sum;used+=widths[i];}
        return widths;
    }
    private void finishPanel(Gfx g){if(!drawingPanel.isBlank()){if(panelScissor){g.disableScissor();panelScissor=false;}g.pose().popMatrix();drawingPanel="";}}
    /** One row of the Tools panel: up to seven tools, and the label of the section they open when it is the first row of one. */
    private record ToolRow(String section,List<Tool> tools){int height(){return (section==null?0:TOOL_HEADER)+TOOL_PITCH;}}
    private static final int TOOL_PITCH=23,TOOL_HEADER=12,TOOLS_TOP=46;
    private List<ToolRow> toolRows=List.of();
    private int toolColumns,toolsBottom,toolScroll;
    private Tool toolScrolledTo;
    /** The tools flowed into rows of as many as fit across the panel. */
    private static List<ToolRow> toolRows(int columns){
        var rows=new ArrayList<ToolRow>();
        Tool.SECTIONS.forEach((section,tools)->{for(int i=0;i<tools.size();i+=columns)rows.add(new ToolRow(i==0?section:null,tools.subList(i,Math.min(tools.size(),i+columns))));});
        return rows;
    }
    /** The first row the panel can scroll to: the lowest one from which every row below it still fits. */
    private int maxToolScroll(){
        int first=toolRows.size(),used=0;
        while(first>0&&used+toolRows.get(first-1).height()<=toolsBottom-TOOLS_TOP){first--;used+=toolRows.get(first).height();}
        return first;
    }
    /** Brings the row holding the tool into view, so choosing a tool by its key never leaves it hidden. */
    private void revealTool(Tool tool){
        int row=0;while(row<toolRows.size()&&!toolRows.get(row).tools().contains(tool))row++;
        if(row==toolRows.size())return;
        if(row<toolScroll)toolScroll=row;
        int used=0;for(int i=toolScroll;i<=row;i++)used+=toolRows.get(i).height();
        while(used>toolsBottom-TOOLS_TOP&&toolScroll<row){used-=toolRows.get(toolScroll).height();toolScroll++;}
    }
    private void tools(Gfx g) {
        panel(g,0,27,LEFT,166,"Tools");
        toolsBottom=pb-4;int columns=Math.max(1,(pw-21)/TOOL_PITCH);
        if(columns!=toolColumns){toolColumns=columns;toolRows=toolRows(columns);toolScrolledTo=null;}
        if(toolScrolledTo!=o().tool){toolScrolledTo=o().tool;revealTool(o().tool);}
        int max=maxToolScroll();toolScroll=Math.clamp(toolScroll,0,max);
        int y=TOOLS_TOP;
        for(int n=toolScroll;n<toolRows.size();n++) {
            var row=toolRows.get(n);
            if(y+row.height()>toolsBottom)break;
            if(row.section()!=null){ // a divider: the section name, then a rule running out to the edge
                text(g,row.section().toUpperCase(Locale.ROOT),9,y+3,MUTED);
                int start=9+font.width(row.section().toUpperCase(Locale.ROOT))+5;g.fill(start,y+7,pw-10,y+8,LINE);
                y+=TOOL_HEADER;
            }
            for(int i=0;i<row.tools().size();i++) {
                Tool tool=row.tools().get(i);int x=9+i*TOOL_PITCH;
                boolean selected=toolActive&&o().tool==tool;
                g.fill(x,y,x+21,y+21,selected?0xff315b84:0xff223247);
                if(selected){g.outline(x,y,21,21,0xff8bc7ff);g.fill(x,y+19,x+21,y+21,ACCENT);}
                g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(.82f,.82f);ToolIcons.draw(g,tool,0,0,selected?0xffffffff:0xffd0deeb);g.pose().popMatrix();
                controls.add(new Control(x,y,21,21,()->{if(toolActive&&o().tool==tool)deselectTool();else select(tool);},toolTip(tool,0)));
            }
            y+=TOOL_PITCH;
        }
        if(max>0){ // a thin scrollbar: the wheel scrolls the panel when the sections do not all fit
            int track=toolsBottom-TOOLS_TOP,thumb=Math.max(12,track*(toolRows.size()-max)/toolRows.size());
            g.fill(pw-6,TOOLS_TOP,pw-3,toolsBottom,0xff1b2838);
            int at=TOOLS_TOP+(track-thumb)*toolScroll/max;g.fill(pw-6,at,pw-3,at+thumb,MUTED);
        }
    }
    private void options(Gfx g) {
        panel(g,0,199,LEFT,h-224,builder?"Builder controls":"Tool options");
        int[] tab=flex(pw-20,4,1,1,1);
        button(g,10,222,tab[0],19,"Tool",!builder&&!pinnedTab,()->{builder=false;pinnedTab=false;init();},"Options of the active tool");
        button(g,14+tab[0],222,tab[1],19,"Builder",builder,()->{builder=true;init();},"");
        button(g,18+tab[0]+tab[1],222,tab[2],19,"Pinned",!builder&&pinnedTab,()->{builder=false;pinnedTab=true;init();},"Properties pinned from the full settings");
        if(builder){scrolled(g,246,6,0,()->builderOptions(g));return;}
        if(pinnedTab){
            button(g,pw-51,201,25,15,"…",false,()->ClientCompat.setScreen(minecraft,new StudioScreen(this)),"Advanced settings · View → Studio inspector");
            pinnedOptions(g);return;
        }
        text(g,o().tool.label,10,252,0xffa5cef2);
        button(g,pw-51,201,25,15,"…",false,()->ClientCompat.setScreen(minecraft,new StudioScreen(this)),"Advanced settings · View → Studio inspector");
        if(o().tool==Tool.MODIFY){modifyOptions(g);return;}
        // The Magic Wand's search buttons fill the room under the name; every other tool starts right under it.
        scrolled(g,262,o().tool.selection()?33:6,o().tool==Tool.MAGIC_SELECT?0:TOOL_NAME_GAP,()->toolBody(g));
    }
    private static final int TOOL_NAME_GAP=36;
    private void toolBody(Gfx g) {
        if(o().tool==Tool.MAGIC_SELECT)searchButtons(g,266);
        if(o().tool==Tool.BOX_SELECT||o().tool==Tool.MAGIC_SELECT||o().tool==Tool.RULER) {
            var s=ClientState.status;
            if(o().tool==Tool.RULER) {
                text(g,"MEASUREMENT",10,311,MUTED);
                var ruler=currentRuler();
                text(g,ClientState.rulers.size()+(ClientState.rulers.size()==1?" ruler":" rulers")+" · click an end to move it",10,332,MUTED);
                if(ruler!=null){
                    text(g,"Start: "+ruler.a.coordinates(),10,353,TEXT);
                    if(ruler.complete()){
                        text(g,"End: "+ruler.b.coordinates(),10,371,TEXT);
                        text(g,"Span: "+ruler.showSpan()+" blocks",10,392,0xffffd783);text(g,"Manhattan: "+ruler.manhattan(),10,410,MUTED);
                    }
                }
                button(g,10,423,pw-20,20,"Clear measurement",false,()->{ClientState.rulers.clear();selectedNode=-1;action("ruler_clear");},"");
                button(g,10,449,pw-20,20,"New rulers: "+o().rulerMode,false,()->o().rulerMode=Options.RulerMode.values()[(o().rulerMode.ordinal()+1)%2],()->o().rulerMode=Options.RulerMode.values()[(o().rulerMode.ordinal()+1)%2],"Inspector → Ruler to split segments and select center");
            } else {
                text(g,"SELECTION BOUNDS",10,311,MUTED);
                text(g,"Min  "+s.min().coordinates(),10,333,TEXT);text(g,"Max  "+s.max().coordinates(),10,353,TEXT);
                text(g,s.selectionSize()+" blocks",10,379,0xffa5cef2);
                modeButtons(g,394);
                if(o().tool==Tool.MAGIC_SELECT){
                    compareButtons(g,444);
                    toggle(g,10,474,"Surface only",o().surfaceOnly,()->o().surfaceOnly=!o().surfaceOnly);
                    toggle(g,10,492,"Hover preview (under "+o().hoverPreviewLimit+")",o().magicPreview,()->o().magicPreview=!o().magicPreview);
                }
                fixed(g,()->fillClear(g));
            }
            return;
        }
        if(o().tool.transform()){
            text(g,"PIVOT",10,311,MUTED);
            text(g,o().pivotSet?String.format(Locale.ROOT,"%.1f, %.1f, %.1f",o().pivotX,o().pivotY,o().pivotZ):"Centre of the selection",10,327,TEXT);
            button(g,10,350,pw-20,20,"Reset pivot",false,()->o().pivotSet=false,"Go back to the centre of the selection");
            wrap(g,"Drag a handle, then press Enter to apply. Esc cancels.",10,382,pw-20,MUTED,3);
            return;
        }
        if(o().tool==Tool.MODIFY){modifyOptions(g);return;}
        if(o().tool!=Tool.TEXT) {
            shapeSelector(g,305);
            // The track stops at 64 to stay usable; a bigger radius is typed in. Shapes are limited by their block count and reach, not by this.
            slider(g,10,347,pw-20,"Radius",o().radius,1,64,BrushMath.MAX_RADIUS,DEFAULTS.radius,v->o().radius=(int)Math.round(v),true);
            o().outlineThickness=Math.clamp(o().outlineThickness,1,o().radius); // a wall is never thicker than the radius
            // A polygon's sides take the row under the radius, and everything below moves down a row. The track stops at 64; more is typed.
            if(sidesShown())
                slider(g,10,375,pw-20,"Sides",o().polygonSides,BrushMath.MIN_SIDES,BrushMath.SLIDER_SIDES,BrushMath.MAX_SIDES,BrushMath.DEFAULT_SIDES,v->o().polygonSides=Math.clamp((int)Math.round(v),BrushMath.MIN_SIDES,BrushMath.MAX_SIDES),true);
            if(EnumSet.of(Tool.SCULPT_DRAW,Tool.ROCK,Tool.ELEVATION,Tool.FLATTEN,Tool.SLOPE,Tool.EXTRUDE).contains(o().tool))
                slider(g,10,375+sidesRow(),pw-20,"Strength",o().strength,.1,5,DEFAULTS.strength,v->o().strength=v,false);
        }
        // Tool-specific controls occupy the same familiar options area.
        switch(o().tool) {
            case NOISE_PAINTER -> {
                text(g,"Secondary block",10,382,MUTED);text(g,"Noise seed",10,423,MUTED);
                slider(g,10,480,pw-20,"Scale",o().noiseScale,1,64,DEFAULTS.noiseScale,v->o().noiseScale=v,false);
                slider(g,10,508,pw-20,"Blend",o().density,0,1,DEFAULTS.density,v->o().density=v,false);
            }
            case BIOME_PAINTER,CLENTAMINATOR -> text(g,"Biome identifier",10,369,MUTED);
            case TEXT -> {text(g,"Text · A–Z, 0–9, ! ? - .",10,309,MUTED);wrap(g,"Place upright letters on the clicked face. Each character is 5 by 7 blocks.",10,354,pw-20,MUTED,4);}
            case DISTORT -> {
                int half=(pw-24)/2;
                text(g,"Noise seed",10,370,MUTED);
                int y=418;
                if(o().distortSeparate){
                    slider(g,10,y,pw-20,"Distance X",o().distortX,0,8,Double.POSITIVE_INFINITY,DEFAULTS.distortX,v->o().distortX=Math.max(0,Math.round(v*100)/100d),false);y+=28;
                    slider(g,10,y,pw-20,"Distance Y",o().distortY,0,8,Double.POSITIVE_INFINITY,DEFAULTS.distortY,v->o().distortY=Math.max(0,Math.round(v*100)/100d),false);y+=28;
                    slider(g,10,y,pw-20,"Distance Z",o().distortZ,0,8,Double.POSITIVE_INFINITY,DEFAULTS.distortZ,v->o().distortZ=Math.max(0,Math.round(v*100)/100d),false);y+=28;
                }else{slider(g,10,y,pw-20,"Distance",o().distortDistance,0,8,Double.POSITIVE_INFINITY,DEFAULTS.distortDistance,v->o().distortDistance=Math.max(0,Math.round(v*100)/100d),false);y+=28;}
                toggle(g,10,y,"Separate axis",o().distortSeparate,()->o().distortSeparate=!o().distortSeparate,"Set how far the noise moves blocks along each axis on its own");y+=22;
                toggle(g,10,y,"Smooth edges",o().distortSmoothEdges,()->o().distortSmoothEdges=!o().distortSmoothEdges,"Fade the distortion out towards the brush's edge so it blends into the terrain");y+=30;
                slider(g,10,y+10,pw-20,"Scale",o().noiseScale,1,64,Double.POSITIVE_INFINITY,DEFAULTS.noiseScale,v->o().noiseScale=Math.max(.1,v),false);
            }
            case ROUGHEN -> {
                text(g,"Noise seed",10,370,MUTED);
                slider(g,10,418,pw-20,"Faces",o().exposedFaces,1,4,6,DEFAULTS.exposedFaces,v->o().exposedFaces=Math.clamp((int)Math.round(v),1,6),true);
                slider(g,10,446,pw-20,"Roughening ratio",o().roughenRatio,0,1,DEFAULTS.roughenRatio,v->o().roughenRatio=Math.round(v*100)/100d,false);
                slider(g,10,474,pw-20,"Noise scale",o().noiseScale,1,64,Double.POSITIVE_INFINITY,DEFAULTS.noiseScale,v->o().noiseScale=Math.max(.1,v),false);
            }
            case SHATTER -> {
                int quarter=(pw-32)/4;
                text(g,"Noise seed",10,370,MUTED);
                slider(g,10,418,pw-20,"Width",o().shatterWidth,0,8,Double.POSITIVE_INFINITY,DEFAULTS.shatterWidth,v->o().shatterWidth=Math.max(0,Math.round(v*100)/100d),false);
                text(g,"Noise dimensions",10,438,MUTED);
                for(var axis:Options.ShatterAxis.values())button(g,10+axis.ordinal()*(quarter+4),450,quarter,20,axis.label,o().shatterAxis==axis,()->o().shatterAxis=axis,axis==Options.ShatterAxis.ALL?"Cracks in all three dimensions":"Cracks only across the "+axis.label+" axis");
                toggle(g,10,478,"Use active block",o().fractureMaterial,()->o().fractureMaterial=!o().fractureMaterial,"Fill the cracks with the active block instead of removing blocks");
                slider(g,10,516,pw-20,"Scale",o().noiseScale,1,64,Double.POSITIVE_INFINITY,DEFAULTS.noiseScale,v->o().noiseScale=Math.max(.1,v),false);
            }
            case ROCK -> {
                text(g,"Noise seed",10,405,MUTED);
                slider(g,10,460,pw-20,"Noise scale",o().noiseScale,1,64,DEFAULTS.noiseScale,v->o().noiseScale=v,false);
                toggle(g,10,489,"Invert / carve",o().invert,()->o().invert=!o().invert);
            }
            default -> {
                int row=sidesRow()+(o().tool==Tool.SMOOTH?smoothRows(g,375+sidesRow()):0);
                toggle(g,10,403+row,"Invert / carve",o().invert,()->o().invert=!o().invert);
                fillButtons(g,424+row);
                toggle(g,10,447+row,"Surface only",o().surfaceOnly,()->o().surfaceOnly=!o().surfaceOnly);
                int next=472+row;
                if(o().hollow){ // the outline's own settings sit under the others, so they only take room while Hollow is chosen
                    int cap=Math.max(1,o().radius);
                    slider(g,10,next+10,pw-20,"Outline thickness",o().outlineThickness,1,Math.max(2,Math.min(cap,64)),cap,DEFAULTS.outlineThickness,v->o().outlineThickness=Math.clamp((int)Math.round(v),1,cap),true);
                    text(g,"Align stroke",10,next+34,MUTED);
                    alignButtons(g,next+44);
                    toggle(g,10,next+68,"Pixel perfect",o().pixelPerfect,()->o().pixelPerfect=!o().pixelPerfect,"One block per step along the outline, with no doubled corners on curves · best at thickness 1");
                    next+=92;
                }
                if(o().tool.selection())modeButtons(g,next);
            }
        }
        if(o().tool==Tool.TEXT||o().tool==Tool.BIOME_PAINTER||o().tool==Tool.CLENTAMINATOR) {
            toggle(g,10,416,"Keep existing",o().keepExisting,()->o().keepExisting=!o().keepExisting);
        }
        if(o().tool.selection()){fixed(g,()->fillClear(g));}
    }
    /** One scrollable row of the Modify panel; {@code paint} gets the row's top. */
    private record ModifyRow(int height,java.util.function.BiConsumer<Gfx,Integer> paint){}
    private int modifyScroll;
    private static final int MODIFY_TOP=316;
    /** The Modify tool's own options: its mode, then only the controls that mode reads, in rows that scroll. Revolve and Rotate Copies turn around the anchored block. */
    private void modifyOptions(Gfx g){
        var o=o();int half=(pw-24)/2,w=pw-32;
        String[] names={"Translate Copies","Revolve","Rotate Copies","Twist"};
        Options.ModifyMode[] order={Options.ModifyMode.TRANSLATE_COPIES,Options.ModifyMode.REVOLVE,Options.ModifyMode.ROTATE_COPIES,Options.ModifyMode.TWIST};
        for(int i=0;i<4;i++){var mode=order[i];button(g,10+(i%2)*(half+4),264+(i/2)*24,half,20,names[i],o.modifyMode==mode,()->o.modifyMode=mode,"");}
        var rows=new ArrayList<ModifyRow>();
        switch(o.modifyMode){
            case TRANSLATE_COPIES -> {
                rows.add(sliderRow(w,"Copies",o.copies,1,16,Double.POSITIVE_INFINITY,DEFAULTS.copies,v->o.copies=(int)Math.max(1,Math.round(v)),true));
                rows.add(sliderRow(w,"Offset X",o.offsetX,-16,16,Double.POSITIVE_INFINITY,0,v->o.offsetX=(int)Math.round(v),true));
                rows.add(sliderRow(w,"Offset Y",o.offsetY,-16,16,Double.POSITIVE_INFINITY,DEFAULTS.offsetY,v->o.offsetY=(int)Math.round(v),true));
                rows.add(sliderRow(w,"Offset Z",o.offsetZ,-16,16,Double.POSITIVE_INFINITY,0,v->o.offsetZ=(int)Math.round(v),true));
                rows.add(new ModifyRow(22,(gg,y)->toggle(gg,10,y+2,o.modifyRelative?"Relative (x selection size)":"Absolute (blocks)",o.modifyRelative,()->o.modifyRelative=!o.modifyRelative,"Relative offsets are multiplied by the size of the selection; absolute offsets are not")));
            }
            case REVOLVE -> {
                rows.add(axisRow());
                rows.add(sliderRow(w,"Angle",o.modifyAngle,-360,360,Double.POSITIVE_INFINITY,DEFAULTS.modifyAngle,v->o.modifyAngle=Math.round(v*10)/10d,false));
                translationRows(rows,w);
            }
            case ROTATE_COPIES -> {
                boolean custom=o.modifyType==Options.RotateType.CUSTOM_ANGLE;
                Runnable flip=()->o.modifyType=custom?Options.RotateType.EQUIDISTANT:Options.RotateType.CUSTOM_ANGLE;
                rows.add(new ModifyRow(26,(gg,y)->button(gg,10,y,pw-32,20,"Type: "+(custom?"Custom Angle":"Equidistant"),false,flip,flip,"Equidistant spreads copies around the anchor; Custom Angle turns each copy by the angle")));
                rows.add(axisRow());
                rows.add(sliderRow(w,"Copies",o.copies,1,16,Double.POSITIVE_INFINITY,DEFAULTS.copies,v->o.copies=(int)Math.max(1,Math.round(v)),true));
                if(custom)rows.add(sliderRow(w,"Angle",o.modifyAngle,-360,360,Double.POSITIVE_INFINITY,DEFAULTS.modifyAngle,v->o.modifyAngle=Math.round(v*10)/10d,false));
                translationRows(rows,w);
            }
            case TWIST -> {
                rows.add(axisRow());
                rows.add(sliderRow(w,"Angle",o.modifyAngle,-360,360,Double.POSITIVE_INFINITY,DEFAULTS.modifyAngle,v->o.modifyAngle=Math.round(v*10)/10d,false));
            }
        }
        int bottom=pb-87,avail=bottom-MODIFY_TOP,max=0,used=0;
        for(int i=rows.size()-1;i>=0;i--){if(used+rows.get(i).height()>avail){max=i+1;break;}used+=rows.get(i).height();}
        modifyScroll=Math.clamp(modifyScroll,0,max);
        int y=MODIFY_TOP,shown=0;
        for(int i=modifyScroll;i<rows.size();i++){var row=rows.get(i);if(y+row.height()>bottom)break;row.paint().accept(g,y);y+=row.height();shown++;}
        if(max>0){ // a thin scrollbar: the wheel, or a click on the track, moves through the rows
            int track=bottom-MODIFY_TOP,thumb=Math.max(14,track*shown/rows.size()),at=MODIFY_TOP+(track-thumb)*modifyScroll/max;
            g.fill(pw-8,MODIFY_TOP,pw-4,bottom,0xff1b2b40);g.fill(pw-8,at,pw-4,at+thumb,MUTED);
            int top=MODIFY_TOP,span=Math.max(1,track-thumb),limit=max;
            controls.add(new Control(pw-11,top,10,track,()->{double py=WorkspaceLayout.inverse("options",mx,my)[1];modifyScroll=Math.clamp((int)Math.round((py-top-thumb/2.0)/span*limit),0,limit);},""));
        }
        boolean cursorBound=ModifyMath.usesCursor(o);
        String where=ModifyPreview.anchored()?(cursorBound&&ModifyPreview.anchor()!=null?"Anchored at "+ModifyPreview.anchor().coordinates():"Anchored"):cursorBound?"Following the cursor · click to anchor":"Click to anchor the preview";
        text(g,where,10,pb-75,ModifyPreview.anchored()?0xffffd66d:MUTED);
        button(g,10,pb-53,pw-20,20,"Edit anchor",!ModifyPreview.anchored(),ModifyPreview::edit,"Let the guides and preview follow the cursor again; click to anchor them somewhere else");
        button(g,10,pb-27,pw-20,20,"Apply (Enter)",false,()->action("use"),"Build the previewed result");
    }
    private ModifyRow sliderRow(int w,String label,double value,double min,double max,double typedMax,double def,java.util.function.DoubleConsumer set,boolean integer){
        return new ModifyRow(28,(g,y)->slider(g,10,y+10,w,label,value,min,max,typedMax,def,set,integer));
    }
    private ModifyRow axisRow(){
        return new ModifyRow(32,(g,y)->{
            text(g,"Axis",10,y,MUTED);int bw=(pw-36)/3;
            for(var axis:Options.ModifyAxis.values())button(g,10+axis.ordinal()*(bw+4),y+10,bw,20,axis.name(),o().modifyAxis==axis,()->o().modifyAxis=axis,"");
        });
    }
    /** The Add Translation toggle and, while it is on, the offset it adds: where a revolve ends or the last rotated copy lands. */
    private void translationRows(List<ModifyRow> rows,int w){
        var o=o();
        rows.add(new ModifyRow(22,(g,y)->toggle(g,10,y+2,"Add Translation",o.modifyTranslate,()->o.modifyTranslate=!o.modifyTranslate,"Offsets the end position, e.g. 1 on Y makes a spiral")));
        if(!o.modifyTranslate)return;
        rows.add(sliderRow(w,"Translate X",o.modifyTranslateX,-16,16,Double.POSITIVE_INFINITY,0,v->o.modifyTranslateX=Math.round(v*100)/100d,false));
        rows.add(sliderRow(w,"Translate Y",o.modifyTranslateY,-16,16,Double.POSITIVE_INFINITY,0,v->o.modifyTranslateY=Math.round(v*100)/100d,false));
        rows.add(sliderRow(w,"Translate Z",o.modifyTranslateZ,-16,16,Double.POSITIVE_INFINITY,0,v->o.modifyTranslateZ=Math.round(v*100)/100d,false));
    }
    private record PinnedRow(String name,OptionFields.Kind kind,int y){}
    private final List<PinnedRow> pinnedRows=new ArrayList<>();
    private int pinnedScroll,pinnedTotal;
    private boolean pinnedMore;
    private static final int PIN_TOP=268;
    private static int pinnedHeight(OptionFields.Kind kind){return switch(kind){case BOOLEAN->22;case ENUM->27;default->40;};}
    /** Works out which pinned properties fit in the tab, from the one scrolled to, and makes an input for those that have them. */
    private void pinnedFields(){
        pinnedRows.clear();pinnedMore=false;
        var names=OptionFields.pinnedInOrder();pinnedTotal=names.size();
        pinnedScroll=Math.clamp(pinnedScroll,0,Math.max(0,names.size()-1));
        int y=PIN_TOP;
        for(int i=pinnedScroll;i<names.size();i++){
            var kind=OptionFields.kind(names.get(i));
            if(y+pinnedHeight(kind)>optionsBottom()-9&&!pinnedRows.isEmpty()){pinnedMore=true;break;}
            pinnedRows.add(new PinnedRow(names.get(i),kind,y));y+=pinnedHeight(kind);
        }
        for(var row:pinnedRows){
            String name=row.name();int fy=row.y()+16;
            switch(row.kind()){
                case BOOLEAN,ENUM -> {}
                case VECTOR -> {
                    if(OptionFields.SINGLE_LINE.contains(name))field("pin:"+name,10,fy,optionsWidth()-26,OptionFields.text(name),s->{try{OptionFields.parse(name,s);}catch(Exception ignored){}});
                    else{
                        String[] values=OptionFields.axes(name);int cell=(optionsWidth()-28)/3;
                        for(int axis=0;axis<3;axis++){int index=axis;field("pin:"+name+":"+axis,10+axis*(cell+4)+12,fy,cell-18,values[axis],s->{try{OptionFields.setAxis(name,index,s);}catch(Exception ignored){}});}
                    }
                }
                default -> field("pin:"+name,10,fy,optionsWidth()-26,OptionFields.text(name),s->{try{OptionFields.parse(name,s);}catch(Exception ignored){}});
            }
        }
    }
    /** The properties pinned in the full settings, in the order laid out there, with the same controls. */
    private void pinnedOptions(Gfx g){
        text(g,"PINNED",10,252,MUTED);
        if(pinnedTotal==0){wrap(g,"Nothing is pinned. Press … to open the full settings, then Pin beside any property to keep it here.",10,272,pw-20,MUTED,6);return;}
        if(pinnedMore||pinnedScroll>0){String range=(pinnedScroll+1)+"–"+(pinnedScroll+pinnedRows.size())+" of "+pinnedTotal+" · wheel";text(g,range,pw-10-font.width(range),252,MUTED);}
        for(var row:pinnedRows){
            String name=row.name(),label=OptionFields.human(name);int y=row.y();
            button(g,pw-24,y-1,14,13,"x",false,()->{OptionFields.togglePin(name);init();},"Unpin");
            switch(row.kind()){
                case BOOLEAN -> toggle(g,10,y,shorten(label,112),OptionFields.bool(name),()->OptionFields.toggle(name));
                case ENUM -> button(g,10,y-2,pw-38,20,shorten(label+": "+OptionFields.choice(name),pw-48),false,()->OptionFields.cycle(name,1),()->OptionFields.cycle(name,-1),"Click for the next value");
                case VECTOR -> {
                    boolean single=OptionFields.SINGLE_LINE.contains(name);
                    text(g,shorten(label,single?90:80),10,y,MUTED);
                    button(g,pw-62,y-1,36,13,single?"Axes":"Text",false,()->{if(!OptionFields.SINGLE_LINE.remove(name))OptionFields.SINGLE_LINE.add(name);init();},single?"Show an input for each axis":"Show one input, with the axes separated by spaces");
                    if(!single){int cell=(pw-20-8)/3;for(int axis=0;axis<3;axis++)text(g,"XYZ".substring(axis,axis+1),10+axis*(cell+4)+2,y+21,MUTED);}
                }
                default -> text(g,shorten(label,pw-42),10,y,MUTED);
            }
        }
    }
    /** The Fill and Clear buttons side by side along the bottom of the options panel. */
    private void fillClear(Gfx g){
        int[] cell=flex(pw-20,6,1,1);
        button(g,10,pb-27,cell[0],20,"Fill",false,()->action("fill"),"Fill the selection with the active block");
        button(g,16+cell[0],pb-27,cell[1],20,"Clear",false,()->action("clear"),"Clear the selection");
    }
    private int optionsScroll,scrollLimit,fieldShift,viewTop,viewBottom;
    /**
     * Draws the options panel's contents in the room between {@code top} and the panel's bottom less {@code reserve}, moved up by
     * {@code gap}. Whatever reaches below that room scrolls: the wheel moves it and a bar on the right shows where it is.
     */
    private void scrolled(Gfx g,int top,int reserve,int gap,Runnable content) {
        int bottom=pb-reserve;optionsScroll=Math.clamp(optionsScroll,0,scrollLimit);
        viewTop=top;viewBottom=bottom;fieldShift=-gap-optionsScroll;
        g.enableScissor(0,top,pw,bottom);g.pose().pushMatrix();g.pose().translate(0,fieldShift);
        scrollActive=true;scrollShift=fieldShift;scrollContent=0;scrollClipTop=top;scrollClipBottom=bottom;
        try{content.run();}finally{
            scrollActive=false;scrollShift=0;scrollClipTop=Integer.MIN_VALUE;scrollClipBottom=Integer.MAX_VALUE;
            g.pose().popMatrix();g.disableScissor();
        }
        scrollLimit=Math.max(0,scrollContent-gap-bottom+6);
        if(scrollLimit>0){
            int track=bottom-top,thumb=Math.max(14,track*track/(track+scrollLimit)),at=top+(track-thumb)*optionsScroll/scrollLimit,span=Math.max(1,track-thumb),limit=scrollLimit;
            g.fill(pw-7,top,pw-3,bottom,0xff1b2b40);g.fill(pw-7,at,pw-3,at+thumb,MUTED);
            controls.add(new Control(pw-10,top,10,track,()->{double py=WorkspaceLayout.inverse("options",mx,my)[1];optionsScroll=Math.clamp((int)Math.round((py-top-thumb/2.0)/span*limit),0,limit);},""));
        }
    }
    /** Runs {@code content} outside the scrolling area, where the bottom buttons stay put. */
    private void fixed(Gfx g,Runnable content) {
        if(!scrollActive){content.run();return;}
        g.pose().popMatrix();g.disableScissor();
        int shift=scrollShift,clipTop=scrollClipTop,clipBottom=scrollClipBottom;
        scrollActive=false;scrollShift=0;scrollClipTop=Integer.MIN_VALUE;scrollClipBottom=Integer.MAX_VALUE;
        try{content.run();}finally{
            scrollActive=true;scrollShift=shift;scrollClipTop=clipTop;scrollClipBottom=clipBottom;
            g.enableScissor(0,viewTop,pw,viewBottom);g.pose().pushMatrix();g.pose().translate(0,shift);
        }
    }
    private void builderOptions(Gfx g) {
        text(g,"CAPABILITIES",10,254,MUTED);
        toggle(g,10,273,"No updates",o().noUpdates,()->o().noUpdates=!o().noUpdates,"Placed blocks do not update their neighbours, so they stay when what holds them is removed");
        toggle(g,10,296,"Force place",o().forcePlace,()->o().forcePlace=!o().forcePlace,"Place blocks where they could not survive, like a torch in the air");
        toggle(g,10,319,"Fast place",o().fastPlace,()->o().fastPlace=!o().fastPlace,"Hold right click and sweep the view to place a block on every new block you aim at, with no delay");
        toggle(g,10,342,"Bulldozer",o().bulldozer,()->o().bulldozer=!o().bulldozer,"Hold left click for a moment, then sweep the view to break every new block you aim at, with no delay");
        toggle(g,10,365,"Angel place",o().angelPlacement,()->o().angelPlacement=!o().angelPlacement,"Right click in the air to place the held block four blocks ahead, shown as a ghost");
        toggle(g,10,388,"Tinker",o().tinker,()->o().tinker=!o().tinker,"Right click a block with an empty hand to change it by where you aim (stairs, slabs, walls, fences, facing, switches) · sneak and click to pick a specific property");
        toggle(g,10,411,"Replace mode",o().replaceMode,()->o().replaceMode=!o().replaceMode,"Placing a block replaces the one you click instead of going beside it · R");
        toggle(g,10,434,"Block step",o().blockStep,()->o().blockStep=!o().blockStep,"Step up a whole block on your own, as you step up a slab");
        button(g,10,457,pw-20,20,"Reach: "+o().reach.label,false,()->o().reach=Options.ReachMode.values()[(o().reach.ordinal()+1)%Options.ReachMode.values().length],()->o().reach=Options.ReachMode.values()[Math.floorMod(o().reach.ordinal()-1,Options.ReachMode.values().length)],"How far you can reach blocks: normal, far (set below) or infinite · right click goes back");
        slider(g,10,494,pw-20,"Far reach: blocks",o().farReach,6,64,DEFAULTS.farReach,v->o().farReach=(int)Math.round(v),true);
        slider(g,10,530,pw-20,"Fly speed",o().flySpeed,.1,10,DEFAULTS.flySpeed,v->o().flySpeed=(float)v,false);
        button(g,10,554,pw-20,20,"Apply flight speed",false,()->action("flight"),"");
        int[] pair=flex(pw-20,6,1,1);
        button(g,10,580,pair[0],20,"Hotbar ↔",false,()->action("hotbar"),"Swap the hotbar with inventory row 1 · Z");
        button(g,16+pair[0],580,pair[1],20,"Game mode",false,()->action("gamemode"),"");
        button(g,10,608,pw-20,20,"Builder tool: "+o().builderTool.name(),false,()->{o().builderTool=Options.BuilderTool.values()[(o().builderTool.ordinal()+1)%Options.BuilderTool.values().length];},()->{o().builderTool=Options.BuilderTool.values()[Math.floorMod(o().builderTool.ordinal()-1,Options.BuilderTool.values().length)];},"Press 0 in the world · Alt + wheel changes tools");
    }
    private void targetInfo(Gfx g) {
        int x=w-RIGHT;panel(g,x,27,RIGHT,82,"Target info");var hit=target();
        if(hit!=null&&minecraft.level!=null){var p=hit.getBlockPos();text(g,p.getX()+"  /  "+p.getY()+"  /  "+p.getZ(),x+10,53,TEXT);text(g,shorten(BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(p).getBlock()).toString(),pw-20),x+10,69,MUTED);}
        else text(g,"Point at a block",x+10,53,MUTED);
        text(g,ClientState.status.selectionSize()+" blocks selected",x+10,89,0xffa5cef2);
    }
    private void clipboard(Gfx g) {
        int x=w-RIGHT;panel(g,x,115,RIGHT,101,"Clipboard");
        text(g,ClientState.status.clipboardSize()==0?"No copied blocks":ClientState.status.clipboardSize()+" blocks ready to place",x+10,141,MUTED);
        int[] top=flex(pw-20,6,1,1,1),bottom=flex(pw-20,4,44,44,84);
        button(g,x+10,159,top[0],20,"Copy",false,()->action("copy"),"Copy selection · Ctrl+C");
        button(g,x+16+top[0],159,top[1],20,"Cut",false,()->action("cut"),"Cut selection · Ctrl+X");
        button(g,x+22+top[0]+top[1],159,top[2],20,"Paste",false,()->action("paste"),"Paste at cursor · Ctrl+V");
        button(g,x+10,186,bottom[0],20,"Rotate",false,()->action("rotate"),"Rotate clipboard 90° · Ctrl+R");
        button(g,x+14+bottom[0],186,bottom[1],20,"Mirror",false,()->action("flip"),"Mirror clipboard on X · Ctrl+F");
        button(g,x+18+bottom[0]+bottom[1],186,bottom[2],20,"Paste in place",false,()->CraftiumClient.send("paste_in_place",null,null),"Paste at the coordinates the blocks were copied from");
    }
    private void palette(Gfx g) {
        int x=w-RIGHT;panel(g,x,222,RIGHT,105,"Block palette");
        // Drawn back to front, like a paint tool: the foreground sits on top of the background.
        blockSlot(g,x+30,264,"Background block",o().secondary,id->o().secondary=id);
        blockSlot(g,x+10,248,"Foreground block",o().block,id->ClientState.pick(id));
        pipetteControls(g,x+70,248);
        // The cells flow across the panel and down as far as it reaches; the first is always "+", which adds a block to the palette
        // and makes it the foreground. What does not fit scrolls.
        var blocks=ClientState.palette;int total=blocks.size()+1,top=298,pitch=26,visibleRows=Math.max(1,(pb-6-top+3)/pitch);
        int columns=Math.max(1,(pw-17)/pitch);
        boolean bar=(total+columns-1)/columns>visibleRows;
        if(bar)columns=Math.max(1,(pw-25)/pitch);
        int rows=(total+columns-1)/columns,limit=Math.max(0,rows-visibleRows);paletteScroll=Math.clamp(paletteScroll,0,limit);
        for(int r=0;r<visibleRows;r++)for(int c=0;c<columns;c++){
            int i=(paletteScroll+r)*columns+c;if(i>=total)break;
            int bx=x+10+c*pitch,by=top+r*pitch;
            if(i==0){
                g.fill(bx,by,bx+23,by+23,0xff243246);g.outline(bx,by,23,23,LINE);g.centeredText(font,"+",bx+11,by+8,TEXT);
                controls.add(new Control(bx,by,23,23,()->ClientCompat.setScreen(minecraft,new BlockPickerScreen(this,"Add to palette","",id->{ClientState.pick(id);ClientState.save();})),"Add block"));
                continue;
            }
            int index=i-1;String block=blocks.get(index);
            boolean active=o().block.equals(block);g.fill(bx,by,bx+23,by+23,active?0xff315b84:0xff1b2838);if(active)g.outline(bx,by,23,23,ACCENT);
            BlockPickerScreen.icon(g,font,block,bx+3,by+3);
            dragSource(new Control(bx,by,23,23,()->ClientState.pick(block),()->openBlockMenu(BlockList.PALETTE,index),block+" · Click to set the foreground · Drag into the world to bucket fill · Right-click for options"),block);
        }
        if(bar){
            int track=visibleRows*pitch-3,thumb=Math.max(10,track*visibleRows/rows),thumbY=top+(track-thumb)*paletteScroll/Math.max(1,limit),barX=x+pw-11,max=limit;
            g.fill(barX,top,barX+5,top+track,0xff1b2838);g.fill(barX,thumbY,barX+5,thumbY+thumb,0xff4a6585);
            controls.add(new Control(barX-2,top,9,track,()->{double py=WorkspaceLayout.inverse("palette",mx,my)[1];paletteScroll=Math.clamp((int)Math.round((py-top-thumb/2.0)/Math.max(1,track-thumb)*max),0,max);},""));
        }
    }
    /** Whether the next click on the world takes the block it lands on into the palette, and the block the pointer is over meanwhile. */
    private boolean pipetting;
    private String pipetteBlock="";
    /** The pipette button, an icon that lights up while the pipette is in hand. */
    private void pipetteControls(Gfx g,int x,int y){
        FlatButton.face(g,x,y,30,30,"",pipetting,true,false);
        int color=pipetting?0xffffffff:0xffd0deeb;
        g.fill(x+19,y+6,x+24,y+11,color); // the bulb
        ToolIcons.line(g,x+16,y+10,x+21,y+15,color); // the collar
        ToolIcons.line(g,x+8,y+22,x+18,y+12,color);ToolIcons.line(g,x+9,y+23,x+19,y+13,color); // the tube
        g.fill(x+7,y+23,x+9,y+25,color); // the tip
        controls.add(new Control(x,y,30,30,()->pipetting=!pipetting,"Click a block in the world to add it to the palette · Esc cancels"));
    }
    /** While the pipette is in hand: finds the block under the pointer and shows its item in a cell above and to the right of the cursor. */
    private void pipettePreview(Gfx g){
        pipetteBlock="";
        if(!pipetting||menu>=0||help||transform)return;
        var hit=pipetteTarget(pointerX,pointerY);
        if(hit==null||minecraft.level==null)return;
        pipetteBlock=BlockStateScreen.describe(minecraft.level.getBlockState(hit.getBlockPos()));
        int cx=Math.clamp(mx+12,0,w-30),cy=Math.clamp(my-42,25,h-52);
        g.fill(cx,cy,cx+30,cy+30,0xf01b2838);g.outline(cx,cy,30,30,ACCENT);
        BlockPickerScreen.icon(g,font,pipetteBlock,cx+7,cy+7);
    }
    private long crosshairCursor;private boolean crosshairShown;
    /** Over the world with the pipette in hand the pointer is a crosshair. */
    private void syncCrosshair(){
        boolean want=pipetting&&!suspended&&menu<0&&!help&&!transform&&inViewport(pointerX/scale,pointerY/scale);
        if(want==crosshairShown)return;
        crosshairShown=want;
        if(want&&crosshairCursor==0)crosshairCursor=GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
        GLFW.glfwSetCursor(minecraft.getWindow().handle(),want?crosshairCursor:0L);
    }
    /** A 30px swatch for one of the two selected blocks; clicking it opens the block chooser to replace it. */
    private void blockSlot(Gfx g,int x,int y,String label,String block,java.util.function.Consumer<String> set) {
        g.fill(x,y,x+30,y+30,0xff1b2838);g.outline(x,y,30,30,label.startsWith("Fore")?ACCENT:LINE);
        BlockPickerScreen.icon(g,font,block,x+7,y+7);
        dragSource(new Control(x,y,30,30,()->ClientCompat.setScreen(minecraft,new BlockPickerScreen(this,label,block,id->{set.accept(id);ClientState.save();})),label+" · "+block+" · Click to replace · Drag into the world to bucket fill"),block);
    }
    /** A block cell that is clicked as usual, or dragged into the world to bucket fill with it. */
    private record DragSource(Control area,String block){}
    private final List<DragSource> dragSources=new ArrayList<>();
    private String dragBlock;private Runnable dragClick;private double dragStartX,dragStartY;private boolean dragMoved;
    /** Whether a block is being dragged out of the palette, so the world shows where it would fill. */
    boolean bucketDragging(){return dragBlock!=null&&dragMoved;}
    /** The block under the pointer for that fill, or null while the pointer is over a panel rather than the world. */
    BlockHitResult bucketTarget(){return bucketDragging()&&inViewport(pointerX/scale,pointerY/scale)?target():null;}
    private void dragSource(Control control,String block){controls.add(control);dragSources.add(new DragSource(control,block));}
    /** Ends a drag from a block cell: a plain click does what the cell does, a drop in the world bucket fills where it lands. */
    private void finishBlockDrag(MouseButtonEvent event){
        String block=dragBlock;Runnable click=dragClick;boolean moved=dragMoved;dragBlock=null;dragClick=null;dragMoved=false;
        if(!moved){click.run();return;}
        if(!inViewport(event.x()/scale,event.y()/scale))return;
        pointerX=event.x();pointerY=event.y();var hit=CraftiumClient.raycast(event.x(),event.y());
        if(hit==null){ClientState.notify("Drop the block onto a block in the world");return;}
        if(ClientState.status.busy()){ClientState.notify("Wait for the current edit to finish");return;}
        var at=hit.getBlockPos();Point point=CraftiumClient.point(at),normal=CraftiumClient.normal(hit);
        sendBucket(block,point,normal);
    }
    /** A question the player answers before something happens: the text, what Confirm is for, and what Confirm does. */
    private record Confirm(String text,String hint,Runnable action){}
    private Confirm confirm;
    private static final long HISTORY_CONFIRM_MS=60_000;
    /** Undo or redo, after a confirmation when the edit it reaches is a minute old or more. */
    private void historyAction(boolean undo){
        long age=HistoryPulse.age(undo);Runnable send=()->CraftiumClient.send(undo?"undo":"redo",null,null);
        if(age<HISTORY_CONFIRM_MS){send.run();return;}
        var status=ClientState.status;String name=undo?status.undoName():status.redoName();
        String when=age>=Long.MAX_VALUE/8?"an earlier session":age<3_600_000?age/60_000+" min ago":age/3_600_000+" h ago";
        confirm=new Confirm((undo?"Undo ":"Redo ")+name+" from "+when+"?",undo?"Undo it":"Redo it",send);
    }
    private void sendBucket(String block,Point target,Point normal){
        // The server fills with the active block, so the dragged one stands in for it for this one request.
        String prior=o().block;o().block=block;
        try{CraftiumClient.send("bucket_fill",target,normal);}finally{o().block=prior;}
    }
    private void acceptConfirm(){var pending=confirm;confirm=null;if(pending!=null)pending.action().run();}
    private void dismissConfirm(){confirm=null;}
    private void swapBlocks() {String front=o().block;ClientState.pick(o().secondary);o().secondary=front;ClientState.save();}
    private void history(Gfx g) {
        int x=w-RIGHT,y=333;
        panel(g,x,y,RIGHT,Math.max(50,h-551),"History");int height=ph;
        int[] cell=flex(pw-20,9,1,1);
        button(g,x+10,y+24,cell[0],19,"Undo "+ClientState.status.undo(),false,()->action("undo"),"Undo · Ctrl+Z");
        button(g,x+19+cell[0],y+24,cell[1],19,"Redo "+ClientState.status.redo(),false,()->action("redo"),"Redo · Ctrl+Y");
        int row=y+51,index=0;for(String event:ClientState.status.history()){if(row>y+height-12)break;int steps=++index;text(g,shorten(event,pw-20),x+10,row,MUTED);controls.add(new Control(x+7,row-3,pw-14,14,()->{o().historySteps=steps;action("history_undo_many");},""));row+=15;}
    }
    private void masks(Gfx g) {
        int x=w-RIGHT,y=h-212;panel(g,x,y,RIGHT,187,"Tool masks");
        int half=(pw-24)/2;
        maskCols=Math.max(1,(pw-19)/MASK_PITCH);maskRows=Math.max(1,(ph-109)/MASK_PITCH);
        button(g,x+10,y+24,half,19,"Include",!o().maskExclude,()->o().maskExclude=false,"");
        button(g,x+14+half,y+24,half,19,"Exclude",o().maskExclude,()->o().maskExclude=true,"");
        var cells=o().maskBlocks;int total=cells.size()+1,rows=(total+maskCols-1)/maskCols;maskScroll=Math.clamp(maskScroll,0,Math.max(0,rows-maskRows));
        for(int r=0;r<maskRows;r++)for(int c=0;c<maskCols;c++){
            int i=(maskScroll+r)*maskCols+c;if(i>=total)break;
            int bx=x+10+c*MASK_PITCH,by=y+50+r*MASK_PITCH;
            if(i==0){
                g.fill(bx,by,bx+22,by+22,0xff243246);g.outline(bx,by,22,22,LINE);g.centeredText(font,"+",bx+11,by+7,TEXT);
                controls.add(new Control(bx,by,22,22,()->ClientCompat.setScreen(minecraft,new BlockPickerScreen(this,"Add to mask","",this::addMaskBlock)),"Add block"));
                continue;
            }
            int index=i-1;String block=cells.get(index);boolean on=o().maskActive.contains(block);
            g.fill(bx,by,bx+22,by+22,on?0xff315b84:0xff1b2838);g.outline(bx,by,22,22,on?ACCENT:LINE);
            BlockPickerScreen.icon(g,font,block,bx+3,by+3);
            if(block.contains("["))g.fill(bx+16,by+16,bx+20,by+20,0xffffd66d);
            controls.add(new Control(bx,by,22,22,()->toggleMaskBlock(block),()->openBlockMenu(BlockList.MASK,index),
                block+(on?" · On":" · Off")+" · Click to toggle · Right-click for options"));
        }
        if(rows>maskRows){
            int track=maskRows*MASK_PITCH-3,thumb=Math.max(10,track*maskRows/rows),thumbY=y+50+(track-thumb)*maskScroll/(rows-maskRows),barX=x+pw-11;
            g.fill(barX,y+50,barX+5,y+50+track,0xff1b2838);g.fill(barX,thumbY,barX+5,thumbY+thumb,0xff4a6585);
            controls.add(new Control(barX-2,y+50,9,track,()->{double py=WorkspaceLayout.inverse("masks",mx,my)[1];maskScroll=Math.clamp((int)Math.round((py-y-50-thumb/2.0)/Math.max(1,track-thumb)*(rows-maskRows)),0,rows-maskRows);},""));
        }
        text(g,"Min Y",x+10,y+ph-53,MUTED);text(g,"Max Y",x+pw-90,y+ph-53,MUTED);
        toggle(g,x+10,y+ph-18,"Only in selection",o().inSelection,()->o().inSelection=!o().inSelection,"Only edit blocks inside the current selection. Blocks outside it are left alone, and with nothing selected nothing changes.");
    }
    private int maskCols=7,maskRows=3;
    private static final int MASK_PITCH=25,MASK_MENU_WIDTH=110,MASK_MENU_ROW=18;
    private int maskScroll,paletteScroll,ctxCell=-1,ctxX,ctxY;
    /** The two lists of blocks that share the Change / Edit block state / Remove menu. */
    private enum BlockList { MASK,PALETTE }
    private BlockList ctxList=BlockList.MASK;
    private List<String> blocksOf(BlockList list){return list==BlockList.MASK?o().maskBlocks:ClientState.palette;}
    private void openBlockMenu(BlockList list,int index){ctxList=list;ctxCell=index;ctxX=Math.min(mx,w-MASK_MENU_WIDTH-4);ctxY=Math.min(my,h-BLOCK_MENU_LABELS.length*MASK_MENU_ROW-4);}
    private static final String[] BLOCK_MENU_LABELS={"Change","Edit block state","Remove"};
    private void toggleMaskBlock(String block){if(!o().maskActive.remove(block))o().maskActive.add(block);ClientState.save();}
    /** Adds a block to the mask palette, switched on; one already there is just switched on. */
    private void addMaskBlock(String block){
        if(!o().maskBlocks.contains(block)){if(o().maskBlocks.size()>=64){ClientState.notify("The mask palette holds up to 64 blocks");return;}o().maskBlocks.add(block);}
        if(!o().maskActive.contains(block))o().maskActive.add(block);ClientState.save();
    }
    private void removeBlock(BlockList list,int index){
        var block=blocksOf(list).remove(index);
        if(list==BlockList.MASK)o().maskActive.remove(block);
        ClientState.save();
    }
    /** Swaps a block in its list for another, which takes over its place, whether it is switched on (masks) or the foreground (palette). */
    private void replaceBlock(BlockList list,String old,String edited){
        var blocks=blocksOf(list);int at=blocks.indexOf(old);if(at<0||edited.equals(old))return;
        boolean on=list==BlockList.MASK&&o().maskActive.remove(old);
        if(blocks.contains(edited))blocks.remove(at);else blocks.set(at,edited);
        if(on&&!o().maskActive.contains(edited))o().maskActive.add(edited);
        if(list==BlockList.PALETTE&&o().block.equals(old))o().block=edited;
        ClientState.save();
    }
    /** Change: choose another block for the cell in the block chooser. */
    private void changeBlock(BlockList list,int index){
        var old=blocksOf(list).get(index);
        ClientCompat.setScreen(minecraft,new BlockPickerScreen(this,"Change block",old,id->replaceBlock(list,old,id)));
    }
    private void editBlockState(BlockList list,int index){
        var old=blocksOf(list).get(index);
        ClientCompat.setScreen(minecraft,new BlockStateScreen(this,old,edited->replaceBlock(list,old,edited)));
    }
    /** The block the pointer is on when it is over the world rather than a panel, for taking that exact block from the chooser. */
    BlockHitResult pipetteTarget(double guiX,double guiY){
        if(!inViewport(guiX/scale,guiY/scale))return null;
        return CraftiumClient.raycast(guiX,guiY);
    }
    private void maskMenu(Gfx g){
        String[] labels=BLOCK_MENU_LABELS;int rows=labels.length;
        g.fill(ctxX,ctxY,ctxX+MASK_MENU_WIDTH,ctxY+rows*MASK_MENU_ROW,0xf5222d3b);g.outline(ctxX,ctxY,MASK_MENU_WIDTH,rows*MASK_MENU_ROW,LINE);
        for(int i=0;i<rows;i++){int y=ctxY+i*MASK_MENU_ROW;if(mx>=ctxX&&mx<ctxX+MASK_MENU_WIDTH&&my>=y&&my<y+MASK_MENU_ROW)g.fill(ctxX+1,y,ctxX+MASK_MENU_WIDTH-1,y+MASK_MENU_ROW,0xff34485e);text(g,labels[i],ctxX+8,y+5,TEXT);}
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
        if(minecraft.level==null){g.fill(l,80,r,h-28,0xff14202e);text(g,"Open a Creative world to start building",l+30,105,TEXT);}
    }
    /** Where the large-selection popup is, or null when it is not showing. It sits in the middle of the viewport. */
    private int[] popupBounds(){
        if(ClientState.largeSelection<=0&&confirm==null)return null;
        int left=panels?LEFT+6:6,right=panels?w-RIGHT-6:w-6;
        return new int[]{(left+right-250)/2,h/2-70,250,62};
    }
    /** A small popup beside the work, not over it: the editor stays visible and nothing is dimmed. */
    private void largeSelectionPopup(Gfx g){
        int[] b=popupBounds();
        if(b==null)return;
        g.fill(b[0],b[1],b[0]+b[2],b[1]+b[3],0xf0141d29);g.outline(b[0],b[1],b[2],b[3],ACCENT);
        if(confirm!=null){
            text(g,shorten(confirm.text(),b[2]-20),b[0]+10,b[1]+9,TEXT);
            button(g,b[0]+10,b[1]+30,112,22,"Confirm",true,this::acceptConfirm,"");
            button(g,b[0]+128,b[1]+30,112,22,"Cancel",false,this::dismissConfirm,"");
            return;
        }
        text(g,"Selected "+ClientState.largeSelection+" blocks, confirm?",b[0]+10,b[1]+9,TEXT);
        button(g,b[0]+10,b[1]+30,112,22,"Confirm",true,this::confirmLargeSelection,"");
        button(g,b[0]+128,b[1]+30,112,22,"Cancel",false,this::cancelLargeSelection,"");
    }
    private void confirmLargeSelection(){ClientState.largeSelection=0;SelectionOverlay.update(ClientState.heldSelection);}
    private void cancelLargeSelection(){
        ClientState.largeSelection=0;ClientState.boxCorners.clear();
        CraftiumClient.send("selection_restore",null,null);
    }
    /** Picks the tool, or puts it away when it is already the one in use, as clicking it in the pinned bar does. */
    private void toggleTool(Tool tool){if(toolActive&&o().tool==tool)deselectTool();else select(tool);}
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
            if(selected){g.outline(x,y,26,26,0xff8bc7ff);g.fill(x,y+24,x+26,y+26,ACCENT);}
            g.pose().pushMatrix();g.pose().translate(x+1,y+1);g.pose().scale(1f,1f);ToolIcons.draw(g,tool,0,0,selected?0xffffffff:0xffd0deeb);g.pose().popMatrix();
            int key=EditorControls.toolDefault(tool);
            controls.add(new Control(x,y,26,26,()->{if(toolActive&&o().tool==tool)deselectTool();else select(tool);},toolTip(tool,slot+1)));
        }
    }
    private void statusbar(Gfx g) {
        g.fill(0,h-20,w,h,0xff1b222c);g.fill(0,h-21,w,h-20,LINE);
        text(g,ToolHints.line(toolActive?o().tool:null,TransformGizmo.available(this),ShapeObject.active(),font::width,w-150),9,h-13,MUTED);
        text(g,(ClientState.status.busy()?"APPLYING…":"● READY")+"   "+String.format(Locale.ROOT,"%.1f×",o().flySpeed),w-132,h-13,ClientState.status.busy()?0xffffd783:0xff89c9ac);
    }
    private void dropdown(Gfx g) {
        menuAlign=true;try{dropdownItems(g);}finally{menuAlign=false;}
    }
    /** Whether {@link #button} left-aligns its label, as the entries of a menu do. */
    private boolean menuAlign;
    private void dropdownItems(Gfx g) {
        List<MenuItem> items=menuItems(menu);int x=108;for(int i=0;i<menu;i++)x+=menuWidth(i);
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
                    for(MenuItem child:item.children){
                        if(!child.children.isEmpty()){ // a second level, such as Preferences > Options
                            boolean open2=openSub2.equals(child.label);
                            button(g,sx+3,sy,220,24,child.label,open2,()->openSub2=open2?"":child.label,"");text(g,">",sx+212,sy+8,MUTED);
                            if(open2){
                                int tx=sx+226;g.fill(tx,sy-3,tx+226,sy-3+4+child.children.size()*25,0xff1c2735);g.outline(tx,sy-3,226,4+child.children.size()*25,LINE);
                                int ty=sy;for(MenuItem leaf:child.children){final var leafRun=leaf.action;button(g,tx+3,ty,220,24,leaf.label,false,()->{menu=-1;openSub="";openSub2="";leafRun.run();},"");ty+=25;}
                            }
                            sy+=25;continue;
                        }
                        final var run=child.action;button(g,sx+3,sy,220,24,child.label,false,()->{menu=-1;openSub="";openSub2="";run.run();},"");sy+=25;
                    }
                }
                y+=25;continue;
            }
            final var action=item.action;button(g,x+3,y,220,24,item.label,false,()->{menu=-1;action.run();},"");
            if(!item.shortcut.isEmpty())text(g,item.shortcut,x+212-font.width(item.shortcut),y+8,MUTED);y+=25;
        }
    }
    private List<MenuItem> menuItems(int which) {
        return switch(which) {
            case 0 -> List.of(item("Open reference image…","",ReferenceImages::choose),item("Save tool settings","",()->{ClientState.save();ClientState.notify("Tool settings saved");}),item("Reset tool settings","",()->{ClientState.options=new Options();init();}),submenu("Preferences",item("Keyboard Shortcuts…","",()->ClientCompat.setScreen(minecraft,new KeyboardShortcutsScreen(this))),item("Options…","",()->ClientCompat.setScreen(minecraft,new PreferencesOptionsScreen(this)))),item("Exit editor","RShift",this::onClose));
            case 1 -> List.of(item("Undo","Ctrl+Z",()->action("undo")),item("Redo","Ctrl+Y",()->action("redo")),divider(),
                item("Copy","Ctrl+C",()->action("copy")),item("Cut","Ctrl+X",()->action("cut")),item("Paste","Ctrl+V",()->action("paste")),
                item("Delete","Del",()->perform(Action.DELETE)),divider(),
                submenu("Rotate",item("Rotate 90","",()->rotateBy(1)),item("Rotate 180","",()->rotateBy(2)),item("Rotate 270","",()->rotateBy(3))),
                submenu("Flip",item("Flip X","",()->flip(0)),item("Flip Y","",()->flip(1)),item("Flip Z","",()->flip(2))),divider(),
                item("Paste at cursor","",this::pasteAtCursor),item("Paste in place","",()->CraftiumClient.send("paste_in_place",null,null)),divider(),
                item("Edits history","",()->ClientCompat.setScreen(minecraft,new HistoryScreen(this))));
            case 2 -> List.of(item("Deselect","Alt+D",()->action("clear")),item("Inverse","",()->action("selection_invert")),
                item(showSelection?"Hide selection":"Show selection","",()->showSelection=!showSelection),divider(),
                item("Expand…","",()->ClientCompat.setScreen(minecraft,new ResizeSelectionScreen(this,true))),item("Shrink…","",()->ClientCompat.setScreen(minecraft,new ResizeSelectionScreen(this,false))),
                item("Expand by 1","",()->resizeSelection("selection_expand",1)),item("Shrink by 1","",()->resizeSelection("selection_shrink",1)));
            case 3 -> List.of(item("Toggle side panels","",()->panels=!panels),item("Tool options","",()->{builder=false;pinnedTab=false;init();}),item("Builder controls","Alt",()->{builder=true;init();}),item("Studio inspector","",()->ClientCompat.setScreen(minecraft,new StudioScreen(this))),item("Colour picker / gradient","",()->ClientCompat.setScreen(minecraft,new ColourScreen(this))));
            case 8 -> List.of(
                item(AiBridge.running()?"Turn off AI building":"Turn on AI building","",()->{AiBridge.toggle();ClientState.notify(AiBridge.running()?"AI building is on":"AI building is off");}),divider(),
                item("Apply AI preview","",()->CraftiumClient.send("ai_apply",null,null)),item("Discard AI preview","",()->CraftiumClient.send("ai_cancel",null,null)),item("Undo last AI build","",()->CraftiumClient.send("ai_undo",null,null)),divider(),
                item("AI status and setup…","",()->{var studio=new StudioScreen(this);studio.selectTab("AI");ClientCompat.setScreen(minecraft,studio);}));
            case 9 -> {var items=new ArrayList<MenuItem>();for(String id:WorkspaceLayout.windows.keySet())items.add(item("Toggle "+id,"",()->WorkspaceLayout.toggle(id)));items.add(item("Save layout","",WorkspaceLayout::save));items.add(item("Reset layout","",WorkspaceLayout::reset));items.add(item("Copy layout","",()->minecraft.keyboardHandler.setClipboard(WorkspaceLayout.json())));items.add(item("Paste layout","",()->{try{WorkspaceLayout.importJson(minecraft.keyboardHandler.getClipboard());WorkspaceLayout.save();}catch(Exception e){ClientState.notify("Invalid layout JSON");}}));yield items;}
            case 5 -> List.of(item("Import structure…","",ClientStructures::chooseImport),divider(),
                item("Export selection…","",()->exportStructure(StructureSettings.SELECTION)),item("Export clipboard…","",()->exportStructure(StructureSettings.CLIPBOARD)),divider(),
                submenu("Export selection as",item("Structure (.nbt)","",()->exportStructure(StructureSettings.SELECTION,StructureSettings.JAVA,StructureSettings.NBT)),item("Schematic (.schem)","",()->exportStructure(StructureSettings.SELECTION,StructureSettings.JAVA,StructureSettings.SCHEM)),item("Bedrock structure (.mcstructure)","",()->exportStructure(StructureSettings.SELECTION,StructureSettings.BEDROCK,StructureSettings.NBT))),
                divider(),item("Open structures folder","",ClientStructures::openFolder));
            case 4 -> List.of(item("Shape","",()->select(Tool.SHAPE)),item("Block text","",()->select(Tool.TEXT)),item("Line","",()->select(Tool.LINE)),item("Rock","",()->select(Tool.ROCK)));
            case 6 -> List.of(item("Fill selection","Ctrl+F",()->action("fill")),item("Delete selection","Del",()->action("delete")),item("Move / clone / stack…","",()->{transform=true;init();}),item("Smooth brush","",()->select(Tool.SMOOTH)),item("Flatten brush","",()->select(Tool.FLATTEN)));
            case 7 -> List.of(item("Restrict to selection","",()->o().inSelection=!o().inSelection),item("Clear block mask","",()->{o().mask="";o().maskActive.clear();}),item("Toggle surface painting","",()->o().surfaceOnly=!o().surfaceOnly));
            default -> List.of(item("Editor controls","",()->help=true),item("About Craftium","",()->ClientCompat.setScreen(minecraft,new AboutScreen(this))));
        };
    }
    /** How a new selection combines with the current one, as four buttons in a grid with the active one lit. */
    private void modeButtons(Gfx g,int y){
        int half=(pw-24)/2;var modes=Selection.Mode.values();
        String[] tips={"Replace the selection","Add to the selection","Subtract from the selection","Keep only what overlaps the selection"};
        for(int i=0;i<modes.length;i++){
            final var mode=modes[i];
            button(g,10+(i%2)*(half+4),y+(i/2)*22,half,19,nice(mode.name()),o().mode==mode,()->o().mode=mode,tips[i]);
        }
    }
    /** Which ways the Magic Wand's search may spread from the clicked block, two buttons a row with the allowed ones lit. */
    private void searchButtons(Gfx g,int y){
        int half=(pw-24)/2;var o=o();
        record Side(String label,String tip,boolean on,Runnable toggle){}
        var sides=List.of(
            new Side("Up","Spread to the blocks above the clicked one",o.selectUp,()->o.selectUp=!o.selectUp),
            new Side("Down","Spread to the blocks below the clicked one",o.selectDown,()->o.selectDown=!o.selectDown),
            new Side("Horizontal","Spread sideways, level with the clicked block",o.selectHorizontal,()->o.selectHorizontal=!o.selectHorizontal),
            new Side("Corners","Also connect blocks that only touch at an edge or corner",o.selectCorners,()->o.selectCorners=!o.selectCorners));
        for(int i=0;i<sides.size();i++){var side=sides.get(i);button(g,10+(i%2)*(half+4),y+(i/2)*21,half,19,side.label(),side.on(),side.toggle(),side.tip());}
    }
    /** Smooth's own controls from {@code y} down, as the docs list them; returns the room they took. */
    private int smoothRows(Gfx g,int y){
        var o=o();int third=(pw-28)/3;
        slider(g,10,y,pw-20,"Smooth strength",o.smoothing,.1,5,DEFAULTS.smoothing,v->o.smoothing=Math.round(v*100)/100d,false);
        slider(g,10,y+28,pw-20,"Block ratio (%)",o.smoothRatio,50,150,Double.POSITIVE_INFINITY,DEFAULTS.smoothRatio,v->o.smoothRatio=Math.max(0,Math.round(v*10)/10d),false);
        for(var mode:Options.SmoothMode.values())button(g,10+mode.ordinal()*(third+4),y+56,third,20,mode.label,o.smoothMode==mode,()->o.smoothMode=mode,switch(mode){case STABLE->"Keep the block count at the ratio";case MELT->"Lean towards taking blocks away";case GROW->"Lean towards adding blocks";});
        toggle(g,10,y+84,"Fix edges",o.smoothFixEdges,()->o.smoothFixEdges=!o.smoothFixEdges,"Leave the brush's outer layer as it was so the smoothing blends into the terrain");
        return 90;
    }
    /** Which blocks the Magic Wand connects, as one icon button each with the active one lit. */
    private void compareButtons(Gfx g,int y){
        var kinds=Options.Compare.values();int width=(pw-20-4*(kinds.length-1))/kinds.length;
        String[] tips={"Any: connect every non-air block","Block: connect blocks of the same type (default)","Block state: connect blocks of the same type and state","Tag: connect blocks that share a block tag with the clicked one","NBT: connect blocks of the same type with the same block entity data"};
        for(int i=0;i<kinds.length;i++){
            final var kind=kinds[i];int x=10+i*(width+4);boolean active=o().compare==kind;
            button(g,x,y,width,22,"",active,()->o().compare=kind,tips[i]);
            g.pose().pushMatrix();g.pose().translate(x+(width-18)/2f,y+2);g.pose().scale(.75f,.75f);ToolIcons.compare(g,kind,0,0,active?0xffffffff:0xffd0deeb);g.pose().popMatrix();
        }
    }
    /** Inside, Center and Outside: where a hollow shape's wall sits against the edge the radius gives it. */
    /** Solid, Hollow and Outline on one line: exactly one is chosen, and Hollow and Outline never apply together. */
    private void fillButtons(Gfx g,int y){
        int width=(pw-20-8)/3;
        String[] labels={"Solid","Hollow","Outline"};
        String[] tips={"A solid shape","A shell with a wall, most visible on 3D shapes","Only the edges of the shape, for 2D and 3D shapes"};
        boolean[] active={!o().hollow&&!o().outline,o().hollow,o().outline};
        Runnable[] pick={()->{o().hollow=false;o().outline=false;},()->{o().hollow=true;o().outline=false;},()->{o().hollow=false;o().outline=true;}};
        for(int i=0;i<3;i++)button(g,10+i*(width+4),y,width,18,labels[i],active[i],pick[i],tips[i]);
    }
    private void alignButtons(Gfx g,int y){
        var kinds=Options.StrokeAlign.values();int width=(pw-20-4*(kinds.length-1))/kinds.length;
        String[] tips={"Inside: the wall grows inward from the shape's edge","Center: the wall straddles the shape's edge","Outside: the wall grows outward, so the radius is the size of the hole"};
        for(int i=0;i<kinds.length;i++){
            final var kind=kinds[i];
            button(g,10+i*(width+4),y,width,18,nice(kind.name()),o().strokeAlign==kind,()->o().strokeAlign=kind,tips[i]);
        }
    }
    // The shape picker is a popup of icons in groups, because there are too many shapes for the narrow options panel.
    private static final int SHAPE_COLS=4,SHAPE_CELL_W=56,SHAPE_CELL_H=44,SHAPE_HEAD=14,SHAPE_PAD=6,SHAPE_MENU_WIDTH=SHAPE_COLS*SHAPE_CELL_W+2*SHAPE_PAD;
    private boolean shapeMenu;private int shapeX,shapeY;
    private record ShapeCell(BrushMath.Shape shape,int x,int y){}
    /** How far the controls under the radius move down while the Sides slider is shown. */
    private int sidesRow(){return sidesShown()?28:0;}
    /** The tools with their own seed fields and painters have no free row; the + and - keys still change the sides there. */
    private boolean sidesShown(){return o().shape==BrushMath.Shape.POLYGON&&!EnumSet.of(Tool.ROCK,Tool.DISTORT,Tool.ROUGHEN,Tool.SHATTER,Tool.NOISE_PAINTER,Tool.BIOME_PAINTER,Tool.CLENTAMINATOR).contains(o().tool);}
    /** The button that shows the current shape and opens the picker. */
    private void shapeSelector(Gfx g,int y){
        var shape=o().shape;
        button(g,10,y,pw-20,24,"",shapeMenu,()->{
            shapeMenu=!shapeMenu;shapeX=Math.clamp(mx-24,4,w-SHAPE_MENU_WIDTH-4);shapeY=Math.clamp(my+8,4,h-shapeMenuHeight()-4);
        },"Choose the shape: solids, curved forms and flat ones");
        g.pose().pushMatrix();g.pose().translate(14,y+3);g.pose().scale(.75f,.75f);ToolIcons.shape(g,shape,0,0,0xffffffff);g.pose().popMatrix();
        text(g,nice(shape.name())+"  ▾",36,y+8,TEXT);
    }
    private int shapeMenuHeight(){
        int height=2*SHAPE_PAD;
        for(var shapes:BrushMath.GROUPS.values())height+=SHAPE_HEAD+(shapes.size()+SHAPE_COLS-1)/SHAPE_COLS*SHAPE_CELL_H;
        return height;
    }
    private List<ShapeCell> shapeCells(){
        var cells=new ArrayList<ShapeCell>();int y=shapeY+SHAPE_PAD;
        for(var shapes:BrushMath.GROUPS.values()){
            y+=SHAPE_HEAD;
            for(int i=0;i<shapes.size();i++)cells.add(new ShapeCell(shapes.get(i),shapeX+SHAPE_PAD+i%SHAPE_COLS*SHAPE_CELL_W,y+i/SHAPE_COLS*SHAPE_CELL_H));
            y+=(shapes.size()+SHAPE_COLS-1)/SHAPE_COLS*SHAPE_CELL_H;
        }
        return cells;
    }
    private BrushMath.Shape shapeAt(double x,double y){
        for(var cell:shapeCells())if(x>=cell.x()&&x<cell.x()+SHAPE_CELL_W&&y>=cell.y()&&y<cell.y()+SHAPE_CELL_H)return cell.shape();
        return null;
    }
    private void shapeMenu(Gfx g){
        int height=shapeMenuHeight();
        g.fill(shapeX,shapeY,shapeX+SHAPE_MENU_WIDTH,shapeY+height,0xf5222d3b);g.outline(shapeX,shapeY,SHAPE_MENU_WIDTH,height,LINE);
        int y=shapeY+SHAPE_PAD;
        for(var group:BrushMath.GROUPS.entrySet()){
            text(g,group.getKey().toUpperCase(Locale.ROOT),shapeX+SHAPE_PAD+2,y+3,MUTED);y+=SHAPE_HEAD;
            y+=(group.getValue().size()+SHAPE_COLS-1)/SHAPE_COLS*SHAPE_CELL_H;
        }
        for(var cell:shapeCells()){
            boolean active=o().shape==cell.shape(),hover=mx>=cell.x()&&mx<cell.x()+SHAPE_CELL_W&&my>=cell.y()&&my<cell.y()+SHAPE_CELL_H;
            g.fill(cell.x()+1,cell.y()+1,cell.x()+SHAPE_CELL_W-1,cell.y()+SHAPE_CELL_H-1,active?0xff315b84:hover?0xff34485e:0xff243246);
            if(active)g.fill(cell.x()+1,cell.y()+SHAPE_CELL_H-3,cell.x()+SHAPE_CELL_W-1,cell.y()+SHAPE_CELL_H-1,ACCENT);
            g.pose().pushMatrix();g.pose().translate(cell.x()+(SHAPE_CELL_W-24)/2f,cell.y()+3);g.pose().scale(1f,1f);ToolIcons.shape(g,cell.shape(),0,0,active?0xffffffff:0xffd0deeb);g.pose().popMatrix();
            String label=nice(cell.shape().name());
            text(g,label,cell.x()+(SHAPE_CELL_W-font.width(label))/2,cell.y()+SHAPE_CELL_H-13,active?0xffffffff:TEXT);
        }
    }
    /** Turns a placement waiting to be put down, or else the selection, by quarter turns about the vertical axis through its centre or pivot. */
    private void rotateBy(int quarters){
        if(ShapeObject.active()){ShapeObject.turn(1,90*quarters);return;}
        if(PlacementPreview.active()){for(int i=0;i<quarters;i++)action("rotate");return;}
        transformSelection(90*quarters,1,1,1);
    }
    /** Mirrors a placement waiting to be put down, or else the selection, across the plane through its centre or pivot normal to axis 0, 1 or 2 (X, Y, Z). */
    /** Opens the structure export pop-up on the given source, and on a format when one is named. */
    private void exportStructure(String source){exportStructure(source,null,null);}
    private void exportStructure(String source,String format,String javaFile){
        var settings=o().structure;settings.source=source;if(format!=null){settings.format=format;settings.javaFile=javaFile;}
        ClientCompat.setScreen(minecraft,new StructureExportScreen(this));
    }
    private void flip(int axis){
        // Every shape is the same mirrored sideways, so flipping X or Z is a half turn about Y and flipping Y a half turn about X.
        if(ShapeObject.active()){ShapeObject.turn(axis==1?0:1,180);return;}
        if(PlacementPreview.active()){action(new String[]{"flip_x","flip_y","flip_z"}[axis]);return;}
        transformSelection(0,axis==0?-1:1,axis==1?-1:1,axis==2?-1:1);
    }
    /** Sends the selection's transform with this turn and scale (negative flips), also turning the blocks' states. */
    private void transformSelection(double yaw,double sizeX,double sizeY,double sizeZ){
        if(ClientState.status.selectionSize()==0){ClientState.notify("Select blocks first");return;}
        if(ClientState.status.busy())return;
        Options original=ClientState.options,request=dev.craftium.net.Packets.JSON.fromJson(dev.craftium.net.Packets.JSON.toJson(original),Options.class);
        try{
            ClientState.options=request;
            request.yaw=yaw;request.pitch=request.roll=0;request.sizeX=sizeX;request.sizeY=sizeY;request.sizeZ=sizeZ;
            request.offsetX=request.offsetY=request.offsetZ=0;request.aboutSet=false;request.turnBlocks=true;
            CraftiumClient.send("selection_transform",null,null);
        }finally{ClientState.options=original;}
    }
    private MenuItem item(String label,String shortcut,Runnable action){return new MenuItem(label,shortcut,action);}
    /** A menu entry that opens a submenu beside it instead of doing something. */
    private MenuItem submenu(String label,MenuItem... children){return new MenuItem(label,"",()->{},List.of(children));}
    /** A thin line between groups of menu entries; it has no action. */
    private MenuItem divider(){return new MenuItem("","",null);}
    /** Grows or shrinks the selection by {@code amount} blocks all round, the server repeating one block at a time. */
    void resizeSelection(String action,int amount){
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
    private void button(Gfx g,int x,int y,int width,int height,String label,boolean active,Runnable action,String tip){button(g,x,y,width,height,label,active,action,null,tip);}
    /** A button that cycles through values: a click goes to the next one and a right click back to the one before. */
    private void button(Gfx g,int x,int y,int width,int height,String label,boolean active,Runnable action,Runnable back,String tip) {
        FlatButton.face(g,x,y,width,height,label,active,true,false,menuAlign);
        controls.add(new Control(x,y,width,height,action,back,back==null||tip.isEmpty()?tip:tip+" · Right-click goes back"));
    }
    private void toggle(Gfx g,int x,int y,String label,boolean value,Runnable action) {toggle(g,x,y,label,value,action,"");}
    private void toggle(Gfx g,int x,int y,String label,boolean value,Runnable action,String tip) {
        FlatCheckbox.box(g,x,y,12,value,false);
        text(g,label,x+19,y+3,TEXT);controls.add(new Control(x,y,Math.min(pw-20,font.width(label)+24),15,action,tip));
    }
    private void slider(Gfx g,int x,int y,int width,String label,double value,double min,double max,double def,DoubleConsumer set,boolean integer) {
        slider(g,x,y,width,label,value,min,max,max,def,set,integer);
    }
    /** A slider whose track runs from {@code min} to {@code max} but which takes a value typed with Ctrl-click up to {@code typedMax}. */
    private void slider(Gfx g,int x,int y,int width,String label,double value,double min,double max,double typedMax,double def,DoubleConsumer set,boolean integer) {
        text(g,label,x,y-10,MUTED);
        var slider=new Slider(x,width,min,max,typedMax,def,set);String panel=drawingPanel,key=panel+"/"+label;
        boolean editing=typing!=null&&typing.key().equals(key);
        String display=editing?typed+(System.currentTimeMillis()/500%2==0?"_":""):integer?Integer.toString((int)value):String.format(Locale.ROOT,"%.2f",value);
        FlatSlider.track(g,x,y,width,17,(value-min)/(max-min),Component.literal(display),editing,false,editing);
        var control=new Control(x,y,width,17,()->{
            if(minecraft.hasControlDown()){typing=new SliderArea(null,slider,key,integer);typed=integer?Integer.toString((int)value):String.format(Locale.ROOT,"%.2f",value);return;}
            dragging=slider;draggingSliderPanel=panel;slider.update(WorkspaceLayout.inverse(panel,mx,my)[0],false,minecraft.hasShiftDown());
        },"Drag to change "+label.toLowerCase(Locale.ROOT)+" · Ctrl-click to type"+(typedMax==Double.POSITIVE_INFINITY?" (no limit)":typedMax>max?" (up to "+(int)typedMax+")":"")+" · Middle-click to reset");
        controls.add(control);sliderAreas.add(new SliderArea(control,slider,key,integer));
    }
    /** Applies what was typed into a slider, clamped to its range, and stops typing. Text that is not a number changes nothing. */
    private void finishTyping(boolean apply){
        var area=typing;String text=typed;typing=null;typed="";
        if(!apply||area==null)return;
        try{double v=Double.parseDouble(text.trim());if(!Double.isFinite(v))return;if(area.slider().typedMax()!=Double.POSITIVE_INFINITY)v=Math.clamp(v,area.slider().min(),area.slider().typedMax());area.slider().set.accept(area.integer()?Math.round(v):v);}catch(NumberFormatException ignored){}
    }
    private void text(Gfx g,String text,int x,int y,int color){if(scrollActive)scrollContent=Math.max(scrollContent,y+9);g.text(font,text,x,y,color,false);}
    /** A tool's tooltip: its name and the key it is on (and the hotbar slot, when it has one) on the first line, its description below. */
    private static String toolTip(Tool tool,int slot){
        var mapping=CraftiumClient.toolKeys.get(tool);
        String key=mapping==null||mapping.isUnbound()?"":mapping.getTranslatedKeyMessage().getString();
        String shortcut=key.isEmpty()?slot>0?String.valueOf(slot):"":slot>0?key+" / "+slot:key;
        return tool.label+(shortcut.isEmpty()?"":" ["+shortcut+"]")+"\n"+tool.description;
    }
    private String shorten(String text,int pixels){if(font.width(text)<=pixels)return text;return font.plainSubstrByWidth(text,Math.max(0,pixels-9))+"…";}
    private void wrap(Gfx g,String text,int x,int y,int width,int color,int maxLines){int line=0;for(var row:font.split(Component.literal(text),width)){if(line>=maxLines)break;g.text(font,row,x,y+line*11,color,false);line++;}if(scrollActive)scrollContent=Math.max(scrollContent,y+line*11);}
    private String nice(String name){String s=name.toLowerCase(Locale.ROOT).replace('_',' ');return Character.toUpperCase(s.charAt(0))+s.substring(1);}
    public BlockHitResult target(){
        if(inViewport(pointerX/scale,pointerY/scale))lastHit=CraftiumClient.raycast(pointerX,pointerY);
        return lastHit;
    }
    /** Opens the named menu of the bar; the game tests use it to capture a dropdown. */
    void showMenu(String name){for(int i=0;i<MENUS.length;i++)if(MENUS[i].equals(name)){menu=i;openSub="";openSub2="";}}
    private void action(String action){
        if(EntityShortcuts.editor(action,this))return;
        if(action.equals("undo")||action.equals("redo")){historyAction(action.equals("undo"));return;}
        var hit=target();
        if(action.equals("clear")){ClientState.boxCorners.clear();o().pivotSet=false;}
        if(action.equals("duplicate")||action.equals("cut")){ClientState.duplicatePending=true;CraftiumClient.send(action.equals("cut")?"cut":"copy",null,null);return;}
        if(action.equals("paste")){
            if(PlacementPreview.active()){PlacementPreview.confirm();return;}
            if(hit!=null){Point p=CraftiumClient.point(hit.getBlockPos()),n=CraftiumClient.normal(hit);PlacementPreview.begin(p.add(n.x(),n.y(),n.z()));}
            else ClientState.notify("Point at a block to position the clipboard");
            return;
        }
        if(action.equals("use")&&o().tool==Tool.MODIFY){
            if(ClientState.hasPending()){CraftiumClient.send("pending_confirm",null,null);return;}
            Point at=ModifyPreview.anchored()&&ModifyPreview.anchor()!=null?ModifyPreview.anchor():hit==null?null:CraftiumClient.point(hit.getBlockPos());
            CraftiumClient.send(action,at,hit==null?new Point(0,1,0):CraftiumClient.normal(hit));
            ModifyPreview.edit();return;
        }
        CraftiumClient.send(action,hit==null?null:CraftiumClient.point(hit.getBlockPos()),hit==null?null:CraftiumClient.normal(hit));
    }
    private boolean inViewport(double x,double y){
        int[] pin=pinnedBounds();
        if(x>=pin[0]&&x<pin[0]+pin[2]&&y>=pin[1]&&y<pin[1]+pin[3])return false; // the pinned tools are not the world
        int[] popup=popupBounds();
        if(popup!=null&&x>=popup[0]&&x<popup[0]+popup[2]&&y>=popup[1]&&y<popup[1]+popup[3])return false;
        return !ReferenceImages.hit(x*scale,y*scale)&&y>27&&y<(height/scale)-23&&(!panels||WorkspaceLayout.hit(x,y,false).isBlank());
    }
    /**
     * Dragging with a selection tool: each new block under the cursor is sent as soon as the server has answered the last one,
     * instead of on a timer. A block reached while the server is still busy is sent the moment its answer arrives.
     */
    private net.minecraft.core.BlockPos lastDragged;
    private boolean useQueued,awaitingReply,repliedSince;
    /** The Sculpt tools work by strokes: what a stroke stages is built the moment the button is released, without Enter. Modify is built by its own Apply. */
    private static boolean sculpts(Tool tool){return tool.group.equals("Sculpt")&&tool!=Tool.MODIFY;}
    private long lastUseSent;
    private void dragUse(){
        if(!o().tool.selection()){use();return;}
        var hit=target();if(hit==null||hit.getBlockPos().equals(lastDragged))return;
        long now=System.currentTimeMillis();
        // A reply that never comes must not stall the drag for good, so a request is given up on after a quarter of a second.
        if(ClientState.status.busy()||(awaitingReply&&!repliedSince&&now-lastUseSent<250)){useQueued=true;return;}
        useQueued=false;lastDragged=hit.getBlockPos();lastUseSent=now;use();
    }
    /** Called when the server's status arrives: sends the block the cursor has moved to meanwhile, if there is one. */
    void statusReceived(){
        repliedSince=true;
        if(useQueued&&painting&&!ClientState.status.busy())dragUse();
    }
    private void use(){
        if(o().tool==Tool.ANNOTATION){var hit=target();if(hit!=null){o().annotationNormal=CraftiumClient.normal(hit);if(o().annotationMode.equals("DRAW")){Point p=CraftiumClient.point(hit.getBlockPos());if(o().annotationPoints.size()<256&&(o().annotationPoints.isEmpty()||!o().annotationPoints.getLast().equals(p)))o().annotationPoints.add(p);return;}}}
        long now=System.currentTimeMillis();if((!o().tool.selection()&&now-lastUse<140)||ClientState.status.busy())return;lastUse=now;awaitingReply=true;repliedSince=false;
        if(o().tool==Tool.PATH||o().tool==Tool.MODELLING||(o().tool==Tool.GRADIENT_PAINTER&&minecraft.hasShiftDown())){var hit=target();if(hit!=null&&o().nodes.size()<64){o().nodes.add(CraftiumClient.point(hit.getBlockPos()));ClientState.notify("Added point "+o().nodes.size()+" · Enter to build · View → Studio inspector for settings");}return;}
        if(o().tool==Tool.SLOPE){var hit=target();if(hit!=null){Point p=CraftiumClient.point(hit.getBlockPos());if(o().nodes.isEmpty()){o().nodes.add(p);ClientState.notify("Slope anchor set · drag from the second point");return;}if(o().nodes.size()==1)o().nodes.add(p);else o().nodes.set(1,p);}}
        if(o().tool==Tool.RULER) {
            var hit=target();if(hit!=null) {
                Point p=CraftiumClient.point(hit.getBlockPos());
                try{ClientState.rulerPoints.add(p);}catch(IllegalArgumentException e){ClientState.notify(e.getMessage());} // the second click finishes a ruler, the next one starts another
            }
            return;
        }
        if(o().tool==Tool.PIVOT){
            var hit=target();
            if(hit!=null){var p=hit.getBlockPos();o().pivotSet=true;o().pivotX=p.getX()+.5;o().pivotY=p.getY()+.5;o().pivotZ=p.getZ()+.5;ClientState.notify("Pivot set");}
            return;
        }
        if(o().tool.transform())return; // these work through their handles, not by clicking
        if(o().tool==Tool.MODIFY){ // the first click anchors the preview where the cursor is; it is built with Apply or Enter
            var hit=target();if(hit!=null)ModifyPreview.place(CraftiumClient.point(hit.getBlockPos()));
            return;
        }
        if(o().tool==Tool.SHAPE){ // the shape is put down, not built: dragging with the button held carries it along
            var hit=target();
            if(hit!=null)ShapeObject.place(CraftiumClient.point(hit.getBlockPos()));
            return;
        }
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
        if(typing!=null){finishTyping(true);return true;} // a click anywhere else keeps what was typed
        if(shapeMenu){shapeMenu=false;var picked=event.button()==0?shapeAt(x,y):null;if(picked!=null)o().shape=picked;return true;} // any click closes the picker
        if(ctxCell>=0){int cell=ctxCell;ctxCell=-1;if(x>=ctxX&&x<ctxX+MASK_MENU_WIDTH&&y>=ctxY&&y<ctxY+BLOCK_MENU_LABELS.length*MASK_MENU_ROW&&cell<blocksOf(ctxList).size()){switch((int)((y-ctxY)/MASK_MENU_ROW)){case 0->changeBlock(ctxList,cell);case 1->editBlockState(ctxList,cell);default->removeBlock(ctxList,cell);}}return true;}
        if(event.button()==2&&!help&&!transform&&menu<0)for(int i=sliderAreas.size()-1;i>=0;i--){var a=sliderAreas.get(i);if(a.control().contains(x,y)){a.slider().reset();return true;}} // back to the default, at once
        if(event.button()==0&&pipetting&&menu<0&&!help&&!transform){
            var hit=pipetteTarget(event.x(),event.y());
            if(hit!=null&&minecraft.level!=null){
                pipetting=false;ClientState.pick(BlockStateScreen.describe(minecraft.level.getBlockState(hit.getBlockPos())));ClientState.save();
                return true;
            }
            if(inViewport(x,y))return true; // a click on empty sky keeps the pipette in hand
        }
        if(event.button()==0) {
            if((panels||transform)&&menu<0&&!help){
                EditBox selected=null;
                for(var entry:fields.entrySet()){
                    EditBox field=entry.getValue();field.setFocused(false);
                    if(transform != (entry.getKey().startsWith("offset")||entry.getKey().equals("copies")))continue;
                    String panel=WorkspaceLayout.fieldPanel(entry.getKey());if(!WorkspaceLayout.visible(panel))continue;var point=WorkspaceLayout.inverse(panel,x,y);
                    if(fieldShown(entry.getKey(),field)&&point[0]>=field.getX()-3&&point[0]<field.getX()+field.getWidth()+3&&point[1]>=field.getY()-4&&point[1]<field.getY()+15)selected=field;
                }
                if(selected!=null){selected.setFocused(true);String id="";for(var entry:fields.entrySet())if(entry.getValue()==selected)id=entry.getKey();var point=WorkspaceLayout.inverse(WorkspaceLayout.fieldPanel(id),x,y);selected.mouseClicked(new MouseButtonEvent(point[0],point[1],event.buttonInfo()),doubleClick);return true;}
            }
            if(!help&&!transform&&menu<0)for(int i=dragSources.size()-1;i>=0;i--){var d=dragSources.get(i);if(d.area().contains(x,y)){dragBlock=d.block();dragClick=d.area().action();dragStartX=event.x();dragStartY=event.y();dragMoved=false;return true;}}
            for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(c.contains(x,y)){c.action.run();return true;}}
            if(help||transform)return true;if(menu>=0){menu=-1;return true;}
            if(panels){String id=WorkspaceLayout.hit(x,y,true);if(!id.isBlank()){var r=WorkspaceLayout.windows.get(id);draggedPanel=id;resizePanel=false;dragOriginX=r.x;dragOriginY=r.y;dragPointerX=x;dragPointerY=y;return true;}
                id=WorkspaceLayout.hit(x,y,false);if(!id.isBlank()){var r=WorkspaceLayout.windows.get(id);if(x>r.x+r.w-12&&y>r.y+r.h-12){draggedPanel=id;resizePanel=true;dragOriginX=r.w;dragOriginY=r.h;dragPointerX=x;dragPointerY=y;return true;}}
            }
        }
        if(event.button()==1&&!help&&!transform&&menu<0)for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(c.contains(x,y)){if(c.back!=null){c.back.run();return true;}break;}} // a cycling button steps back
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
            var points=handleNodes();double best=144;int index=-1;for(int i=0;i<points.size();i++){var pixel=project(points.get(i),TransformGizmo.nodeOffset());if(pixel==null)continue;double distance=Math.pow(event.x()-pixel[0],2)+Math.pow(event.y()-pixel[1],2);if(distance<best){best=distance;index=i;}}
            if(index>=0){selectedNode=index;if(!toolActive)ClientState.selectedRuler=rulerOfNode(index);draggingNode=true;placementDragOrigin=points.get(index);placementDragX=event.x();placementDragY=event.y();return true;}
            if(!toolActive){var line=rulerAt(event.x(),event.y());if(line!=null){ClientState.selectedRuler=line;selectedNode=-1;TransformGizmo.cancel();return true;}} // no tool chosen: a click on a ruler picks it, without choosing the Ruler tool
        }
        if(PlacementPreview.active()){
            if(event.button()==1){var hit=target();if(hit!=null){Point p=CraftiumClient.point(hit.getBlockPos()),n=CraftiumClient.normal(hit);PlacementPreview.origin=p.add(n.x(),n.y(),n.z());}return true;}
            if(event.button()==0&&pickPlacementAxis(event.x(),event.y())){draggingPlacement=true;placementDragOrigin=PlacementPreview.origin;placementDragX=event.x();placementDragY=event.y();return true;}
        }
        if(!toolActive&&EditorBindings.matches(Action.CROSSHAIR,event)){cameraGesture=CameraGesture.ROTATE;cameraButton=event.button();return true;}
        if(EditorBindings.matches(Action.ROTATE_CAMERA,event)){cameraGesture=CameraGesture.ROTATE;cameraButton=event.button();return true;}
        if(toolActive&&EditorBindings.matches(Action.USE_TOOL,event)){if(o().tool==Tool.ANNOTATION&&o().annotationMode.equals("DRAW"))o().annotationPoints.clear();painting=true;ClientState.strokeId=System.nanoTime();lastDragged=null;useQueued=false;if(o().tool==Tool.LASSO_SELECT){lasso.clear();lasso.add(new LassoSelection.Pixel(event.x(),event.y()));}else use();}
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
        Vec3 ray=ClientCompat.ray(camera,(float)(x/minecraft.getWindow().getGuiScaledWidth()*2-1),(float)(1-y/minecraft.getWindow().getGuiScaledHeight()*2));
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
        ClientState.pick(block);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){if(dragBlock!=null&&event.button()==0){finishBlockDrag(event);return true;}if(cameraGesture!=CameraGesture.NONE&&event.button()==cameraButton){if(pendingPick&&!middleDragged)pickBlock(pickedHit);pendingPick=false;cameraGesture=CameraGesture.NONE;cameraButton=-1;return true;}if(event.button()==0&&TransformGizmo.release(this))return true;ReferenceImages.release();if(draggingNode)handlesMoved();draggingNode=false;draggingPlacement=false;dragging=null;if(!draggedPanel.isBlank()){WorkspaceLayout.snap(draggedPanel,w,h);WorkspaceLayout.save();draggedPanel="";}if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&EditorBindings.get(Action.USE_TOOL).mouse()){if(painting&&o().tool==Tool.ANNOTATION&&o().annotationMode.equals("DRAW")&&o().annotationPoints.size()>1)action("annotation_create");if(painting&&sculpts(o().tool))CraftiumClient.send("stroke_end",null,null);painting=false;ClientState.strokeId=0;if(o().tool==Tool.LASSO_SELECT&&!lasso.isEmpty()){o().selectionPoints=new ArrayList<>(LassoSelection.project(lasso));action("lasso_select");o().selectionPoints.clear();lasso.clear();}}return true;}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {
        if(dragBlock!=null){if(!dragMoved&&Math.hypot(event.x()-dragStartX,event.y()-dragStartY)>5)dragMoved=true;return true;}
        if(ReferenceImages.drag(event.x(),event.y()))return true;
        pointerX=event.x();pointerY=event.y();
        if(cameraGesture!=CameraGesture.NONE){
            // A few pixels of wobble on a middle click is still a click, not a pan.
            if(pendingPick&&!middleDragged){if(Math.hypot(event.x()-gestureX,event.y()-gestureY)<=3)return true;middleDragged=true;}
            if(cameraGesture==CameraGesture.RADIUS)o().radius=Math.clamp(initialRadius+(int)Math.round(((event.x()-gestureX)-(event.y()-gestureY))/8),1,BrushMath.MAX_RADIUS);
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
        if(dragging!=null){dragging.update(WorkspaceLayout.inverse(draggingSliderPanel,event.x()/scale,event.y()/scale)[0],minecraft.hasControlDown(),minecraft.hasShiftDown());return true;}
        if(help||transform||menu>=0)return true;
        if(inViewport(event.x()/scale,event.y()/scale)) {
            if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&o().tool==Tool.LASSO_SELECT){if(lasso.size()<1024)lasso.add(new LassoSelection.Pixel(event.x(),event.y()));return true;}
            if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&painting && o().tool!=Tool.BOX_SELECT&&o().tool!=Tool.RULER&&o().tool!=Tool.LINE&&o().tool!=Tool.PATH&&o().tool!=Tool.MODELLING)dragUse();
        }
        return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(help||transform||menu>=0)return true;
        ctxCell=-1;
        if(cameraGesture==CameraGesture.ORBIT&&vertical!=0){ // zoom along the arcball: the camera moves toward its centre
            orbitDistance=Math.clamp(orbitDistance*(1-.15*Math.signum(vertical)),.5,512);
            Vec3 eye=orbitPivot.subtract(minecraft.player.getLookAngle().scale(orbitDistance));
            minecraft.player.setPos(eye.x,eye.y-minecraft.player.getEyeHeight(),eye.z);minecraft.player.setDeltaMovement(Vec3.ZERO);minecraft.player.setOldPosAndRot();
            return true;
        }
        if(panels&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("palette")){paletteScroll=Math.max(0,paletteScroll+(vertical<0?1:-1));return true;} // clamped when drawn
        if(panels&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("rulers")){rulerScroll=Math.max(0,rulerScroll+(vertical<0?1:-1));return true;} // clamped when drawn
        if(panels&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("masks")){maskScroll=Math.max(0,maskScroll+(vertical<0?1:-1));return true;} // clamped when drawn
        if(panels&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("tools")){toolScroll=Math.clamp(toolScroll+(vertical<0?1:-1),0,maxToolScroll());return true;}
        if(panels&&!pinnedTab&&o().tool!=Tool.MODIFY&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("options")){optionsScroll=Math.clamp(optionsScroll+(vertical<0?24:-24),0,scrollLimit);return true;}
        if(panels&&!pinnedTab&&!builder&&o().tool==Tool.MODIFY&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("options")){modifyScroll=Math.max(0,modifyScroll+(vertical<0?1:-1));return true;} // clamped when drawn
        if(panels&&pinnedTab&&!builder&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("options")){
            if(vertical<0&&pinnedMore)pinnedScroll++;else if(vertical>0&&pinnedScroll>0)pinnedScroll--;
            init();return true;
        }
        if(builder){action(vertical>0?"hotbar_previous":"hotbar_next");return true;}
        if(EditorBindings.held(Action.ADJUST_SPEED)){o().flySpeed=Math.clamp(o().flySpeed+(float)vertical*.2f,.1f,10f);action("flight");return true;}
        if(EditorBindings.held(Action.ADJUST_RADIUS)){o().radius=Math.clamp(o().radius+(int)Math.signum(vertical),1,BrushMath.MAX_RADIUS);return true;}
        if(!toolActive){zoomToCursor(x,y,vertical);return true;} // no tool chosen: the wheel is the camera's
        if(ShapeObject.active()&&vertical!=0){ShapeObject.adjustRadius((int)Math.signum(vertical));return true;} // the wheel sizes the shape that is down
        if(TransformGizmo.available(this)){TransformGizmo.nudge(this,EditorControls.nudge(vertical>0?Action.NUDGE_FORWARD:Action.NUDGE_BACK,minecraft.player.getYRot()));return true;}
        zoomToCursor(x,y,vertical);return true; // the tool has nothing for the wheel to do, so it is the camera's
    }
    @Override public boolean charTyped(CharacterEvent event){if(typing!=null){String c=event.codepointAsString();if(c.length()==1&&(Character.isDigit(c.charAt(0))||".-,".indexOf(c.charAt(0))>=0)&&typed.length()<12)typed+=c.equals(",")?".":c;return true;}for(EditBox field:fields.values())if(field.isFocused())return field.charTyped(event);return false;}
    /** Keys currently held, so a repeat of a key already down (a movement key held while Ctrl goes down) is not read as a fresh shortcut such as Ctrl+D. */
    private final java.util.Set<Integer> keysDown=new java.util.HashSet<>();
    @Override public boolean keyReleased(KeyEvent event){keysDown.remove(event.key());return super.keyReleased(event);}
    @Override public boolean keyPressed(KeyEvent event) {
        boolean repeat=!keysDown.add(event.key());
        if(typing!=null){
            if(event.key()==GLFW.GLFW_KEY_ENTER||event.key()==GLFW.GLFW_KEY_KP_ENTER)finishTyping(true);
            else if(event.key()==GLFW.GLFW_KEY_ESCAPE)finishTyping(false);
            else if(event.key()==GLFW.GLFW_KEY_BACKSPACE&&!typed.isEmpty())typed=typed.substring(0,typed.length()-1);
            return true;
        }
        for(EditBox field:fields.values())if(field.isFocused()){
            if(event.key()==GLFW.GLFW_KEY_ESCAPE||event.key()==GLFW.GLFW_KEY_ENTER){field.setFocused(false);return true;}
            return field.keyPressed(event);
        }
        if(confirm!=null){
            if(event.key()==GLFW.GLFW_KEY_ENTER){acceptConfirm();return true;}
            if(event.key()==GLFW.GLFW_KEY_ESCAPE){dismissConfirm();return true;}
        }
        if(ClientState.largeSelection>0){
            if(event.key()==GLFW.GLFW_KEY_ENTER){confirmLargeSelection();return true;}
            if(event.key()==GLFW.GLFW_KEY_ESCAPE){cancelLargeSelection();return true;}
        }
        if(CraftiumClient.editorKey.matches(event)){onClose();return true;}
        if(repeat&&(event.modifiers()&7)!=0)return true; // flying right with D, then pressing Ctrl, must not fire Ctrl+D (Deselect)
        if(event.key()==GLFW.GLFW_KEY_ESCAPE&&pipetting){pipetting=false;return true;}
        if(event.key()==GLFW.GLFW_KEY_ESCAPE){if(ClientState.hasPending())CraftiumClient.send("pending_cancel",null,null);else if(ShapeObject.active())ShapeObject.cancel();else if(PlacementPreview.active())PlacementPreview.cancel();else if(shapeMenu)shapeMenu=false;else if(ctxCell>=0)ctxCell=-1;else if(help)help=false;else if(transform){transform=false;init();}else if(menu>=0)menu=-1;else onClose();return true;}
        if(EditorBindings.matches(Action.HELP,event)){perform(Action.HELP);return true;}
        if(help||transform)return true;
        // Shared chords are resolved by the active context, before ordinary tool letters.
        if((o().tool==Tool.SHAPE||ShapeObject.active())&&!PlacementPreview.active()&&turnShape(event))return true;
        if(PlacementPreview.active()){
            if(EditorBindings.matches(Action.ROTATE_PLACEMENT,event)){action("rotate");return true;}
            if(EditorBindings.matches(Action.FLIP_PLACEMENT,event)){action("flip_facing");return true;}
        }
        if((o().tool==Tool.PATH||o().tool==Tool.MODELLING)&&EditorBindings.matches(Action.EXTRUDE_POINT,event)&&!o().nodes.isEmpty()){
            if(o().nodes.size()>=64){ClientState.notify("Maximum 64 control points");return true;}o().nodes.add(o().nodes.getLast());selectedNode=o().nodes.size()-1;return true;
        }
        if(o().shape==BrushMath.Shape.POLYGON&&o().tool!=Tool.TEXT&&!o().tool.transform()){
            int step=event.key()==GLFW.GLFW_KEY_EQUAL||event.key()==GLFW.GLFW_KEY_KP_ADD?1:event.key()==GLFW.GLFW_KEY_MINUS||event.key()==GLFW.GLFW_KEY_KP_SUBTRACT?-1:0;
            if(step!=0){o().polygonSides=Math.clamp(o().polygonSides+step,BrushMath.MIN_SIDES,BrushMath.MAX_SIDES);return true;}
        }
        for(Action binding:Action.values())if(!EditorBindings.get(binding).mouse()&&EditorBindings.matches(binding,event)&&perform(binding))return true;
        if(event.key()==GLFW.GLFW_KEY_BACKSPACE&&(event.modifiers()&7)==0){perform(Action.DELETE);return true;}
        if((event.modifiers()&7)==0)for(var entry:CraftiumClient.toolKeys.entrySet())if(entry.getValue().matches(event)){select(entry.getKey());return true;}
        return true;
    }
    /**
     * Ctrl+R turns the shape a quarter about the vertical axis, Ctrl+T tilts it a quarter away from the camera and Ctrl+F flips it over
     * a half turn about the camera's line of sight. The camera's direction is snapped to the nearest world axis, so the shape stays on
     * the grid. This works on the shape once it is down and on the hover preview before that.
     */
    private boolean turnShape(KeyEvent event){
        boolean rotate=EditorBindings.matches(Action.ROTATE_PLACEMENT,event),tilt=EditorBindings.matches(Action.TILT_PLACEMENT,event),flip=EditorBindings.matches(Action.FLIP_PLACEMENT,event);
        if(!rotate&&!tilt&&!flip||minecraft.player==null)return false;
        double yaw=Math.toRadians(minecraft.player.getYRot()),x=-Math.sin(yaw),z=Math.cos(yaw);
        Vec3 forward=Math.abs(x)>Math.abs(z)?new Vec3(Math.signum(x),0,0):new Vec3(0,0,Math.signum(z)),up=new Vec3(0,1,0);
        if(rotate)ShapeObject.turnAbout(up,90);else if(tilt)ShapeObject.turnAbout(up.cross(forward),90);else ShapeObject.turnAbout(forward,180);
        return true;
    }
    private boolean perform(Action binding){
        switch(binding){
            case CONFIRM -> {if(ClientState.hasPending())CraftiumClient.send("pending_confirm",null,null);else if(ShapeObject.active())ShapeObject.confirm();else if(PlacementPreview.active())PlacementPreview.confirm();else action(o().tool==Tool.PATH||o().tool==Tool.MODELLING||o().tool==Tool.MODIFY?"use":"clear");}
            case UNDO,UNDO_ALT -> action("undo");
            case DESELECT -> action("clear");
            case TOGGLE_MOVE -> toggleTool(Tool.MOVE);case TOGGLE_ROTATE -> toggleTool(Tool.ROTATE);case TOGGLE_SCALE -> toggleTool(Tool.SCALE);case TOGGLE_PIVOT -> toggleTool(Tool.PIVOT);
case REDO -> action("redo");case COPY -> action("copy");case CUT -> action("cut");case DUPLICATE -> action("duplicate");case PASTE -> action("paste");
            case COPY_AIR -> {boolean old=o().pasteAir;o().pasteAir=true;action("copy");o().pasteAir=old;}
            case DELETE -> {if(deleteRuler()){}else if(ShapeObject.active())ShapeObject.cancel();else if(PlacementPreview.active())PlacementPreview.cancel();else action("delete");}
            case SWAP_BLOCKS -> swapBlocks();
            case QUICK_FILL -> action("fill");case QUICK_REPLACE -> action("replace");
            case ROTATE_PLACEMENT -> {if(!PlacementPreview.active())return false;action("rotate");}
            case FLIP_PLACEMENT -> {if(!PlacementPreview.active())return false;action("flip_facing");}
            case EXTRUDE_POINT -> {if((o().tool!=Tool.PATH&&o().tool!=Tool.MODELLING)||o().nodes.isEmpty())return false;if(o().nodes.size()>=64)ClientState.notify("Maximum 64 control points");else{o().nodes.add(o().nodes.getLast());selectedNode=o().nodes.size()-1;}}
            case SAVE_BLUEPRINT -> {if(ClientState.status.clipboardSize()==0&&ClientState.status.clipboardEntityCount()==0)ClientState.notify("Copy a selection first");else ClientCompat.setScreen(minecraft,new SaveBlueprintScreen(this));}
            case SHOW_BIOMES -> o().showBiomes=!o().showBiomes;
            case CYCLE_SELECTION_STYLE -> {
                var all=Options.SelectionStyle.values();var next=all[(o().selectionStyle.ordinal()+1)%all.length];o().selectionStyle=next;
                ClientState.notify("Selection display: "+next.label);
            }
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
            // Shift flies down, but not with Ctrl held: Ctrl+Shift is a shortcut, like Ctrl+Shift+Z, not a move.
            (CraftiumClient.held(minecraft.options.keyJump)?1:0)-(CraftiumClient.held(minecraft.options.keyShift)&&!minecraft.hasControlDown()?1:0)};
    }
    private boolean cursorHidden;
    /**
     * While a gizmo handle is being dragged the cursor is hidden and locked, so it never meets the edge of the window.
     * GLFW keeps counting the movement past the edge, so the drag carries on as far as the mouse does, and the cursor
     * comes back where it was when the handle was grabbed.
     */
    private void syncCursor(){
        boolean grab=TransformGizmo.dragging()||draggingNode||draggingPlacement||dragging!=null||cameraGesture==CameraGesture.ROTATE||cameraGesture==CameraGesture.ORBIT; // holding the rotate button is a first-person look, and the arcball turns the view round a point
        if(grab==cursorHidden)return;
        cursorHidden=grab;
        GLFW.glfwSetInputMode(minecraft.getWindow().handle(),GLFW.GLFW_CURSOR,grab?GLFW.GLFW_CURSOR_DISABLED:GLFW.GLFW_CURSOR_NORMAL);
    }
    @Override public void tick() {
        if(heldContext){if(!CraftiumClient.held(CraftiumClient.contextKey))onClose();return;}
    }
    /**
     * Flies the camera for the time since the last frame. Moving on game ticks would update the viewport
     * only 20 times a second whatever the Max Framerate setting is, so this runs on every rendered frame
     * at the same 7 blocks per second (0.35 per tick) the tick-based version covered.
     */
    private boolean sprinting,wasMoving;
    private void fly() {
        long now=System.nanoTime();
        double seconds=lastFlightNanos==0?0:Math.min((now-lastFlightNanos)/1e9,.1);
        lastFlightNanos=now;
        var player=minecraft.player;
        if(heldContext||player==null||help||transform||menu>=0||!(player.isCreative()||player.isSpectator()))return;
        if(typing!=null)return;
        for(EditBox field:fields.values())if(field.isFocused())return;
        double[] axes=flightAxes();double forward=axes[0],side=axes[1],up=axes[2];
        boolean moving=forward!=0||side!=0||up!=0,ctrl=minecraft.hasControlDown();
        // Ctrl only sprints when pressed after the player is already moving; pressed first it starts a chord, so it does not move.
        // Holding a gizmo is no chord, so there Ctrl moves the player whichever was pressed first.
        if(!ctrl||!moving)sprinting=false;else if(wasMoving||TransformGizmo.dragging())sprinting=true;
        wasMoving=moving&&(!ctrl||sprinting);
        if(!moving||(ctrl&&!sprinting))return;
        double yaw=Math.toRadians(player.getYRot());
        // Alt slows the camera like sneaking and Ctrl speeds it up like sprinting, both relative to the Fly speed setting.
        double modifier=minecraft.hasAltDown()?.3:sprinting?2.5:1;
        Vec3 direction=new Vec3(-Math.sin(yaw)*forward-Math.cos(yaw)*side,up,Math.cos(yaw)*forward-Math.sin(yaw)*side);
        // Only with the right button held, forward follows where the camera looks, pitch included, like a first-person fly camera.
        if(cameraGesture==CameraGesture.ROTATE&&cameraButton==GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            direction=minecraft.player.getLookAngle().scale(forward).add(new Vec3(-Math.cos(yaw)*side,0,-Math.sin(yaw)*side)).add(0,up,0);
        Vec3 delta=direction.normalize().scale(.35*20*o().flySpeed*modifier*seconds);
        if(!player.getAbilities().flying){player.getAbilities().flying=true;player.onUpdateAbilities();}
        player.setDeltaMovement(Vec3.ZERO);player.setPos(player.position().add(delta));
        player.setOldPosAndRot(); // the renderer interpolates from the previous position, so make it the current one
    }
    private List<Point> handleNodes(){return !toolActive?ClientState.rulerPoints:o().tool==Tool.BOX_SELECT?ClientState.boxCorners:EnumSet.of(Tool.PATH,Tool.MODELLING,Tool.GRADIENT_PAINTER,Tool.SLOPE).contains(o().tool)?o().nodes:new ArrayList<>();}
    double pointerX(){return pointerX;}
    double pointerY(){return pointerY;}
    boolean pointerInViewport(){return inViewport(pointerX/scale,pointerY/scale);}
    public Point handlePoint(){return selectedNode>=0&&selectedNode<handleNodes().size()?handleNodes().get(selectedNode):null;}
    public int handleAxis(){return nodeAxis;}
    /** Which handle point has the gizmo, as its place in the list of handle points, or -1 for none. */
    public int handleIndex(){return selectedNode>=0&&selectedNode<handleNodes().size()?selectedNode:-1;}
    /** The ruler the tool panel shows: the one picked, or else the latest. */
    Ruler currentRuler(){
        var picked=ClientState.selectedRuler();if(picked!=null)return picked;
        var rulers=ClientState.rulers;return rulers.isEmpty()?null:rulers.getLast();
    }
    /** The ruler that owns the end at this place in the list of all ends, or null. */
    private Ruler rulerOfNode(int index){
        int at=index;
        for(var r:ClientState.rulers){int ends=r.complete()?2:1;if(at<ends)return r;at-=ends;}
        return null;
    }
    /** The ruler whose line the pointer is on, within a few pixels, or null. */
    private Ruler rulerAt(double x,double y){
        Ruler found=null;double best=64;
        for(var r:ClientState.rulers){
            if(!r.complete())continue;
            var a=project(r.a,.5);var b=project(r.b,.5);if(a==null||b==null)continue;
            double dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy;
            double t=length<1e-6?0:Math.clamp(((x-a[0])*dx+(y-a[1])*dy)/length,0,1);
            double distance=Math.pow(x-a[0]-t*dx,2)+Math.pow(y-a[1]-t*dy,2);
            if(distance<best){best=distance;found=r;}
        }
        return found;
    }
    /** Takes away the ruler that is picked. */
    private boolean deleteRuler(){
        var picked=ClientState.selectedRuler();if(picked==null)return false;
        ClientState.rulers.remove(picked);ClientState.selectedRuler=null;selectedNode=-1;TransformGizmo.cancel();return true;
    }
    private void clearRulers(){ClientState.rulers.clear();ClientState.selectedRuler=null;selectedNode=-1;TransformGizmo.cancel();}
    private int rulerScroll;
    private static String modeName(Options.RulerMode mode){return mode==Options.RulerMode.CIRCLE?"Circle":"Line";}
    /** The Rulers panel: every ruler in this world as an entry to pick, and what can be done to the picked one. */
    private void rulerPanel(Gfx g){
        if(ClientState.rulers.isEmpty()&&!(toolActive&&o().tool==Tool.RULER))return; // there is nothing to list until a ruler is made
        int x=w-RIGHT-244,y=27; // just left of the right-hand panels, clear of the viewport tools
        panel(g,x,y,236,230,"Rulers");
        var picked=ClientState.selectedRuler();
        int[] halves=flex(pw-20,6,1,1);
        button(g,x+10,y+24,halves[0],19,picked==null?"Mode":"Mode: "+modeName(picked.mode),false,()->{var r=ClientState.selectedRuler();if(r!=null)r.mode=r.mode==Options.RulerMode.LINE?Options.RulerMode.CIRCLE:Options.RulerMode.LINE;},"Change how the picked ruler is drawn: a line, or a line with a circle round its first end");
        button(g,x+16+halves[0],y+24,halves[1],19,"Delete",false,()->deleteRuler(),"Delete the picked ruler");
        int top=y+50,bottom=y+ph-32,rowHeight=18,rows=Math.max(1,(bottom-top)/rowHeight),total=ClientState.rulers.size();
        g.fill(x+8,top-2,x+pw-8,top+rows*rowHeight+1,0xff141c26);g.outline(x+8,top-2,pw-16,rows*rowHeight+3,LINE);
        rulerScroll=Math.clamp(rulerScroll,0,Math.max(0,total-rows));
        if(total==0)text(g,"No rulers yet",x+16,top+5,MUTED);
        for(int i=0;i<rows&&rulerScroll+i<total;i++){
            var r=ClientState.rulers.get(rulerScroll+i);int ry=top+i*rowHeight;boolean chosen=r==picked;
            if(chosen)g.fill(x+9,ry,x+pw-9,ry+rowHeight,0xff315b84);
            String label=(rulerScroll+i+1)+"  ·  "+(r.complete()?r.showSpan():"second end not placed")+(r.complete()?"  ·  "+modeName(r.mode):"");
            text(g,shorten(label,pw-40),x+16,ry+5,chosen?TEXT:MUTED);
            controls.add(new Control(x+9,ry,pw-18,rowHeight,()->{ClientState.selectedRuler=r;selectedNode=-1;TransformGizmo.cancel();},"Pick this ruler · The number is its span: how many blocks it covers along each axis, counting both end blocks, so two touching blocks are 2 and not 1"));
        }
        if(total>rows){ // a thin bar shows where the list is
            int track=rows*rowHeight,thumb=Math.max(10,track*rows/total),thumbY=top+(track-thumb)*rulerScroll/Math.max(1,total-rows);
            g.fill(x+pw-14,top,x+pw-10,top+track,0xff1b2b40);g.fill(x+pw-14,thumbY,x+pw-10,thumbY+thumb,ACCENT);
        }
        button(g,x+10,y+ph-26,pw-20,20,"Clear all",false,this::clearRulers,"Delete every ruler in this world");
    }
    public void moveHandle(Point point){if(selectedNode>=0&&selectedNode<handleNodes().size())handleNodes().set(selectedNode,point);}
    private double[] project(Point p){return project(p,0);}
    private double[] project(Point p,double shift){
        var camera=ClientCompat.camera(minecraft);Vec3 v=new Vec3(p.x()+shift,p.y()+shift,p.z()+shift).subtract(camera.position());
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
    @Override public void removed(){keysDown.clear();pipetting=false;syncCrosshair();TransformGizmo.cancel();draggingNode=draggingPlacement=false;cameraGesture=CameraGesture.NONE;syncCursor();if(hudHidden){if(ClientCompat.hudHidden(minecraft)!=previousHideGui)ClientCompat.toggleHud(minecraft);hudHidden=false;}ClientState.strokeId=0;ClientState.save();super.removed();}
    @Override public void onClose(){ClientState.save();if(ClientState.largeSelection>0)confirmLargeSelection();ClientCompat.setScreen(minecraft,null);if(ClientState.hasPending()&&CraftiumClient.supported())CraftiumClient.send("pending_cancel",null,null);if(CraftiumClient.supported()&&minecraft.player!=null&&minecraft.player.isSpectator())CraftiumClient.send("gamemode_restore",null,null);}
}
