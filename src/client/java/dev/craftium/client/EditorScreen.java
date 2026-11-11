package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import dev.craftium.core.EditorControls.Action;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.client.interaction.EditorContext;
import dev.craftium.client.interaction.Interactions;
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
    private static final int LEFT=182,RIGHT=300; // the right-hand column is 50% wider than its first 200
    private float scale=1;
    private long lastFlightNanos;
    private int w,h,mx,my;
    private boolean builder,help,transform,panels=true,painting,pinnedTab;
    private boolean hudHidden,previousHideGui;
    private int menu=-1;
    /** {@link #menu} while the right-click menu of a row of the Hierarchy is open. */
    private static final int ROW_MENU=99;
    /** The menu bar in order; {@link #menuItems} numbers its cases the same way. */
    private static final String[] MENUS={"File","Edit","Select","View","Create","Structure","Operations","Masks","AI","Window","Help"};
    /** A menu's width in the bar: its name with padding, and room for the status square beside AI while its bridge is on. */
    private int menuWidth(int index){return TextCache.width(font,MENUS[index])+16+(MENUS[index].equals("AI")&&AiBridge.running()?12:0);}
    private long lastUse;
    private double pointerX,pointerY;
    private BlockHitResult lastHit;
    private double[] hitKey;
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
    /** Where an unbounded slider's drag began on the track, and the value it had then. */
    private double sliderStartX,sliderStartValue;
    /** A clickable area. {@code back} is what a right click does, for a control that cycles through values; null for the others. */
    /** A control whose tip opens with this is recorded by its text for tests but shows no tooltip: the rows of the Hierarchy. */
    private static final String HIDDEN_TIP="\u0001";
    private record Control(int x,int y,int width,int height,Runnable action,Runnable back,String tip,String panel,int clipTop,int clipBottom) {
        Control{PanelLog.control(x,y,width,height,tip.startsWith(HIDDEN_TIP)||tip.startsWith(BLOCK_TIP)?tip.substring(1):tip,panel);}
        Control(int x,int y,int width,int height,Runnable action,String tip){this(x,placed(y,height),width,height,action,null,tip,drawingPanel,scrollClipTop,scrollClipBottom);}
        Control(int x,int y,int width,int height,Runnable action,Runnable back,String tip){this(x,placed(y,height),width,height,action,back,tip,drawingPanel,scrollClipTop,scrollClipBottom);}
        boolean contains(double px,double py){if(!WorkspaceLayout.visible(panel))return false;var point=WorkspaceLayout.inverse(panel,px,py);return point[0]>=x&&point[1]>=y&&point[0]<x+width&&point[1]<y+height&&point[1]>=clipTop&&point[1]<clipBottom&&!WorkspaceLayout.hiddenByFold(panel,point[1]);}
    }
    // While a panel's contents scroll, controls are made where they are drawn (shifted) and only answer inside the visible part.
    private static boolean scrollActive;
    private static int scrollShift,scrollContent,scrollClipTop=Integer.MIN_VALUE,scrollClipBottom=Integer.MAX_VALUE;
    /** Where a control drawn at {@code y} really is, noting how far down the scrolling contents reach. */
    /** Whether a row at {@code y} of the scrolling area lies wholly outside what is showing: it is not drawn, but still takes its place in the layout and its controls. */
    private static boolean culled(int y,int height){return scrollActive&&(y+height+scrollShift<=scrollClipTop||y+scrollShift>=scrollClipBottom);}
    private static int placed(int y,int height){if(scrollActive)scrollContent=Math.max(scrollContent,y+height);return y+scrollShift;}
    /** {@code typedMax} is how far a typed value may go; the track itself stops at {@code max}. */
    private record Slider(int x,int width,double min,double max,double typedMax,double def,DoubleConsumer set,boolean unbounded) {
        /** Sets the value for a pointer at {@code px}; Ctrl steps it by 1 and Shift by 0.01. */
        void update(double px,boolean ctrl,boolean shift){
            set.accept(Math.clamp(FlatSlider.step(min+FlatSlider.fractionAt(px,x,width)*(max-min),ctrl,shift),min,max));
        }
        /** An unbounded slider's drag: the value turns from where it was when the drag began by the distance moved, one track width being the whole range, with no end to it. */
        void drag(double px,double startPx,double startValue,boolean ctrl,boolean shift){
            set.accept(FlatSlider.step(startValue+(px-startPx)/(width-4)*(max-min),ctrl,shift));
        }
        void reset(){set.accept(def);}
    }
    /** A slider's clickable area with the slider itself and the key that tells it apart from the others, rebuilt every frame. */
    private record SliderArea(Control control,Slider slider,String key,boolean integer) {}
    private final List<SliderArea> sliderAreas=new ArrayList<>();
    /** The slider whose value is being typed, with the text typed so far; null when none is. */
    private SliderArea typing;private final NumberEntry entry=new NumberEntry();private boolean selectingText;
    private static final Options DEFAULTS=new Options();
    private record MenuItem(String label,String shortcut,Runnable action,List<MenuItem> children) {
        MenuItem(String label,String shortcut,Runnable action){this(label,shortcut,action,List.of());}
    }
    /** The label of the menu entry whose submenu is open, or empty. */
    private String openSub="",openSub2="";
    private final boolean heldContext;
    private enum CameraGesture { NONE,ROTATE,ORBIT,PAN,RADIUS }
    private CameraGesture cameraGesture=CameraGesture.NONE;
    /** Where the player was at the start of the current tick-long stretch of an arcball turn, and when that began; see the orbit gesture. */
    private Vec3 orbitRef;private long orbitRefAt;
    private int cameraButton=-1,initialRadius;private double gestureX,gestureY,orbitDistance;private Vec3 orbitPivot;
    private Tool previousTool=Tool.BOX_SELECT;
    private boolean toolActive=true;
    public boolean toolActive(){return toolActive;}
    /** What the interactions may use of this screen. */
    public EditorContext context(){return context;}
    private boolean showSelection=true;
    public EditorScreen(boolean builder){this(builder,false);}
    public EditorScreen(boolean builder,boolean heldContext){super(Component.literal("Craftium Building Studio"));this.builder=builder;this.heldContext=heldContext;
        if(ClientState.options.tool==Tool.BOX_SELECT&&ClientState.boxCorners.size()==2)selectedNode=1;} // a box left from before: put the gizmo on a corner again, or the selection's own gizmo would stand beside both corner handles
    public boolean showSelection(){return showSelection;}

    private Options o(){return ClientState.options;}
    /** What the per-tool interactions may use of this screen: the options, the block under the cursor, Shift, and which control point is selected. */
    private final EditorContext context=new EditorContext(){
        @Override public Options options(){return o();}
        @Override public BlockHitResult target(){return EditorScreen.this.target();}
        @Override public boolean shiftDown(){return minecraft.hasShiftDown();}
        @Override public void request(String action){action(action);}
        @Override public boolean asking(){return confirm!=null;}
        @Override public void ask(String text,String hint,Runnable action){confirm=new Confirm(text,hint,action);}
        @Override public List<LassoSelection.Pixel> lasso(){return lasso;}
        @Override public int selectedNode(){return selectedNode;}
        @Override public boolean toolActive(){return EditorScreen.this.toolActive;}
        @Override public int handleIndex(){return EditorScreen.this.handleIndex();}
        @Override public void selectNode(int index){selectedNode=index;}
        @Override public boolean hitsPointHandles(Point at,int index,double x,double y){return TransformGizmo.hitsPointHandles(TransformGizmo.nodeAnchor(at,index),x,y);}
    };
    /** The editor lays itself out on at least 1000 by 562 interface units, so on Auto the scale is lowered until that fits. */
    @Override protected int[] canvas(){return new int[]{1000,562};}
    /** Ctrl+= and Ctrl+-: one half step larger or smaller, kept in the preferences and applied at once. */
    private void resizeInterface(int step){
        int now=(int)Math.round(InterfaceScale.pixels(minecraft)*2),next=Math.clamp(now+step,2,InterfaceScale.largest(minecraft)*2);
        if(next==now){ClientState.notify(step>0?"The interface is as large as this window allows ("+InterfaceScale.name(now)+")":"The interface is as small as it goes (1x: one screen pixel per text pixel)");return;}
        ClientState.options.interfaceHalves=next;ClientState.save();ClientState.notify("Interface size: "+InterfaceScale.name(next));
        relayout();
    }
    @Override protected void init() {
        lastFlightNanos=0;
        if(!hudHidden){previousHideGui=ClientCompat.hudHidden(minecraft);hudHidden=true;if(!previousHideGui)ClientCompat.toggleHud(minecraft);}
        scale=1;w=width;h=height; // the interface size is the window's: CraftiumScreen draws at the zoom and the window counts in unitsWorkspaceLayout.fit(w,h,InterfaceScale.legacyCanvas(minecraft));
        fields.clear();renaming=-1;suspended=false;
        int masksW=WorkspaceLayout.width("masks",RIGHT),masksH=WorkspaceLayout.height("masks",masksHeight()),masksY=masksTop();
        layoutKey=layoutKey();
        if(maskLimitsOpen){
            field("minY",w-RIGHT+10,masksY+masksH-39,80,Integer.toString(o().minY),s->{try{o().minY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
            field("maxY",w-RIGHT+masksW-90,masksY+masksH-39,80,Integer.toString(o().maxY),s->{try{o().maxY=Integer.parseInt(s);}catch(NumberFormatException ignored){}});
        }
        if(!builder) {
            if(pinnedTab)pinnedFields();
            else if(o().tool==Tool.NOISE_PAINTER) {
                field("secondary",10,398,optionsWidth()-20,o().secondary,s->o().secondary=s);
                field("seed",10,439,optionsWidth()-20,Long.toString(o().seed),s->{try{o().seed=Long.parseLong(s);}catch(NumberFormatException ignored){}});
            } else if(o().tool==Tool.BIOME_PAINTER || o().tool==Tool.CLENTAMINATOR)
                field("biome",10,382,optionsWidth()-20,o().biome,s->o().biome=s);
            else if(o().tool==Tool.TEXT)field("text",10,326,optionsWidth()-20,o().text,s->o().text=s);
            else if(o().tool==Tool.ROCK)
                field("seed",10,418-lift(),optionsWidth()-20,Long.toString(o().seed),s->{try{o().seed=Long.parseLong(s);}catch(NumberFormatException ignored){}});
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
    /** Tool options fills the left column down to the Target info panel, which sits at the bottom with a gap of 6 between them. */
    private int optionsHeight(){return h-224-(TARGET_HEIGHT+6)-optionsShift();}
    /** The Tools window opens tall enough for every tool: the top bar, the section headings and the rows of tools, with a little room below. */
    private int toolsHeight(){int sum=0;for(var row:toolRows((LEFT-21)/TOOL_PITCH))sum+=row.height();return TOOLS_TOP+sum+6;}
    /**
     * How far Tool options starts below where its contents were designed (199, under a Tools window of 166): the contents are laid out against
     * that top, so the window is moved by this much instead and ends where it did.
     */
    private int optionsShift(){return Math.max(0,27+toolsHeight()+6-199);}
    private static final int TARGET_HEIGHT=82;
    private int optionsWidth(){return WorkspaceLayout.width("options",LEFT);}
    private int optionsBottom(){return 199+WorkspaceLayout.height("options",optionsHeight());}
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
    /** Chooses a tool as a click in the tool list does. Not private, so the game tests can choose one the way a player does. */
    void select(Tool tool){
        cancelSelectionJobs();
        if(tool.transform()&&tool!=Tool.PIVOT&&ClientState.status.selectionSize()==0&&!ShapeObject.active())ClientState.notify("Select blocks first, then use "+tool.label);
        toolActive=true;previousTool=o().tool;selectedNode=-1;Interactions.of(tool).chosen(context);o().tool=tool;o().gizmoTarget=Options.GizmoTarget.AUTO;builder=false;ClientState.save();init();}
    /** Set while a pop-up such as the block picker draws this screen as its backdrop: no flight or cursor handling then. */
    boolean suspended;
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean isInGameUi(){return true;}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta) {
        if(!suspended){fly();syncCursor();}
        BG=o().uiBackground;PANEL=o().uiPanel;LINE=o().uiBorder;ACCENT=o().uiAccent;TEXT=o().uiText;MUTED=o().uiMuted;
        if(!suspended&&layoutKey!=layoutKey())init();
        runAfterStatus();
        pointerX=mouseX;pointerY=mouseY;mx=(int)(mouseX/scale);my=(int)(mouseY/scale);controls.clear();renameAreas.clear();sliderAreas.clear();dragSources.clear();dropTargets.clear();PanelLog.frame();
        syncCrosshair();
        SelectionOverlay.tint(g); // under the panels, so only the world is washed
        if(cameraGesture==CameraGesture.ROTATE&&cameraButton==GLFW.GLFW_MOUSE_BUTTON_RIGHT){g.crosshair(width/2,height/2);} // the first-person look aims with the vanilla crosshair
        g.pose().pushMatrix();g.pose().scale(scale,scale);
        topbar(g);
        if(panels){tools(g);options(g);targetInfo(g);clipboard(g);palette(g);history(g);masks(g);rulerPanel(g);hierarchyPanel(g);magicPanel(g);}
        finishPanel(g);
        viewport(g);
        axisGizmo(g);
        for(int i=1;i<lasso.size();i++)ToolIcons.line(g,(int)(lasso.get(i-1).x()/scale),(int)(lasso.get(i-1).y()/scale),(int)(lasso.get(i).x()/scale),(int)(lasso.get(i).y()/scale),0xffffd66d);
        statusbar(g);
        rowDragGhost(g);
        if(menu==ROW_MENU)rowMenuDraw(g);else if(menu>=0)dropdown(g);
        if(ctxCell>=0)maskMenu(g);
        if(shapeMenu)shapeMenu(g);
        if(help)help(g);
        if(transform)transform(g);
        if((panels||transform) && menu<0 && !help)for(var field:fields.entrySet()) {
            if(transform != (field.getKey().startsWith("offset")||field.getKey().equals("copies")))continue;
            String panel=WorkspaceLayout.fieldPanel(field.getKey());if(!WorkspaceLayout.visible(panel)||WorkspaceLayout.folded(panel))continue;g.pose().pushMatrix();WorkspaceLayout.apply(g,panel);var point=WorkspaceLayout.inverse(panel,mx,my);
            var f=field.getValue();if(!fieldShown(field.getKey(),f)){g.pose().popMatrix();continue;}
            g.fill(f.getX()-3,f.getY()-4,f.getX()+f.getWidth()+3,f.getY()+14,0xff1b2838);
            if(f.isFocused())g.outline(f.getX()-4,f.getY()-5,f.getWidth()+8,20,ACCENT);
            g.widget(f,(int)point[0],(int)point[1],delta);g.pose().popMatrix();
        }
        hoverFeedback(g);
        pipettePreview(g);
        selectionModeBadge(g);
        if(!help&&menu<0&&ctxCell<0&&!shapeMenu&&confirm==null) { // an open menu or pop-up is what the pointer is for, so no tooltip over it
            String tooltip="";for(var c:controls)if(c.contains(mx,my)&&!c.tip.isEmpty()&&!c.tip.startsWith(HIDDEN_TIP)&&!c.tip.startsWith(BLOCK_TIP))tooltip=c.tip;
            if(!tooltip.isEmpty()) {
                // A new line starts a new paragraph; each is wrapped to the pane, and a pane with a title shows it brighter than what follows.
                var pane=tooltipPane(tooltip);var rows=pane.rows();
                int tw=Math.min(320,pane.widest()+14),th=rows.size()*10+12,tx=Math.min(mx+14,w-tw-4),ty=Math.min(my+18,h-th-12);
                g.fill(tx,ty,tx+tw,ty+th,0xf5222d3b);g.outline(tx,ty,tw,th,LINE);
                for(int i=0;i<rows.size();i++)g.text(font,rows.get(i),tx+6,ty+6+i*10,pane.paragraphs()>1&&i>=pane.firstRows()?MUTED:TEXT,false);
            }
        }
        if(dragBlock!=null&&dragMoved){
            for(int i=dropTargets.size()-1;i>=0;i--){var t=dropTargets.get(i).area();if(t.contains(mx,my)){g.pose().pushMatrix();WorkspaceLayout.apply(g,t.panel());g.outline(t.x(),t.y(),t.width(),t.height(),ACCENT);g.pose().popMatrix();break;}} // the cell the block would land in
            BlockDragCursor.draw(g,font,dragBlock,mx,my,w,h,ACCENT,inViewport(mx,my));}else BlockDragCursor.release();
        g.pose().popMatrix();ReferenceImages.draw(g,true,mouseX,mouseY);
    }
    /** A tooltip laid out: its wrapped rows, how many belong to the first paragraph, and the widest row. */
    private record TooltipPane(String text,List<net.minecraft.util.FormattedCharSequence> rows,int firstRows,int paragraphs,int widest){}
    private TooltipPane tooltipPane;
    /** The pane for a tooltip, laid out once while the pointer stays on the same control rather than on every frame. */
    private TooltipPane tooltipPane(String tooltip){
        var pane=tooltipPane;
        if(pane!=null&&pane.text().equals(tooltip))return pane;
        // A new line starts a new paragraph; each is wrapped to the pane, and a pane with a title shows it brighter than what follows.
        String[] paragraphs=tooltip.split("\n");var rows=new ArrayList<net.minecraft.util.FormattedCharSequence>();var firstRows=0;int widest=0;
        for(int i=0;i<paragraphs.length;i++){
            var split=TextCache.split(font,paragraphs[i],306);
            if(i==0)firstRows=split.size();
            for(var row:split){rows.add(row);widest=Math.max(widest,font.width(row));}
        }
        return tooltipPane=new TooltipPane(tooltip,List.copyOf(rows),firstRows,paragraphs.length,widest);
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
            if(menu==ROW_MENU&&i<rowMenuFrom)return; // an open menu is what the pointer is on: nothing behind it takes the hover look
            if(!c.contains(mx,my))continue;
            if(c.tip.startsWith(BLOCK_TIP))return; // the menu's own surface
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
            if(!panelScissor&&WorkspaceLayout.folded(id)){g.enableScissor(x,y,x+width,y+WorkspaceLayout.TITLE);panelScissor=true;} // folded: only the title bar shows
        }
        pw=width;ph=height;pb=y+height;
        boolean folded=!id.isBlank()&&WorkspaceLayout.folded(id);int shown=folded?WorkspaceLayout.TITLE:height;
        g.fill(x,y,x+width,y+shown,BG);g.outline(x,y,width,shown,LINE);
        g.fill(x+1,y+1,x+width-1,y+18,0xff202a37);g.fill(x+1,y+1,x+3,y+18,ACCENT);
        text(g,folded?"▸":"▾",x+7,y+6,MUTED);text(g,title,x+17,y+6,TEXT);
        if(!id.isBlank()){text(g,"×",x+width-12,y+5,MUTED);controls.add(new Control(x+width-17,y+2,15,14,()->WorkspaceLayout.toggle(id),""));controls.add(new Control(x+3,y+2,14,14,()->WorkspaceLayout.fold(id),folded?"Unfold this window":"Fold this window up to its title bar"));if(!folded){boolean grip=resizeGripHovered(id);g.fill(x+width-6,y+height-6,x+width-2,y+height-2,grip||id.equals(draggedPanel)&&resizePanel?ACCENT:LINE);}}
    }
    /** The size of the panel being drawn, and where its bottom edge is; contents lay out against these, never against fixed sizes. */
    private int pw,ph,pb;
    private boolean resizeGripHovered(String id){var r=WorkspaceLayout.windows.get(id);return r!=null&&r.visible&&!r.folded&&draggedPanel.isBlank()&&mx>r.x+r.w-12&&mx<r.x+r.w&&my>r.y+r.h-12&&my<r.y+r.h;}
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
        panel(g,0,27,LEFT,toolsHeight(),"Tools");
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
                g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(.82f,.82f);Icons.draw(g,tool.spec().iconId(),0,0,selected?0xffffffff:0xffd0deeb);g.pose().popMatrix();
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
        WorkspaceLayout.shift("options",optionsShift());
        panel(g,0,199,LEFT,optionsHeight(),builder?"Builder controls":"Tool options");
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
        scrolled(g,262,o().tool==Tool.BOX_SELECT||o().tool==Tool.MAGIC_SELECT?100:o().tool.selection()?33:6,o().tool==Tool.MAGIC_SELECT?0:TOOL_NAME_GAP,()->toolBody(g));
    }
    private static final int TOOL_NAME_GAP=36;
    /** The block limit slider: 1 to {@link Options#MAX_BLOCK_LIMIT}, at the very bottom of the options of every tool that builds, removes or moves blocks. */
    private void blockLimit(Gfx g,int y){
        slider(g,10,y,pw-20,"Block limit",o().blockLimit,1,Options.MAX_BLOCK_LIMIT,DEFAULTS.blockLimit,v->o().blockLimit=(int)Math.clamp(Math.round(v),1,Options.MAX_BLOCK_LIMIT),true);
    }
    private void toolBody(Gfx g) {
        toolOptions(g);
        if(sizeAtEnd())sizeControls(g,scrollContent+34); // the tools with their own fixed layouts keep the shape's size at the bottom
        if(o().tool.placesBlocks())blockLimit(g,scrollContent+24);
    }
    /** The settings a tool declares for itself, as a form at the top of the options area. A tool without any keeps the built-in controls below. */
    private boolean propertyForm(Gfx g) {
        var spec=o().tool.spec();
        if(spec.properties().isEmpty())return false;
        var values=ClientState.toolProperties.computeIfAbsent(spec.id(),id->new PropertyValues(spec.properties()));
        PropertyForm.draw(new PropertyForm.PanelBuilder(){
            @Override public void slider(int x,int y,int width,String label,double value,double min,double max,double start,DoubleConsumer set,boolean integer){EditorScreen.this.slider(g,x,y,width,label,value,min,max,start,set,integer);}
            @Override public void toggle(int x,int y,String label,boolean value,Runnable action){EditorScreen.this.toggle(g,x,y,label,value,action);}
            @Override public void cycle(int x,int y,int width,int height,String label,Runnable next,Runnable previous){button(g,x,y,width,height,label,false,next,previous,"");}
        },values,10,311,pw-20);
        return true;
    }
    private void toolOptions(Gfx g) {
        if(propertyForm(g))return;
        if(o().tool==Tool.MAGIC_SELECT)MagicSelectOptions.directions(magicHost(g),o(),10,266,pw-20);
        if(o().tool==Tool.PAINT_BUCKET){ // the Magic Wand's search settings, without the selection mode: the bucket fills what it finds
            int used=MagicSelectOptions.draw(magicHost(g),o(),10,311,pw-20,MagicSelectOptions.SEARCH_ONLY);
            toggle(g,10,311+used+8,"Surface only",o().surfaceOnly,()->o().surfaceOnly=!o().surfaceOnly,"Fill only blocks that touch air");
            wrap(g,"Click a block to fill every connected block of its type with your foreground block. Directions and select type are the Magic Wand's.",10,311+used+34,pw-20,MUTED,4);
            return;
        }
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
                modeButtons(g,311);
                if(o().tool==Tool.MAGIC_SELECT){
                    MagicSelectOptions.type(magicHost(g),o(),10,361,pw-20);
                    toggle(g,10,391,"Surface only",o().surfaceOnly,()->o().surfaceOnly=!o().surfaceOnly);
                    toggle(g,10,409,"Hover preview (under "+o().hoverPreviewLimit+")",o().magicPreview,()->o().magicPreview=!o().magicPreview);
                    toggle(g,10,427,"Select liquids",o().selectLiquids,()->o().selectLiquids=!o().selectLiquids,"Aim at and select water and lava; off, the wand passes over them");
                }
                // The bounds are only information, so they sit at the very bottom, over the Fill and Clear buttons.
                fixed(g,()->{
                    text(g,"SELECTION BOUNDS",10,pb-96,MUTED);
                    text(g,"Min  "+s.min().coordinates(),10,pb-84,TEXT);text(g,"Max  "+s.max().coordinates(),10,pb-72,TEXT);
                    text(g,s.selectionSize()+" blocks",10,pb-60,0xffa5cef2);
                    fillClear(g);
                });
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
            var use=o().tool.brushUse();
            if(o().tool.picksShape())shapeSelector(g,305);
            // The track stops at 64 to stay usable; a bigger radius is typed in. Shapes are limited by their block count and reach, not by this.
            if(use!=Tool.BrushUse.NONE)slider(g,10,347-lift(),pw-20,use==Tool.BrushUse.SMALL_SHAPE?"Radius (shape up to 4)":radiusLabel(),o().radius,1,Math.min(64,dev.craftium.core.ShapeLimits.maxRadius(o())),dev.craftium.core.ShapeLimits.maxRadius(o()),DEFAULTS.radius,v->o().radius=(int)Math.round(v),true);
            o().outlineThickness=Math.clamp(o().outlineThickness,1,o().radius); // a wall is never thicker than the radius
            // A polygon's sides take the row under the radius, and everything below moves down a row. The track stops at 64; more is typed.
            if(sidesShown())
                slider(g,10,375,pw-20,o().shape==BrushMath.Shape.STAR?"Points":"Sides",o().polygonSides,BrushMath.MIN_SIDES,BrushMath.SLIDER_SIDES,BrushMath.MAX_SIDES,BrushMath.DEFAULT_SIDES,v->o().polygonSides=Math.clamp((int)Math.round(v),BrushMath.MIN_SIDES,BrushMath.MAX_SIDES),true);
            if(extraShown()&&o().shape==BrushMath.Shape.IRREGULAR_POLYGON) // which irregular polygon these sides make: the same seed gives the same one
                slider(g,10,403,pw-20,"Seed",o().polygonSeed,0,999,99_999,DEFAULTS.polygonSeed,v->o().polygonSeed=(int)Math.clamp(Math.round(v),0,99_999),true);
            if(extraShown()&&o().shape==BrushMath.Shape.STAR) // how far in the notches between the points go
                slider(g,10,403,pw-20,"Inner radius",o().starInner,.1,.9,DEFAULTS.starInner,v->o().starInner=Math.clamp(Math.round(v*100)/100d,.1,.9),false);
            if(sizeInline())sizeControls(g,375+sidesBlock()); // the shape's size, under the radius and the polygon's sides
            if(o().tool==Tool.EXTRUDE) // layers, 1 to 64
                slider(g,10,375+sidesRow()-lift(),pw-20,"Count",o().extrudeCount,1,Options.MAX_EXTRUDE_COUNT,Options.MAX_EXTRUDE_COUNT,DEFAULTS.extrudeCount,v->o().extrudeCount=(int)Math.clamp(Math.round(v),1,Options.MAX_EXTRUDE_COUNT),true);
            else if(hasStrength())
                slider(g,10,375+sidesRow()-lift(),pw-20,"Strength",o().strength,.1,5,DEFAULTS.strength,v->o().strength=v,false);
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
                text(g,"Noise seed",10,405-lift(),MUTED);
                slider(g,10,460-lift(),pw-20,"Noise scale",o().noiseScale,1,64,DEFAULTS.noiseScale,v->o().noiseScale=v,false);
            }
            default -> {
                // Rows follow one another: the slot of a Strength slider is only left open for the tools that have one, and Fill, which belongs to the shape, only for the tools that pick one.
                int row=sidesRow()-lift()+(o().tool==Tool.SMOOTH?smoothRows(g,375+sidesRow()):hasStrength()?0:-28);
                if(o().tool==Tool.FLOODFILL){ // Floodfill has no brush, so its spread takes the room where the radius would be
                    floodButtons(g,403+row);row+=48;
                    slider(g,10,403+row+10,pw-20,"Gap tolerance",o().floodGap,0,8,Options.MAX_FLOOD_GAP,DEFAULTS.floodGap,v->o().floodGap=(int)Math.clamp(Math.round(v),0,Options.MAX_FLOOD_GAP),true);row+=38;
                }
                int fillRow=o().tool.picksShape()?23:0;
                if(o().tool.picksShape())fillButtons(g,403+row);
                toggle(g,10,403+fillRow+row,"Surface only",o().surfaceOnly,()->o().surfaceOnly=!o().surfaceOnly);
                int next=428+fillRow+row;
                if(o().tool==Tool.EXTRUDE){toggle(g,10,next,"Into grass and water",o().extrudeReplaceable,()->o().extrudeReplaceable=!o().extrudeReplaceable,"Grow into replaceable blocks like grass and water, replacing them, instead of stopping at them");next+=22;
                    toggle(g,10,next,"Smart extrude",o().smartExtrude,()->o().smartExtrude=!o().smartExtrude,"Take in the whole family of the clicked block, stairs, slabs and walls of the same material, each block keeping its own shape");next+=22;}
                if(o().hollow&&o().tool.picksShape()){ // the outline's own settings sit under the others, so they only take room while Hollow is chosen
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
                rows.add(sliderRow(w,"Copies",o.copies,1,16,64,DEFAULTS.copies,v->o.copies=(int)Math.max(1,Math.round(v)),true));
                rows.add(sliderRow(w,"Offset X",o.offsetX,-64,64,64,0,v->o.offsetX=(int)Math.round(v),true));
                rows.add(sliderRow(w,"Offset Y",o.offsetY,-64,64,64,DEFAULTS.offsetY,v->o.offsetY=(int)Math.round(v),true));
                rows.add(sliderRow(w,"Offset Z",o.offsetZ,-64,64,64,0,v->o.offsetZ=(int)Math.round(v),true));
                rows.add(new ModifyRow(22,(gg,y)->toggle(gg,10,y+2,o.modifyRelative?"Relative (x selection size)":"Absolute (blocks)",o.modifyRelative,()->o.modifyRelative=!o.modifyRelative,"Relative offsets are multiplied by the size of the selection; absolute offsets are not")));
            }
            case REVOLVE -> {
                rows.add(axisRow());
                rows.add(sliderRow(w,"Angle",o.modifyAngle,-360,360,Double.POSITIVE_INFINITY,DEFAULTS.modifyAngle,v->o.modifyAngle=Math.round(v*10)/10d,false,true));
                translationRows(rows,w);
            }
            case ROTATE_COPIES -> {
                boolean custom=o.modifyType==Options.RotateType.CUSTOM_ANGLE;
                Runnable flip=()->o.modifyType=custom?Options.RotateType.EQUIDISTANT:Options.RotateType.CUSTOM_ANGLE;
                rows.add(new ModifyRow(26,(gg,y)->button(gg,10,y,pw-32,20,"Type: "+(custom?"Custom Angle":"Equidistant"),false,flip,flip,"Equidistant spreads copies around the anchor; Custom Angle turns each copy by the angle")));
                rows.add(axisRow());
                rows.add(sliderRow(w,"Copies",o.copies,1,16,64,DEFAULTS.copies,v->o.copies=(int)Math.max(1,Math.round(v)),true));
                if(custom)rows.add(sliderRow(w,"Angle",o.modifyAngle,-360,360,Double.POSITIVE_INFINITY,DEFAULTS.modifyAngle,v->o.modifyAngle=Math.round(v*10)/10d,false,true));
                translationRows(rows,w);
            }
            case TWIST -> {
                rows.add(axisRow());
                rows.add(sliderRow(w,"Angle",o.modifyAngle,-360,360,Double.POSITIVE_INFINITY,DEFAULTS.modifyAngle,v->o.modifyAngle=Math.round(v*10)/10d,false,true));
            }
        }
        rows.add(sliderRow(w,"Block limit",o.blockLimit,1,Options.MAX_BLOCK_LIMIT,Options.MAX_BLOCK_LIMIT,DEFAULTS.blockLimit,v->o.blockLimit=(int)Math.clamp(Math.round(v),1,Options.MAX_BLOCK_LIMIT),true));
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
        button(g,10,pb-53,pw-20,20,"Edit anchor",ModifyPreview.knob(),()->{boolean on=ModifyPreview.editAnchor(SelectionOverlay.points().isEmpty()?null:SelectionOverlay.points().getFirst());selectedNode=on?0:-1;if(!on)TransformGizmo.cancel();},"Turn the anchor into a knob the Move gizmo can carry · click again to let the preview follow the cursor");
        button(g,10,pb-27,pw-20,20,"Apply (Enter)",false,()->action("use"),"Build the previewed result");
    }
    private ModifyRow sliderRow(int w,String label,double value,double min,double max,double typedMax,double def,java.util.function.DoubleConsumer set,boolean integer){return sliderRow(w,label,value,min,max,typedMax,def,set,integer,false);}
    /** {@code unbounded}: dragging turns the value by the distance moved, as far as it goes, instead of setting it from where the pointer is on the track. */
    private ModifyRow sliderRow(int w,String label,double value,double min,double max,double typedMax,double def,java.util.function.DoubleConsumer set,boolean integer,boolean unbounded){
        return new ModifyRow(28,(g,y)->slider(g,10,y+10,w,label,value,min,max,typedMax,def,set,integer,unbounded));
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
        rows.add(sliderRow(w,"Translate X",o.modifyTranslateX,-64,64,64,0,v->o.modifyTranslateX=Math.round(v*100)/100d,false));
        rows.add(sliderRow(w,"Translate Y",o.modifyTranslateY,-64,64,64,0,v->o.modifyTranslateY=Math.round(v*100)/100d,false));
        rows.add(sliderRow(w,"Translate Z",o.modifyTranslateZ,-64,64,64,0,v->o.modifyTranslateZ=Math.round(v*100)/100d,false));
        if(!ClientHandshake.serverHas(Handshake.MODIFY_EXPANSION)){rows.add(new ModifyRow(22,(g,y)->text(g,"Expansion needs a server update",10,y+4,MUTED)));return;}
        rows.add(sliderRow(w,"Expansion",o.modifyTranslateScale,ModifyMath.MIN_EXPANSION,ModifyMath.MAX_EXPANSION,ModifyMath.MAX_EXPANSION,1,v->o.modifyTranslateScale=Math.round(v*100)/100d,false));
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
        toggle(g,10,457,"Sticky flight",o().stickyFlight,()->o().stickyFlight=!o().stickyFlight,"Slows you to a stop as you fly up to a wall; let go and move again to bump into it, or pass through it with No clip");
        button(g,10,480,pw-20,20,"Reach: "+o().reach.label,false,()->o().reach=Options.ReachMode.values()[(o().reach.ordinal()+1)%Options.ReachMode.values().length],()->o().reach=Options.ReachMode.values()[Math.floorMod(o().reach.ordinal()-1,Options.ReachMode.values().length)],"How far you can reach blocks: normal, far (set below) or infinite · right click goes back");
        slider(g,10,517,pw-20,"Far reach: blocks",o().farReach,6,64,DEFAULTS.farReach,v->o().farReach=(int)Math.round(v),true);
        slider(g,10,553,pw-20,"Fly speed",o().flySpeed,.1,10,DEFAULTS.flySpeed,v->o().flySpeed=(float)v,false);
        button(g,10,577,pw-20,20,"Apply flight speed",false,()->action("flight"),"");
        int[] pair=flex(pw-20,6,1,1);
        button(g,10,603,pair[0],20,"Hotbar ↔",false,()->action("hotbar"),"Swap the hotbar with inventory row 1 · Z");
        button(g,16+pair[0],603,pair[1],20,"Game mode",false,()->action("gamemode"),"");
        button(g,10,631,pw-20,20,"Builder tool: "+o().builderTool.name(),false,()->{o().builderTool=Options.BuilderTool.values()[(o().builderTool.ordinal()+1)%Options.BuilderTool.values().length];},()->{o().builderTool=Options.BuilderTool.values()[Math.floorMod(o().builderTool.ordinal()-1,Options.BuilderTool.values().length)];},"Press 0 in the world · Alt + wheel changes tools");
    }
    private void targetInfo(Gfx g) {
        int x=0,y=h-25-TARGET_HEIGHT; // the bottom of the left column, under Tool options
        panel(g,x,y,LEFT,TARGET_HEIGHT,"Target info");var hit=target();
        if(hit!=null&&minecraft.level!=null){var p=hit.getBlockPos();text(g,p.getX()+"  /  "+p.getY()+"  /  "+p.getZ(),x+10,y+26,TEXT);text(g,shorten(BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(p).getBlock()).toString(),pw-20),x+10,y+42,MUTED);}
        else text(g,"Point at a block",x+10,y+26,MUTED);
        var shown=SelectionOverlay.outline().fallback();text(g,shorten(shown==null?ClientState.status.selectionSize()+" blocks selected":ClientState.status.selectionSize()+" blocks · "+shown,pw-20),x+10,y+62,0xffa5cef2);
    }
    private void clipboard(Gfx g) {
        int x=w-RIGHT-488,y=27; // off until it is opened from the Window menu; where the Selections window used to float
        panel(g,x,y,RIGHT,101,"Clipboard");
        text(g,ClientState.status.clipboardSize()==0?"No copied blocks":ClientState.status.clipboardSize()+" blocks ready to place",x+10,y+26,MUTED);
        int[] top=flex(pw-20,6,1,1,1),bottom=flex(pw-20,4,44,44,84);
        button(g,x+10,y+44,top[0],20,"Copy",false,()->action("copy"),"Copy selection · Ctrl+C");
        button(g,x+16+top[0],y+44,top[1],20,"Cut",false,()->action("cut"),"Cut selection · Ctrl+X");
        button(g,x+22+top[0]+top[1],y+44,top[2],20,"Paste",false,()->action("paste"),"Paste at cursor · Ctrl+V");
        button(g,x+10,y+71,bottom[0],20,"Rotate",false,()->action("rotate"),"Rotate clipboard 90° · Ctrl+R");
        button(g,x+14+bottom[0],y+71,bottom[1],20,"Mirror",false,()->action("flip"),"Mirror clipboard on X · Ctrl+F");
        button(g,x+18+bottom[0]+bottom[1],y+71,bottom[2],20,"Paste in place",false,PlacementPreview::beginInPlace,"Start a placement at the coordinates the blocks were copied from · Ctrl+Shift+V · Enter builds it");
    }
    private void palette(Gfx g) {
        int x=w-RIGHT,y=groupTop(),height=Math.max(105,(WorkspaceLayout.visible("masks")?masksTop()-6:h-25)-y);
        panel(g,x,y,RIGHT,height,"Block palette"); // the top of the palette and masks group, down to the Tool masks
        dropArea(x,y,RIGHT,height,this::dropInPalette); // a block dropped anywhere on the panel joins the palette
        // Drawn back to front, like a paint tool: the foreground sits on top of the background.
        handSlot(g,x+10,y+26);
        blockSlot(g,x+70,y+42,"Background block",o().secondary,id->o().secondary=id);
        blockSlot(g,x+50,y+26,"Foreground block",o().block,id->ClientState.pick(id));
        transparencyToggle(g,x+51,y+58);
        pipetteControls(g,x+110,y+26);
        // The cells flow across the panel and down as far as it reaches; the first is always "+", which adds a block to the palette
        // and makes it the foreground. What does not fit scrolls.
        var blocks=ClientState.palette;int total=blocks.size()+1,top=y+76,pitch=26,visibleRows=Math.max(1,(pb-6-top+3)/pitch);
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
            dragSource(new Control(bx,by,23,23,()->ClientState.pick(block),()->openBlockMenu(BlockList.PALETTE,index),block+" · Click to set the foreground · Drag into the world to bucket fill, or to another slot · Right-click for options"),block);
        }
        if(bar){
            int track=visibleRows*pitch-3,thumb=Math.max(10,track*visibleRows/rows),thumbY=top+(track-thumb)*paletteScroll/Math.max(1,limit),barX=x+pw-11,max=limit;
            g.fill(barX,top,barX+5,top+track,0xff1b2838);g.fill(barX,thumbY,barX+5,thumbY+thumb,0xff4a6585);
            controls.add(new Control(barX-2,top,9,track,()->{double py=WorkspaceLayout.inverse("palette",mx,my)[1];paletteScroll=Math.clamp((int)Math.round((py-top-thumb/2.0)/Math.max(1,track-thumb)*max),0,max);},""));
        }
    }
    /** Turns transparency on or off: while on, the foreground paints as air, and the slot keeps showing the block it will go back to. */
    private void toggleTransparency(){
        o().transparency=!o().transparency;ClientState.save();
        ClientState.notify("Transparency "+(o().transparency?"on: the foreground paints as air":"off"));
    }
    /** The transparency toggle under the foreground slot, a small checkerboard that lights up while it is on. */
    private void transparencyToggle(Gfx g,int x,int y){
        boolean on=o().transparency;
        FlatButton.face(g,x,y,18,16,"",on,true,false);
        int light=on?0xffffffff:0xffd0deeb,dark=on?0xff6f87a3:0xff4a5f78;
        for(int r=0;r<2;r++)for(int c=0;c<2;c++)g.fill(x+4+c*5,y+3+r*5,x+9+c*5,y+8+r*5,(r+c)%2==0?light:dark);
        controls.add(new Control(x,y,18,16,this::toggleTransparency,"Transparency · T · "+(on?"on: the foreground paints as air":"off: click to paint as air")+", the slot keeps the block"));
    }
    /** Whether the next click on the world takes the block it lands on into the palette, and the block the pointer is over meanwhile. */
    private boolean pipetting;
    private String pipetteBlock="";
    /** The pipette button, an icon that lights up while the pipette is in hand. */
    private void pipetteControls(Gfx g,int x,int y){
        FlatButton.face(g,x,y,30,30,"",pipetting,true,false);
        int color=pipetting?0xffffffff:0xffd0deeb;
        Icons.draw(g,IconCatalog.PIPETTE,x,y,color);
        controls.add(new Control(x,y,30,30,()->pipetting=!pipetting,"Click a block in the world to take it as the foreground · Ctrl + click adds it to the palette and keeps the pipette · Esc cancels"));
    }
    /**
     * On the pointer's bottom-right, while a selection tool is over the world: a small icon of how the next selection combines with the current one. Replace is one
     * square, add two joined squares, subtract a square with a cut out of it, and intersect two outlines with only where they meet filled.
     */
    private void selectionModeBadge(Gfx g){
        if(!toolActive||!o().tool.selection()||pipetting||menu>=0||help||transform||TransformGizmo.dragging()||!inViewport(pointerX/scale,pointerY/scale))return;
        // As big as the pointer, which is about 16 screen pixels across and centred on the point it aims at: the badge's top-left corner sits on the pointer's bottom-right.
        double cursor=16.0*minecraft.getWindow().getGuiScaledWidth()/Math.max(1,minecraft.getWindow().getScreenWidth())/scale;
        int size=Math.max(8,(int)Math.round(cursor)),x=Math.clamp(mx+(int)Math.round(cursor/2),0,w-size-2),y=Math.clamp(my+(int)Math.round(cursor/2),25,h-size-24);
        g.fill(x,y,x+size+2,y+size+2,0xd01b2838);
        SelectionModeIcon.draw(g,o().mode,x+1,y+1,size,0xffe6eef7);
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
    private long crosshairCursor,handCursor;private int cursorShown;
    /** Whether the chosen tool shows a preview where the pointer is: the selection picks, extrude and flood fill, and the brush tools. */
    private boolean hoverPreviewTool(){
        if(!toolActive)return false;
        Tool t=o().tool;
        return t==Tool.MAGIC_SELECT||t==Tool.BOX_SELECT||t==Tool.EXTRUDE||t==Tool.FLOODFILL||t==Tool.PAINT_BUCKET||!t.selection()&&!t.transform()&&t.brushUse()!=Tool.BrushUse.NONE;
    }
    /** Over the world with the pipette in hand, or a tool that previews what is under the pointer, the pointer is a crosshair. */
    private void syncCrosshair(){
        boolean over=ClientCompat.screen(minecraft)==this&&!suspended&&menu<0&&!help&&!transform&&inViewport(pointerX/scale,pointerY/scale);
        boolean grab=over&&gizmoHover(),want=over&&(pipetting||hoverPreviewTool());
        if((want||grab)&&ClientState.selecting())return; // the spinner has the cursor while a big selection is worked out
        int mode=grab?2:want?1:0; // a hand over a gizmo handle, a crosshair over the world for the tools that preview
        if(mode==cursorShown&&mode==0)return;
        cursorShown=mode;
        if(mode==1&&crosshairCursor==0)crosshairCursor=GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
        if(mode==2&&handCursor==0)handCursor=GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        GLFW.glfwSetCursor(minecraft.getWindow().handle(),mode==2?handCursor:mode==1?crosshairCursor:0L);
    }
    /** Gives the pointer back as the ordinary arrow for whatever screen comes next: a cursor set here stays on the window until something resets it. */
    private void releaseCursor(){
        cursorShown=0;
        if(minecraft==null||ClientState.selecting())return; // the spinner owns the pointer until the selection is worked out, and puts the arrow back itself
        GLFW.glfwSetCursor(minecraft.getWindow().handle(),0L);
    }
    /** Whether the pointer is over a handle of a gizmo: the one being worked on, or the other corner's of a box selection. */
    private boolean gizmoHover(){
        if(TransformGizmo.hovering())return true;
        if(draggingNode||TransformGizmo.dragging()||o().tool!=Tool.BOX_SELECT||ClientState.boxCorners.size()!=2)return false;
        for(int i=0;i<2;i++)if(i!=selectedNode&&TransformGizmo.hitsPointHandles(TransformGizmo.cornerAnchor(i),pointerX,pointerY))return true;
        return false;
    }
    /** A 30px swatch for one of the two selected blocks; clicking it opens the block chooser to replace it. */
    /** The block in the player's main hand, as it changes; empty when the hand holds no block. Click to make it the foreground, or drag it into the world to bucket fill. */
    private void handSlot(Gfx g,int x,int y) {
        String block=minecraft.player!=null&&minecraft.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem item?BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString():"";
        g.fill(x,y,x+30,y+30,0xff1b2838);g.outline(x,y,30,30,LINE);
        dropArea(x,y,30,30,this::dropInHand);
        text(g,"Hand",x+3,y+32,MUTED);
        if(block.isEmpty()){controls.add(new Control(x,y,30,30,()->{},"Hand · no block held"));return;}
        BlockPickerScreen.icon(g,font,block,x+7,y+7);
        dragSource(new Control(x,y,30,30,()->{ClientState.pick(block);ClientState.save();},"Hand · "+block+" · Click to set the foreground · Drag into the world to bucket fill"),block);
    }
    private void blockSlot(Gfx g,int x,int y,String label,String block,java.util.function.Consumer<String> set) {
        g.fill(x,y,x+30,y+30,0xff1b2838);g.outline(x,y,30,30,label.startsWith("Fore")?ACCENT:LINE);
        dropArea(x,y,30,30,id->{set.accept(id);ClientState.save();});
        BlockPickerScreen.icon(g,font,block,x+7,y+7);
        if(o().transparency&&label.startsWith("Fore")){ // painting as air: the block stays in view, faded behind a checkerboard
            g.fill(x+1,y+1,x+29,y+29,0x99141c26);
            for(int r=0;r<6;r++)for(int c=0;c<6;c++)if((r+c)%2==0)g.fill(x+1+c*5,y+1+r*5,x+6+c*5,y+6+r*5,0x40ffffff);
        }
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
    /** Where a dragged block cell can be dropped: the hand, the two blocks, the palette and the tool masks. Rebuilt with the panels each frame. */
    private record DropTarget(Control area,java.util.function.Consumer<String> accept){}
    private final List<DropTarget> dropTargets=new ArrayList<>();
    private void dropArea(int x,int y,int width,int height,java.util.function.Consumer<String> accept){dropTargets.add(new DropTarget(new Control(x,y,width,height,()->{},""),accept));}
    /** Drops the block on the cell under the pointer (raw window coordinates), if there is one. Also used by the block chooser, whose cells can be dragged here. */
    boolean dropBlock(double rawX,double rawY,String block){
        double px=rawX/scale,py=rawY/scale;
        for(int i=dropTargets.size()-1;i>=0;i--){var t=dropTargets.get(i);if(t.area().contains(px,py)){t.accept().accept(block);return true;}}
        return false;
    }
    private void dropInPalette(String block){
        if(block.isEmpty()||block.equals("minecraft:air"))return;
        ClientState.addToPalette(block);
        ClientState.save();
    }
    /** Puts the block in the player's main hand, as the creative inventory would. Blocks without an item (liquids) cannot be held. */
    private void dropInHand(String block){
        var id=dev.craftium.compat.Compat.tryParse(block.split("\\[",2)[0]);
        var item=id==null?null:BuiltInRegistries.BLOCK.getOptional(id).map(b->b.asItem()).orElse(null);
        if(item==null||item==net.minecraft.world.item.Items.AIR){ClientState.notify("That block has no item to hold");return;}
        // In the editor the player is a spectator, which cannot use the creative menu, so the server sets the inventory instead.
        if(minecraft.player!=null&&minecraft.player.isSpectator()){
            String prior=o().block;o().block=block;
            try{CraftiumClient.send("hand_set",null,null);}finally{o().block=prior;}
            return;
        }
        if(minecraft.player==null||minecraft.gameMode==null||!minecraft.player.isCreative()){ClientState.notify("Only a creative player can hold it");return;}
        // What is in the hand is not thrown away: the block goes to the first empty hotbar slot, which is then the one held. Only a full hotbar replaces the held item.
        var inventory=minecraft.player.getInventory();int slot=inventory.getSelectedSlot();
        if(!inventory.getItem(slot).isEmpty())for(int i=0;i<9;i++)if(inventory.getItem(i).isEmpty()){slot=i;break;}
        minecraft.gameMode.handleCreativeModeItemAdd(new net.minecraft.world.item.ItemStack(item),36+slot);
        inventory.setSelectedSlot(slot);
    }
    /** Ends a drag from a block cell: a plain click does what the cell does, a drop in the world bucket fills where it lands. */
    private void finishBlockDrag(MouseButtonEvent event){
        String block=dragBlock;Runnable click=dragClick;boolean moved=dragMoved;dragBlock=null;dragClick=null;dragMoved=false;
        if(!moved){click.run();return;}
        if(dropBlock(event.x(),event.y(),block))return;
        if(!inViewport(event.x()/scale,event.y()/scale))return;
        pointerX=event.x();pointerY=event.y();var hit=CraftiumClient.raycast(event.x(),event.y());
        if(hit==null){ClientState.notify("Drop the block onto a block in the world");return;}
        if(ClientState.status.busy()){ClientState.notify("Wait for the current edit to finish");return;}
        var at=hit.getBlockPos();Point point=CraftiumClient.point(at),normal=CraftiumClient.normal(hit);
        sendBucket(block,point,normal);
    }
    /** A question the player answers before something happens: the text, what Confirm is for, and what Confirm does. */
    /** A question with a button to go ahead; {@code optOut}, when given, adds a tick box with that label, and ticking it turns {@code onOptOut} on when the question is answered yes. */
    private record Confirm(String text,String hint,Runnable action,String optOut,Runnable onOptOut){Confirm(String text,String hint,Runnable action){this(text,hint,action,null,null);}}
    private Confirm confirm;private boolean confirmOptOut;
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
    private void acceptConfirm(){var pending=confirm;confirm=null;if(pending!=null){if(pending.optOut()!=null&&confirmOptOut)pending.onOptOut().run();confirmOptOut=false;pending.action().run();}}
    private void dismissConfirm(){confirm=null;confirmOptOut=false;}
    private void swapBlocks() {String front=o().block;ClientState.pick(o().secondary);o().secondary=front;ClientState.save();}
    private void history(Gfx g) {
        int x=w-2*RIGHT-8,y=333; // beside the right-hand column, which the palette now fills; hidden until opened
        panel(g,x,y,RIGHT,Math.max(120,h-551),"History");int height=ph;
        int[] cell=flex(pw-20,9,1,1);
        button(g,x+10,y+24,cell[0],19,"Undo "+ClientState.status.undo(),false,()->action("undo"),"Undo · Ctrl+Z");
        button(g,x+19+cell[0],y+24,cell[1],19,"Redo "+ClientState.status.redo(),false,()->action("redo"),"Redo · Ctrl+Y");
        int row=y+51,index=0;for(String event:ClientState.status.history()){if(row>y+height-12)break;int steps=++index;text(g,shorten(event,pw-20),x+10,row,MUTED);controls.add(new Control(x+7,row-3,pw-14,14,()->{o().historySteps=steps;action("history_undo_many");},""));row+=15;}
    }
    /** The Magic select window: directions, mode and select type of the Magic Wand, shown from Window → Toggle magic whichever tool is active. */
    private void magicPanel(Gfx g){
        int x=w-RIGHT-244,y=263; // under where the Rulers panel opens, left of the right-hand column
        panel(g,x,y,236,160,"Magic select");
        MagicSelectOptions.draw(magicHost(g),o(),x+10,y+26,pw-20,MagicSelectOptions.ALL);
    }
    private void masks(Gfx g) {
        int x=w-RIGHT,y=masksTop();panel(g,x,y,RIGHT,masksHeight(),"Tool masks");
        dropArea(x,y,RIGHT,masksHeight(),b->{if(!b.isEmpty()&&!b.equals("minecraft:air"))addMaskBlock(b);});
        maskCols=Math.max(1,(pw-19)/MASK_PITCH);maskRows=1;
        button(g,x+10,y+24,19,19,"",o().maskInBuilder,()->o().maskInBuilder=!o().maskInBuilder,"Use in Builder tools · "+(o().maskInBuilder?"On: the tool masks also apply to the Builder tools":"Off: the Builder tools ignore the tool masks"));
        Icons.draw(g,IconCatalog.HARD_HAT,x+10,y+24,o().maskInBuilder?0xffffffff:0xffa9bccd);
        int share=(pw-20-23-4)/2;
        // Include and Exclude are never on together; clicking the one that is on turns both off, and the mask is ignored.
        boolean include=!o().maskOff&&!o().maskExclude,exclude=!o().maskOff&&o().maskExclude;
        button(g,x+33,y+24,share,19,"Include",include,()->{o().maskOff=include;o().maskExclude=false;ClientState.save();},"Only blocks in the mask may be changed · click again to ignore the mask");
        button(g,x+37+share,y+24,share,19,"Exclude",exclude,()->{o().maskOff=exclude;o().maskExclude=true;ClientState.save();},"Blocks in the mask are kept out of every change · click again to ignore the mask");
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
            dragSource(new Control(bx,by,22,22,()->toggleMaskBlock(block),()->openBlockMenu(BlockList.MASK,index),
                block+(on?" · On":" · Off")+" · Click to toggle · Drag to another slot · Right-click for options"),block);
        }
        if(rows>maskRows){
            int track=maskRows*MASK_PITCH-3,thumb=Math.max(10,track*maskRows/rows),thumbY=y+50+(track-thumb)*maskScroll/(rows-maskRows),barX=x+pw-11;
            g.fill(barX,y+50,barX+5,y+50+track,0xff1b2838);g.fill(barX,thumbY,barX+5,thumbY+thumb,0xff4a6585);
            controls.add(new Control(barX-2,y+50,9,track,()->{double py=WorkspaceLayout.inverse("masks",mx,my)[1];maskScroll=Math.clamp((int)Math.round((py-y-50-thumb/2.0)/Math.max(1,track-thumb)*(rows-maskRows)),0,rows-maskRows);},""));
        }
        // The height range and Only in selection fold away under one heading, so the panel is the buttons and a row of blocks.
        text(g,(maskLimitsOpen?"▾ ":"▸ ")+"Limits",x+10,y+78,MUTED);
        controls.add(new Control(x+7,y+75,pw-14,14,()->{maskLimitsOpen=!maskLimitsOpen;init();},"Height range and Only in selection"));
        if(maskLimitsOpen){
            text(g,"Min Y",x+10,y+ph-53,MUTED);text(g,"Max Y",x+pw-90,y+ph-53,MUTED);
            toggle(g,x+10,y+ph-18,"Only in selection",o().inSelection,()->o().inSelection=!o().inSelection,"Only edit blocks inside the current selection. Blocks outside it are left alone, and with nothing selected nothing changes.");
        }
    }
    private boolean maskLimitsOpen;
    /** The Tool masks panel: the buttons and one row of blocks, and with the limits unfolded the height range and Only in selection under them. It sits against the bottom of the screen. */
    private int masksHeight(){return maskLimitsOpen?150:98;}
    /** The Block palette and Tool masks sit together at the bottom of the right column and take 3/8 of its height, never less than what they need (a palette of 105 and the masks). Hierarchy has the rest. */
    private int groupHeight(){return Math.max(Math.round((h-25-27)*3f/8),105+6+masksHeight());}
    private int groupTop(){return h-25-groupHeight();}
    private int masksTop(){return h-25-masksHeight();}
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
    private static final int AXIS_SIZE=45,AXIS_TOP=29,AXIS_GAP=6;
    /** Sizes of the gizmo's parts: how far an end sits from the centre, and the radii of the positive and negative end circles. */
    private static final double AXIS_REACH=16.1,AXIS_PLUS=4.2,AXIS_MINUS=2.8;
    /**
     * Where the world axes gizmo sits: against the right edge, pushed left past any shown window that is in its way, so it
     * follows the Target info panel (or whatever is there) and falls back to the edge when nothing is.
     */
    private int axisGizmoX(){
        int x=w-AXIS_SIZE-AXIS_GAP;
        if(!panels)return x;
        for(int pass=0;pass<8;pass++){
            boolean moved=false;
            for(var r:WorkspaceLayout.windows.values()){
                if(!r.visible||r.y>=AXIS_TOP+AXIS_SIZE||r.y+r.h<=AXIS_TOP||r.x>=x+AXIS_SIZE+AXIS_GAP||r.x+r.w<=x-AXIS_GAP)continue;
                int next=r.x-AXIS_SIZE-AXIS_GAP;
                if(next<x){x=next;moved=true;}
            }
            if(!moved)break;
        }
        return Math.max(AXIS_GAP,x);
    }
    private static final String[] AXIS_NAMES={"X","Y","Z"};
    private static final int[] AXIS_COLOURS={0xffe8574f,0xff6fcf5a,0xff4f8fe8};
    /**
     * The world's X, Y and Z axes turning with the camera, as in a 3D editor. The end circles are handles: clicking one turns the
     * camera to look along that axis from that side, and the dim circle at the other end looks from the opposite side.
     */
    private void axisGizmo(Gfx g){
        if(!o().showAxisGizmo||minecraft.player==null||minecraft.level==null)return;
        int left=axisGizmoX(),cx=left+AXIS_SIZE/2,cy=AXIS_TOP+AXIS_SIZE/2;final double reach=AXIS_REACH;
        Vec3[] cam=TransformGizmo.cameraAxes();
        // Six ends: axis*2 is the positive end, axis*2+1 the negative. The farthest are drawn first, so the near ones cover them.
        double[][] ends=new double[6][];
        for(int axis=0;axis<3;axis++){
            double[] unit={axis==0?1:0,axis==1?1:0,axis==2?1:0};
            Vec3 a=new Vec3(unit[0],unit[1],unit[2]);
            double sx=a.dot(cam[0]),sy=-a.dot(cam[1]),depth=a.dot(cam[2]);
            for(int side=0;side<2;side++){
                double sign=side==0?1:-1,z=depth*sign;
                // Orthographic, like other editors' orientation gizmos; only the circle size hints at which end is nearer.
                ends[axis*2+side]=new double[]{sx*sign,sy*sign,z,1-z*.1};
            }
        }
        Integer[] order={0,1,2,3,4,5};
        java.util.Arrays.sort(order,(p,q)->Double.compare(ends[q][2],ends[p][2]));
        // Drawn at eight times the resolution through the pose, so the lines and discs are not snapped to whole interface pixels.
        final int S=8;
        g.pose().pushMatrix();g.pose().scale(1f/S,1f/S);
        for(int e:order)if(e%2==0){
            double dx=ends[e][0]*reach*S,dy=ends[e][1]*reach*S,length=Math.hypot(dx,dy);
            if(length<1)continue;
            g.pose().pushMatrix();g.pose().translate((float)(cx*S),(float)(cy*S));g.pose().rotate((float)Math.atan2(dy,dx));
            g.fill(0,-3,(int)Math.round(length),3,axisShade(AXIS_COLOURS[e/2],ends[e][2],false));
            g.pose().popMatrix();
        }
        for(int e:order){
            boolean positive=e%2==0;double[] end=ends[e];
            double ex=(cx+end[0]*reach)*S,ey=(cy+end[1]*reach)*S;int radius=(int)Math.round((positive?AXIS_PLUS:AXIS_MINUS)*S*end[3]);
            int colour=axisShade(AXIS_COLOURS[e/2],end[2],!positive);
            int step=3;
            for(int dy=-radius;dy<radius;dy+=step){
                double mid=dy+step/2.0,half=Math.sqrt(Math.max(0,radius*radius-mid*mid));
                g.fill((int)Math.round(ex-half),(int)Math.round(ey+dy),(int)Math.round(ex+half),(int)Math.round(ey+dy)+step,colour);
            }
        }
        g.pose().popMatrix();
        for(int e:order){
            int axis=e/2;boolean positive=e%2==0;double[] end=ends[e];
            int ex=cx+(int)Math.round(end[0]*reach),ey=cy+(int)Math.round(end[1]*reach),radius=positive?5:4;
            if(positive){g.pose().pushMatrix();g.pose().translate(ex,ey);g.pose().scale(.7f,.7f);g.centeredText(font,AXIS_NAMES[axis],0,-4,0xffffffff);g.pose().popMatrix();}
            final int fa=axis;final boolean fp=positive;
            controls.add(new Control(ex-radius-1,ey-radius-1,radius*2+3,radius*2+3,()->faceAxis(fa,fp),""));
        }
    }
    /** An axis colour darker the farther that end points from the camera, and dimmer still for the negative end. */
    private static int axisShade(int colour,double depth,boolean negative){
        double k=Math.clamp(.8-depth*.25,.45,1)*(negative?.6:1);
        int r=(int)(((colour>>16)&255)*k),gr=(int)(((colour>>8)&255)*k),b=(int)((colour&255)*k);
        return 0xff000000|r<<16|gr<<8|b;
    }
    /** Turns the camera, in place, to look along an axis from its positive or negative side. Straight up or down keeps the heading. */
    private void faceAxis(int axis,boolean positive){
        var player=minecraft.player;if(player==null)return;
        double look=positive?-1:1; // viewing from the +X side means looking toward -X
        double fx=axis==0?look:0,fy=axis==1?look:0,fz=axis==2?look:0;
        if(axis!=1)player.setYRot((float)Math.toDegrees(Math.atan2(-fx,fz)));
        player.setXRot((float)Math.toDegrees(-Math.asin(fy)));
        player.setDeltaMovement(Vec3.ZERO);player.setOldPosAndRot();
    }
    /** Where the large-selection popup is, or null when it is not showing. It sits in the middle of the viewport. */
    private int[] popupBounds(){
        if(ClientState.largeSelection<=0&&confirm==null)return null;
        int left=panels?LEFT+6:6,right=panels?w-RIGHT-6:w-6;
        if(confirm!=null){ // as tall as its lines, with room for the tick box when there is one
            int lines=confirm.text().split("\n").length,height=26+lines*11+(confirm.optOut()==null?0:20)+30;
            return new int[]{(left+right-300)/2,h/2-70,300,height};
        }
        return new int[]{(left+right-250)/2,h/2-70,250,62};
    }
    /** A small popup beside the work, not over it: the editor stays visible and nothing is dimmed. */
    private void largeSelectionPopup(Gfx g){
        int[] b=popupBounds();
        if(b==null)return;
        g.fill(b[0],b[1],b[0]+b[2],b[1]+b[3],0xf0141d29);g.outline(b[0],b[1],b[2],b[3],ACCENT);
        if(confirm!=null){
            int y=b[1]+9;
            for(var line:confirm.text().split("\n")){text(g,shorten(line,b[2]-20),b[0]+10,y,TEXT);y+=11;}
            y+=6;
            if(confirm.optOut()!=null){ // "Don't show this message again"
                boolean over=mx>=b[0]+10&&mx<b[0]+b[2]-10&&my>=y&&my<y+14;
                FlatCheckbox.box(g,b[0]+10,y,14,confirmOptOut,over);text(g,confirm.optOut(),b[0]+30,y+3,MUTED);
                controls.add(new Control(b[0]+10,y,b[2]-20,14,()->confirmOptOut=!confirmOptOut,HIDDEN_TIP+"menu "+confirm.optOut()));
                y+=20;
            }
            button(g,b[0]+10,y,112,22,"Confirm",true,this::acceptConfirm,"");
            button(g,b[0]+128,y,112,22,"Cancel",false,this::dismissConfirm,"");
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
            g.pose().pushMatrix();g.pose().translate(x+1,y+1);g.pose().scale(1f,1f);Icons.draw(g,tool.spec().iconId(),0,0,selected?0xffffffff:0xffd0deeb);g.pose().popMatrix();
            int key=EditorControls.toolDefault(tool);
            controls.add(new Control(x,y,26,26,()->{if(toolActive&&o().tool==tool)deselectTool();else select(tool);},toolTip(tool,slot+1)));
        }
    }
    /** The editor's own frame rate, counted over half a second of drawn frames. */
    private int frameCount,fps;private long fpsSince=System.nanoTime();
    private void statusbar(Gfx g) {
        g.fill(0,h-20,w,h,0xff1b222c);g.fill(0,h-21,w,h-20,LINE);
        text(g,ToolHints.line(toolActive?o().tool:null,TransformGizmo.available(this),ShapeObject.active(),TransformGizmo.draggingMode(),font::width,w-210),9,h-13,MUTED);
        frameCount++;long now=System.nanoTime();
        if(now-fpsSince>=500_000_000L){fps=(int)Math.round(frameCount*1e9/(now-fpsSince));frameCount=0;fpsSince=now;}
        text(g,fps+" FPS",w-190,h-13,fps<30?0xffffb6ae:MUTED);
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
                item("Paste at cursor","",this::pasteAtCursor),item("Paste in place","",PlacementPreview::beginInPlace),divider(),
                item("Edits history","",()->ClientCompat.setScreen(minecraft,new HistoryScreen(this))));
            case 2 -> List.of(item("Deselect","Alt+D",()->action("clear")),item("Inverse","",()->action("selection_invert")),
                item(showSelection?"Hide selection":"Show selection","",()->showSelection=!showSelection),divider(),
                item("Expand…","",()->ClientCompat.setScreen(minecraft,new ResizeSelectionScreen(this,true))),item("Shrink…","",()->ClientCompat.setScreen(minecraft,new ResizeSelectionScreen(this,false))),
                item("Expand by 1","",()->resizeSelection("selection_expand",1)),item("Shrink by 1","",()->resizeSelection("selection_shrink",1)),
                item("Select outline…","",()->ClientCompat.setScreen(minecraft,new ResizeSelectionScreen(this,ResizeSelectionScreen.Kind.OUTLINE))));
            case 3 -> List.of(item("Toggle side panels","",()->panels=!panels),item("Tool options","",()->{builder=false;pinnedTab=false;init();}),item("Builder controls","Alt",()->{builder=true;init();}),item("Studio inspector","",()->ClientCompat.setScreen(minecraft,new StudioScreen(this))),item("Colour picker / gradient","",()->ClientCompat.setScreen(minecraft,new ColourScreen(this))));
            case 8 -> {
                int sessions=ClientState.aiSessions.size();
                var items=new ArrayList<MenuItem>();
                items.add(item(AiBridge.running()?"Turn off AI building":"Turn on AI building","",()->{
                    Runnable toggle=()->{AiBridge.toggle();ClientState.notify(AiBridge.running()?"AI building is on":"AI building is off");};
                    // Turning it off disconnects whatever AI is building: ask first, as turning it on costs nothing.
                    if(AiBridge.running())confirm=new Confirm("Turn off AI building? Any AI connected will stop building.","Turn off",toggle);else toggle.run();
                }));
                items.add(divider());
                items.add(item("Build sessions…"+(sessions>0?" ("+sessions+")":""),"",()->ClientCompat.setScreen(minecraft,new AiSessionsScreen(this))));
                if(sessions>1){ // with several sessions the buttons below would have to guess which one is meant: they act on all, and the sessions screen acts on one
                    items.add(item("Apply all AI previews","",AiSessionActions::applyAll));items.add(item("Discard all AI previews","",AiSessionActions::discardAll));
                }else{
                    items.add(item("Apply AI preview","",()->CraftiumClient.sendAiToOnly("ai_apply")));items.add(item("Discard AI preview","",()->CraftiumClient.sendAiToOnly("ai_cancel")));
                    items.add(item("Undo last AI build","",()->CraftiumClient.sendAiToOnly("ai_undo")));items.add(item("Redo AI build","",()->CraftiumClient.sendAiToOnly("ai_redo")));
                }
                items.add(divider());
                items.add(item("AI status and setup…","",()->{var studio=new StudioScreen(this);studio.selectTab("AI");ClientCompat.setScreen(minecraft,studio);}));
                yield items;
            }
            case 9 -> {var items=new ArrayList<MenuItem>();for(String id:WorkspaceLayout.windows.keySet())items.add(item("Toggle "+id,"",()->WorkspaceLayout.toggle(id)));items.add(item("Save layout","",WorkspaceLayout::save));items.add(item("Reset layout","",WorkspaceLayout::reset));items.add(item("Copy layout","",()->minecraft.keyboardHandler.setClipboard(WorkspaceLayout.json())));items.add(item("Paste layout","",()->{try{WorkspaceLayout.importJson(minecraft.keyboardHandler.getClipboard());WorkspaceLayout.save();}catch(Exception e){ClientState.notify("Invalid layout JSON");}}));yield items;}
            case 5 -> List.of(item("Blueprint library","",()->ClientCompat.setScreen(minecraft,new BlueprintScreen(this))),divider(),
                item("Import structure…","",ClientStructures::chooseImport),divider(),
                item("Export selection…","",()->exportStructure(StructureSettings.SELECTION)),item("Export clipboard…","",()->exportStructure(StructureSettings.CLIPBOARD)),divider(),
                submenu("Export selection as",item("Structure (.nbt)","",()->exportStructure(StructureSettings.SELECTION,StructureSettings.JAVA,StructureSettings.NBT)),item("Schematic (.schem)","",()->exportStructure(StructureSettings.SELECTION,StructureSettings.JAVA,StructureSettings.SCHEM)),item("Bedrock structure (.mcstructure)","",()->exportStructure(StructureSettings.SELECTION,StructureSettings.BEDROCK,StructureSettings.NBT))),
                divider(),item("Open structures folder","",ClientStructures::openFolder));
            case 4 -> List.of(item("Shape","",()->select(Tool.SHAPE)),item("Block text","",()->select(Tool.TEXT)),item("Line","",()->select(Tool.LINE)),item("Rock","",()->select(Tool.ROCK)));
            case 6 -> List.of(item("Fill selection","Ctrl+F",()->action("fill")),item("Delete selection","Del",()->action("delete")),item("Move / clone / stack…","",()->{transform=true;init();}),item("Smooth brush","",()->select(Tool.SMOOTH)),item("Flatten brush","",()->select(Tool.FLATTEN)));
            case 7 -> List.of(item("Restrict to selection","",()->o().inSelection=!o().inSelection),item("Clear block mask","",()->{o().mask="";o().maskActive.clear();}),item("Toggle surface painting","",()->o().surfaceOnly=!o().surfaceOnly));
            default -> List.of(item("Editor controls","",()->help=true),item("About Craftium","",()->ClientCompat.setScreen(minecraft,new AboutScreen(this))));
        };
    }
    /** The panel the Magic Select options widget draws into: this screen's buttons and icons. */
    private MagicSelectOptions.Host magicHost(Gfx g){
        return new MagicSelectOptions.Host(){
            @Override public void button(int x,int y,int width,int height,String label,boolean active,Runnable action,String tip){EditorScreen.this.button(g,x,y,width,height,label,active,action,tip);}
            @Override public void modeIcon(int x,int y,Selection.Mode mode,boolean active){SelectionModeIcon.draw(g,mode,x,y,13,active?0xffffffff:0xffd0deeb);}
            @Override public void icon(float x,float y,String iconId,boolean active){g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(.75f,.75f);Icons.draw(g,iconId,0,0,active?0xffffffff:0xffd0deeb);g.pose().popMatrix();}
        };
    }
    /** How a new selection combines with the current one, as four buttons in a grid with the active one lit. */
    private void modeButtons(Gfx g,int y){MagicSelectOptions.mode(magicHost(g),o(),10,y,pw-20);}
    /** The Floodfill tool's spread, the same four switches as the Magic Wand's search. */
    private void floodButtons(Gfx g,int y){
        var o=o();
        sideButtons(g,y,new boolean[]{o.floodUp,o.floodDown,o.floodHorizontal,o.floodCorners},new Runnable[]{()->o.floodUp=!o.floodUp,()->o.floodDown=!o.floodDown,()->o.floodHorizontal=!o.floodHorizontal,()->o.floodCorners=!o.floodCorners});
    }
    /** Up, Down, Horizontal and Corners as two rows of switches: which way a search or fill may spread from the clicked block. */
    private void sideButtons(Gfx g,int y,boolean[] on,Runnable[] flip){
        int half=(pw-24)/2;
        String[] labels={"Up","Down","Horizontal","Corners"};
        String[] tips={"Spread to the blocks above the clicked one","Spread to the blocks below the clicked one","Spread sideways, level with the clicked block","Also connect blocks that only touch at an edge or corner"};
        for(int i=0;i<4;i++)button(g,10+(i%2)*(half+4),y+(i/2)*21,half,19,labels[i],on[i],flip[i],tips[i]);
    }
    /** Smooth's own controls from {@code y} down, as the docs list them; returns the room they took. */
    private int smoothRows(Gfx g,int y){
        var o=o();int third=(pw-28)/3;
        slider(g,10,y,pw-20,"Smooth strength",o.smoothing,.1,5,DEFAULTS.smoothing,v->o.smoothing=Math.round(v*100)/100d,false);
        slider(g,10,y+28,pw-20,"Block ratio (%)",o.smoothRatio,50,150,Double.POSITIVE_INFINITY,DEFAULTS.smoothRatio,v->o.smoothRatio=Math.max(0,Math.round(v*10)/10d),false);
        for(var mode:Options.SmoothMode.values())button(g,10+mode.ordinal()*(third+4),y+56,third,20,mode.label,o.smoothMode==mode,()->o.smoothMode=mode,switch(mode){case STABLE->"Keep the block count at the ratio";case MELT->"Lean towards taking blocks away";case GROW->"Lean towards adding blocks";});
        toggle(g,10,y+84,"Fix edges",o.smoothFixEdges,()->o.smoothFixEdges=!o.smoothFixEdges,"Leave the brush's outer layer as it was so the smoothing blends into the terrain");
        return 84;
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
    private int sidesRow(){return sidesBlock()+(sizeInline()?sizeHeight():0);}
    /** The tools whose brush is the picked shape have its size as properties: a control for each dimension that shape has, and a Chain that keeps the proportions. */
    private static final EnumSet<Tool> FIXED_LAYOUT=EnumSet.of(Tool.ROCK,Tool.DISTORT,Tool.ROUGHEN,Tool.SHATTER,Tool.NOISE_PAINTER,Tool.BIOME_PAINTER,Tool.CLENTAMINATOR);
    private boolean sizes(){return o().tool.brushUse()==Tool.BrushUse.SHAPE;}
    /** Under the radius, for the tools whose rows follow one another; the others have their rows at fixed places, so theirs go below them. */
    private boolean sizeInline(){return sizes()&&!FIXED_LAYOUT.contains(o().tool);}
    private boolean sizeAtEnd(){return sizes()&&FIXED_LAYOUT.contains(o().tool)&&o().tool!=Tool.ROCK;}
    /** The brush tools that paint along a stroke have a flow; a selection, or a shape that is put down and then built, does not. */
    private boolean flowShown(){return sizes()&&!o().tool.selection()&&o().tool!=Tool.SHAPE;}
    private int sizeHeight(){int n=dev.craftium.core.ShapeSizes.rows(o().shape).size();return n*28+(n>1?24:0)+(flowShown()?28:0);}
    /** The radius slider's name, saying how far this shape can go when that is less than the largest radius there is. */
    private String radiusLabel(){int most=dev.craftium.core.ShapeLimits.maxRadius(o());return most<BrushMath.MAX_RADIUS?"Radius (up to "+most+" for this shape)":"Radius";}
    private void sizeControls(Gfx g,int y){
        var o=o();var rows=dev.craftium.core.ShapeSizes.rows(o.shape);
        ShapeObject.syncOptions(); // a shape already put down shows the sizes its handles gave it
        double top=dev.craftium.core.ShapeSizes.maxCount(o);
        for(var row:rows){
            slider(g,10,y,pw-20,row.label()+" (blocks)",Math.round(dev.craftium.core.ShapeSizes.count(o,row)),1,Math.min(64,top),top,dev.craftium.core.ShapeSizes.defaultCount(o),v->{dev.craftium.core.ShapeSizes.set(o(),rows,row,Math.round(v));ShapeObject.adoptSizes();},true);
            y+=28;
        }
        if(rows.size()>1){ // a button with a chain on it: linked when the sizes keep their proportions, broken when each is its own
            button(g,10,y,30,20,"",o.sizeChain,()->o().sizeChain=!o().sizeChain,"Chain · "+(o.sizeChain?"on: changing one size changes the others by the same factor":"off: each size changes on its own")+" · middle-click a size to reset it");
            Icons.draw(g,o.sizeChain?IconCatalog.CHAIN_LINKED:IconCatalog.CHAIN_BROKEN,16,y+5,o.sizeChain?0xffffffff:0xff8a9bad);
            text(g,o.sizeChain?"Proportions kept":"Sizes independent",46,y+6,MUTED);
        }
        if(flowShown())slider(g,10,y+(rows.size()>1?24:0)+10,pw-20,"Flow",o.brushFlow,Options.FLOW_MIN,Options.FLOW_MAX,Options.FLOW_DEFAULT,v->o().brushFlow=(int)Math.clamp(Math.round(v),Options.FLOW_MIN,Options.FLOW_MAX),true);
    }
    /** The tools with their own seed fields and painters have no free row; the + and - keys still change the sides there. */
    /**
     * How far the tool's options move up because it has less brush to set: a round brush has no shape picker, and a tool with no brush has no radius
     * either. Text lays out its own panel, so it keeps its place.
     */
    /** The tools with a Strength slider under the radius. */
    private boolean hasStrength(){return EnumSet.of(Tool.SCULPT_DRAW,Tool.ROCK,Tool.ELEVATION,Tool.FLATTEN,Tool.SLOPE,Tool.EXTRUDE).contains(o().tool);}
    private int lift(){
        if(o().tool==Tool.TEXT)return 0;
        return switch(o().tool.brushUse()){case DISC,SPHERE->32;case NONE->60;default->0;};
    }
    private static boolean polygonLike(BrushMath.Shape shape){return shape==BrushMath.Shape.POLYGON||shape==BrushMath.Shape.IRREGULAR_POLYGON||shape==BrushMath.Shape.STAR;}
    /** The room the polygon's own rows take under the radius: its sides, and for the irregular polygon and the star a second row, the seed or the notch depth. */
    private int sidesBlock(){return (sidesShown()?28:0)+(extraShown()?28:0);}
    private boolean extraShown(){return sidesShown()&&o().shape!=BrushMath.Shape.POLYGON;}
    private boolean sidesShown(){return o().tool.picksShape()&&polygonLike(o().shape)&&!EnumSet.of(Tool.ROCK,Tool.DISTORT,Tool.ROUGHEN,Tool.SHATTER,Tool.NOISE_PAINTER,Tool.BIOME_PAINTER,Tool.CLENTAMINATOR).contains(o().tool);}
    /** The button that shows the current shape and opens the picker. */
    private void shapeSelector(Gfx g,int y){
        var shape=o().shape;
        button(g,10,y,pw-20,24,"",shapeMenu,()->{
            shapeMenu=!shapeMenu;shapeX=Math.clamp(mx-24,4,w-SHAPE_MENU_WIDTH-4);shapeY=Math.clamp(my+8,4,h-shapeMenuHeight()-4);
        },"Choose the shape: solids, curved forms and flat ones");
        g.pose().pushMatrix();g.pose().translate(14,y+3);g.pose().scale(.75f,.75f);Icons.draw(g,IconCatalog.shape(shape),0,0,0xffffffff);g.pose().popMatrix();
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
            g.pose().pushMatrix();g.pose().translate(cell.x()+(SHAPE_CELL_W-24)/2f,cell.y()+3);g.pose().scale(1f,1f);Icons.draw(g,IconCatalog.shape(cell.shape()),0,0,active?0xffffffff:0xffd0deeb);g.pose().popMatrix();
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
        panel(g,x,y,460,290,"Building Studio");
        String[] lines={"A familiar workspace for building, painting and sculpting.","","Right Shift          Open / close the editor","Left drag            Look around the world","W A S D / Space Shift Fly horizontally / up and down","Right click / drag   Use the selected tool","Middle click         Pick the block under the cursor","Ctrl + middle drag   Adjust brush radius","Ctrl Z / Ctrl Y      Undo / redo","Ctrl C / X / V       Copy / cut / paste at cursor","Ctrl R / Ctrl F      Rotate / mirror pending placement","Ctrl F / Ctrl R      Fill / replace the selection","Enter / Delete       Clear / delete selection","Ctrl J               Duplicate selection as a placement","Ctrl P / Ctrl B      Save blueprint / show biomes","View > Keybinds      Edit shortcuts; inspector via View","","Creative mode + cheats / operator level 2 required.","Biome edits use Minecraft's 4 × 4 × 4 biome cells."};
        int row=y+30;for(String line:lines){text(g,line,x+18,row,MUTED);row+=14;}
        button(g,x+350,y+258,92,22,"Back to editor",false,()->help=false,"");
    }
    private void button(Gfx g,int x,int y,int width,int height,String label,boolean active,Runnable action,String tip){button(g,x,y,width,height,label,active,action,null,tip);}
    /** A button that cycles through values: a click goes to the next one and a right click back to the one before. */
    private void button(Gfx g,int x,int y,int width,int height,String label,boolean active,Runnable action,Runnable back,String tip) {
        if(!culled(y,height))FlatButton.face(g,x,y,width,height,label,active,true,false,menuAlign);
        controls.add(new Control(x,y,width,height,action,back,back==null||tip.isEmpty()?tip:tip+" · Right-click goes back"));
    }
    private void toggle(Gfx g,int x,int y,String label,boolean value,Runnable action) {toggle(g,x,y,label,value,action,"");}
    private void toggle(Gfx g,int x,int y,String label,boolean value,Runnable action,String tip) {
        if(!culled(y,12))FlatCheckbox.box(g,x,y,12,value,false);
        text(g,label,x+19,y+3,TEXT);controls.add(new Control(x,y,Math.min(pw-20,TextCache.width(font,label)+24),15,action,tip));
    }
    private void slider(Gfx g,int x,int y,int width,String label,double value,double min,double max,double def,DoubleConsumer set,boolean integer) {
        slider(g,x,y,width,label,value,min,max,max,def,set,integer);
    }
    /** A slider whose track runs from {@code min} to {@code max} but which takes a value typed with Ctrl-click up to {@code typedMax}. */
    private void slider(Gfx g,int x,int y,int width,String label,double value,double min,double max,double typedMax,double def,DoubleConsumer set,boolean integer) {slider(g,x,y,width,label,value,min,max,typedMax,def,set,integer,false);}
    private void slider(Gfx g,int x,int y,int width,String label,double value,double min,double max,double typedMax,double def,DoubleConsumer set,boolean integer,boolean unbounded) {
        text(g,label,x,y-10,MUTED);
        var slider=new Slider(x,width,min,max,typedMax,def,set,unbounded);String panel=drawingPanel,key=panel+"/"+label;
        boolean editing=typing!=null&&typing.key().equals(key);
        String display=integer?Integer.toString((int)value):String.format(Locale.ROOT,"%.2f",value); // what is typed over when Ctrl-click starts editing
        // A turn that has no end (-360 to 360 and unbounded) is shown as whole degrees, or as 360°×n at a whole number of turns.
        String shown=unbounded&&min==-360&&max==360?degreeText(value):display;
        if(!culled(y,17)){
            FlatSlider.track(g,x,y,width,17,(value-min)/(max-min),editing?Component.empty():Component.literal(shown),editing,false,editing);
            if(editing)entry.paint(g,x,y,width,17);
        }
        var control=new Control(x,y,width,17,()->{
            if(minecraft.hasControlDown()){typing=new SliderArea(null,slider,key,integer);entry.start(display);selectingText=false;return;}
            dragging=slider;draggingSliderPanel=panel;
            if(unbounded){sliderStartX=WorkspaceLayout.inverse(panel,mx,my)[0];sliderStartValue=value;}else slider.update(WorkspaceLayout.inverse(panel,mx,my)[0],false,minecraft.hasShiftDown());
        },"Drag to change "+label.toLowerCase(Locale.ROOT)+" · Ctrl-click to type"+(typedMax==Double.POSITIVE_INFINITY?" (no limit)":typedMax>max?" (up to "+(int)typedMax+")":"")+" · Middle-click to reset");
        controls.add(control);sliderAreas.add(new SliderArea(control,slider,key,integer));
    }
    /** An angle that can go round more than once: {@code 567°}, but {@code 360°×2} where it is a whole number of turns ({@code 360°} for one). */
    static String degreeText(double degrees){
        long whole=Math.round(degrees);
        if(whole!=0&&whole%360==0&&Math.abs(whole)>360)return "360°×"+whole/360;
        return whole+"°";
    }
    /** Applies what was typed into a slider, clamped to its range, and stops typing. Text that is not a number changes nothing. */
    private void finishTyping(boolean apply){
        var area=typing;String text=entry.text();typing=null;selectingText=false;
        if(!apply||area==null)return;
        try{double v=Double.parseDouble(text.trim());if(!Double.isFinite(v))return;if(area.slider().typedMax()!=Double.POSITIVE_INFINITY)v=Math.clamp(v,area.slider().min(),area.slider().typedMax());area.slider().set.accept(area.integer()?Math.round(v):v);}catch(NumberFormatException ignored){}
    }
    private void text(Gfx g,String text,int x,int y,int color){PanelLog.text(text,x,y,drawingPanel);if(scrollActive)scrollContent=Math.max(scrollContent,y+9);if(culled(y,9))return;g.text(font,text,x,y,color,false);}
    /** A tool's tooltip: its name and the key it is on (and the hotbar slot, when it has one) on the first line, its description below. */
    private static String toolTip(Tool tool,int slot){
        var mapping=CraftiumClient.toolKeys.get(tool);
        String key=mapping==null||mapping.isUnbound()?"":mapping.getTranslatedKeyMessage().getString();
        String shortcut=key.isEmpty()?slot>0?String.valueOf(slot):"":slot>0?key+" / "+slot:key;
        return tool.label+(shortcut.isEmpty()?"":" ["+shortcut+"]")+"\n"+tool.description;
    }
    private String shorten(String text,int pixels){if(TextCache.width(font,text)<=pixels)return text;return TextCache.fit(font,text,Math.max(0,pixels-9))+"…";}
    private void wrap(Gfx g,String text,int x,int y,int width,int color,int maxLines){int line=0;for(var row:TextCache.split(font,text,width)){if(line>=maxLines)break;g.text(font,row,x,y+line*11,color,false);line++;}if(scrollActive)scrollContent=Math.max(scrollContent,y+line*11);}
    private String nice(String name){String s=name.toLowerCase(Locale.ROOT).replace('_',' ');return Character.toUpperCase(s.charAt(0))+s.substring(1);}
    public BlockHitResult target(){
        if(inViewport(pointerX/scale,pointerY/scale)){
            // Called several times a frame (panels, previews, gizmos), each time for the same ray: cast it once per pointer, camera and game tick.
            var level=minecraft.level;var camera=dev.craftium.client.compat.ClientCompat.camera(minecraft);
            long tick=level==null?0:level.getGameTime();
            var eye=camera.position();var look=dev.craftium.client.compat.ClientCompat.ray(camera,0,0);
            var key=new double[]{pointerX,pointerY,eye.x,eye.y,eye.z,look.x,look.y,look.z,tick};
            if(!java.util.Arrays.equals(key,hitKey)){hitKey=key;lastHit=CraftiumClient.raycast(pointerX,pointerY);}
        }
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
            else if(ClientState.status.clipboardOrigin()!=null)PlacementPreview.beginInPlace(); // no block under the pointer (it is over a panel, or the sky): where it was copied from
            else ClientState.notify("Point at a block to position the clipboard");
            return;
        }
        if(action.equals("use")&&Interactions.of(o().tool).sendUse(context))return; // a tool that sends its own request when it is applied
        CraftiumClient.send(action,hit==null?null:CraftiumClient.point(hit.getBlockPos()),hit==null?null:CraftiumClient.normal(hit));
    }
    /** Whether a left press here lands on something that drags: a gizmo handle, either corner of a box selection, or a node. */
    private boolean overHandle(MouseButtonEvent event){
        if(event.button()!=0)return false;
        if(o().tool==Tool.BOX_SELECT&&ClientState.boxCorners.size()==2)for(int i=0;i<2;i++)if(TransformGizmo.hitsPointHandles(TransformGizmo.cornerAnchor(i),event.x(),event.y()))return true;
        if(TransformGizmo.hits(this,event.x(),event.y()))return true;
        if(PlacementPreview.active())return false;
        var points=handleNodes();for(int i=0;i<points.size();i++){var pixel=project(TransformGizmo.nodeAnchor(points.get(i),i));if(pixel!=null&&Math.pow(event.x()-pixel[0],2)+Math.pow(event.y()-pixel[1],2)<144)return true;}
        return false;
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
    /** The Sculpt and Paint tools work by strokes: what a stroke stages is built the moment the button is released, without Enter. So does every tool when Enter to build is off in the preferences. Modify is built by its own Apply. */
    private boolean sculpts(Tool tool){return !o().enterToBuild||tool.spec().buildsOnRelease();}
    private long lastUseSent;
    /** How long a stroke waits between one application of the brush and the next: 140 ms at the default flow of 25, longer for less, 35 ms at 100. */
    private long useInterval(){return flowShown()?Math.round(140.0*Options.FLOW_DEFAULT/Math.clamp(o().brushFlow,Options.FLOW_MIN,Options.FLOW_MAX)):140;}
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
        if(Interactions.of(o().tool).beforeUse(context))return; // sees every click and every step of a drag, whatever the use interval says
        long now=System.currentTimeMillis();if((!o().tool.selection()&&now-lastUse<useInterval())||ClientState.status.busy())return;lastUse=now;awaitingReply=true;repliedSince=false;
        if(Interactions.of(o().tool).use(context))return; // what this kind of tool does with a click; false lets the click go on to the server as a plain request
        action("use");
    }
    /** Called when a handle has been let go of: the box follows the handles, replacing the box it made before. */
    void handlesMoved(){Interactions.of(o().tool).handlesMoved(context);}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        if(ReferenceImages.click(event.x(),event.y(),event.button()))return true;
        double x=event.x()/scale,y=event.y()/scale;mx=(int)x;my=(int)y;pointerX=event.x();pointerY=event.y();
        if(typing!=null){
            // A click on the slider being typed in moves the caret (Shift stretches the selection, Ctrl or a double click selects all); anywhere else keeps what was typed.
            for(var a:sliderAreas)if(a.key().equals(typing.key())&&a.control().contains(x,y)&&event.button()==0){
                double local=WorkspaceLayout.inverse(a.control().panel(),x,y)[0];
                if(minecraft.hasControlDown()||doubleClick)entry.selectAll();else entry.click(local,a.slider().x(),a.slider().width(),minecraft.hasShiftDown());
                selectingText=true;return true;
            }
            finishTyping(true);return true;
        }
        if(menu==ROW_MENU&&event.button()<=1){ // the open menu takes the click: one of its items does it, and any other place closes the menu with nothing behind it hearing
            for(int i=controls.size()-1;i>=rowMenuFrom;i--){var c=controls.get(i);if(c.contains(x,y)){c.action.run();return true;}}
            menu=-1;if(event.button()==0)return true; // a left click elsewhere only closes it; a right click goes on, so it can open the menu of another row
        }
        if(event.button()!=0)for(var field:fields.values())field.setFocused(false); // a right or middle click is not typing in a box either, so the shortcuts work
        if(shapeMenu){shapeMenu=false;var picked=event.button()==0?shapeAt(x,y):null;if(picked!=null)o().shape=picked;return true;} // any click closes the picker
        if(ctxCell>=0){int cell=ctxCell;ctxCell=-1;if(x>=ctxX&&x<ctxX+MASK_MENU_WIDTH&&y>=ctxY&&y<ctxY+BLOCK_MENU_LABELS.length*MASK_MENU_ROW&&cell<blocksOf(ctxList).size()){switch((int)((y-ctxY)/MASK_MENU_ROW)){case 0->changeBlock(ctxList,cell);case 1->editBlockState(ctxList,cell);default->removeBlock(ctxList,cell);}}return true;}
        if(event.button()==2&&!help&&!transform&&menu<0)for(int i=sliderAreas.size()-1;i>=0;i--){var a=sliderAreas.get(i);if(a.control().contains(x,y)){a.slider().reset();return true;}} // back to the default, at once
        if(event.button()==0&&pipetting&&menu<0&&!help&&!transform){
            var hit=pipetteTarget(event.x(),event.y());
            if(hit!=null&&minecraft.level!=null){
                String picked=BlockStateScreen.describe(minecraft.level.getBlockState(hit.getBlockPos()));
                // Ctrl keeps the pipette in hand and adds each block to the palette; a plain click sets the foreground and puts it away.
                if(minecraft.hasControlDown()){if(ClientState.addToPalette(picked))ClientState.notify("Added to the palette: "+picked);}
                else{pipetting=false;ClientState.pick(picked);}
                ClientState.save();
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
                    String panel=WorkspaceLayout.fieldPanel(entry.getKey());if(!WorkspaceLayout.visible(panel)||WorkspaceLayout.folded(panel))continue;var point=WorkspaceLayout.inverse(panel,x,y);
                    if(fieldShown(entry.getKey(),field)&&point[0]>=field.getX()-3&&point[0]<field.getX()+field.getWidth()+3&&point[1]>=field.getY()-4&&point[1]<field.getY()+15)selected=field;
                }
                if(selected!=null){selected.setFocused(true);String id="";for(var entry:fields.entrySet())if(entry.getValue()==selected)id=entry.getKey();var point=WorkspaceLayout.inverse(WorkspaceLayout.fieldPanel(id),x,y);selected.mouseClicked(new MouseButtonEvent(point[0],point[1],event.buttonInfo()),doubleClick);return true;}
            }
            if(!help&&!transform&&menu<0)for(int i=dragSources.size()-1;i>=0;i--){var d=dragSources.get(i);if(d.area().contains(x,y)){dragBlock=d.block();dragClick=d.area().action();dragStartX=event.x();dragStartY=event.y();dragMoved=false;return true;}}
            if(doubleClick&&!help&&!transform&&menu<0)for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(!c.contains(x,y))continue;for(var area:renameAreas)if(area.row()==c){startRename(area);return true;}break;} // the second click of a double click on a row renames it
            if(!help&&!transform&&menu<0)for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(!c.contains(x,y))continue;for(var area:renameAreas)if(area.row()==c){rowDragId=area.id();rowDragIds=draggedSet(area.id());rowDragX=event.x();rowDragY=event.y();rowDragMoved=false;rowDragClick=c.action;return true;}break;} // a row acts when let go, so it can be dragged first
            for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(c.contains(x,y)){c.action.run();return true;}}
            if(help||transform)return true;if(menu>=0){menu=-1;return true;}
            if(panels){String id=WorkspaceLayout.hit(x,y,true);if(!id.isBlank()){var r=WorkspaceLayout.windows.get(id);draggedPanel=id;resizePanel=false;dragOriginX=r.x;dragOriginY=r.y;dragPointerX=x;dragPointerY=y;return true;}
                id=WorkspaceLayout.hit(x,y,false);if(!id.isBlank()){var r=WorkspaceLayout.windows.get(id);if(!r.folded&&x>r.x+r.w-12&&y>r.y+r.h-12){draggedPanel=id;resizePanel=true;dragOriginX=r.w;dragOriginY=r.h;dragPointerX=x;dragPointerY=y;return true;}}
            }
        }
        if(event.button()==0&&panels&&!help&&!transform&&menu<0&&inHierarchyBody(x,y)){ // empty space in the Hierarchy: nothing is picked
            for(var e:ClientState.savedSelections())if(e.active()){action("saved_selection:release");break;}
            return true;
        }
        if(event.button()==1&&!help&&!transform&&menu<0)for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(!c.contains(x,y))continue;for(var area:renameAreas)if(area.row()==c){openRowMenu(area.id(),(int)x,(int)y);return true;}break;} // a right click on a row of the Hierarchy opens its menu
        if(event.button()==1&&panels&&!help&&!transform&&menu<0&&inHierarchyBody(x,y)){ // empty space in the Hierarchy opens its own menu; a row has taken the click already
            boolean overRow=false;for(var area:renameAreas)if(area.row().contains(x,y))overRow=true;
            if(!overRow){openHierarchyMenu((int)x,(int)y);return true;}
        }
        if(event.button()==1&&!help&&!transform&&menu<0)for(int i=controls.size()-1;i>=0;i--){var c=controls.get(i);if(c.contains(x,y)){if(c.back!=null){c.back.run();return true;}break;}} // a cycling button steps back
        if(help||transform||menu>=0)return true;
        if(!inViewport(x,y))return true;
        if(PlacementPreview.active()&&EditorBindings.matches(Action.ROTATE_PLACEMENT,event)){perform(Action.ROTATE_PLACEMENT);return true;}
        if(PlacementPreview.active()&&EditorBindings.matches(Action.FLIP_PLACEMENT,event)){perform(Action.FLIP_PLACEMENT);return true;}
        if(EditorBindings.matches(Action.EXTRUDE_POINT,event)&&perform(Action.EXTRUDE_POINT))return true;
        for(Action binding:Action.values())if(EditorBindings.get(binding).mouse()&&EditorBindings.matches(binding,event)&&perform(binding))return true;
        // A press on a gizmo handle is the gizmo's, even with the camera's modifier already held: Ctrl then a drag moves the handle, it does not orbit.
        if((EditorBindings.matches(Action.ARCBALL,event)||EditorBindings.matches(Action.PAN_CAMERA,event)||EditorBindings.matches(Action.ADJUST_RADIUS,event))&&!overHandle(event)){
            cameraGesture=EditorBindings.matches(Action.ARCBALL,event)?CameraGesture.ORBIT:EditorBindings.matches(Action.PAN_CAMERA,event)?CameraGesture.PAN:CameraGesture.RADIUS;cameraButton=event.button();gestureX=event.x();gestureY=event.y();initialRadius=o().radius;
            var hit=target();orbitPivot=hit==null?minecraft.player.getEyePosition().add(minecraft.player.getLookAngle().scale(12)):hit.getLocation();orbitDistance=Math.max(1,orbitPivot.distanceTo(minecraft.player.getEyePosition()));return true;
        }
        if(event.button()==0)Interactions.of(o().tool).grabHandle(context,event.x(),event.y()); // a tool whose points all show a gizmo can select the one under the pointer
        if(event.button()==0&&TransformGizmo.click(this,event.x(),event.y()))return true;
        if(event.button()==0&&!PlacementPreview.active()){
            var points=handleNodes();double best=144;int index=-1;for(int i=0;i<points.size();i++){var pixel=project(TransformGizmo.nodeAnchor(points.get(i),i));if(pixel==null)continue;double distance=Math.pow(event.x()-pixel[0],2)+Math.pow(event.y()-pixel[1],2);if(distance<best){best=distance;index=i;}}
            if(index>=0){selectedNode=index;if(!toolActive)ClientState.selectedRuler=rulerOfNode(index);draggingNode=true;placementDragOrigin=points.get(index);placementDragX=event.x();placementDragY=event.y();return true;}
            if(!toolActive){var line=rulerAt(event.x(),event.y());if(line!=null){ClientState.selectedRuler=line;selectedNode=-1;TransformGizmo.cancel();return true;}} // no tool chosen: a click on a ruler picks it, without choosing the Ruler tool
        }
        if(PlacementPreview.active()){
            if(event.button()==1){var hit=target();if(hit!=null){Point p=CraftiumClient.point(hit.getBlockPos()),n=CraftiumClient.normal(hit);PlacementPreview.origin=p.add(n.x(),n.y(),n.z());}return true;}
            if(event.button()==0&&pickPlacementAxis(event.x(),event.y())){draggingPlacement=true;placementDragOrigin=PlacementPreview.origin;placementDragX=event.x();placementDragY=event.y();return true;}
        }
        if(!toolActive&&EditorBindings.matches(Action.CROSSHAIR,event)){cameraGesture=CameraGesture.ROTATE;cameraButton=event.button();return true;}
        if(EditorBindings.matches(Action.ROTATE_CAMERA,event)){cameraGesture=CameraGesture.ROTATE;cameraButton=event.button();return true;}
        if(toolActive&&EditorBindings.matches(Action.USE_TOOL,event)){Interactions.of(o().tool).strokeBegan(context);painting=true;ClientState.strokeId=System.nanoTime();lastDragged=null;useQueued=false;if(!Interactions.of(o().tool).pressed(context,event.x(),event.y()))use();}
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
    @Override public boolean mouseReleased(MouseButtonEvent event){if(rowDragId>=0&&event.button()==0){mx=(int)(event.x()/scale);my=(int)(event.y()/scale);finishRowDrag();return true;}if(selectingText){selectingText=false;return true;}if(dragBlock!=null&&event.button()==0){finishBlockDrag(event);return true;}if(cameraGesture!=CameraGesture.NONE&&event.button()==cameraButton){if(pendingPick&&!middleDragged)pickBlock(pickedHit);pendingPick=false;cameraGesture=CameraGesture.NONE;cameraButton=-1;return true;}if(event.button()==0&&TransformGizmo.release(this))return true;ReferenceImages.release();if(draggingNode)handlesMoved();draggingNode=false;draggingPlacement=false;dragging=null;if(!draggedPanel.isBlank()){WorkspaceLayout.snap(draggedPanel,w,h);WorkspaceLayout.save();draggedPanel="";}if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&EditorBindings.get(Action.USE_TOOL).mouse()){if(painting)Interactions.of(o().tool).strokeEnded(context);if(painting&&sculpts(o().tool))CraftiumClient.send("stroke_end",null,null);painting=false;ClientState.strokeId=0;Interactions.of(o().tool).released(context);}return true;}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {
        if(rowDragId>=0){mx=(int)(event.x()/scale);my=(int)(event.y()/scale);if(!rowDragMoved&&Math.hypot(event.x()-rowDragX,event.y()-rowDragY)>5)rowDragMoved=true;return true;}
        if(dragBlock!=null){if(!dragMoved&&Math.hypot(event.x()-dragStartX,event.y()-dragStartY)>5)dragMoved=true;return true;}
        if(ReferenceImages.drag(event.x(),event.y()))return true;
        pointerX=event.x();pointerY=event.y();
        if(typing!=null&&selectingText){
            for(var a:sliderAreas)if(a.key().equals(typing.key())){entry.drag(WorkspaceLayout.inverse(a.control().panel(),event.x()/scale,event.y()/scale)[0],a.slider().x(),a.slider().width());break;}
            return true;
        }
        if(cameraGesture!=CameraGesture.NONE){
            // A few pixels of wobble on a middle click is still a click, not a pan.
            if(pendingPick&&!middleDragged){if(Math.hypot(event.x()-gestureX,event.y()-gestureY)<=3)return true;middleDragged=true;}
            if(cameraGesture==CameraGesture.RADIUS)o().radius=Math.clamp(initialRadius+(int)Math.round(((event.x()-gestureX)-(event.y()-gestureY))/8),1,dev.craftium.core.ShapeLimits.maxRadius(o()));
            else if(cameraGesture==CameraGesture.ROTATE)minecraft.player.turn(dx*2,dy*2);
            else if(cameraGesture==CameraGesture.ORBIT){
                minecraft.player.turn(dx*2,dy*2);Vec3 eye=orbitPivot.subtract(minecraft.player.getLookAngle().scale(orbitDistance));Vec3 to=new Vec3(eye.x,eye.y-minecraft.player.getEyeHeight(),eye.z);
                // The player is moved a few times between two ticks, but the server only sees the position at each tick, so a whip of the mouse is spread over several ticks where the server would put the player back.
                long now=System.nanoTime();if(orbitRef==null||now-orbitRefAt>=50_000_000L){orbitRef=minecraft.player.position();orbitRefAt=now;}
                double limit=CraftiumClient.stepLimit(),away=to.distanceTo(orbitRef);
                if(away>limit)to=orbitRef.add(to.subtract(orbitRef).scale(limit/away));
                minecraft.player.setPos(to.x,to.y,to.z);
            }
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
        if(dragging!=null){double px=WorkspaceLayout.inverse(draggingSliderPanel,event.x()/scale,event.y()/scale)[0];if(dragging.unbounded())dragging.drag(px,sliderStartX,sliderStartValue,minecraft.hasControlDown(),minecraft.hasShiftDown());else dragging.update(px,minecraft.hasControlDown(),minecraft.hasShiftDown());return true;}
        if(help||transform||menu>=0)return true;
        if(inViewport(event.x()/scale,event.y()/scale)) {
            if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&Interactions.of(o().tool).dragged(context,event.x(),event.y()))return true;
            if(event.button()==EditorBindings.get(Action.USE_TOOL).key()&&painting&&Interactions.of(o().tool).repeatsWhenDragged())dragUse();
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
        if(panels&&WorkspaceLayout.hit(x/scale,y/scale,false).equals("hierarchy")){savedScroll=Math.max(0,savedScroll+(vertical<0?1:-1));return true;}
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
        if(EditorBindings.held(Action.ADJUST_RADIUS)){o().radius=Math.clamp(o().radius+(int)Math.signum(vertical),1,dev.craftium.core.ShapeLimits.maxRadius(o()));return true;}
        if(!toolActive){zoomToCursor(x,y,vertical);return true;} // no tool chosen: the wheel is the camera's
        if(ShapeObject.active()&&vertical!=0){ShapeObject.adjustRadius((int)Math.signum(vertical));return true;} // the wheel sizes the shape that is down
        if(TransformGizmo.available(this)){TransformGizmo.nudge(this,EditorControls.nudge(vertical>0?Action.NUDGE_FORWARD:Action.NUDGE_BACK,minecraft.player.getYRot()));return true;}
        zoomToCursor(x,y,vertical);return true; // the tool has nothing for the wheel to do, so it is the camera's
    }
    @Override public boolean charTyped(CharacterEvent event){if(typing!=null){entry.type(event.codepointAsString());return true;}for(EditBox field:fields.values())if(field.isFocused())return field.charTyped(event);return false;}
    /** Keys currently held, so a repeat of a key already down (a movement key held while Ctrl goes down) is not read as a fresh shortcut such as Ctrl+D. */
    private final java.util.Set<Integer> keysDown=new java.util.HashSet<>();
    @Override public boolean keyReleased(KeyEvent event){keysDown.remove(event.key());return super.keyReleased(event);}
    /** The gizmo axes the held X, Y and Z keys lock, as a mask: none while typing or with a modifier held, as Ctrl+X is Cut. */
    int axisLock(){
        if(typing!=null||minecraft.hasControlDown()||minecraft.hasAltDown())return 0;
        for(EditBox field:fields.values())if(field.isFocused())return 0;
        return (keysDown.contains(GLFW.GLFW_KEY_X)?1:0)|(keysDown.contains(GLFW.GLFW_KEY_Y)?2:0)|(keysDown.contains(GLFW.GLFW_KEY_Z)?4:0);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        boolean repeat=!keysDown.add(event.key());
        if(typing!=null){
            switch(entry.key(event)){case APPLY->finishTyping(true);case CANCEL->finishTyping(false);default->{}}
            return true;
        }
        for(EditBox field:fields.values())if(field.isFocused()){
            if(renaming>=0&&field==fields.get("rename")){
                if(event.key()==GLFW.GLFW_KEY_ESCAPE){cancelRename();return true;}
                if(event.key()==GLFW.GLFW_KEY_ENTER||event.key()==GLFW.GLFW_KEY_KP_ENTER){commitRename();return true;}
            }
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
        // With a gizmo showing, plain X, Y and Z lock its axes, as in the Builder; they are not the editor's hotkeys then.
        if((event.modifiers()&7)==0&&(event.key()==GLFW.GLFW_KEY_X||event.key()==GLFW.GLFW_KEY_Y||event.key()==GLFW.GLFW_KEY_Z)&&TransformGizmo.available(this))return true;
        // Shared chords are resolved by the active context, before ordinary tool letters.
        if((Interactions.of(o().tool).turnsPlacement()||ShapeObject.active())&&!PlacementPreview.active()&&turnShape(event))return true;
        if(PlacementPreview.active()){
            if(EditorBindings.matches(Action.ROTATE_PLACEMENT,event)){action("rotate");return true;}
            if(EditorBindings.matches(Action.FLIP_PLACEMENT,event)){action("flip_facing");return true;}
        }
        if(EditorBindings.matches(Action.EXTRUDE_POINT,event)&&Interactions.of(o().tool).extrudePoint(context))return true;
        if(polygonLike(o().shape)&&o().tool!=Tool.TEXT&&!o().tool.transform()){
            int step=event.key()==GLFW.GLFW_KEY_EQUAL||event.key()==GLFW.GLFW_KEY_KP_ADD?1:event.key()==GLFW.GLFW_KEY_MINUS||event.key()==GLFW.GLFW_KEY_KP_SUBTRACT?-1:0;
            if(step!=0){o().polygonSides=Math.clamp(o().polygonSides+step,BrushMath.MIN_SIDES,BrushMath.MAX_SIDES);return true;}
        }
        if(toolActive&&ToolHints.brush(o().tool)&&(event.modifiers()&7)==0&&(event.key()==GLFW.GLFW_KEY_LEFT_BRACKET||event.key()==GLFW.GLFW_KEY_RIGHT_BRACKET)){ // [ and ] shrink and grow the brush
            o().radius=Math.clamp(o().radius+(event.key()==GLFW.GLFW_KEY_RIGHT_BRACKET?1:-1),1,dev.craftium.core.ShapeLimits.maxRadius(o()));return true;
        }
        // The keypad's + and -, and Ctrl+Shift+= (which is +), also resize the interface when the main bindings are the defaults.
        if((event.modifiers()&7)==EditorControls.CONTROL){int key=event.key();if(key==GLFW.GLFW_KEY_KP_ADD){resizeInterface(1);return true;}if(key==GLFW.GLFW_KEY_KP_SUBTRACT){resizeInterface(-1);return true;}}
        if((event.modifiers()&7)==(EditorControls.CONTROL|EditorControls.SHIFT)&&event.key()==GLFW.GLFW_KEY_EQUAL){resizeInterface(1);return true;}
        for(Action binding:Action.values())if(!EditorBindings.get(binding).mouse()&&EditorBindings.matches(binding,event)&&perform(binding))return true;
        if(event.key()==GLFW.GLFW_KEY_BACKSPACE&&(event.modifiers()&7)==0){perform(Action.DELETE);return true;}
        if((event.modifiers()&7)==0)for(var entry:CraftiumClient.toolKeys.entrySet())if(entry.getValue().matches(event)){select(entry.getKey());return true;}
        if((event.modifiers()&7)==0&&event.key()==GLFW.GLFW_KEY_T&&!repeat){toggleTransparency();return true;} // T: transparency, unless a tool has taken the key
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
            case CONFIRM -> {if(ClientState.hasPending())CraftiumClient.send("pending_confirm",null,null);else if(ShapeObject.active())ShapeObject.confirm();else if(PlacementPreview.active())PlacementPreview.confirm();else action(Interactions.of(o().tool).confirmAction());}
            case UNDO,UNDO_ALT -> action("undo");
            case DESELECT -> action("clear");
            case FOCUS_OBJECT -> {if(!focusBuildObject())return false;}
            case RENAME_OBJECT -> {
                var shown=new ArrayList<dev.craftium.net.Packets.SavedSelection>();for(var e:ClientState.savedSelections())if(e.active())shown.add(e);
                if(shown.size()!=1){ClientState.notify(shown.isEmpty()?"Show a BuildObject first: click one in the Hierarchy":"Rename one BuildObject at a time");return true;}
                int id=shown.getFirst().id();
                for(var p=savedById(shown.getFirst().parent());p!=null;p=savedById(p.parent()))if(ClientState.savedFolded(p.id()))ClientState.toggleSavedFolded(p.id()); // it has to be a row to be renamed in
                int at=0;for(int i=0;i<hierarchyRows.size();i++)if(hierarchyRows.get(i).id()==id)at=i;
                savedScroll=Math.clamp(savedScroll,Math.max(0,at-hierarchyRowCount+1),at); // and in view
                startRename(new RenameArea(null,id));
            }
            case DUPLICATE_OBJECT -> duplicateObjects();
            case GROUP_OBJECTS -> {
                var shown=new ArrayList<String>();for(var e:ClientState.savedSelections())if(e.active())shown.add(String.valueOf(e.id()));
                if(shown.isEmpty())ClientState.notify("Show a BuildObject first: click one in the Hierarchy");
                else action("saved_selection:group:"+String.join(",",shown)); // an empty parent around the BuildObjects shown
            }
            case TOGGLE_MOVE -> toggleTool(Tool.MOVE);case TOGGLE_ROTATE -> toggleTool(Tool.ROTATE);case TOGGLE_SCALE -> toggleTool(Tool.SCALE);case TOGGLE_PIVOT -> toggleTool(Tool.PIVOT);
case REDO -> action("redo");case COPY -> action("copy");case CUT -> cutShown();case DUPLICATE -> action("duplicate");case PASTE -> action("paste");case PASTE_IN_PLACE -> PlacementPreview.beginInPlace();
            case COPY_AIR -> {boolean old=o().pasteAir;o().pasteAir=true;action("copy");o().pasteAir=old;}
            case DELETE -> {if(deleteRuler()){}else if(ShapeObject.active())ShapeObject.cancel();else if(PlacementPreview.active())PlacementPreview.cancel();else action("delete");}
            case SWAP_BLOCKS -> swapBlocks();
            case QUICK_FILL -> action("fill");case QUICK_REPLACE -> action("replace");
            case ROTATE_PLACEMENT -> {if(!PlacementPreview.active())return false;action("rotate");}
            case FLIP_PLACEMENT -> {if(!PlacementPreview.active())return false;action("flip_facing");}
            case EXTRUDE_POINT -> {if(!Interactions.of(o().tool).extrudePoint(context))return false;}
            case SAVE_BLUEPRINT -> {if(ClientState.status.clipboardSize()==0&&ClientState.status.clipboardEntityCount()==0)ClientState.notify("Copy a selection first");else ClientCompat.setScreen(minecraft,new SaveBlueprintScreen(this));}
            case SHOW_BIOMES -> o().showBiomes=!o().showBiomes;
            case CYCLE_SELECTION_STYLE -> CraftiumClient.cycleSelectionStyle();
            case SHOW_SELECTION -> showSelection=!showSelection;case SHOW_ANNOTATIONS -> o().showAnnotations=!o().showAnnotations;
            case SHOW_DISPLAYS -> o().showDisplayGizmos=!o().showDisplayGizmos;case SHOW_MARKERS -> o().showMarkerGizmos=!o().showMarkerGizmos;
            case SHOW_COLLISION -> o().showCollisionMesh=!o().showCollisionMesh;case SHOW_LIGHTS -> o().showLightBlocks=!o().showLightBlocks;case SHOW_VOID -> o().showStructureVoid=!o().showStructureVoid;
            case NUDGE_FORWARD,NUDGE_BACK,NUDGE_LEFT,NUDGE_RIGHT,NUDGE_UP,NUDGE_DOWN -> TransformGizmo.nudge(this,EditorControls.nudge(binding,minecraft.player.getYRot()));
            case PASTE_SELECT -> PlacementPreview.pasteAndSelect();case LAST_TOOL -> select(previousTool);
            case INSPECTOR -> ClientCompat.setScreen(minecraft,new StudioScreen(this));case HELP -> help=!help;case PANELS -> panels=!panels;case INTERFACE_LARGER -> resizeInterface(1);case INTERFACE_SMALLER -> resizeInterface(-1);
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
    private List<Point> handleNodes(){
        if(!toolActive)return ClientState.rulerPoints;
        var points=Interactions.of(o().tool).points(context);
        return points!=null?points:new ArrayList<>();
    }
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
        if(!o().rulersIntroduced){ // the first time a ruler is used the window comes up, once; after that it is the player's to open and close
            var window=WorkspaceLayout.windows.get("rulers");
            if(window!=null){o().rulersIntroduced=true;if(!window.visible){window.visible=true;WorkspaceLayout.save();}ClientState.save();}
        }
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
    private int savedScroll;
    private static final String[] PENCIL={"......XX.",".....XXXX","....XXXX.","...XXXX..","..XXXX...",".XXXX....",".XXX.....","XX......."};
    private static final String[] PLUS={"....X....","....X....","....X....","....X....","XXXXXXXXX","....X....","....X....","....X....","....X...."};
    private static final String[] EYE={"..XXXXX..",".X.....X.","X..XXX..X",".X.....X.","..XXXXX.."};
    private static final String[] CROSS={"X.......X",".X.....X.","..X...X..","...X.X...","....X....","...X.X...","..X...X..",".X.....X.","X.......X"};
    /** A small picture drawn pixel by pixel from rows of X, centred on the given box. */
    private void glyph(Gfx g,int x,int y,int width,int height,String[] rows,int colour){
        int gw=rows[0].length(),ox=x+(width-gw)/2,oy=y+(height-rows.length)/2;
        for(int r=0;r<rows.length;r++)for(int c=0;c<gw;c++)if(rows[r].charAt(c)=='X')g.fill(ox+c,oy+r,ox+c+1,oy+r+1,colour);
    }
    /** "3 x 4 x 2": how many blocks a saved selection spans along each axis. */
    private static String savedSpan(dev.craftium.net.Packets.SavedSelection s){return (s.max().x()-s.min().x()+1)+" x "+(s.max().y()-s.min().y()+1)+" x "+(s.max().z()-s.min().z()+1);}
    /** The Hierarchy panel, the top of the right column: every selection saved by name, to look at (click), edit (pencil) or delete (cross). The player's own selection is only swapped out while one is looked at, and given back when it is let go. It is the list BuildObjects will grow into. */
    /** The ids of the group rows start here, apart from those of BuildObjects, so one number says which row it is (see {@link HRow#id}). */
    private static final int GROUP_BASE=1_000_000;
    private static final int GROUP_BAND=0xff212832,GROUP_TEXT=0xffd9e3ec;
    /** A row of the Hierarchy: a group's heading, or a BuildObject. */
    private record HRow(dev.craftium.net.Packets.SavedGroup group,dev.craftium.net.Packets.SavedSelection entry){int id(){return group!=null?GROUP_BASE+group.id():entry.id();}}
    /** The group a BuildObject is in as far as the Hierarchy is concerned: its own, or the first when the server named one that is not there. 0 when there are no groups. */
    private int groupIdOf(dev.craftium.net.Packets.SavedSelection e){
        var groups=ClientState.savedGroups();if(groups.isEmpty())return 0;
        for(var gr:groups)if(gr.id()==e.group())return gr.id();
        return groups.getFirst().id();
    }
    private int groupIdOfRow(HRow row){return row.group()!=null?row.group().id():groupIdOf(row.entry());}
    private dev.craftium.net.Packets.SavedGroup groupById(int id){for(var gr:ClientState.savedGroups())if(gr.id()==id)return gr;return null;}
    /** The BuildObjects at the top level in a group (all of them when {@code group} is 0). */
    private int rootCount(int group){int k=0;for(var e:hierarchyAll)if(e.parent()==0&&(group==0||groupIdOf(e)==group))k++;return k;}
    private void hierarchyPanel(Gfx g){
        hierarchyAll=ClientState.savedSelections();
        var groups=ClientState.savedGroups();
        var saved=new ArrayList<HRow>(); // the rows shown: a heading for each group, and in it everything but what is under a folded BuildObject
        for(var gr:groups.isEmpty()?java.util.Collections.singletonList((dev.craftium.net.Packets.SavedGroup)null):groups){
            if(gr!=null){saved.add(new HRow(gr,null));if(ClientState.groupFolded(gr.id()))continue;}
            for(var e:hierarchyAll){
                if(gr!=null&&groupIdOf(e)!=gr.id())continue;
                boolean shown=true;int n=0;for(var p=savedById(e.parent());p!=null&&n<64;p=savedById(p.parent()),n++)if(ClientState.savedFolded(p.id())){shown=false;break;}
                if(shown)saved.add(new HRow(null,e));
            }
        }
        hierarchyRows=saved;
        var parents=new HashSet<Integer>();for(var e:hierarchyAll)parents.add(e.parent());
        int x=w-RIGHT,y=27;
        panel(g,x,y,RIGHT,Math.max(90,groupTop()-6-y),"Hierarchy");
        boolean looking=ClientState.activeSaved()!=null;
        int top=y+28,bottom=y+ph-36,rowHeight=20,rows=Math.max(1,(bottom-top)/rowHeight),total=saved.size();
        g.fill(x+8,top-2,x+pw-8,top+rows*rowHeight+1,0xff141c26);g.outline(x+8,top-2,pw-16,rows*rowHeight+3,LINE);
        boolean renameShown=false;hierarchyTop=top;hierarchyRow=rowHeight;hierarchyRowCount=rows;
        ClientState.hoverSaved=0; // the row the pointer is on is drawn in the world, with what is under it
        if(menu==ROW_MENU)ClientState.hoverSaved=rowMenuRow;
        else if(menu<0&&pointerFree()&&rowDragId<0&&WorkspaceLayout.visible("hierarchy")&&!WorkspaceLayout.folded("hierarchy")){
            var at=WorkspaceLayout.inverse("hierarchy",mx,my);
            if(at[0]>=x+8&&at[0]<x+pw-8&&at[1]>=top&&at[1]<top+rows*rowHeight){int over=savedScroll+(int)((at[1]-top)/rowHeight);if(over<saved.size()&&saved.get(over).entry()!=null)ClientState.hoverSaved=saved.get(over).entry().id();}
        }
        savedScroll=Math.clamp(savedScroll,0,Math.max(0,total-rows));
        if(hierarchyAll.isEmpty()&&groups.isEmpty())text(g,"No BuildObjects yet",x+16,top+6,MUTED);
        for(int i=0;i<rows&&savedScroll+i<total;i++){
            var shownRow=saved.get(savedScroll+i);int ry=top+i*rowHeight;
            int rowRight=total>rows?x+pw-17:x+pw-9; // a scrollbar, when there is one, has the right edge to itself
            int edge=total>rows?x+pw-18:x+pw-10,eye=edge-18; // the eye keeps to the left of the scrollbar
            if(shownRow.group()!=null){ // a group's heading: its name in a lighter colour on a band of its own
                var group=shownRow.group();int gid=GROUP_BASE+group.id(),nameX=x+14+12;boolean folded=ClientState.groupFolded(group.id());
                g.fill(x+9,ry,rowRight,ry+rowHeight,GROUP_BAND);
                if(renaming==gid){renameShown=true;var box=renameBox(group.name());box.setX(nameX);box.setY(ry+4);box.setWidth(eye-4-nameX);}
                else text(g,shorten(group.name(),eye-4-nameX),nameX,ry+6,GROUP_TEXT);
                if(rowDragMoved&&rowDragId==gid)g.fill(x+9,ry,x+pw-9,ry+rowHeight,0xaa141c26);
                var headControl=new Control(x+9,ry,rowRight-(x+9),rowHeight,()->{},HIDDEN_TIP+"Group · The arrow folds it · Double-click to rename · Drag to move it · Right-click for more");controls.add(headControl);renameAreas.add(new RenameArea(headControl,gid));
                foldArrow(g,x+15,folded?ry+6:ry+7,folded,GROUP_TEXT);
                controls.add(new Control(x+11,ry,14,rowHeight,()->ClientState.toggleGroupFolded(group.id()),folded?"Show this group's BuildObjects":"Fold this group's BuildObjects"));
                var members=new ArrayList<Integer>();for(var e:hierarchyAll)if(groupIdOf(e)==group.id())members.add(e.id());
                boolean allHidden=!members.isEmpty()&&members.stream().allMatch(ClientState::savedHidden);
                button(g,eye,ry+2,18,rowHeight-4,"",false,()->{for(int m:members)ClientState.setSavedHidden(m,!allHidden);},allHidden?"Show the outlines of everything in this group":"Hide the outlines of everything in this group");
                glyph(g,eye,ry+2,18,rowHeight-4,EYE,allHidden?0xff56606e:TEXT);
                if(allHidden){int ox=eye+(18-9)/2,oy=ry+2+(rowHeight-4-5)/2;for(int k=0;k<9;k++)g.fill(ox+k,oy+4-k/2,ox+k+1,oy+5-k/2,0xffe07070);}
                continue;
            }
            var entry=shownRow.entry();int id=entry.id();
            int indent=Math.min(depthOf(entry),6)*12,nameX=x+14+indent+12; // a level in is 12 further right; the arrow takes the first 12
            if(entry.active())g.fill(x+9,ry,rowRight,ry+rowHeight,entry.editing()?0xff5b4a84:0xff315b84);
            if(renaming==id){
                renameShown=true;var box=renameBox(entry.name());box.setX(nameX);box.setY(ry+4);box.setWidth(eye-4-nameX);
            }else text(g,shorten(entry.name(),eye-4-nameX),nameX,ry+6,entry.active()?TEXT:MUTED);
            if(rowDragMoved&&rowDragIds.contains(id))g.fill(x+9,ry,x+pw-9,ry+rowHeight,0xaa141c26); // the one being dragged is left looking emptied
            // later controls win where they overlap, so the row comes first and the cells and buttons on it after
            var rowControl=new Control(x+9,ry,rowRight-(x+9),rowHeight,()->rowClick(entry),HIDDEN_TIP+savedSpan(entry)+" blocks · "+(entry.active()&&!entry.editing()?"Let go":"Select this BuildObject")); controls.add(rowControl);renameAreas.add(new RenameArea(rowControl,id));
            if(parents.contains(id)){ // something is under it: an arrow folds it
                boolean folded=ClientState.savedFolded(id);foldArrow(g,x+15+indent,folded?ry+6:ry+7,folded,MUTED); // centred a pixel above the middle of the row
                controls.add(new Control(x+11+indent,ry,14,rowHeight,()->ClientState.toggleSavedFolded(id),folded?"Show what is under this BuildObject":"Fold what is under this BuildObject"));
            }
            boolean hidden=ClientState.savedHidden(id);
            button(g,eye,ry+2,18,rowHeight-4,"",false,()->eyeClick(entry,minecraft.hasControlDown()),hidden?"Show this BuildObject's outline in the world · It shows while it is selected, whatever the eye · Ctrl+click does the same for everything under it":"Hide this BuildObject's outline in the world · Ctrl+click does the same for everything under it");
            glyph(g,eye,ry+2,18,rowHeight-4,EYE,hidden?0xff56606e:TEXT);
            if(hidden){int ox=eye+(18-9)/2,oy=ry+2+(rowHeight-4-5)/2;for(int k=0;k<9;k++)g.fill(ox+k,oy+4-k/2,ox+k+1,oy+5-k/2,0xffe07070);} // a slash through it
        }
        if(total>rows){
            int track=rows*rowHeight,thumb=Math.max(10,track*rows/total),thumbY=top+(track-thumb)*savedScroll/Math.max(1,total-rows);
            g.fill(x+pw-14,top,x+pw-10,top+track,0xff1b2b40);g.fill(x+pw-14,thumbY,x+pw-10,thumbY+thumb,ACCENT);
        }
        if(rowDragMoved){ // where the dragged row would land: a line between rows, or a frame round the row it would go under
            var drop=dropAt();
            if(drop!=null&&total>0){
                int at=drop.row()-savedScroll,ry=top+at*rowHeight;
                var target=saved.get(Math.clamp(drop.row(),0,total-1));
                int indentTo=x+14+(target.entry()==null?0:Math.min(depthOf(target.entry()),6)*12);
                if(at>=0&&at<rows)switch(drop.kind()){
                    case DROP_INTO->g.outline(x+9,ry,pw-18,rowHeight,ACCENT);
                    case DROP_ABOVE,DROP_GROUP_BEFORE->g.fill(drop.kind()==DROP_GROUP_BEFORE?x+9:indentTo,ry-1,x+pw-9,ry+1,ACCENT);
                    case DROP_FIRST_CHILD->g.fill(indentTo+12,ry+rowHeight-1,x+pw-9,ry+rowHeight+1,ACCENT);
                    default->g.fill(drop.kind()==DROP_GROUP_AFTER?x+9:indentTo,ry+rowHeight-1,x+pw-9,ry+rowHeight+1,ACCENT);
                }
            }
        }
        if(renaming>=0&&!renameShown)cancelRename(); // scrolled out of view or deleted
        else if(renaming>=0&&fields.get("rename")!=null&&!fields.get("rename").isFocused())commitRename(); // a click elsewhere keeps the name
        g.fill(x+1,y+ph-30,x+pw-1,y+ph-29,LINE); // the buttons along the bottom
        button(g,x+10,y+ph-25,24,20,"",false,()->action("saved_selection:save"),looking?"Create a BuildObject from what is selected now · While one is shown, the new one goes around it":"Create a BuildObject from what is selected now · Any BuildObject the selection touches goes inside it, whole");
        glyph(g,x+10,y+ph-25,24,20,PLUS,TEXT);
    }
    /**
     * The fold arrow of a Hierarchy row, drawn in pixels so it keeps its shape at any interface size: a 7 by 7 box at {@code x}, {@code y}, a triangle
     * of four rows pointing down when open and four columns pointing right when folded. It sits one pixel higher than the font's own arrows did.
     */
    private static void foldArrow(Gfx g,int x,int y,boolean folded,int color){
        for(int i=0;i<4;i++){
            int span=7-2*i; // 7, 5, 3, 1
            if(folded)g.fill(x+i,y+i,x+i+1,y+i+span,color);else g.fill(x+i,y+i,x+i+span,y+i+1,color);
        }
    }
    /** Opens or closes the eye of a BuildObject; with Ctrl, of everything under it as well, all to the new state. */
    private void eyeClick(dev.craftium.net.Packets.SavedSelection entry,boolean below){
        boolean hide=!ClientState.savedHidden(entry.id());
        for(var e:hierarchyAll)if(e.id()==entry.id()||below&&isUnder(e,entry.id()))ClientState.setSavedHidden(e.id(),hide);
    }
    /** What happens next, once the server has answered what was just sent and the answer has settled: a copy that a duplicate waits for, a cut that is followed by a delete. */
    private Runnable afterStatus;private dev.craftium.net.Packets.Status statusWas;private long afterFrom,afterUntil;
    private void afterNextStatus(Runnable run){
        statusWas=ClientState.status;long now=System.currentTimeMillis();afterFrom=now+150;afterUntil=now+5000; // the server drops requests that come too close together
        afterStatus=run;
    }
    private void runAfterStatus(){
        if(afterStatus==null)return;
        long now=System.currentTimeMillis();
        if(now>afterUntil){afterStatus=null;return;}
        if(now>=afterFrom&&ClientState.status!=statusWas&&!ClientState.status.busy()){var run=afterStatus;afterStatus=null;run.run();}
    }
    /** Ctrl+D: copies the blocks of the BuildObjects shown (with everything under them), then starts a placement where they were copied from; confirming it pastes them as a selection and makes a new BuildObject of them. Move the placement first to put the copy somewhere else. */
    private void duplicateObjects(){
        var shown=new ArrayList<dev.craftium.net.Packets.SavedSelection>();for(var e:ClientState.savedSelections())if(e.active())shown.add(e);
        if(shown.isEmpty()){ClientState.notify("Show a BuildObject first: click one in the Hierarchy");return;}
        String name=shown.size()==1?(shown.getFirst().name()+" (copy)"):"BuildObject";if(name.length()>40)name=name.substring(0,40);
        final String made=name;final int after=shown.getLast().id();
        action("saved_selection:duplicate");
        afterNextStatus(()->{
            if(ClientState.status.error())return;
            PlacementPreview.beginInPlace();
            if(PlacementPreview.active()){PlacementPreview.asObject=made;PlacementPreview.asObjectAfter=after;}
        });
    }
    /** Deletes these BuildObjects and every block they hold, asking first unless the player ticked "Don't show this message again". One step in the history. */
    private void deleteWithBlocks(List<Integer> ids){
        Runnable go=()->action("saved_selection:deleteblocks:"+String.join(",",ids.stream().map(String::valueOf).toList()));
        if(!o().warnDeleteBlocks){go.run();return;}
        confirmOptOut=false;
        confirm=new Confirm("Delete the BuildObject and its blocks in the world?\nCtrl+Z brings both back.","Delete blocks",go,"Don't show this message again",()->{o().warnDeleteBlocks=false;ClientState.save();});
    }
    /** Ctrl+X: cuts the blocks, and with BuildObjects shown takes them along, as the menu's Cut does. */
    private void cutShown(){
        var shown=new ArrayList<Integer>();for(var e:ClientState.savedSelections())if(e.active())shown.add(e.id());
        if(shown.isEmpty())action("cut");else cutObjects(shown);
    }
    /** Cuts the blocks of the BuildObjects shown, and then the BuildObjects themselves, so a paste is where they live now. */
    private void cutObjects(List<Integer> ids){
        action("cut");
        afterNextStatus(()->{if(!ClientState.status.error())action("saved_selection:deleteset:"+String.join(",",ids.stream().map(String::valueOf).toList()));});
    }
    /** Starts a placement of the clipboard where it was copied from, and confirming it also makes a BuildObject of the pasted blocks, after {@code after}. */
    private void pasteAsObject(int after){
        PlacementPreview.beginInPlace();
        if(PlacementPreview.active()){PlacementPreview.asObject="BuildObject";PlacementPreview.asObjectAfter=after;}
    }
    /** Whether the point is in the body of the Hierarchy window: under its title and over its buttons, where no control was hit. */
    private boolean inHierarchyBody(double x,double y){
        var r=WorkspaceLayout.windows.get("hierarchy");
        return r!=null&&r.visible&&!r.folded&&x>=r.x&&x<r.x+r.w&&y>=r.y+19&&y<r.y+r.h-30;
    }
    /** The row a span selection starts from: the one last clicked. */
    private int hierarchyAnchor;
    /**
     * A click on a row of the Hierarchy. A plain click shows that BuildObject alone (and lets go if it is the one shown); Ctrl adds it to the ones shown or takes it away;
     * Shift and Ctrl together add every row shown from the last one clicked to this one. The editor's selection is all of the BuildObjects shown, together.
     */
    private void rowClick(dev.craftium.net.Packets.SavedSelection entry){
        boolean ctrl=minecraft.hasControlDown(),shift=minecraft.hasShiftDown();
        var chosen=new ArrayList<Integer>();for(var e:hierarchyAll)if(e.active())chosen.add(e.id());
        if(ctrl&&shift){
            int from=-1,to=-1;for(int i=0;i<hierarchyRows.size();i++){int id=hierarchyRows.get(i).id();if(id==hierarchyAnchor)from=i;if(id==entry.id())to=i;}
            if(from<0)from=to;
            for(int i=Math.min(from,to);i<=Math.max(from,to);i++){if(hierarchyRows.get(i).entry()==null)continue;int id=hierarchyRows.get(i).id();if(!chosen.contains(id))chosen.add(id);}
        }else if(ctrl){
            if(!chosen.remove((Integer)entry.id()))chosen.add(entry.id());hierarchyAnchor=entry.id();
        }else{
            hierarchyAnchor=entry.id();
            action(entry.active()&&!entry.editing()&&chosen.size()==1?"saved_selection:release":"saved_selection:view:"+entry.id());return;
        }
        action("saved_selection:viewset:"+String.join(",",chosen.stream().map(String::valueOf).toList()));
    }
    /** F with exactly one BuildObject shown: the view goes back along the way it is looking until the middle of that BuildObject is in the middle of the screen and all of it fits between the panels. */
    private boolean focusBuildObject(){
        int shown=0;for(var e:ClientState.savedSelections())if(e.active())shown++;
        if(shown!=1||minecraft.player==null)return false;
        if(ClientState.status.selectionSize()==0){ClientState.notify("That BuildObject has no blocks to look at");return true;}
        return focusOn(ClientState.status.min(),ClientState.status.max());
    }
    /** Moves the view to face the middle of the box between the corners, back far enough that all of it fits between the panels. */
    private boolean focusOn(Point lo,Point hi){
        double sx=hi.x()-lo.x()+1,sy=hi.y()-lo.y()+1,sz=hi.z()-lo.z()+1;
        var centre=new Vec3(lo.x()+sx/2,lo.y()+sy/2,lo.z()+sz/2);
        double radius=Math.max(1,.5*Math.sqrt(sx*sx+sy*sy+sz*sz)); // a sphere round all of it, so it fits whichever way it is seen
        double half=Math.tan(Math.toRadians(ClientCompat.fov(minecraft,ClientCompat.camera(minecraft)))/2);
        double t=Math.min(half*(h-52)/h,half*(w-LEFT-RIGHT)/h); // how wide a view is left between the panels and the bars, as the tangent of its half angle
        double distance=Math.max(radius+1.5,radius*Math.sqrt(1+t*t)/t*1.08);
        var eye=centre.subtract(minecraft.player.getLookAngle().scale(distance));
        var to=new Vec3(eye.x,eye.y-minecraft.player.getEyeHeight(),eye.z);
        var from=minecraft.player.position();double away=to.distanceTo(from),limit=CraftiumClient.stepLimit();
        if(away>limit){to=from.add(to.subtract(from).scale(limit/away));ClientState.notify("That is further than this server lets you move at once: press F again");}
        minecraft.player.setPos(to.x,to.y,to.z);minecraft.player.setDeltaMovement(Vec3.ZERO);minecraft.player.setOldPosAndRot();
        return true;
    }
    // ---- the right-click menu of a row ---------------------------------------------------------------------------------------------------
    private List<MenuItem> rowMenu=List.of();private int rowMenuX,rowMenuY,rowMenuFrom,rowMenuRow;
    /** A tip that marks a control as a surface: it takes the pointer, shows no tooltip and no hover look. */
    private static final String BLOCK_TIP="";
    /** The BuildObjects the menu works on: the ones shown when the row is one of them, else the row alone, which the click has just chosen. */
    private List<Integer> rowMenuTargets(int id){
        var shown=new ArrayList<Integer>();for(var e:hierarchyAll)if(e.active())shown.add(e.id());
        return shown.contains(id)?shown:List.of(id);
    }
    private void openRowMenu(int id,int x,int y){
        if(id>=GROUP_BASE){openGroupMenu(id-GROUP_BASE,x,y);return;}
        var row=savedById(id);if(row==null)return;
        var targets=rowMenuTargets(id);int n=targets.size();String csv=String.join(",",targets.stream().map(String::valueOf).toList());
        if(!targets.equals(List.of(id))||!row.active())if(n==1)action("saved_selection:view:"+id); // right-clicking one chooses it, as in any list
        var items=new ArrayList<MenuItem>();
        var kids=new ArrayList<Integer>();for(var e:hierarchyAll)if(e.parent()==id)kids.add(e.id());
        boolean only=n==1&&row.active()&&!row.editing();
        if(!only||n>1)items.add(item("Select","",()->action("saved_selection:view:"+id)));
        if(!kids.isEmpty())items.add(item("Select children only","",()->action("saved_selection:viewset:"+String.join(",",kids.stream().map(String::valueOf).toList()))));
        items.add(row.active()&&row.editing()?item("Stop editing","",()->action("saved_selection:release")):item("Edit blocks","",()->action("saved_selection:edit:"+id)));
        items.add(divider());
        items.add(n>1?item("Group selected ("+n+")","Ctrl+G",()->action("saved_selection:group:"+csv)):item("Create empty parent",row.active()?"Ctrl+G":"",()->action("saved_selection:group:"+id)));
        if(ClientState.status.selectionSize()>0&&!row.active())items.add(item("Create child from selection","",()->action("saved_selection:createchild:"+id)));
        items.add(divider());
        items.add(item("Rename",n==1&&row.active()?"F2":"",()->startRename(new RenameArea(null,id))));
        items.add(item("Duplicate","Ctrl+D",this::duplicateObjects));
        items.add(item("Copy","Ctrl+C",()->action("copy")));
        items.add(item("Cut","Ctrl+X",()->cutObjects(targets)));
        if(ClientState.status.clipboardSize()>0&&ClientState.status.clipboardOrigin()!=null)items.add(item("Paste","",()->pasteAsObject(id)));
        items.add(n>1?item("Delete selected ("+n+")","",()->action("saved_selection:deleteset:"+csv)):item("Delete","",()->action("saved_selection:delete:"+id)));
        items.add(item(n>1?"Delete selected + blocks ("+n+")":"Delete blocks","",()->deleteWithBlocks(targets))); // the BuildObjects and the blocks they hold
        items.add(item("Export…","",()->exportStructure(StructureSettings.SELECTION))); // the blocks shown, which are everything under each BuildObject
        var moves=new ArrayList<MenuItem>();
        int at=siblingIndex(row);int siblings=siblingCount(row);String inGroup=row.parent()==0&&groupIdOf(row)>0?":"+groupIdOf(row):"";
        if(at>0)moves.add(item("Move up","",()->action("saved_selection:place:"+id+":"+row.parent()+":"+(at-1)+inGroup)));
        if(at<siblings-1)moves.add(item("Move down","",()->action("saved_selection:place:"+id+":"+row.parent()+":"+(at+1)+inGroup)));
        if(row.parent()!=0){var up=savedById(row.parent());if(up!=null)moves.add(item("Move out of parent","",()->action("saved_selection:place:"+id+":"+up.parent()+":"+(siblingIndex(up)+1)+(up.parent()==0&&groupIdOf(up)>0?":"+groupIdOf(up):""))));}
        if(row.parent()==0&&ClientState.savedGroups().size()>1)for(var gr:ClientState.savedGroups()){ // a BuildObject at the top goes to the end of another group
            if(gr.id()==groupIdOf(row))continue;if(moves.size()>=10)break;
            moves.add(item("Move to group: "+shorten(gr.name(),120),"",()->action("saved_selection:place:"+id+":0:"+rootCount(gr.id())+":"+gr.id())));
        }
        if(!kids.isEmpty()){boolean folded=ClientState.savedFolded(id);moves.add(item(folded?"Unfold all below":"Fold all below","",()->foldBelow(id,!folded)));}
        if(!moves.isEmpty()){items.add(divider());items.addAll(moves);}
        items.add(divider());
        boolean allHidden=targets.stream().allMatch(ClientState::savedHidden);
        items.add(item(n>1?(allHidden?"Show outlines ("+n+")":"Hide outlines ("+n+")"):(allHidden?"Show outline":"Hide outline"),"",()->{for(int t:targets)ClientState.setSavedHidden(t,!allHidden);}));
        if(n==1&&ClientState.hasSavedColor(id))items.add(item("Reset colour","",()->ClientState.resetSavedColor(id)));
        if(n==1)items.add(item("Change colour…","",()->ColourPickerScreen.open(this,row.name()+" colour",()->ClientState.savedColor(id),c->ClientState.setSavedColor(id,c),()->dev.craftium.core.SavedSelectionColors.forIndex(ClientState.options.selectionColor,0))));
        var box=subtreeBounds(id);
        if(box!=null)items.add(item("Focus","F",()->focusOn(box[0],box[1])));
        rowMenu=List.copyOf(items);rowMenuX=x;rowMenuY=y;rowMenuRow=id;menu=ROW_MENU;openSub="";openSub2="";
    }
    /** The right-click menu of a group's heading. */
    private void openGroupMenu(int groupId,int x,int y){
        var group=groupById(groupId);if(group==null)return;
        var groups=ClientState.savedGroups();int at=0;for(int i=0;i<groups.size();i++)if(groups.get(i).id()==groupId)at=i;final int position=at;
        var items=new ArrayList<MenuItem>();
        items.add(item("Rename","",()->startRename(new RenameArea(null,GROUP_BASE+groupId))));
        items.add(item("Create empty BuildObject here","",()->action("saved_selection:empty:"+groupId)));
        items.add(item("Create new Group","",()->action("saved_selection:groupnew")));
        boolean folded=ClientState.groupFolded(groupId);
        items.add(item(folded?"Unfold":"Fold","",()->ClientState.toggleGroupFolded(groupId)));
        var moves=new ArrayList<MenuItem>();
        if(position>0)moves.add(item("Move up","",()->action("saved_selection:groupmove:"+groupId+":"+(position-1))));
        if(position<groups.size()-1)moves.add(item("Move down","",()->action("saved_selection:groupmove:"+groupId+":"+(position+1))));
        if(!moves.isEmpty()){items.add(divider());items.addAll(moves);}
        if(groups.size()>1){items.add(divider());items.add(item("Delete group","",()->action("saved_selection:groupdelete:"+groupId)));} // what is in it goes to the group before
        rowMenu=List.copyOf(items);rowMenuX=x;rowMenuY=y;rowMenuRow=0;menu=ROW_MENU;openSub="";openSub2="";
    }
    /** The right-click menu of the empty space in the Hierarchy. */
    private void openHierarchyMenu(int x,int y){
        var items=new ArrayList<MenuItem>();
        var groups=ClientState.savedGroups();
        if(!groups.isEmpty())items.add(item("Create new Group","",()->action("saved_selection:groupnew")));
        items.add(item("Create empty BuildObject","",()->action("saved_selection:empty"+(groups.isEmpty()?"":":"+groups.getLast().id())))); // at the end of the last group, which is where the empty space is
        rowMenu=List.copyOf(items);rowMenuX=x;rowMenuY=y;rowMenuRow=0;menu=ROW_MENU;openSub="";openSub2="";
    }
    /** The corners of the box round the blocks of the BuildObject and everything under it, or null when there are none. */
    private Point[] subtreeBounds(int id){return ClientState.subtreeBounds(id);}
    /** Folds or unfolds the BuildObject and everything under it that has something under it. */
    private void foldBelow(int id,boolean fold){
        var parents=new HashSet<Integer>();for(var e:hierarchyAll)parents.add(e.parent());
        for(var e:hierarchyAll)if((e.id()==id||isUnder(e,id))&&parents.contains(e.id())&&ClientState.savedFolded(e.id())!=fold)ClientState.toggleSavedFolded(e.id());
    }
    private void rowMenuDraw(Gfx g){
        int width=226,height=4;for(var item:rowMenu)height+=item.action==null?9:25;
        int x=Math.max(0,rowMenuX+width>w?rowMenuX-width:rowMenuX),y=Math.clamp(rowMenuY,27,Math.max(27,h-25-height));
        g.fill(x,y,x+width,y+height,0xff1c2735);g.outline(x,y,width,height,LINE);
        rowMenuFrom=controls.size();
        controls.add(new Control(x,y,width,height,()->{},BLOCK_TIP)); // the menu as a whole: under its items, over everything else, so nothing behind it is hovered or clicked through it
        menuAlign=true;
        try{
            int row=y+3;
            for(var item:rowMenu){
                if(item.action==null){g.fill(x+8,row+4,x+width-8,row+5,LINE);row+=9;continue;}
                final var run=item.action;button(g,x+3,row,width-6,24,item.label,false,()->{menu=-1;run.run();},HIDDEN_TIP+"menu "+item.label); // named for tests, no tooltip
                if(!item.shortcut.isEmpty())text(g,item.shortcut,x+width-14-font.width(item.shortcut),row+8,MUTED);
                row+=25;
            }
        }finally{menuAlign=false;}
    }
    /** A row of the Hierarchy that a double click renames: the row's own control, so a click on a button of the row is not a rename. */
    private record RenameArea(Control row,int id){}
    /** The row being pressed to click or to drag to another place, how far the pointer has gone, and where the list of rows is drawn. */
    /** The BuildObjects a drag carries: the pressed row, or all the rows shown when it is one of several (the outermost of them, in list order). */
    private List<Integer> rowDragIds=List.of();
    private List<Integer> draggedSet(int pressed){
        var shown=new ArrayList<Integer>();for(var e:hierarchyAll)if(e.active())shown.add(e.id());
        if(shown.size()<2||!shown.contains(pressed))return List.of(pressed);
        var roots=new ArrayList<Integer>();
        for(var e:hierarchyAll){if(!shown.contains(e.id()))continue;boolean inner=false;for(int other:shown)if(other!=e.id()&&isUnder(e,other))inner=true;if(!inner)roots.add(e.id());}
        return roots;
    }
    private int rowDragId=-1,hierarchyTop,hierarchyRow=20,hierarchyRowCount=1;private double rowDragX,rowDragY;private boolean rowDragMoved;private Runnable rowDragClick;
    /** The BuildObjects as the client has them, and the rows listed of them (those not under a folded one), for the drag to work out where it lands. */
    private List<dev.craftium.net.Packets.SavedSelection> hierarchyAll=List.of();private List<HRow> hierarchyRows=List.of();
    private dev.craftium.net.Packets.SavedSelection savedById(int id){if(id==0)return null;for(var e:hierarchyAll)if(e.id()==id)return e;return null;}
    private int depthOf(dev.craftium.net.Packets.SavedSelection e){int d=0;for(var p=savedById(e.parent());p!=null&&d<64;p=savedById(p.parent()))d++;return d;}
    private boolean isUnder(dev.craftium.net.Packets.SavedSelection e,int ancestor){int n=0;for(var p=savedById(e.parent());p!=null&&n<64;p=savedById(p.parent()),n++)if(p.id()==ancestor)return true;return false;}
    /** Which child of its parent the row is, counting the others before it. */
    private int siblingIndex(dev.craftium.net.Packets.SavedSelection e){int k=0;for(var o:hierarchyAll){if(o.id()==e.id())return k;if(o.parent()==e.parent()&&(e.parent()!=0||groupIdOf(o)==groupIdOf(e)))k++;}return k;} // at the top level, the place in its group
    /** How many BuildObjects are beside this one, itself included: under its parent, or in its group at the top level. */
    private int siblingCount(dev.craftium.net.Packets.SavedSelection e){return e.parent()==0?rootCount(groupIdOf(e)):childCount(e.parent());}
    private int childCount(int id){int k=0;for(var o:hierarchyAll)if(o.parent()==id)k++;return k;}
    /** What dropping a dragged row here does: under {@code parent} (0 for the top) as child number {@code index}, shown by {@code kind} at {@code row}. */
    private record Drop(int parent,int index,int kind,int row,int group){}
    private static final int DROP_ABOVE=0,DROP_BELOW=1,DROP_INTO=2,DROP_FIRST_CHILD=3,DROP_END=4,DROP_GROUP_BEFORE=5,DROP_GROUP_AFTER=6;
    /**
     * Where the dragged row would land, from the pointer: the top quarter of a row puts it before that row, the bottom quarter after it (or first
     * under it when it is open), the middle makes it a child, and below the last row it goes to the end of the top level. Null where it cannot go: on itself
     * or on what is under it.
     */
    private Drop dropAt(){
        int total=hierarchyRows.size();if(total==0)return null;
        double py=WorkspaceLayout.inverse("hierarchy",mx,my)[1]-hierarchyTop,at=Math.floor(py/hierarchyRow);
        int row=(int)at+savedScroll;
        if(rowDragId>=GROUP_BASE){ // a group: it goes before or after the group the pointer is over, by which half of that group's rows it is in
            int dragged=rowDragId-GROUP_BASE;
            var hit=hierarchyRows.get(row>=total?total-1:Math.max(row,0));int target=groupIdOfRow(hit);
            if(target==dragged)return null;
            int first=-1,last=-1;for(int i=0;i<total;i++)if(groupIdOfRow(hierarchyRows.get(i))==target){if(first<0)first=i;last=i;}
            boolean before=row<total&&py/hierarchyRow+savedScroll<first+(last-first+1)/2.0;
            var others=new ArrayList<Integer>();for(var gr:ClientState.savedGroups())if(gr.id()!=dragged)others.add(gr.id());
            int index=others.indexOf(target)+(before?0:1);
            return new Drop(0,index,before?DROP_GROUP_BEFORE:DROP_GROUP_AFTER,before?first:last,target);
        }
        if(row>=total){int lastGroup=groupIdOfRow(hierarchyRows.get(total-1));return new Drop(0,rootCount(lastGroup),DROP_END,total-1,lastGroup);}
        var target=hierarchyRows.get(Math.max(row,0));
        if(target.group()!=null)return new Drop(0,rootCount(target.group().id()),DROP_INTO,Math.max(row,0),target.group().id()); // onto a group's heading: the end of that group
        var entry=target.entry();
        for(int dragged:rowDragIds)if(entry.id()==dragged||isUnder(entry,dragged))return null; // not onto what is being carried, nor onto what is under it
        double f=py<0?0:py/hierarchyRow-at;
        int group=entry.parent()==0?groupIdOf(entry):0;
        if(f<.25)return new Drop(entry.parent(),siblingIndex(entry),DROP_ABOVE,Math.max(row,0),group);
        if(f>.75){boolean open=childCount(entry.id())>0&&!ClientState.savedFolded(entry.id());return open?new Drop(entry.id(),0,DROP_FIRST_CHILD,row,0):new Drop(entry.parent(),siblingIndex(entry)+1,DROP_BELOW,row,group);}
        return new Drop(entry.id(),childCount(entry.id()),DROP_INTO,row,0);
    }
    /** Lets go of a pressed row: where it was dropped moves it, and a press that never moved is the click it always was. */
    private void finishRowDrag(){
        int id=rowDragId;var ids=rowDragIds;boolean moved=rowDragMoved;var click=rowDragClick;var drop=moved?dropAt():null;var dragged=savedById(id);rowDragId=-1;rowDragIds=List.of();rowDragMoved=false;rowDragClick=null;
        if(!moved){if(click!=null)click.run();return;}
        if(id>=GROUP_BASE){if(drop!=null)action("saved_selection:groupmove:"+(id-GROUP_BASE)+":"+drop.index());return;} // a group, to its new place among the groups
        if(drop==null||dragged==null)return;
        int index=drop.index();
        for(int carried:ids){var row=savedById(carried);if(row!=null&&row.parent()==drop.parent()&&(drop.parent()!=0||drop.group()==0||groupIdOf(row)==drop.group())&&siblingIndex(row)<drop.index())index--;} // the place is counted without the rows carried
        String where=drop.parent()+":"+index+(drop.parent()==0&&drop.group()>0?":"+drop.group():""); // at the top level the place is in a group
        if(ids.size()>1)action("saved_selection:placeset:"+String.join(",",ids.stream().map(String::valueOf).toList())+":"+where);
        else action("saved_selection:place:"+id+":"+where);
    }
    /** The dragged row, following the pointer so it is plain that it is being carried. */
    private void rowDragGhost(Gfx g){
        if(!rowDragMoved)return;
        if(rowDragId>=GROUP_BASE){var group=groupById(rowDragId-GROUP_BASE);if(group==null)return;int gw=140,gh=18,gx=Math.min(mx+12,w-gw-4),gy=my-9;g.fill(gx,gy,gx+gw,gy+gh,0xe0212832);g.outline(gx,gy,gw,gh,ACCENT);text(g,shorten(group.name(),gw-12),gx+6,gy+5,GROUP_TEXT);return;}
        var e=savedById(rowDragId);if(e==null)return;
        int gw=140,gh=18,gx=Math.min(mx+12,w-gw-4),gy=my-9;
        g.fill(gx,gy,gx+gw,gy+gh,0xe0315b84);g.outline(gx,gy,gw,gh,ACCENT);
        g.fill(gx+4,gy+4,gx+14,gy+gh-4,0xff000000|ClientState.savedColor(e.id())&0xffffff);
        text(g,rowDragIds.size()>1?rowDragIds.size()+" BuildObjects":shorten(e.name(),gw-26),gx+20,gy+5,TEXT);
    }
    private final List<RenameArea> renameAreas=new ArrayList<>();
    /** The id of the entry being renamed in place, or -1. */
    private int renaming=-1;
    private EditBox renameBox(String name){
        var box=fields.get("rename");
        if(box==null){
            field("rename",0,0,100,name,s->{});box=fields.get("rename");box.setMaxLength(40);
            box.moveCursorToEnd(false);box.setHighlightPos(0);box.setFocused(true); // the whole name is selected, so typing replaces it
        }
        return box;
    }
    private void startRename(RenameArea area){fields.remove("rename");renaming=area.id();}
    private void cancelRename(){renaming=-1;fields.remove("rename");}
    private void commitRename(){
        var box=fields.get("rename");int id=renaming;cancelRename();
        if(box==null)return;
        if(id>=GROUP_BASE){var group=groupById(id-GROUP_BASE);String named=box.getValue().strip();if(group!=null&&!named.isEmpty()&&!group.name().equals(named))action("saved_selection:grouprename:"+group.id()+":"+named);return;}
        String name=box.getValue().strip();var current=ClientState.savedSelections().stream().filter(e->e.id()==id).findFirst();
        if(!name.isEmpty()&&current.isPresent()&&!current.get().name().equals(name))action("saved_selection:rename:"+id+":"+name);
    }
    public void moveHandle(Point point){if(selectedNode>=0&&selectedNode<handleNodes().size())handleNodes().set(selectedNode,point);}
    private double[] project(Point p){return project(p,0);}
    private double[] project(Point p,double shift){return project(new Vec3(p.x()+shift,p.y()+shift,p.z()+shift));}
    private double[] project(Vec3 at){
        var camera=ClientCompat.camera(minecraft);Vec3 v=at.subtract(camera.position());
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
    @Override public void removed(){BlockDragCursor.release();keysDown.clear();pipetting=false;releaseCursor();TransformGizmo.cancel();draggingNode=draggingPlacement=false;cameraGesture=CameraGesture.NONE;syncCursor();if(hudHidden){if(ClientCompat.hudHidden(minecraft)!=previousHideGui)ClientCompat.toggleHud(minecraft);hudHidden=false;}ClientState.strokeId=0;ClientState.save();super.removed();}
    @Override public void onClose(){ClientState.save();if(ClientState.largeSelection>0)confirmLargeSelection();ClientCompat.setScreen(minecraft,null);if(ClientState.hasPending()&&CraftiumClient.supported())CraftiumClient.send("pending_cancel",null,null);if(CraftiumClient.supported()&&minecraft.player!=null&&minecraft.player.isSpectator())CraftiumClient.send("gamemode_restore",null,null);}
}
