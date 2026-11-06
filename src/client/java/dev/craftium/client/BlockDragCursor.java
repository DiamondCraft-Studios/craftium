package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.lwjgl.glfw.GLFW;

/**
 * What a block being dragged out of a cell looks like: the pointer becomes the crosshair the pipette has, and the block's icon
 * is carried in a cell above and to the right of it, as the pipette shows the block it is over.
 */
final class BlockDragCursor {
    private static long crosshair;
    private static boolean shown;
    private BlockDragCursor(){}
    /** Draws the carried block beside the pointer ({@code mx}, {@code my} in the screen's own coordinates) and makes the pointer a crosshair. */
    static void draw(Gfx g,Font font,String block,int mx,int my,int width,int height,int accent){
        int cx=Math.clamp(mx+12,0,width-30),cy=Math.clamp(my-42,25,Math.max(25,height-52));
        g.fill(cx,cy,cx+30,cy+30,0xf01b2838);g.outline(cx,cy,30,30,accent);
        BlockPickerScreen.icon(g,font,block,cx+7,cy+7);
        set(true);
    }
    /** Gives the pointer back. Safe to call whenever a drag ends or its screen closes. */
    static void release(){set(false);}
    private static void set(boolean want){
        if(want==shown)return;
        var mc=Minecraft.getInstance();if(mc==null)return;
        shown=want;
        if(want&&crosshair==0)crosshair=GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
        GLFW.glfwSetCursor(mc.getWindow().handle(),want?crosshair:0L);
    }
}
