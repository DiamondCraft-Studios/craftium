package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.awt.Color;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A colour picker with a real 2D area: saturation along it and brightness down it, a strip for the hue, a strip for the opacity and a
 * typeable hex code. It edits one ARGB colour through a getter and a setter, so it can sit inside any screen (see
 * {@link SelectionOptionsScreen}) or fill a window of its own ({@link ColourPickerScreen}). The owner adds {@link #init}'s widgets, draws
 * it with {@link #draw}, and passes it the mouse.
 */
final class ColourPicker {
    static final int AREA_W=214,AREA_H=130,BAR=18,GAP=6,WIDTH=AREA_W+2*(GAP+BAR),HEIGHT=AREA_H+46;
    private static final int NONE=0,AREA=1,HUE=2,OPACITY=3;

    private final int x,y;
    private final IntSupplier get,fallback;
    private final IntConsumer set;
    /** The colour as hue, saturation and brightness (0 to 1) and opacity (0 to 255). The hue and saturation are kept apart from the ARGB so they survive passing through grey and black. */
    private float hue,sat,val;
    private int alpha,emitted;
    private boolean loaded;
    private int dragging=NONE;
    private EditBox hex;
    private boolean updatingHex;

    ColourPicker(int x,int y,IntSupplier get,IntConsumer set,IntSupplier fallback){this.x=x;this.y=y;this.get=get;this.set=set;this.fallback=fallback;}

    /** Makes the hex box and the reset button; the owner adds them to its screen. */
    void init(Font font,Consumer<AbstractWidget> add){
        hex=new EditBox(font,x+52,y+AREA_H+8,92,20,Component.literal("Hex colour"));
        hex.setMaxLength(9);hex.setValue(hexOf(get.getAsInt()));
        hex.setResponder(text->{if(updatingHex)return;Integer parsed=parse(text,get.getAsInt());if(parsed!=null)set.accept(parsed);});
        add.accept(hex);
        add.accept(new FlatButton(x+150,y+AREA_H+8,WIDTH-150,20,"Reset",()->{set.accept(fallback.getAsInt());sync();showHex();}));
    }

    /** Takes in a colour changed from outside (the hex box, a reset) without losing the hue the picker was showing. */
    private void sync(){
        int c=get.getAsInt();
        if(loaded&&c==emitted)return;
        loaded=true;emitted=c;alpha=c>>>24;
        float[] f=Color.RGBtoHSB(c>>16&255,c>>8&255,c&255,null);
        if(f[1]>0&&f[2]>0)hue=f[0]; // grey and black have no hue of their own: keep the one that was showing
        if(f[2]>0)sat=f[1];
        val=f[2];
    }
    private void emit(){
        int c=alpha<<24|Color.HSBtoRGB(hue,sat,val)&0xffffff;
        emitted=c;loaded=true;set.accept(c);showHex();
    }
    private void showHex(){if(hex!=null&&!hex.isFocused()){updatingHex=true;hex.setValue(hexOf(get.getAsInt()));updatingHex=false;}}

    private int areaRight(){return x+AREA_W;}
    private int hueLeft(){return x+AREA_W+GAP;}
    private int opacityLeft(){return x+AREA_W+GAP+BAR+GAP;}

    void draw(Gfx g,Font font){
        sync();
        int rgb=Color.HSBtoRGB(hue,sat,val)&0xffffff;
        // the area: every column is the colour of that saturation at full brightness, fading to black at the bottom
        g.fill(x-1,y-1,areaRight()+1,y+AREA_H+1,0xff000000);
        for(int i=0;i<AREA_W;i++)g.fillGradient(x+i,y,x+i+1,y+AREA_H,0xff000000|Color.HSBtoRGB(hue,i/(AREA_W-1f),1)&0xffffff,0xff000000);
        int px=x+Math.round(sat*(AREA_W-1)),py=y+Math.round((1-val)*(AREA_H-1));
        g.outline(px-4,py-4,9,9,0xff000000);g.outline(px-3,py-3,7,7,0xffffffff);
        // the hue strip: six steps round the colour wheel
        int hl=hueLeft();g.fill(hl-1,y-1,hl+BAR+1,y+AREA_H+1,0xff000000);
        for(int k=0;k<6;k++)g.fillGradient(hl,y+k*AREA_H/6,hl+BAR,y+(k+1)*AREA_H/6,0xff000000|Color.HSBtoRGB(k/6f,1,1)&0xffffff,0xff000000|Color.HSBtoRGB((k+1)/6f,1,1)&0xffffff);
        marker(g,hl,y+Math.round(hue*(AREA_H-1)));
        // the opacity strip: the colour over a chequer, solid at the top and see-through at the bottom
        int ol=opacityLeft();g.fill(ol-1,y-1,ol+BAR+1,y+AREA_H+1,0xff000000);
        for(int cy=0;cy<AREA_H;cy+=6)for(int cx=0;cx<BAR;cx+=6)g.fill(ol+cx,y+cy,ol+Math.min(BAR,cx+6),y+Math.min(AREA_H,cy+6),((cx+cy)/6&1)==0?0xff6b7785:0xff3a4452);
        g.fillGradient(ol,y,ol+BAR,y+AREA_H,0xff000000|rgb,rgb);
        marker(g,ol,y+Math.round((1-alpha/255f)*(AREA_H-1)));
        // the colour itself, over a chequer so its opacity shows, next to the hex code
        int sy=y+AREA_H+8;g.fill(x-1,sy-1,x+47,sy+21,0xff000000);
        for(int cx=0;cx<46;cx+=6)for(int cy=0;cy<20;cy+=6)g.fill(x+cx,sy+cy,x+Math.min(46,cx+6),sy+Math.min(20,cy+6),((cx+cy)/6&1)==0?0xff6b7785:0xff3a4452);
        g.fill(x,sy,x+46,sy+20,alpha<<24|rgb);
        g.text(font,"Opacity "+Math.round(alpha*100/255f)+"%  ·  6 digits keep it, 8 are RRGGBBAA",x,y+AREA_H+34,0xff7e91a4,false);
    }
    /** The line across a strip that shows where the value sits. */
    private static void marker(Gfx g,int left,int at){
        g.fill(left-2,at-2,left+BAR+2,at+3,0xff000000);g.fill(left-1,at-1,left+BAR+1,at+2,0xffffffff);
    }

    private void apply(double mx,double my){
        switch(dragging){
            case AREA->{sat=(float)Math.clamp((mx-x)/(AREA_W-1),0,1);val=1-(float)Math.clamp((my-y)/(AREA_H-1),0,1);}
            case HUE->hue=(float)Math.clamp((my-y)/(AREA_H-1),0,.9999);
            case OPACITY->alpha=(int)Math.round((1-Math.clamp((my-y)/(AREA_H-1),0,1))*255);
            default->{return;}
        }
        emit();
    }
    private static boolean inside(double mx,double my,int left,int top,int w,int h){return mx>=left&&mx<left+w&&my>=top&&my<top+h;}

    boolean mouseClicked(double mx,double my,int button){
        if(button!=0)return false;
        if(inside(mx,my,x,y,AREA_W,AREA_H))dragging=AREA;
        else if(inside(mx,my,hueLeft(),y,BAR,AREA_H))dragging=HUE;
        else if(inside(mx,my,opacityLeft(),y,BAR,AREA_H))dragging=OPACITY;
        else return false;
        if(hex!=null)hex.setFocused(false);
        sync();apply(mx,my);return true;
    }
    boolean mouseDragged(double mx,double my,int button){
        if(dragging==NONE||button!=0)return false;
        apply(mx,my);return true;
    }
    boolean mouseReleased(int button){
        if(dragging==NONE||button!=0)return false;
        dragging=NONE;return true;
    }

    /** The colour's red, green and blue as hex; the opacity has its own strip. */
    static String hexOf(int argb){return String.format(Locale.ROOT,"#%06X",argb&0xffffff);}
    /** A hex code of six digits (the opacity is kept) or eight (red, green, blue, opacity), with or without the #; null when it is not one. */
    static Integer parse(String text,int current){
        String t=text.trim();if(t.startsWith("#"))t=t.substring(1);
        if(t.length()!=6&&t.length()!=8)return null;
        try{
            long v=Long.parseLong(t,16);
            if(t.length()==6)return current&0xff000000|(int)v;
            return (int)(v&0xff)<<24|(int)(v>>8);
        }catch(NumberFormatException e){return null;}
    }
}
