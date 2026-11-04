package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Options;
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
    private sealed interface Row permits Toggle,Number,Link {}
    /** A row that opens another screen of settings, which returns here. */
    private record Link(String label,String about,Function<Screen,Screen> open) implements Row {}
    private record Toggle(String label,String about,Function<Options,Boolean> get,java.util.function.BiConsumer<Options,Boolean> set) implements Row {}
    private record Number(String label,String about,int min,int max,Function<Options,Integer> get,java.util.function.BiConsumer<Options,Integer> set) implements Row {}

    private static final List<Row> ROWS=List.of(
        new Toggle("Undo / redo pulse","Flash the changed blocks when you undo or redo",o->o.historyPulse,(o,v)->o.historyPulse=v),
        new Toggle("Magic Wand hover preview","Outline what a Magic Wand click would select",o->o.magicPreview,(o,v)->o.magicPreview=v),
        new Number("Hover preview limit","No outline when a click would select this many blocks or more",100,20_000,o->o.hoverPreviewLimit,(o,v)->o.hoverPreviewLimit=v),
        new Link("Selection options","How the selection looks: its style, line width and colours",SelectionOptionsScreen::new));

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
            }else if(ROWS.get(i) instanceof Link link){
                addRenderableWidget(new FlatButton(x+245,y,180,20,"Open…",()->ClientCompat.setScreen(minecraft,link.open().apply(this))));
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
    private static String labelOf(Row row){return switch(row){case Toggle t->t.label();case Number n->n.label();case Link l->l.label();};}
    private static String aboutOf(Row row){return switch(row){case Toggle t->t.about();case Number n->n.about();case Link l->l.about();};}
    private static void reset(Row row){
        var o=ClientState.options;
        if(row instanceof Toggle t)t.set().accept(o,t.get().apply(DEFAULTS));
        else if(row instanceof Number n)n.set().accept(o,n.get().apply(DEFAULTS));
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
