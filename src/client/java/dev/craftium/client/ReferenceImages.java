package dev.craftium.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import net.minecraft.client.Minecraft;
import dev.craftium.client.compat.Gfx;
import dev.craftium.client.compat.Tex;
import net.minecraft.client.renderer.texture.DynamicTexture;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** Local reference windows use Minecraft-managed textures and never upload images. */
public final class ReferenceImages {
    private static final class Texture extends DynamicTexture {
        Texture(NativeImage image){super(()->"Craftium reference image",image);}
        void filtering(boolean linear){sampler=RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,linear?FilterMode.LINEAR:FilterMode.NEAREST,FilterMode.NEAREST,false);}
    }
    private static final class Window {
        Tex id;Texture texture;String name;int x=220,y=60,w=240,h=180,pixelW,pixelH,opacity=100;boolean game,borderless,linear;
    }
    private static final List<Window> windows=new ArrayList<>();
    private static Window dragged,menu;
    private static boolean resizing;
    private static double dragX,dragY;
    private static int oldX,oldY,oldW,oldH,menuX,menuY;
    private ReferenceImages(){}
    public static void closeAll(){for(Window window:windows)Minecraft.getInstance().getTextureManager().release(window.id.id());windows.clear();dragged=null;menu=null;}
    public static void load(Path file){
        if(windows.size()>=4)throw new IllegalArgumentException("Close a reference image first (maximum four)");
        try{
            if(Files.size(file)>8*1024*1024)throw new IllegalArgumentException("Reference image limit: 8 MiB");
            try(var input=ImageIO.createImageInputStream(file.toFile())){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("Choose a PNG image");var reader=readers.next();try{reader.setInput(input);if(reader.getWidth(0)>2048||reader.getHeight(0)>2048)throw new IllegalArgumentException("Reference image limit: 2048 × 2048");}finally{reader.dispose();}}
            NativeImage image;try(var input=Files.newInputStream(file)){image=NativeImage.read(input);}
            Window window=new Window();window.pixelW=image.getWidth();window.pixelH=image.getHeight();window.h=Math.clamp(window.w*window.pixelH/window.pixelW,40,360);window.id=Tex.of("craftium","reference/"+UUID.randomUUID());window.texture=new Texture(image);window.name=file.getFileName().toString();Minecraft.getInstance().getTextureManager().register(window.id.id(),window.texture);windows.add(window);
        }catch(java.io.IOException e){throw new IllegalArgumentException("Could not open reference image",e);}
    }
    static void choose(){Thread picker=new Thread(()->{String selected=org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Open Craftium reference PNG","",null,null,false);if(selected!=null)Minecraft.getInstance().execute(()->{try{load(Path.of(selected));}catch(Exception e){ClientState.notify(e.getMessage());}});},"Craftium reference chooser");picker.setDaemon(true);picker.start();}
    static void draw(Gfx g,boolean editor,int mx,int my){
        var font=Minecraft.getInstance().font;for(Window window:windows){if(!editor&&!window.game)continue;int header=window.borderless?0:16;
            if(!window.borderless){g.fill(window.x-2,window.y-2,window.x+window.w+2,window.y+window.h+header+2,0xe5172330);g.text(font,font.plainSubstrByWidth(window.name,window.w-8),window.x+4,window.y+4,0xffb5dcfc,false);}
            int tint=(window.opacity*255/100)<<24|0xffffff;g.blit(window.id,window.x,window.y+header,0,0,window.w,window.h,window.pixelW,window.pixelH,window.pixelW,window.pixelH,tint);
            if(editor&&!window.borderless)g.fill(window.x+window.w-5,window.y+header+window.h-5,window.x+window.w,window.y+header+window.h,0xffb5dcfc);
        }
        if(editor&&menu!=null){String[] choices={"Show in game: "+menu.game,"Borderless: "+menu.borderless,"Opacity: "+menu.opacity+"%","Filtering: "+(menu.linear?"Linear":"Nearest"),"Close image"};for(int i=0;i<choices.length;i++){int y=menuY+i*20;g.fill(menuX,y,menuX+180,y+20,mx>=menuX&&mx<menuX+180&&my>=y&&my<y+20?0xff315473:0xff172330);g.text(font,choices[i],menuX+6,y+6,0xffdce6ef,false);}}
    }
    static boolean hit(double x,double y){return windowAt(x,y)!=null;}
    private static Window windowAt(double x,double y){for(int i=windows.size()-1;i>=0;i--){Window w=windows.get(i);if(x>=w.x&&x<w.x+w.w&&y>=w.y&&y<w.y+w.h+(w.borderless?0:16))return w;}return null;}
    static boolean click(double x,double y,int button){
        if(menu!=null){Window w=menu;if(x>=menuX&&x<menuX+180&&y>=menuY&&y<menuY+100){int row=(int)(y-menuY)/20;switch(row){case 0->w.game=!w.game;case 1->w.borderless=!w.borderless;case 2->w.opacity=w.opacity<=25?100:w.opacity-25;case 3->{w.linear=!w.linear;w.texture.filtering(w.linear);}case 4->{windows.remove(w);Minecraft.getInstance().getTextureManager().release(w.id.id());}}menu=null;return true;}menu=null;}
        Window w=windowAt(x,y);if(w==null)return false;if(button==1){menu=w;menuX=(int)x;menuY=(int)y;return true;}
        if(button==0){dragged=w;resizing=x>w.x+w.w-14&&y>w.y+w.h+(w.borderless?0:16)-14;dragX=x;dragY=y;oldX=w.x;oldY=w.y;oldW=w.w;oldH=w.h;windows.remove(w);windows.add(w);}return true;
    }
    static boolean drag(double x,double y){if(dragged==null)return false;if(resizing){dragged.w=Math.clamp(oldW+(int)(x-dragX),40,1600);dragged.h=Math.clamp(oldH+(int)(y-dragY),40,1200);}else{dragged.x=Math.max(0,oldX+(int)(x-dragX));dragged.y=Math.max(20,oldY+(int)(y-dragY));}return true;}
    static void release(){dragged=null;}
}
