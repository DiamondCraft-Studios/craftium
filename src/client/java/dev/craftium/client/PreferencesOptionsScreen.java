package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The editor's general preferences, laid out like the keyboard shortcuts screen: a label, its control and a Reset for
 * each one. Changes are kept as they are made and saved when the screen closes.
 */
final class PreferencesOptionsScreen extends CraftiumScreen {
    private static final Options DEFAULTS=new Options();
    private static final int LIST_TOP=76,ROW=24;

    /** One preference: a switch, or a number set with a slider. */
    private sealed interface Row permits Toggle,Number,Field,Link,Cycle {}
    /** A row that opens another screen of settings, which returns here. */
    private record Link(String label,String about,Function<Screen,Screen> open) implements Row {}
    private record Toggle(String label,String about,Function<Options,Boolean> get,java.util.function.BiConsumer<Options,Boolean> set) implements Row {}
    /** A choice among a few named values: the button shows the current one and a click steps to the next. */
    private record Cycle(String label,String about,Function<Options,String> get,java.util.function.Consumer<Options> next,java.util.function.BiConsumer<Options,Options> reset) implements Row {}
    /** A whole number typed into a box, kept between {@code min} and {@code max}. */
    private record Field(String label,String about,int min,int max,Function<Options,Integer> get,java.util.function.BiConsumer<Options,Integer> set) implements Row {}
    private record Number(String label,String about,int min,int max,Function<Options,Integer> get,java.util.function.BiConsumer<Options,Integer> set,Function<Integer,String> text) implements Row {
        Number(String label,String about,int min,int max,Function<Options,Integer> get,java.util.function.BiConsumer<Options,Integer> set){this(label,about,min,max,get,set,String::valueOf);}
    }

