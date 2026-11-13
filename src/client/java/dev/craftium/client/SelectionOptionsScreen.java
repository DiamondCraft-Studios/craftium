package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/**
 * How the selection looks, in the editor and in the builder tools: its style, the width of its lines and every colour it and its previews
 * are drawn in, grouped by use. Clicking a colour's swatch opens a 2D colour picker for it, with a typeable hex code.
 */
public final class SelectionOptionsScreen extends CraftiumScreen {
    private static final int W=640,H=420,ROW=19;
    private static final Options DEFAULTS=new Options();

    /** One colour setting: what it is called, where it is kept and what it starts as. */
    private record Entry(String group,String name,IntSupplier get,IntConsumer set,IntSupplier fallback){}
    /** One slider for a number: a line width from 0.1 to 10 pixels, or (with {@code percent}) a size shown as a percentage between {@code min} and {@code max} times what it is at 100%. */
    private record Width(String label,ToDoubleFunction<Options> get,ObjDoubleConsumer<Options> set,double min,double max,boolean percent){
        Width(String label,ToDoubleFunction<Options> get,ObjDoubleConsumer<Options> set){this(label,get,set,.1,10,false);}
        /** The slider's own units: pixels, or percent. */
        double units(double value){return percent?value*100:value;}
        double fromUnits(double units){return percent?Math.round(units)/100d:Math.round(units*100)/100d;}
        String show(double units){return percent?Math.round(units)+"%":Double.toString(Math.round(units*100)/100d);}
    }

    /** Every colour of the editor's and the builder tools' selections and previews, grouped by what they are used for. */
    private static final List<Entry> ENTRIES=List.of(
        entry("Selection","Outline",o->o.selectionColor,(o,c)->o.selectionColor=c),
        entry("Selection","Fill",o->o.selectionFill,(o,c)->o.selectionFill=c),
        entry("Selection","Fill seen from inside",o->o.selectionInsideFill,(o,c)->o.selectionInsideFill=c),
        entry("Selection","Screen tint inside",o->o.selectionInsideTint,(o,c)->o.selectionInsideTint=c),
        entry("Selection","Selected blocks tint",o->o.selectionTint,(o,c)->o.selectionTint=c),
        entry("Selection","Face against a solid block",o->o.selectionContact,(o,c)->o.selectionContact=c),
        entry("Builder selection","Outline",o->o.builderSelectionColor,(o,c)->o.builderSelectionColor=c),
        entry("Builder selection","Fill",o->o.builderSelectionFill,(o,c)->o.builderSelectionFill=c),
        entry("Destination blocks","Outline",o->o.previewColor,(o,c)->o.previewColor=c),
        entry("Destination blocks","Fill",o->o.previewFill,(o,c)->o.previewFill=c),
        entry("Destination blocks","Blocks tint",o->o.previewTint,(o,c)->o.previewTint=c),
        entry("Remove blocks","Fill",o->o.removedFill,(o,c)->o.removedFill=c),
        entry("Remove blocks","Blocks tint",o->o.removedTint,(o,c)->o.removedTint=c),
        entry("Hover preview","Outline",o->o.hoverColor,(o,c)->o.hoverColor=c),
        entry("Hover preview","Fill",o->o.hoverFill,(o,c)->o.hoverFill=c),
        entry("AI preview","Outline",o->o.aiColor,(o,c)->o.aiColor=c),
        entry("AI preview","Fill",o->o.aiFill,(o,c)->o.aiFill=c),
        entry("AI preview","Blocks tint",o->o.aiTint,(o,c)->o.aiTint=c),
        entry("Other players' AI preview","Outline",o->o.sharedAiColor,(o,c)->o.sharedAiColor=c),
        entry("Other players' AI preview","Fill",o->o.sharedAiFill,(o,c)->o.sharedAiFill=c),
        entry("Other players' AI preview","Blocks tint",o->o.sharedAiTint,(o,c)->o.sharedAiTint=c));
    private static final List<Width> WIDTHS=List.of(
        new Width("Selection line width",o->o.selectionWidth,(o,v)->o.selectionWidth=v),
        new Width("Builder selection line width",o->o.builderSelectionWidth,(o,v)->o.builderSelectionWidth=v),
        new Width("Destination line width",o->o.builderPreviewWidth,(o,v)->o.builderPreviewWidth=v));

