package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.Font;
import java.util.function.IntConsumer;

/**
 * A colour picker for the editor's panels: a square to choose saturation and brightness, a bar to choose the hue, and
 * the colour as hex. It draws itself at a position given each frame and is driven by the editor's mouse events: call
 * {@link #pressSquare}/{@link #pressHue} when a press lands on it, {@link #drag} while the button is held and
 * {@link #release} when it is let go. The result is always opaque.
 */
final class ColourPicker {
    static final int WIDTH=140,SQUARE_HEIGHT=88,BAR_HEIGHT=12,GAP=6;
    private static final int CELL_W=5,CELL_H=4;
    private enum Drag { NONE,SQUARE,HUE }

    private float hue,saturation=1,value=1;
    private int x,y;
    private Drag drag=Drag.NONE;
    private final IntConsumer onChange;

    ColourPicker(IntConsumer onChange){this.onChange=onChange;}

    /** Total height of the widget, including the hex line. */
    static int height(){return SQUARE_HEIGHT+GAP+BAR_HEIGHT+GAP+10;}
    int squareY(){return y;}
    int barY(){return y+SQUARE_HEIGHT+GAP;}
    int x(){return x;}
    boolean active(){return drag!=Drag.NONE;}

    void set(int argb){
        float r=(argb>>16&255)/255f,g=(argb>>8&255)/255f,b=(argb&255)/255f;
        float max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),delta=max-min;
        value=max;saturation=max==0?0:delta/max;
        if(delta>0){
            float h=max==r?((g-b)/delta)%6:max==g?(b-r)/delta+2:(r-g)/delta+4;
            hue=(h/6+1)%1;
        }
    }
    int colour(){return rgb(hue,saturation,value);}

    private static int rgb(float h,float s,float v){
        float c=v*s,hh=(h*6)%6,xx=c*(1-Math.abs(hh%2-1)),m=v-c;
        float r,g,b;
        switch((int)hh){
            case 0->{r=c;g=xx;b=0;}case 1->{r=xx;g=c;b=0;}case 2->{r=0;g=c;b=xx;}
            case 3->{r=0;g=xx;b=c;}case 4->{r=xx;g=0;b=c;}default->{r=c;g=0;b=xx;}
        }
        return 0xff000000|Math.round((r+m)*255)<<16|Math.round((g+m)*255)<<8|Math.round((b+m)*255);
    }

    void draw(Gfx g,Font font,int x,int y){
        this.x=x;this.y=y;
        int cols=WIDTH/CELL_W,rows=SQUARE_HEIGHT/CELL_H;
        for(int row=0;row<rows;row++)for(int col=0;col<cols;col++)
            g.fill(x+col*CELL_W,y+row*CELL_H,x+(col+1)*CELL_W,y+(row+1)*CELL_H,rgb(hue,col/(cols-1f),1-row/(rows-1f)));
        int px=x+Math.round(saturation*(WIDTH-1)),py=y+Math.round((1-value)*(SQUARE_HEIGHT-1));
        g.outline(px-3,py-3,7,7,0xff000000);g.outline(px-2,py-2,5,5,0xffffffff);
        int by=barY();
        for(int col=0;col<cols;col++)g.fill(x+col*CELL_W,by,x+(col+1)*CELL_W,by+BAR_HEIGHT,rgb(col/(cols-1f),1,1));
        int hx=x+Math.round(hue*(WIDTH-1));
        g.fill(hx-1,by-2,hx+2,by+BAR_HEIGHT+2,0xff000000);g.fill(hx,by-2,hx+1,by+BAR_HEIGHT+2,0xffffffff);
        g.fill(x,by+BAR_HEIGHT+GAP,x+10,by+BAR_HEIGHT+GAP+10,colour());
        g.text(font,String.format("#%06X",colour()&0xffffff),x+16,by+BAR_HEIGHT+GAP+1,0xffc7d6e5,false);
    }

    void pressSquare(double px,double py){drag=Drag.SQUARE;move(px,py);}
    void pressHue(double px,double py){drag=Drag.HUE;move(px,py);}
    void drag(double px,double py){if(drag!=Drag.NONE)move(px,py);}
    void release(){drag=Drag.NONE;}

    private void move(double px,double py){
        if(drag==Drag.SQUARE){
            saturation=(float)Math.clamp((px-x)/(WIDTH-1),0,1);
            value=1-(float)Math.clamp((py-y)/(SQUARE_HEIGHT-1),0,1);
        }else if(drag==Drag.HUE){
            hue=(float)Math.clamp((px-x)/(WIDTH-1),0,.9999);
        }
        onChange.accept(colour());
    }
}
