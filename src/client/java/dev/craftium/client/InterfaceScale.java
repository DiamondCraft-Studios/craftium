package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import net.minecraft.client.Minecraft;

/**
 * The size of Craftium's own screens. While one is open the game's GUI scale is a whole number of screen pixels per interface unit, chosen
 * in the preferences, so every pixel of the text and icons is the same size and each screen lays itself out in that many units. The
 * player's own GUI scale comes back as soon as a screen that is not Craftium's is showing.
 */
public final class InterfaceScale {
    private static boolean active;
    private InterfaceScale(){}

    private static double zoom=1;
    /** Told whenever the scale a Craftium screen is drawn at may have changed (and when the game takes its own back). */
    public static volatile Runnable listener=()->{};
    /** The most a chosen size may be, in screen pixels per unit: the screen must still be 400 by 225 units, and 8 is the top of the slider. */
    public static int largest(Minecraft mc){var w=mc.getWindow();return Math.clamp(Math.min(w.getScreenWidth()/400,w.getScreenHeight()/225),1,8);}
    /** The player's own GUI scale. */
    public static int vanilla(Minecraft mc){return mc.getWindow().calculateScale(mc.options.guiScale().get(),mc.isEnforceUnicode());}
    /**
     * The screen pixels per interface unit for a screen. A chosen size wins, and may be a half step. On Auto a screen keeps the player's GUI
     * scale, except one that needs a canvas ({@code canvas} is its width and height in units), which takes the largest whole scale at which that
     * canvas fits, a little short of it allowed.
     */
    public static double target(Minecraft mc,int[] canvas){
        var w=mc.getWindow();int wanted=ClientState.options.interfaceHalves;
        if(wanted>0)return Math.min(wanted/2.0,largest(mc));
        int vanilla=vanilla(mc);if(canvas==null)return vanilla;
        int fit=Math.max(1,(int)Math.floor(Math.min(w.getScreenWidth()/(double)canvas[0],w.getScreenHeight()/(double)canvas[1])/.95+1e-6));
        return Math.min(vanilla,fit);
    }
    /**
     * Sets the scale a Craftium screen wants; true if that changed what the screen sees, so it has to lay itself out again. The game's GUI scale is a whole
     * number, so a half step such as 1.5 is set as the next whole scale up (2) and the window is told it is {@code screen pixels / 1.5} units wide and high,
     * which is what the game's own mouse position and screen size are worked out from. The screen then draws itself at {@link #zoom} (0.75) of the whole scale,
     * see {@code CraftiumScreen}, so a unit is 1.5 screen pixels everywhere: layout, the pointer and drawing agree and no screen has to convert anything.
     */
    public static boolean apply(Minecraft mc,int[] canvas){
        active=true;var window=mc.getWindow();double pixels=target(mc,canvas);int gui=(int)Math.ceil(pixels-1e-6);
        double nextZoom=pixels/gui;
        int units=(int)Math.ceil(window.getScreenWidth()/pixels-1e-6),high=(int)Math.ceil(window.getScreenHeight()/pixels-1e-6);
        boolean changed=window.getGuiScale()!=gui||nextZoom!=zoom||nextZoom!=1&&(window.getGuiScaledWidth()!=units||window.getGuiScaledHeight()!=high);
        // Leaving a half step keeps the same whole scale (1.5x -> 2x is GUI scale 2 both times), so the scaled size we forced for the half step
        // would stay; setting the scale again works the real size out from the window.
        if(window.getGuiScale()!=gui||nextZoom==1&&zoom!=1)window.setGuiScale(gui);
        if(nextZoom!=1){var access=(dev.craftium.client.mixin.WindowAccessor)(Object)window;access.craftium$setGuiScaledWidth(units);access.craftium$setGuiScaledHeight(high);}
        zoom=nextZoom;listener.run();return changed;
    }
    /** How much of the game's whole GUI scale a Craftium screen is drawn at: 1, or 0.75 to land on 1.5 screen pixels with a GUI scale of 2. */
    public static double zoom(){return zoom;}
    /** Screen pixels per interface unit on screen now. */
    public static double pixels(Minecraft mc){return mc.getWindow().getGuiScale()*zoom;}
    /** A size in halves as the player reads it: 2 is 1x, 3 is 1.5x. */
    public static String name(int halves){return halves<=0?"Auto":halves%2==0?halves/2+"x":halves/2.0+"x";}
    /**
     * The step (+1 larger, -1 smaller, 0 none) a key press asks for: the bound Craftium shortcuts (Ctrl+= and Ctrl+- by default), Ctrl with the
     * keypad's + and -, and Ctrl+Shift+= (which is +). The same on every Craftium screen.
     */
    public static int hotkey(net.minecraft.client.input.KeyEvent event){
        if(EditorBindings.matches(dev.craftium.core.EditorControls.Action.INTERFACE_LARGER,event))return 1;
        if(EditorBindings.matches(dev.craftium.core.EditorControls.Action.INTERFACE_SMALLER,event))return -1;
        int mods=event.modifiers()&7,key=event.key(),control=dev.craftium.core.EditorControls.CONTROL;
        if(mods==control)return key==org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD?1:key==org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT?-1:0;
        return mods==(control|dev.craftium.core.EditorControls.SHIFT)&&key==org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL?1:0;
    }
    /** Ctrl+= and Ctrl+-: one half step larger or smaller, kept in the preferences. The screen is laid out again by whoever calls this. */
    public static void resize(Minecraft mc,int step){
        int now=(int)Math.round(pixels(mc)*2),next=Math.clamp(now+step,2,largest(mc)*2);
        if(next==now){ClientState.notify(step>0?"The interface is as large as this window allows ("+name(now)+")":"The interface is as small as it goes (1x: one screen pixel per text pixel)");return;}
        ClientState.options.interfaceHalves=next;ClientState.save();ClientState.notify("Interface size: "+name(next));
    }
    /** Every tick: once no Craftium screen is showing, gives the player's GUI scale back. */
    public static void restoreIfIdle(Minecraft mc){
        if(!active||ClientCompat.screen(mc) instanceof CraftiumScreen)return;
        active=false;zoom=1;listener.run();ClientCompat.refreshGui(mc);
    }
    /** The canvas the editor was laid out on before sizes were chosen: its window in the player's GUI scale, shrunk to fit 1000 by 562. */
    public static int[] legacyCanvas(Minecraft mc){
        var w=mc.getWindow();int g=Math.max(1,vanilla(mc));double gw=w.getScreenWidth()/(double)g,gh=w.getScreenHeight()/(double)g;
        double s=Math.min(1,Math.min(gw/1000,gh/562));return new int[]{(int)Math.round(gw/s),(int)Math.round(gh/s)};
    }
}
