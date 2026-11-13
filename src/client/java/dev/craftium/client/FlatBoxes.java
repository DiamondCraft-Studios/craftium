package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/**
 * Draws text boxes with a flat border instead of vanilla's sprite. The sprite is stretched in nine slices, and at a fractional
 * interface scale (1.5x) its edge tiles, leaving a vertical line every few hundred pixels of a wide box.
 */
public final class FlatBoxes {
    private FlatBoxes(){}
    private record Saved(EditBox box,int x,int y,int width){}

    /** Paints each visible box's background and outline, then moves it in by what the border took so its text stays where it was. */
    public static List<Object> before(Screen screen,Gfx g){
        var saved=new ArrayList<Object>();
        for(var child:screen.children()){
            if(!(child instanceof EditBox box)||!box.visible)continue;
            int x=box.getX(),y=box.getY(),w=box.getWidth(),h=box.getHeight();
            g.fill(x,y,x+w,y+h,0xff000000);
            g.outline(x,y,w,h,box.isFocused()?0xffffffff:0xffa0a0a0);
            box.setBordered(false);
            saved.add(new Saved(box,x,y,w));
            box.setX(x+4);box.setY(y+(h-8)/2);box.setWidth(w-8);
        }
        return saved;
    }

    public static void after(List<Object> saved){
        for(var o:saved){var s=(Saved)o;s.box().setX(s.x());s.box().setY(s.y());s.box().setWidth(s.width());}
    }
}
