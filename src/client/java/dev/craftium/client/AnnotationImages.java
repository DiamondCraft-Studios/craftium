package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Annotation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

final class AnnotationImages {
    private AnnotationImages(){}
    static void choose(Runnable refresh){Thread picker=new Thread(()->{try{
        String file=org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Choose annotation PNG (sampled to 32 pixels)","",null,null,false);if(file==null)return;Path path=Path.of(file);if(Files.size(path)>8*1024*1024)throw new IllegalArgumentException("Image limit: 8 MiB");
        java.awt.image.BufferedImage image;try(var stream=ImageIO.createImageInputStream(path.toFile())){var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IllegalArgumentException("Unsupported image");var reader=readers.next();try{reader.setInput(stream);if(reader.getWidth(0)>2048||reader.getHeight(0)>2048)throw new IllegalArgumentException("Image limit: 2048 × 2048");image=reader.read(0);}finally{reader.dispose();}}
        int width=Math.max(1,32*image.getWidth()/Math.max(image.getWidth(),image.getHeight())),height=Math.max(1,32*image.getHeight()/Math.max(image.getWidth(),image.getHeight()));var pixels=new ArrayList<Integer>();for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels.add(image.getRGB(x*image.getWidth()/width,y*image.getHeight()/height));
        Minecraft.getInstance().execute(()->{var o=ClientState.options;o.annotationPixels=pixels;o.annotationImageWidth=width;o.annotationImageHeight=height;o.annotationMode="IMAGE";ClientState.notify("Annotation image ready · right-click a surface to place");refresh.run();});
    }catch(Exception e){Minecraft.getInstance().execute(()->ClientState.notify(e.getMessage()));}},"Craftium annotation image chooser");picker.setDaemon(true);picker.start();}
    static void draw(Annotation note){
        if(note.pixels()==null||note.imageWidth()<1||note.imageHeight()<1||note.pixels().size()!=note.imageWidth()*note.imageHeight()||note.normal()==null)return;
        var normal=note.normal();var at=note.from();double size=note.imageScale()/Math.max(note.imageWidth(),note.imageHeight());
        for(int index=0;index<note.pixels().size();index++){
            int color=note.pixels().get(index);if((color>>>24)<8)continue;double u=(index%note.imageWidth())*size,v=(note.imageHeight()-1-index/note.imageWidth())*size;
            double x=at.x()+.5+normal.x()*.501,y=at.y()+.5+normal.y()*.501,z=at.z()+.5+normal.z()*.501;
            AABB box=normal.y()!=0?new AABB(x+u,y,z+v,x+u+size,y+.002,z+v+size):normal.x()!=0?new AABB(x,y+v,z+u,x+.002,y+v+size,z+u+size):new AABB(x+u,y+v,z,x+u+size,y+v+size,z+.002);
            Overlay.box(box,0,0,color);
        }
    }
}
