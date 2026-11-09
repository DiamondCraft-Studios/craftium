package dev.craftium.core;

/** The outline colour a saved selection starts with: the player's selection colour for the first, then the same colour turned round the hue wheel by the golden angle for each next one, so neighbours never look alike. */
public final class SavedSelectionColors {
    private SavedSelectionColors(){}
    public static final double STEP=137.50776405003785;
    /** The colour for the index-th saved selection (0 is the base colour itself), keeping the base's alpha, saturation and brightness. */
    public static int forIndex(int base,int index){
        if(index<=0)return base;
        int r=base>>16&255,g=base>>8&255,b=base&255;
        int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        double v=max/255.0,s=max==0?0:(max-min)/(double)max,h;
        if(max==min)return base; // a grey has no hue to turn
        double d=max-min;
        if(max==r)h=((g-b)/d)%6;else if(max==g)h=(b-r)/d+2;else h=(r-g)/d+4;
        h=((h*60+STEP*index)%360+360)%360;
        double c=v*s,x=c*(1-Math.abs((h/60)%2-1)),m=v-c,rr,gg,bb;
        if(h<60){rr=c;gg=x;bb=0;}else if(h<120){rr=x;gg=c;bb=0;}else if(h<180){rr=0;gg=c;bb=x;}else if(h<240){rr=0;gg=x;bb=c;}else if(h<300){rr=x;gg=0;bb=c;}else{rr=c;gg=0;bb=x;}
        return base&0xff000000|Math.round((float)((rr+m)*255))<<16|Math.round((float)((gg+m)*255))<<8|Math.round((float)((bb+m)*255));
    }
}