    private static final List<Entry> RULER_ENTRIES=List.of(
        entry("Ruler","Line",o->o.rulerLineColor,(o,c)->o.rulerLineColor=c),
        entry("Ruler","Line along X",o->o.rulerAxisXColor,(o,c)->o.rulerAxisXColor=c),
        entry("Ruler","Line along Y",o->o.rulerAxisYColor,(o,c)->o.rulerAxisYColor=c),
        entry("Ruler","Line along Z",o->o.rulerAxisZColor,(o,c)->o.rulerAxisZColor=c),
        entry("Ruler","Knobs",o->o.rulerKnobColor,(o,c)->o.rulerKnobColor=c),
        entry("Ruler","Knob (selected)",o->o.rulerKnobSelectedColor,(o,c)->o.rulerKnobSelectedColor=c),
        entry("Span label","Text",o->o.rulerTextColor,(o,c)->o.rulerTextColor=c),
        entry("Span label","Background",o->o.rulerTextBackground,(o,c)->o.rulerTextBackground=c));
    private static final List<Width> RULER_WIDTHS=List.of(new Width("Span label size",o->o.rulerLabelSize,(o,v)->o.rulerLabelSize=v,Options.RULER_LABEL_MIN*100,Options.RULER_LABEL_MAX*100,true));

    private static Entry entry(String group,String name,ToIntFunction<Options> get,ObjIntConsumer<Options> set){
        return new Entry(group,name,()->get.applyAsInt(ClientState.options),c->set.accept(ClientState.options,c),()->get.applyAsInt(DEFAULTS));
    }

    private final Screen parent;
    private final String heading;
    private final List<Entry> entries;
    private final List<Width> widths;
    /** Whether the screen sets the selection's style: the selection screen does, the ruler's does not. */
    private final boolean styled;
    private int left,top,selected=-1;
    private ColourPicker picker;

    public SelectionOptionsScreen(Screen parent){this(parent,"Selection options",ENTRIES,WIDTHS,true);}
    private SelectionOptionsScreen(Screen parent,String title,List<Entry> entries,List<Width> widths,boolean styled){
        super(Component.literal(title));this.parent=parent;heading=title;this.entries=entries;this.widths=widths;this.styled=styled;
    }
    /** The Ruler tool's colours: its line, its knobs and its span label. */
    static SelectionOptionsScreen ruler(Screen parent){return new SelectionOptionsScreen(parent,"Ruler options",RULER_ENTRIES,RULER_WIDTHS,false);}

    @Override protected void init(){
        left=(width-W)/2;top=(height-H)/2;
        clearWidgets();picker=null;
        var o=ClientState.options;
        int ex0=left+W-290,sy=top+262; // the style and the line widths sit under the colour editor, so the list of colours has the whole left side
        if(styled){
            addRenderableWidget(new FlatButton(ex0,sy,270,20,"Style: "+o.selectionStyle.label+"   (click to cycle)",()->{
                var all=Options.SelectionStyle.values();o.selectionStyle=all[(o.selectionStyle.ordinal()+1)%all.length];init();
            }).back(()->{var all=Options.SelectionStyle.values();o.selectionStyle=all[(o.selectionStyle.ordinal()+all.length-1)%all.length];init();}));
            sy+=38; // the line under the button says what the style does
        }
        for(var w:widths){
            double low=w.min(),span=w.max()-w.min();
            var widthSlider=new FlatSlider(ex0,sy,270,19,Component.empty(),(w.units(w.get().applyAsDouble(o))-low)/span){
                protected void updateMessage(){setMessage(Component.literal(w.label()+": "+w.show(low+value*span)));}
                protected void applyValue(){w.set().accept(o,w.fromUnits(low+value*span));}
            };
            widthSlider.range(low,w.max(),w.units(w.get().applyAsDouble(DEFAULTS)));widthSlider.setRaw((w.units(w.get().applyAsDouble(o))-low)/span);addRenderableWidget(widthSlider);
            sy+=22;
        }
        addRenderableWidget(new FlatButton(ex0,top+H-30,132,22,"Reset all",()->{for(var e:entries)e.set().accept(e.fallback().getAsInt());for(var w:widths)w.set().accept(o,w.get().applyAsDouble(DEFAULTS));if(styled)o.selectionStyle=DEFAULTS.selectionStyle;init();}));
        addRenderableWidget(new FlatButton(ex0+138,top+H-30,132,22,"Done",this::onClose));
        if(selected<0)return;
        var entry=entries.get(selected);int ex=left+W-290;
        picker=new ColourPicker(ex,top+52,entry.get(),entry.set(),entry.fallback());
        picker.init(font,w->addRenderableWidget(w));
    }

