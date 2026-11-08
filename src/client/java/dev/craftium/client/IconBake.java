package dev.craftium.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.Gfx;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Draws the editor's line icons once into a texture and puts that on screen with one quad afterwards. An icon is dozens of one-pixel
 * fills, and the Tools panel alone held enough of them to cost more than the rest of the editor's frame put together.
 * <p>
 * The icon is rasterised at the size it is shown at, in screen pixels, using the same rule the GPU uses for a fill (a pixel is covered
 * when its centre is inside), so the baked icon looks like the drawn one. The size comes from the current pose scale and the GUI scale,
 * so a texture is made per icon, colour and size and is kept until the cache grows large.
 */
final class IconBake {
    private IconBake(){}
    /** Room around the icon on every side, in icon units: strokes, stars and dots reach a little past the 24 unit grid. */
    private static final int MARGIN=4;
    private record Key(Object icon,int color,int scale){}
    /** A baked icon: its texture, how many pixels square it is, and how many of those are margin on each side. */
    private record Baked(Tex texture,int pixels,int margin){}
    private static final Map<Key,Baked> CACHE=new HashMap<>();
    private static final int LIMIT=400;
    /** Off only for the tests that compare baked icons with drawn ones. */
    static boolean enabled=true;
    /** Draws what is painted at the origin of an icon at {@code x},{@code y} instead. */
    private record Offset(Gfx g,int x,int y) implements Canvas {
        @Override public void fill(int x1,int y1,int x2,int y2,int color){g.fill(x+x1,y+y1,x+x2,y+y2,color);}
        @Override public void outline(int ox,int oy,int width,int height,int color){g.outline(x+ox,y+oy,width,height,color);}
    }

    /**
     * Draws the icon that {@code painter} paints on a {@code size} unit square at {@code x},{@code y} in the current pose.
     * {@code icon} and {@code color} say which icon it is, so that the same one is made only once.
     */
    static void draw(Gfx g,Object icon,int color,int x,int y,int size,Consumer<Canvas> painter){
        if(!enabled){painter.accept(new Offset(g,x,y));return;}
        var mc=Minecraft.getInstance();
        release(mc,false);
        float unit=g.pose().m00()*(float)Math.max(1,mc.getWindow().getGuiScale()); // screen pixels to a unit of the icon
        var key=new Key(icon,color,Math.round(unit*100));
        var baked=CACHE.get(key);
        if(baked==null){baked=bake(mc,painter,size,unit);if(CACHE.size()>=LIMIT)retire();CACHE.put(key,baked);}
        // Drawn in screen pixels, one texel to a pixel, from a whole pixel of margin: the icon's own grid is then where the GPU would have put it.
        var pose=g.pose();pose.pushMatrix();pose.translate(x,y);pose.scale(1f/unit,1f/unit);
        g.blit(baked.texture(),-baked.margin(),-baked.margin(),0f,0f,baked.pixels(),baked.pixels(),baked.pixels(),baked.pixels(),baked.pixels(),baked.pixels(),0xffffffff);
        pose.popMatrix();
    }
    private static Baked bake(Minecraft mc,Consumer<Canvas> painter,int size,float unit){
        int margin=Math.round(MARGIN*unit),pixels=Math.max(1,Math.round(size*unit)+2*margin);
        var canvas=new PixelCanvas(pixels,pixels,unit,margin);
        painter.accept(canvas);
        var image=new NativeImage(pixels,pixels,false);
        for(int py=0;py<pixels;py++)for(int px=0;px<pixels;px++)image.setPixel(px,py,canvas.argb[py*pixels+px]);
        var texture=new ReferenceTexture(image);texture.filtering(false);
        var id=Tex.of("craftium","icon/"+UUID.randomUUID());
        mc.getTextureManager().register(id.id(),texture);
        return new Baked(id,pixels,margin);
    }
    /** Textures taken out of the cache, and when. A frame that is still being drawn may hold one, so it is freed a moment later, not at once. */
    private static final java.util.List<Tex> RETIRED=new java.util.ArrayList<>();
    private static long retiredAt;
    private static void retire(){for(var baked:CACHE.values())RETIRED.add(baked.texture());CACHE.clear();retiredAt=System.nanoTime();}
    private static void release(Minecraft mc,boolean now){
        if(RETIRED.isEmpty()||!now&&System.nanoTime()-retiredAt<500_000_000L)return;
        for(var tex:RETIRED)mc.getTextureManager().release(tex.id());
        RETIRED.clear();
    }
    /** Frees every baked texture, for when the editor is done with them. */
    static void clear(){retire();release(Minecraft.getInstance(),true);}

    /** A canvas of ARGB pixels. Rectangles are laid on it the way the GPU fills them, and blended over what is there. */
    static final class PixelCanvas implements Canvas {
        final int[] argb;private final int width,height,margin;private final float unit;
        PixelCanvas(int width,int height,float unit,int margin){this.width=width;this.height=height;this.unit=unit;this.margin=margin;argb=new int[width*height];}
        @Override public void fill(int x1,int y1,int x2,int y2,int color){
            int left=Math.max(0,pixel(x1)),right=Math.min(width,pixel(x2)),top=Math.max(0,pixel(y1)),bottom=Math.min(height,pixel(y2));
            for(int py=top;py<bottom;py++)for(int px=left;px<right;px++)argb[py*width+px]=over(argb[py*width+px],color);
        }
        /** The first pixel whose centre is at or past {@code edge} units from the icon's origin, which sits {@code margin} pixels in. */
        private int pixel(int edge){return margin+(int)Math.ceil(edge*unit-0.5);}
        @Override public void outline(int x,int y,int width,int height,int color){
            fill(x,y,x+width,y+1,color);fill(x,y+height-1,x+width,y+height,color);fill(x,y+1,x+1,y+height-1,color);fill(x+width-1,y+1,x+width,y+height-1,color);
        }
        /** {@code src} laid over {@code dst}, both as non-premultiplied ARGB. */
        static int over(int dst,int src){
            int sa=src>>>24;if(sa==255||dst>>>24==0)return src;if(sa==0)return dst;
            int da=dst>>>24;float s=sa/255f,d=da/255f*(1-s),a=s+d;
            int r=Math.round((((src>>16)&255)*s+((dst>>16)&255)*d)/a),g=Math.round((((src>>8)&255)*s+((dst>>8)&255)*d)/a),b=Math.round(((src&255)*s+(dst&255)*d)/a);
            return Math.round(a*255)<<24|r<<16|g<<8|b;
        }
    }
}