    /** The preferences in groups, each shown on its own tab; the order here is the order on screen. */
    private static final java.util.Map<String,List<Row>> GROUPS=new java.util.LinkedHashMap<>();
    static{
        GROUPS.put("General",List.of(
            new Toggle("Check for updates","Once per game session, look at the project's GitHub releases and say when a newer build exists. Nothing is downloaded",o->o.checkForUpdates,(o,v)->o.checkForUpdates=v),
            new Toggle("Enter to build","On: an edit waits for Enter before it is built. Off: a stroke is built when you let go of the button, and any other edit is built at once",o->o.enterToBuild,(o,v)->o.enterToBuild=v),
            new Toggle("Undo normal building","On: the blocks you place and break by hand go into the undo history, a held place button as one step. Off: only the editor and Builder tools can be undone",o->o.recordPlayerBuilding,(o,v)->o.recordPlayerBuilding=v),
            new Toggle("Undo / redo pulse","Flash the changed blocks when you undo or redo",o->o.historyPulse,(o,v)->o.historyPulse=v)));
        GROUPS.put("Selection",List.of(
            new Toggle("Large selection warnings","Ask before keeping a big selection, in the editor and the Builder; a right click confirms it",o->o.largeSelectionWarnings,(o,v)->o.largeSelectionWarnings=v),
            new Field("Warn at (blocks)","A selection this many blocks bigger than before is confirmed first. Used by the editor and the Builder",1,dev.craftium.core.Selection.LIMIT,o->o.largeSelectionThreshold,(o,v)->o.largeSelectionThreshold=v),
            new Toggle("Magic Wand hover preview","Outline what a Magic Wand click would select",o->o.magicPreview,(o,v)->o.magicPreview=v),
            new Field("Hover preview limit","No outline when a click would select this many blocks or more, and Extrude and Flood fill ask first. Only about drawing cost",100,32_768,o->o.hoverPreviewLimit,(o,v)->o.hoverPreviewLimit=v),
            new Toggle("Mark faces against solid blocks","Tint the selection's faces that touch a solid block outside it; the colour is in Selection options",o->o.showSelectionContact,(o,v)->o.showSelectionContact=v),
            new Link("Selection options","Every selection colour in one place, grouped by use: the editor and builder selections, destination and removed blocks, hover previews, and line widths",SelectionOptionsScreen::new)));
        GROUPS.put("Builder",List.of(
            new Toggle("Builder follows Editor magic select","On: the Builder's magic select uses the Magic Wand settings from the editor. Off: it uses its own, set by the block match and the limit below",o->o.builderFollowsMagic,(o,v)->o.builderFollowsMagic=v),
            new Cycle("Builder magic select matches","What the Builder's magic select treats as connected when it does not follow the editor: any block, the same block, the same block state, a shared tag, or the same block entity data",o->o.builderMagicCompare.name().charAt(0)+o.builderMagicCompare.name().substring(1).toLowerCase(),o->o.builderMagicCompare=Options.Compare.values()[(o.builderMagicCompare.ordinal()+1)%Options.Compare.values().length],(o,d)->o.builderMagicCompare=d.builderMagicCompare),
            new Field("Builder magic select limit","The most blocks the Builder's magic select looks for when it does not follow the editor. A bigger area is cut off at this many",1,dev.craftium.core.Selection.LIMIT,o->o.builderMagicLimit,(o,v)->o.builderMagicLimit=v),
            new Toggle("Live Builder selection box","After the first Builder click, draw the box to the block you aim at and let it follow your aim",o->o.builderLiveBox,(o,v)->o.builderLiveBox=v),
            new Toggle("Bulldozer same blocks","On: a full Bulldozer sweep only breaks blocks like the first one it met, states included, and a different block stops it until you let go. Off: it breaks everything it passes over",o->o.bulldozerSameBlocks,(o,v)->o.bulldozerSameBlocks=v)));
        GROUPS.put("Gizmos & Rulers",List.of(
            new Toggle("Show axis gizmo","Draw the X, Y and Z axes at the top right of the editor; click an end to look along that axis",o->o.showAxisGizmo,(o,v)->o.showAxisGizmo=v),
            new Toggle("Hide other gizmo handles","While a gizmo handle is held, draw only that handle",o->o.gizmoHideOthers,(o,v)->o.gizmoHideOthers=v),
            new Link("Ruler options","The colours of the ruler's line, its knobs and its span label",SelectionOptionsScreen::ruler)));
        GROUPS.put("UI",List.of(
            new Number("Interface size","How big the text and buttons of every Craftium screen are. Auto keeps the game's GUI scale (the editor lowers it until it fits the window); 1x to 8x draw every pixel of the text with that many screen pixels, in half steps. Whole steps stay perfectly sharp; a half step is only exact in the editor, and every other screen rounds it up to the next whole step. A size bigger than the window fits can push panels off the screen; Ctrl and - (or Auto) bring it back. Ctrl and + / - change it inside the editor",0,16,o->o.interfaceHalves,(o,v)->o.interfaceHalves=v,InterfaceScale::name)));
        GROUPS.put("AI",List.of(
            new Number("AI preview models","How many block models an AI preview draws; the rest of a bigger preview shows as its outline. More looks fuller but costs frame rate",Options.AI_GHOST_DRAW_MIN,Options.AI_GHOST_DRAW_MAX,o->o.aiGhostDrawLimit,(o,v)->o.aiGhostDrawLimit=v)));
    }
    private static final List<String> TABS=List.copyOf(GROUPS.keySet());

    private final Screen parent;
    private int tab;
    private boolean draggingBar;
    /** How far the list is scrolled, in pixels. The rows are one long list, clipped to the space between the tabs' top and the footer. */
    private double scroll;
    /** The controls of the rows, and where each sits in the list before scrolling. They are drawn and moved here, not by the screen, so they can be clipped. */
    private final java.util.List<AbstractWidget> listWidgets=new java.util.ArrayList<>();
    private final java.util.List<Integer> listBase=new java.util.ArrayList<>();
    private List<Row> rows(){return GROUPS.get(TABS.get(tab));}

    /** The size of the screens follows the slider only once the button is let go, since laying out again would drop the slider being dragged. */
    @Override protected boolean holdScale(){return minecraft.mouseHandler.isLeftPressed();}
    PreferencesOptionsScreen(Screen parent){super(Component.literal("Options"));this.parent=parent;}