    private int rowY(int index){
        int y=top+34;String group="";
        for(int i=0;i<=index;i++){
            if(!entries.get(i).group().equals(group)){group=entries.get(i).group();y+=15;}
            if(i<index)y+=ROW;
        }
        return y;
    }

    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        var o=ClientState.options;
        g.fill(0,0,width,height,0xef101923);g.fill(left-8,top-8,left+W+8,top+H+8,0xff172330);
        g.text(font,heading,left+14,top+10,0xffb5dcfc,false);
        String group="";
        for(int i=0;i<entries.size();i++){
            var e=entries.get(i);int y=rowY(i);
            if(!e.group().equals(group)){group=e.group();g.text(font,group.toUpperCase(Locale.ROOT),left+14,y-13,0xff9db5cb,false);}
            boolean over=mouseX>=left+14&&mouseX<left+314&&mouseY>=y&&mouseY<y+ROW-1;
            g.fill(left+14,y,left+314,y+ROW-1,i==selected?0xff315b84:over?0xff2a3d55:0xff223247);
            g.text(font,e.name(),left+22,y+5,0xffdce6ef,false);
            swatch(g,left+270,y+2,38,ROW-5,e.get().getAsInt());
        }
        if(selected>=0){
            var e=entries.get(selected);int ex=left+W-290;
            g.text(font,e.group()+" · "+e.name(),ex,top+36,0xffffd66d,false);
        }else g.text(font,"Click a colour to edit it",left+W-290,top+40,0xff9db5cb,false);
        if(styled)g.text(font,o.selectionStyle==Options.SelectionStyle.SILHOUETTE?"Silhouette: only the contour on the screen":o.selectionStyle==Options.SelectionStyle.OUTLINE?"Outline: the edges on the near side":"All edges: every edge of the selection",left+W-290,top+286,0xff7e91a4,false);
        if(picker!=null)picker.draw(g,font);
        drawWidgets(g,mouseX,mouseY,delta);
    }

    /** A colour over a chequer, so how see-through it is can be seen. */
    private static void swatch(Gfx g,int x,int y,int w,int h,int argb){
        g.fill(x-1,y-1,x+w+1,y+h+1,0xff000000);
        for(int cx=0;cx<w;cx+=6)for(int cy=0;cy<h;cy+=6)g.fill(x+cx,y+cy,Math.min(x+w,x+cx+6),Math.min(y+h,y+cy+6),((cx+cy)/6&1)==0?0xff6b7785:0xff3a4452);
        g.fill(x,y,x+w,y+h,argb);
        g.fill(x,y+h-3,x+w,y+h,0xff000000|argb); // the colour with its opacity ignored, along the bottom
    }

    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
        if(event.button()==1)for(var child:children())if(child instanceof FlatButton rb&&rb.rightClick(event.x(),event.y()))return true; // a cycling button steps back
        if(picker!=null&&picker.mouseClicked(event.x(),event.y(),event.button()))return true;
        if(event.button()==0)for(int i=0;i<entries.size();i++){
            int y=rowY(i);
            if(event.x()>=left+14&&event.x()<left+314&&event.y()>=y&&event.y()<y+ROW-1){selected=selected==i?-1:i;init();return true;}
        }
        return super.mouseClicked(event,twice);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(picker!=null&&picker.mouseDragged(event.x(),event.y(),event.button()))return true;
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){
        if(picker!=null&&picker.mouseReleased(event.button()))return true;
        return super.mouseReleased(event);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientState.save();ClientCompat.setScreen(minecraft,parent);}
}
