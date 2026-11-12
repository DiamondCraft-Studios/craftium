package dev.craftium.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.Gfx;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * Text drawn with its edges blended, for a fractional interface scale. The game font is a bitmap: at 1.5 screen pixels per font pixel some of its pixels land
 * on one screen pixel and some on two, which makes the letters lumpy. Here the letters are made from the font's own bitmap (the first 128 characters, the plain
 * Latin ones) and averaged down onto the screen pixels, so a font pixel is 1.5 pixels wide as far as the eye can tell, and the layout is the game's own.
 * The picture of a text is made once and kept; it starts on a whole screen pixel. Anything it cannot draw (other characters, other fonts) is left to the game.
 */
public final class CrispText {
    private CrispText(){}

    private record Picture(Tex tex,int width,int height,long used){}
    private static final Map<String,Picture> PICTURES=new HashMap<>();
    private static final long KEEP_MS=4000;
    private static final int MOST=900;
    private static int made;
    private static byte[][] glyph; // 8 x 8 alpha (0 or 1) by character
    private static int[] advance;
    private static boolean loaded,usable;

    /** Draws the text with its top left (or its middle, when centred) at x, y of the current pose, if the pose puts a font pixel between whole screen pixels. False: not drawn, draw it as the game does. */
    public static boolean draw(Gfx g,Font font,String text,int x,int y,int color,boolean shadow,boolean centred){
        if(TextMode.mode!=TextMode.Mode.SUPERSAMPLED||text==null)return false;
        var pose=g.pose();
        double gui=Minecraft.getInstance().getWindow().getGuiScale(),sx=pose.m00(),pixels=gui*sx;
        if(sx<=0||pose.m11()!=sx||pose.m01()!=0||pose.m10()!=0||Math.abs(pixels-Math.rint(pixels))<.02)return false;
        if(text.isEmpty())return true;
        if((color>>>24)==0)return true; // the game draws nothing for a colour with no alpha either
        if(!load())return false;
        int width=0;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c<32||c>126)return false;width+=advance[c];}
        if(width!=font.width(text))return false; // a different font or resource pack: not this bitmap
        String key=text+'\0'+Integer.toHexString(color)+(shadow?'s':'p')+Math.round(pixels*1000);
        long now=System.currentTimeMillis();
        var picture=PICTURES.get(key);
        if(picture==null){
            if(PICTURES.size()>=MOST)forgetOld(now);
            picture=make(text,width,color,shadow,pixels);
            if(picture==null)return false;
        }
        PICTURES.put(key,new Picture(picture.tex(),picture.width(),picture.height(),now));
        int left=centred?x-width/2:x;
        double ox=Math.round((sx*left+pose.m20())*gui),oy=Math.round((sx*y+pose.m21())*gui);
        pose.pushMatrix();
        pose.identity();
        pose.translate((float)(ox/gui),(float)(oy/gui));
        pose.scale((float)(1/gui),(float)(1/gui)); // one unit is now one screen pixel
        try{g.blit(picture.tex(),0,0,0,0,picture.width(),picture.height(),picture.width(),picture.height(),picture.width(),picture.height(),0xffffffff);}
        finally{pose.popMatrix();}
        return true;
    }

    /** Lets go of the pictures not used for a while (never one that may still be waiting to be drawn this frame). */
    private static void forgetOld(long now){
        var manager=Minecraft.getInstance().getTextureManager();
        PICTURES.entrySet().removeIf(e->{if(now-e.getValue().used()<KEEP_MS)return false;manager.release(e.getValue().tex().id());return true;});
    }

    public static void releaseAll(){
        var manager=Minecraft.getInstance().getTextureManager();
        for(var p:PICTURES.values())manager.release(p.tex().id());
        PICTURES.clear();
    }

    private static Picture make(String text,int width,int color,boolean shadow,double scale){
        int sw=width+(shadow?1:0),sh=8+(shadow?1:0);
        boolean[][] main=new boolean[sh][sw],dark=new boolean[sh][sw];
        int cursor=0;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            byte[] cell=glyph[c];
            for(int row=0;row<8;row++)for(int col=0;col<8;col++)if(cell[row*8+col]!=0&&cursor+col<width){main[row][cursor+col]=true;if(shadow)dark[row+1][cursor+col+1]=true;}
            cursor+=advance[c];
        }
        int w=(int)Math.ceil(sw*scale),h=(int)Math.ceil(sh*scale);
        float[][] coverMain=average(main,sw,sh,w,h,scale),coverDark=average(dark,sw,sh,w,h,scale);
        int alpha=color>>>24,r=(color>>16)&255,gr=(color>>8)&255,b=color&255;
        int dr=r/4,dg=gr/4,db=b/4; // the game shadow: the colour at a quarter of its brightness
        var image=new NativeImage(w,h,false);
        for(int row=0;row<h;row++)for(int col=0;col<w;col++){
            double am=coverMain[row][col]*alpha/255.0,as=coverDark[row][col]*alpha/255.0,a=am+as*(1-am);
            if(a<=0.002){image.setPixel(col,row,0);continue;}
            int outR=(int)Math.round((am*r+as*(1-am)*dr)/a),outG=(int)Math.round((am*gr+as*(1-am)*dg)/a),outB=(int)Math.round((am*b+as*(1-am)*db)/a);
            image.setPixel(col,row,((int)Math.round(a*255)&255)<<24|outR<<16|outG<<8|outB);
        }
        var texture=new ReferenceTexture(image);
        texture.filtering(false);
        var tex=Tex.of("craftium","crisp_text/"+(made++));
        Minecraft.getInstance().getTextureManager().register(tex.id(),texture);
        return new Picture(tex,w,h,System.currentTimeMillis());
    }

    /** The share of each screen pixel (w by h of them, each 1/scale of a font pixel across) that the lit font pixels cover. */
    private static float[][] average(boolean[][] lit,int sw,int sh,int w,int h,double scale){
        double[][] across=weights(w,scale),down=weights(h,scale);
        float[][] out=new float[h][w];
        for(int row=0;row<h;row++){
            double[] wy=down[row];
            int y0=(int)wy[0];
            for(int col=0;col<w;col++){
                double[] wx=across[col];
                int x0=(int)wx[0];
                double sum=0;
                for(int j=1;j<wy.length;j++){
                    int sy=y0+j-1;if(sy>=sh)break;
                    for(int i=1;i<wx.length;i++){int sxx=x0+i-1;if(sxx>=sw)break;if(lit[sy][sxx])sum+=wy[j]*wx[i];}
                }
                out[row][col]=(float)Math.min(1,sum);
            }
        }
        return out;
    }

    /** For each screen pixel, the first font pixel it overlaps (element 0) and how much of the screen pixel each font pixel from there on covers (as a share of the screen pixel). */
    private static double[][] weights(int pixels,double scale){
        double[][] out=new double[pixels][];
        for(int p=0;p<pixels;p++){
            double from=p/scale,to=(p+1)/scale;
            int first=(int)Math.floor(from),last=(int)Math.ceil(to)-1;
            double[] row=new double[last-first+2];
            row[0]=first;
            for(int s=first;s<=last;s++){double overlap=Math.min(to,s+1)-Math.max(from,s);row[s-first+1]=Math.max(0,overlap)*scale;}
            out[p]=row;
        }
        return out;
    }

    /** Reads the plain Latin font bitmap of the game, once. */
    private static boolean load(){
        if(loaded)return usable;
        loaded=true;
        try{
            var id=Tex.of("minecraft","textures/font/ascii.png").id();
            var resource=Minecraft.getInstance().getResourceManager().getResource(id);
            if(resource.isEmpty())return false;
            try(var in=resource.get().open();var image=NativeImage.read(in)){
                if(image.getWidth()!=128||image.getHeight()!=128)return false; // another sized font: not drawn here
                glyph=new byte[128][];advance=new int[128];
                for(int c=0;c<128;c++){
                    glyph[c]=new byte[64];
                    int widest=0;
                    for(int row=0;row<8;row++)for(int col=0;col<8;col++)
                        if((image.getPixel(c%16*8+col,c/16*8+row)>>>24)>=128){glyph[c][row*8+col]=1;widest=Math.max(widest,col+1);}
                    advance[c]=c==' '?4:widest==0?0:widest+1;
                }
            }
            usable=true;
        }catch(Exception e){usable=false;}
        return usable;
    }
}
