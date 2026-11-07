package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Options;
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
    private record Number(String label,String about,int min,int max,Function<Options,Integer> get,java.util.function.BiConsumer<Options,Integer> set) implements Row {}

    private static final List<Row> ROWS=List.of(
        new Toggle("Undo / redo pulse","Flash the changed blocks when you undo or redo",o->o.historyPulse,(o,v)->o.historyPulse=v),
        new Toggle("Enter to build","On: an edit waits for Enter before it is built. Off: a stroke is built when you let go of the button, and any other edit is built at once",o->o.enterToBuild,(o,v)->o.enterToBuild=v),
        new Toggle("Undo normal building","On: the blocks you place and break by hand go into the undo history, a held place button as one step. Off: only the editor and Builder tools can be undone",o->o.recordPlayerBuilding,(o,v)->o.recordPlayerBuilding=v),
        new Toggle("Builder follows Editor magic select","On: the Builder's magic select uses the Magic Wand settings from the editor. Off: it uses its own, set by the block match and the limit below",o->o.builderFollowsMagic,(o,v)->o.builderFollowsMagic=v),
        new Cycle("Builder magic select matches","What the Builder's magic select treats as connected when it does not follow the editor: any block, the same block, the same block state, a shared tag, or the same block entity data",o->o.builderMagicCompare.name().charAt(0)+o.builderMagicCompare.name().substring(1).toLowerCase(),o->o.builderMagicCompare=Options.Compare.values()[(o.builderMagicCompare.ordinal()+1)%Options.Compare.values().length],(o,d)->o.builderMagicCompare=d.builderMagicCompare),
        new Field("Builder magic select limit","The most blocks the Builder's magic select looks for when it does not follow the editor. A bigger area is cut off at this many",1,dev.craftium.core.Selection.LIMIT,o->o.builderMagicLimit,(o,v)->o.builderMagicLimit=v),
        new Toggle("Bulldozer same blocks","On: a full Bulldozer sweep only breaks blocks like the first one it met, states included, and a different block stops it until you let go. Off: it breaks everything it passes over",o->o.bulldozerSameBlocks,(o,v)->o.bulldozerSameBlocks=v),
        new Toggle("Live Builder selection box","After the first Builder click, draw the box to the block you aim at and let it follow your aim",o->o.builderLiveBox,(o,v)->o.builderLiveBox=v),
        new Toggle("Hide other gizmo handles","While a gizmo handle is held, draw only that handle",o->o.gizmoHideOthers,(o,v)->o.gizmoHideOthers=v),
        new Toggle("Large selection warnings","Ask before keeping a big selection, in the editor and the Builder; a right click confirms it",o->o.largeSelectionWarnings,(o,v)->o.largeSelectionWarnings=v),
        new Field("Warn at (blocks)","A selection this many blocks bigger than before is confirmed first. Used by the editor and the Builder",1,dev.craftium.core.Selection.LIMIT,o->o.largeSelectionThreshold,(o,v)->o.largeSelectionThreshold=v),
        new Toggle("Check for updates","Once per game session, look at the project's GitHub releases and say when a newer build exists. Nothing is downloaded",o->o.checkForUpdates,(o,v)->o.checkForUpdates=v),
        new Toggle("Magic Wand hover preview","Outline what a Magic Wand click would select",o->o.magicPreview,(o,v)->o.magicPreview=v),
        new Toggle("Mark faces against solid blocks","Tint the selection's faces that touch a solid block outside it; the colour is in Selection options",o->o.showSelectionContact,(o,v)->o.showSelectionContact=v),
        new Field("Hover preview limit","No outline when a click would select this many blocks or more, and Extrude and Flood fill ask first. Only about drawing cost",100,32_768,o->o.hoverPreviewLimit,(o,v)->o.hoverPreviewLimit=v),
        new Number("AI preview models","How many block models an AI preview draws; the rest of a bigger preview shows as its outline. More looks fuller but costs frame rate",Options.AI_GHOST_DRAW_MIN,Options.AI_GHOST_DRAW_MAX,o->o.aiGhostDrawLimit,(o,v)->o.aiGhostDrawLimit=v),
        new Link("Selection options","Every selection colour in one place, grouped by use: the editor and builder selections, destination and removed blocks, hover previews, and line widths",SelectionOptionsScreen::new),
        new Link("Ruler options","The colours of the ruler's line, its knobs and its span label",SelectionOptionsScreen::ruler));

    private final Screen parent;
    private int page;

    PreferencesOptionsScreen(Screen parent){super(Component.literal("Options"));this.parent=parent;}

    private int rowsPerPage(){return Math.max(1,(height-LIST_TOP-80)/ROW);}
    private int left(){return Math.max(12,(width-480)/2);}

    @Override protected void init(){
        clearWidgets();
        var o=ClientState.options;int x=left(),per=rowsPerPage(),pages=Math.max(1,(ROWS.size()+per-1)/per);
        page=Math.clamp(page,0,pages-1);
        for(int i=page*per;i<Math.min(ROWS.size(),(page+1)*per);i++){
            int y=LIST_TOP-6+(i-page*per)*ROW;
            if(ROWS.get(i) instanceof Toggle t){
                addRenderableWidget(new FlatButton(x+245,y,180,20,t.get().apply(o)?"On":"Off",()->{t.set().accept(o,!t.get().apply(o));init();}));
                addRenderableWidget(new FlatButton(x+430,y,50,20,"Reset",()->{t.set().accept(o,t.get().apply(DEFAULTS));init();}));
            }else if(ROWS.get(i) instanceof Cycle c){
                addRenderableWidget(new FlatButton(x+245,y,180,20,c.get().apply(o),()->{c.next().accept(o);init();}));
                addRenderableWidget(new FlatButton(x+430,y,50,20,"Reset",()->{c.reset().accept(o,DEFAULTS);init();}));
            }else if(ROWS.get(i) instanceof Link link){
                addRenderableWidget(new FlatButton(x+245,y,180,20,"Open…",()->ClientCompat.setScreen(minecraft,link.open().apply(this))));
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
                addRenderableWidget(box);
                addRenderableWidget(new FlatButton(x+430,y,50,20,"Reset",()->{f.set().accept(o,f.get().apply(DEFAULTS));init();}));
            }else if(ROWS.get(i) instanceof Number n){
                double span=n.max()-n.min();
                var slider=new FlatSlider(x+245,y,180,20,Component.empty(),(n.get().apply(o)-n.min())/span){
                    protected void updateMessage(){setMessage(Component.literal(String.valueOf(n.min()+(int)Math.round(value*span))));}
                    protected void applyValue(){n.set().accept(o,n.min()+(int)Math.round(value*span));}
                };
                slider.range(n.min(),n.max(),n.get().apply(DEFAULTS));slider.setRaw((n.get().apply(o)-n.min())/span);
                addRenderableWidget(slider);
                addRenderableWidget(new FlatButton(x+430,y,50,20,"Reset",()->{n.set().accept(o,n.get().apply(DEFAULTS));init();}));
            }
        }
        addRenderableWidget(new FlatButton(x,height-62,32,20,"‹",()->{page--;init();}));
        addRenderableWidget(new FlatButton(x+37,height-62,32,20,"›",()->{page++;init();}));
        addRenderableWidget(new FlatButton(x+280,height-62,110,20,"Reset all",()->{for(Row r:ROWS)reset(r);init();}));
        addRenderableWidget(new FlatButton(x+395,height-62,85,20,"Done",this::onClose));
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
        int x=left(),per=rowsPerPage();
        g.text(font,"CRAFTIUM / OPTIONS",x,14,0xffdce6ef,false);
        g.text(font,"General preferences. Ctrl-click a slider to type a value; middle-click resets it.",x,27,0xff8595a8,false);
        for(int i=page*per;i<Math.min(ROWS.size(),(page+1)*per);i++){
            int y=LIST_TOP-6+(i-page*per)*ROW;Row row=ROWS.get(i);
            String label=labelOf(row),about=aboutOf(row);
            g.text(font,label,x,y+6,0xffdce6ef,false);
            if(mouseX>=x&&mouseX<x+240&&mouseY>=y&&mouseY<y+20)g.text(font,font.plainSubstrByWidth(about,width-x*2),x,height-92,0xff8595a8,false);
        }
        g.text(font,"Page "+(page+1)+" / "+Math.max(1,(ROWS.size()+per-1)/per),x+80,height-79,0xff8595a8,false);
        drawWidgets(g,mouseX,mouseY,delta);
    }

    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){page+=vertical<0?1:-1;init();return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientState.save();ClientCompat.setScreen(minecraft,parent);}
}