    private int viewTop(){return LIST_TOP-6;}
    private int viewBottom(){return height-96;}
    private int viewHeight(){return Math.max(ROW,viewBottom()-viewTop());}
    private int maxScroll(){return Math.max(0,rows().size()*ROW-viewHeight());}
    /** Adds the control of a row; {@code base} is the row's place in the list. */
    private <T extends AbstractWidget> T track(int base,T widget){addWidget(widget);listWidgets.add(widget);listBase.add(base);return widget;}
    /** Puts every row's controls where the scrolling has them, and turns off the ones that are out of sight so a click cannot reach them. */
    private void layout(){
        scroll=Math.clamp(scroll,0,maxScroll());
        for(int i=0;i<listWidgets.size();i++){
            var w=listWidgets.get(i);int y=viewTop()+listBase.get(i)-(int)scroll;
            w.setY(y);w.visible=y+w.getHeight()>viewTop()&&y<viewBottom();
        }
    }
    private int left(){return Math.max(12,(width-600)/2)+120;}
    private int tabsLeft(){return left()-120;}

    @Override protected void init(){
        clearWidgets();listWidgets.clear();listBase.clear();
        var o=ClientState.options;int x=left();var ROWS=rows(); // the rows of the chosen tab
        for(int t=0;t<TABS.size();t++){int index=t;addRenderableWidget(new FlatButton(tabsLeft(),LIST_TOP-6+t*ROW,110,20,TABS.get(t),()->{tab=index;scroll=0;init();}).lit(t==tab));}
        for(int i=0;i<ROWS.size();i++){
            int y=viewTop()+i*ROW,base=i*ROW;
            if(ROWS.get(i) instanceof Toggle t){
                track(base,new FlatButton(x+245,y,180,20,t.get().apply(o)?"On":"Off",()->{t.set().accept(o,!t.get().apply(o));init();}));
                track(base,new FlatButton(x+430,y,50,20,"Reset",()->{t.set().accept(o,t.get().apply(DEFAULTS));init();}));
            }else if(ROWS.get(i) instanceof Cycle c){
                track(base,new FlatButton(x+245,y,180,20,c.get().apply(o),()->{c.next().accept(o);init();}).back(()->{stepBack(c);init();}));
                track(base,new FlatButton(x+430,y,50,20,"Reset",()->{c.reset().accept(o,DEFAULTS);init();}));
            }else if(ROWS.get(i) instanceof Link link){
                track(base,new FlatButton(x+245,y,180,20,"Open…",()->ClientCompat.setScreen(minecraft,link.open().apply(this))));
            }else if(ROWS.get(i) instanceof Field f){
                var box=new EditBox(font,x+245,y,180,20,Component.literal(""));
                boolean[] fixing={false};
                box.setMaxLength(9);box.setValue(f.get().apply(o).toString());
                box.setResponder(t->{
                    String clean=t.replaceAll("[^0-9]","");
                    if(!clean.equals(t)){if(!fixing[0]){fixing[0]=true;box.setValue(clean);fixing[0]=false;}return;}
                    if(clean.isEmpty())return;
                    f.set().accept(o,Math.clamp(Long.parseLong(clean),f.min(),f.max()));
                });
                track(base,box);
                track(base,new FlatButton(x+430,y,50,20,"Reset",()->{f.set().accept(o,f.get().apply(DEFAULTS));init();}));
            }else if(ROWS.get(i) instanceof Number n){
                double span=n.max()-n.min();
                var slider=new FlatSlider(x+245,y,180,20,Component.empty(),(n.get().apply(o)-n.min())/span){
                    protected void updateMessage(){setMessage(Component.literal(n.text().apply(n.min()+(int)Math.round(value*span))));}
                    protected void applyValue(){n.set().accept(o,n.min()+(int)Math.round(value*span));}
                };
                slider.range(n.min(),n.max(),n.get().apply(DEFAULTS));slider.setRaw((n.get().apply(o)-n.min())/span);
                track(base,slider);
                track(base,new FlatButton(x+430,y,50,20,"Reset",()->{n.set().accept(o,n.get().apply(DEFAULTS));init();}));
            }
        }
        addRenderableWidget(new FlatButton(x+280,height-62,110,20,"Reset these",()->{for(Row r:rows())reset(r);init();}));
        addRenderableWidget(new FlatButton(x+395,height-62,85,20,"Done",this::onClose));
        layout();
    }
    private static String labelOf(Row row){return switch(row){case Toggle t->t.label();case Number n->n.label();case Field f->f.label();case Link l->l.label();case Cycle c->c.label();};}
    private static String aboutOf(Row row){return switch(row){case Toggle t->t.about();case Number n->n.about();case Field f->f.about();case Link l->l.about();case Cycle c->c.about();};}
    private static void reset(Row row){
        var o=ClientState.options;
        if(row instanceof Toggle t)t.set().accept(o,t.get().apply(DEFAULTS));
        else if(row instanceof Number n)n.set().accept(o,n.get().apply(DEFAULTS));
        else if(row instanceof Field f)f.set().accept(o,f.get().apply(DEFAULTS));
        else if(row instanceof Cycle c)c.reset().accept(o,DEFAULTS);
    }

    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        g.fill(0,0,width,height,0xf510151c);
        int x=left();var ROWS=rows(); // the rows of the chosen tab
        g.text(font,"OPTIONS",tabsLeft(),14,0xffdce6ef,false);
        g.text(font,"Pick a category on the left. Ctrl-click a slider to type a value; middle-click resets it.",tabsLeft(),27,0xff8595a8,false);
        String about="";
        g.enableScissor(0,viewTop(),width,viewBottom()); // the rows scroll under this: whatever is above or below the list is cut off
        for(int i=0;i<ROWS.size();i++){
            int y=viewTop()+i*ROW-(int)scroll;
            if(y+20<viewTop()||y>viewBottom())continue;
            Row row=ROWS.get(i);
            g.text(font,labelOf(row),x,y+6,0xffdce6ef,false);
            if(mouseX>=x&&mouseX<x+240&&mouseY>=Math.max(y,viewTop())&&mouseY<Math.min(y+20,viewBottom()))about=aboutOf(row);
        }
        for(var w:listWidgets)if(w.visible)g.widget(w,mouseX,mouseY,delta);
        g.disableScissor();
        if(!about.isEmpty())g.text(font,font.plainSubstrByWidth(about,width-x*2),x,height-92,0xff8595a8,false);
        if(maxScroll()>0){ // the scrollbar: its handle is as long as the part of the list that shows
            int top=viewTop(),track=viewHeight(),thumb=thumbHeight(),thumbY=top+(int)((track-thumb)*(scroll/maxScroll()));
            g.fill(barX(),top,barX()+BAR,top+track,0xff1b2838);
            g.fill(barX(),thumbY,barX()+BAR,thumbY+thumb,draggingBar?0xff7cc4ff:0xff4a6585);
        }
        drawWidgets(g,mouseX,mouseY,delta);
    }

    private static final int BAR=6;
    private int barX(){return left()+496;}
    private int thumbHeight(){return Math.max(20,viewHeight()*viewHeight()/Math.max(viewHeight(),rows().size()*ROW));}
    /** Scrolls to where the bar is held. */
    private void dragTo(double y){
        int travel=viewHeight()-thumbHeight();
        if(travel<=0)return;
        scroll=Math.clamp((y-viewTop()-thumbHeight()/2.0)/travel*maxScroll(),0,maxScroll());layout();
    }
    /** Goes to the value before this one, for a cycle that only knows how to go on: it goes round once to count the values, then stops one short of the start. */
    private void stepBack(Cycle c){
        var o=ClientState.options;String start=c.get().apply(o);int n=0;
        do{c.next().accept(o);n++;}while(!c.get().apply(o).equals(start)&&n<64);
        for(int i=0;i<n-1;i++)c.next().accept(o);
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){
        if(event.button()==1)for(var child:children())if(child instanceof FlatButton rb&&rb.rightClick(event.x(),event.y()))return true; // a cycling button steps back
        if(event.button()==0&&maxScroll()>0&&event.x()>=barX()-3&&event.x()<barX()+BAR+4&&event.y()>=viewTop()&&event.y()<viewTop()+viewHeight()){draggingBar=true;dragTo(event.y());return true;}
        // A control that is partly scrolled out of the list is still there under the tabs and the footer: a click outside the list does not reach it.
        boolean inList=event.y()>=viewTop()&&event.y()<viewBottom();
        if(!inList)for(var w:listWidgets)w.active=false;
        try{return super.mouseClicked(event,twice);}
        finally{if(!inList)for(var w:listWidgets)w.active=true;}
    }
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,double dx,double dy){
        if(draggingBar){dragTo(event.y());return true;}
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event){
        if(draggingBar){draggingBar=false;return true;}
        return super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){scroll=Math.clamp(scroll-vertical*ROW*1.5,0,maxScroll());layout();return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientState.save();ClientCompat.setScreen(minecraft,parent);}
}
