package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Options;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/**
 * How the selection looks: its style, the width of its lines and every colour it and its previews are drawn in.
 * Clicking a colour's swatch opens an editor for it, with a typeable hex code and a slider for each channel.
 * {@link #builder} opens the same screen on the builder tools' own selection, which has no style.
 */
public final class SelectionOptionsScreen extends CraftiumScreen {
    private static final int W=640,H=420,ROW=19;
    private static final Options DEFAULTS=new Options();

    /** One colour setting: what it is called, where it is kept and what it starts as. */
    private record Entry(String group,String name,IntSupplier get,IntConsumer set,IntSupplier fallback){}
    /** One line width, from 0.1 to 10 pixels. */
    private record Width(String label,ToDoubleFunction<Options> get,ObjDoubleConsumer<Options> set){}

    private static final List<Entry> ENTRIES=List.of(
        entry("Selection","Outline",o->o.selectionColor,(o,c)->o.selectionColor=c),
        entry("Selection","Fill",o->o.selectionFill,(o,c)->o.selectionFill=c),
        entry("Selection","Selected blocks tint",o->o.selectionTint,(o,c)->o.selectionTint=c),
        entry("Destination preview","Outline",o->o.previewColor,(o,c)->o.previewColor=c),
        entry("Destination preview","Fill",o->o.previewFill,(o,c)->o.previewFill=c),
        entry("Destination preview","Blocks tint",o->o.previewTint,(o,c)->o.previewTint=c),
        entry("Removed blocks","Outline",o->o.removedColor,(o,c)->o.removedColor=c),
        entry("Removed blocks","Fill",o->o.removedFill,(o,c)->o.removedFill=c),
        entry("Removed blocks","Blocks tint",o->o.removedTint,(o,c)->o.removedTint=c),
        entry("Hover preview","Outline",o->o.hoverColor,(o,c)->o.hoverColor=c),
        entry("Hover preview","Fill",o->o.hoverFill,(o,c)->o.hoverFill=c));
    private static final List<Width> WIDTHS=List.of(new Width("Line width",o->o.selectionWidth,(o,v)->o.selectionWidth=v));

    private static final List<Entry> BUILDER_ENTRIES=List.of(
        entry("Builder selection","Outline",o->o.builderSelectionColor,(o,c)->o.builderSelectionColor=c),
        entry("Builder selection","Fill",o->o.builderSelectionFill,(o,c)->o.builderSelectionFill=c),
        entry("Where it lands","Outline",o->o.builderPreviewColor,(o,c)->o.builderPreviewColor=c),
        entry("Where it lands","Fill",o->o.builderPreviewFill,(o,c)->o.builderPreviewFill=c),
        entry("Where it lands","Blocks tint",o->o.builderPreviewTint,(o,c)->o.builderPreviewTint=c),
        entry("Blocks being moved","Blocks tint",o->o.builderMovingTint,(o,c)->o.builderMovingTint=c));
    private static final List<Width> BUILDER_WIDTHS=List.of(
        new Width("Selection line width",o->o.builderSelectionWidth,(o,v)->o.builderSelectionWidth=v),
        new Width("Landing line width",o->o.builderPreviewWidth,(o,v)->o.builderPreviewWidth=v));

    private static final List<Entry> RULER_ENTRIES=List.of(
        entry("Ruler","Line",o->o.rulerLineColor,(o,c)->o.rulerLineColor=c),
        entry("Ruler","Knobs",o->o.rulerKnobColor,(o,c)->o.rulerKnobColor=c),
        entry("Ruler","Knob with the gizmo",o->o.rulerKnobSelectedColor,(o,c)->o.rulerKnobSelectedColor=c),
        entry("Length label","Text",o->o.rulerTextColor,(o,c)->o.rulerTextColor=c),
        entry("Length label","Background",o->o.rulerTextBackground,(o,c)->o.rulerTextBackground=c));

    private static Entry entry(String group,String name,ToIntFunction<Options> get,ObjIntConsumer<Options> set){
        return new Entry(group,name,()->get.applyAsInt(ClientState.options),c->set.accept(ClientState.options,c),()->get.applyAsInt(DEFAULTS));
    }

    private final Screen parent;
    private final String heading;
    private final List<Entry> entries;
    private final List<Width> widths;
    /** Whether the screen sets the selection's style: the editor's selection has one, the builder's does not. */
    private final boolean styled;
    private int left,top,selected=-1;
    private EditBox hex;
    private boolean updatingHex;
    private final FlatSlider[] channels=new FlatSlider[4];
    private static final String[] CHANNEL_NAMES={"Red","Green","Blue","Opacity"};
    private static final int[] CHANNEL_SHIFT={16,8,0,24};

    public SelectionOptionsScreen(Screen parent){this(parent,"Selection options",ENTRIES,WIDTHS,true);}
    private SelectionOptionsScreen(Screen parent,String title,List<Entry> entries,List<Width> widths,boolean styled){
        super(Component.literal(title));this.parent=parent;heading=title.toUpperCase(Locale.ROOT);this.entries=entries;this.widths=widths;this.styled=styled;
    }
    /** The builder tools' selection: its colours, the colours of where it would land, and both line widths. */
    static SelectionOptionsScreen builder(Screen parent){return new SelectionOptionsScreen(parent,"Builder selection options",BUILDER_ENTRIES,BUILDER_WIDTHS,false);}

    /** The Ruler tool's colours: its line, its knobs and its length label. */
    static SelectionOptionsScreen ruler(Screen parent){return new SelectionOptionsScreen(parent,"Ruler options",RULER_ENTRIES,List.of(),false);}

    @Override protected void init(){
        left=(width-W)/2;top=(height-H)/2;
        clearWidgets();hex=null;
        var o=ClientState.options;
        int sy=top+34;
        if(styled){
            addRenderableWidget(new FlatButton(left+14,sy,300,20,"Style: "+o.selectionStyle.label+"   (click to cycle)",()->{
                var all=Options.SelectionStyle.values();o.selectionStyle=all[(o.selectionStyle.ordinal()+1)%all.length];init();
            }).back(()->{var all=Options.SelectionStyle.values();o.selectionStyle=all[(o.selectionStyle.ordinal()+all.length-1)%all.length];init();}));
            sy+=26;
        }
        for(var w:widths){
            var widthSlider=new FlatSlider(left+14,sy,300,19,Component.empty(),(w.get().applyAsDouble(o)-.1)/9.9){
                protected void updateMessage(){setMessage(Component.literal(w.label()+": "+Math.round((.1+value*9.9)*100)/100d));}
                protected void applyValue(){w.set().accept(o,Math.round((.1+value*9.9)*100)/100d);}
            };
            widthSlider.range(.1,10,w.get().applyAsDouble(DEFAULTS));widthSlider.setRaw((w.get().applyAsDouble(o)-.1)/9.9);addRenderableWidget(widthSlider);
            sy+=24;
        }
        addRenderableWidget(new FlatButton(left+14,top+H-34,146,22,"Reset all",()->{for(var e:entries)e.set().accept(e.fallback().getAsInt());for(var w:widths)w.set().accept(o,w.get().applyAsDouble(DEFAULTS));if(styled)o.selectionStyle=DEFAULTS.selectionStyle;init();}));
        addRenderableWidget(new FlatButton(left+168,top+H-34,146,22,"Done",this::onClose));
        if(selected<0)return;
        var entry=entries.get(selected);int ex=left+W-290;
        hex=new EditBox(font,ex,top+78,100,20,Component.literal("Hex colour"));
        hex.setMaxLength(9);hex.setValue(hexOf(entry.get().getAsInt()));
        hex.setResponder(text->{if(updatingHex)return;Integer parsed=parse(text,entry.get().getAsInt());if(parsed!=null){entry.set().accept(parsed);syncSliders();}});
        addRenderableWidget(hex);
        for(int i=0;i<4;i++){
            int channel=i,shift=CHANNEL_SHIFT[i];
            var slider=new FlatSlider(ex,top+108+i*24,270,19,Component.empty(),(entry.get().getAsInt()>>shift&255)/255d){
                protected void updateMessage(){setMessage(Component.literal(CHANNEL_NAMES[channel]+": "+Math.round(value*255)));}
                protected void applyValue(){
                    int colour=entry.get().getAsInt();
                    entry.set().accept(colour&~(255<<shift)|(int)Math.round(value*255)<<shift);
                    if(hex!=null&&!hex.isFocused()){updatingHex=true;hex.setValue(hexOf(entry.get().getAsInt()));updatingHex=false;}
                }
            };
            slider.range(0,255,entry.fallback().getAsInt()>>shift&255);slider.setRaw((entry.get().getAsInt()>>shift&255)/255d);
            channels[i]=slider;addRenderableWidget(slider);
        }
        addRenderableWidget(new FlatButton(ex,top+212,132,20,"Reset this colour",()->{entry.set().accept(entry.fallback().getAsInt());init();}));
    }

    private void syncSliders(){
        if(selected<0)return;int colour=entries.get(selected).get().getAsInt();
        for(int i=0;i<4;i++)if(channels[i]!=null)channels[i].setRaw((colour>>CHANNEL_SHIFT[i]&255)/255d);
    }
    /** The colour's red, green and blue as hex; the opacity has its own slider. */
    private static String hexOf(int argb){return String.format(Locale.ROOT,"#%06X",argb&0xffffff);}
    /** A hex code of six digits (the opacity is kept) or eight (red, green, blue, opacity), with or without the #; null when it is not one. */
    private static Integer parse(String text,int current){
        String t=text.trim();if(t.startsWith("#"))t=t.substring(1);
        if(t.length()!=6&&t.length()!=8)return null;
        try{
            long v=Long.parseLong(t,16);
            if(t.length()==6)return current&0xff000000|(int)v;
            return (int)(v&0xff)<<24|(int)(v>>8);
        }catch(NumberFormatException e){return null;}
    }

    private int rowY(int index){
        int y=top+34+(styled?26:0)+widths.size()*24+8;String group="";
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
        g.text(font,"CRAFTIUM  /  "+heading,left+14,top+10,0xffb5dcfc,false);
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
            swatch(g,ex,top+50,270,20,e.get().getAsInt());
            g.text(font,"Hex",ex+108,top+84,0xff9db5cb,false);
            g.text(font,"6 digits keep the opacity · 8 digits are RRGGBBAA",ex,top+240,0xff7e91a4,false);
        }else g.text(font,"Click a colour to edit it",left+W-290,top+40,0xff9db5cb,false);
        if(styled)g.text(font,o.selectionStyle==Options.SelectionStyle.SILHOUETTE?"Silhouette: only the contour of the selection on the screen":o.selectionStyle==Options.SelectionStyle.OUTLINE?"Outline: the edges on the near side of the selection":"All edges: every edge of the selection",left+330,top+H-26,0xff7e91a4,false);
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
        if(event.button()==0)for(int i=0;i<entries.size();i++){
            int y=rowY(i);
            if(event.x()>=left+14&&event.x()<left+314&&event.y()>=y&&event.y()<y+ROW-1){selected=selected==i?-1:i;init();return true;}
        }
        return super.mouseClicked(event,twice);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientState.save();ClientCompat.setScreen(minecraft,parent);}
}
