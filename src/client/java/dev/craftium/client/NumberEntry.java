package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The text typed into a slider after a Ctrl-click: a short line of digits with a caret and a selection, edited the way any text box is.
 * Ctrl+A selects all, Ctrl+C / X / V copy, cut and paste, Shift with the arrows extends the selection, and a click or drag with the
 * pointer places the caret or selects. Only the characters of a number are accepted.
 */
final class NumberEntry {
    /** What the owner should do after a key: nothing, apply the text and stop typing, or stop typing and drop it. */
    enum Result {NONE,APPLY,CANCEL}
    private static final int MAX_LENGTH=12;
    private String text="";
    private int caret,anchor;

    /** Starts typing {@code initial} with all of it selected, so the first key replaces it. */
    void start(String initial){text=initial;anchor=0;caret=text.length();}
    String text(){return text;}
    void selectAll(){anchor=0;caret=text.length();}
    private int low(){return Math.min(caret,anchor);}
    private int high(){return Math.max(caret,anchor);}
    private boolean selecting(){return caret!=anchor;}

    private static boolean allowed(char c){return Character.isDigit(c)||c=='.'||c=='-'||c==',';}
    /** Types {@code s} over the selection, keeping only the characters of a number. */
    void type(String s){
        var kept=new StringBuilder();
        for(char c:s.toCharArray())if(allowed(c))kept.append(c==','?'.':c);
        if(kept.isEmpty())return;
        String next=text.substring(0,low())+kept+text.substring(high());
        if(next.length()>MAX_LENGTH)return;
        int at=low()+kept.length();text=next;caret=anchor=at;
    }
    private void delete(boolean forward){
        if(selecting()){text=text.substring(0,low())+text.substring(high());caret=anchor=Math.min(caret,anchor);return;}
        if(forward&&caret<text.length())text=text.substring(0,caret)+text.substring(caret+1);
        else if(!forward&&caret>0){text=text.substring(0,caret-1)+text.substring(caret);caret--;}
        anchor=caret;
    }
    private void move(int to,boolean extend){caret=Math.clamp(to,0,text.length());if(!extend)anchor=caret;}

    /** Handles a key press. Every key is the entry's while it is open, so the result only says whether typing is over. */
    Result key(KeyEvent event){
        int key=event.key();boolean ctrl=event.hasControlDown(),shift=Minecraft.getInstance().hasShiftDown();
        var keyboard=Minecraft.getInstance().keyboardHandler;
        if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)return Result.APPLY;
        if(key==GLFW.GLFW_KEY_ESCAPE)return Result.CANCEL;
        if(ctrl){
            if(key==GLFW.GLFW_KEY_A){selectAll();return Result.NONE;}
            if(key==GLFW.GLFW_KEY_C){if(selecting())keyboard.setClipboard(text.substring(low(),high()));return Result.NONE;}
            if(key==GLFW.GLFW_KEY_X){if(selecting()){keyboard.setClipboard(text.substring(low(),high()));delete(false);}return Result.NONE;}
            if(key==GLFW.GLFW_KEY_V){type(keyboard.getClipboard().strip());return Result.NONE;}
        }
        switch(key){
            case GLFW.GLFW_KEY_BACKSPACE->delete(false);
            case GLFW.GLFW_KEY_DELETE->delete(true);
            case GLFW.GLFW_KEY_LEFT->move(selecting()&&!shift&&!ctrl?low():ctrl?0:caret-1,shift);
            case GLFW.GLFW_KEY_RIGHT->move(selecting()&&!shift&&!ctrl?high():ctrl?text.length():caret+1,shift);
            case GLFW.GLFW_KEY_HOME->move(0,shift);
            case GLFW.GLFW_KEY_END->move(text.length(),shift);
            default->{}
        }
        return Result.NONE;
    }

    private int leftOf(Font font,int left,int width){return left+width/2-font.width(text)/2;}
    private int indexAt(double px,int left,int width){
        Font font=Minecraft.getInstance().font;int origin=leftOf(font,left,width),best=0;double distance=Double.MAX_VALUE;
        for(int i=0;i<=text.length();i++){double d=Math.abs(origin+font.width(text.substring(0,i))-px);if(d<distance){distance=d;best=i;}}
        return best;
    }
    /** A click at {@code px}: the caret goes there, and with {@code extend} (Shift) the selection stretches to it. */
    void click(double px,int left,int width,boolean extend){move(indexAt(px,left,width),extend);}
    /** A drag to {@code px}: the selection stretches from where the click was. */
    void drag(double px,int left,int width){move(indexAt(px,left,width),true);}

    /** Draws the text centred in a box, with the selection behind it and the caret blinking when nothing is selected. */
    void paint(Gfx g,int left,int top,int width,int height){
        Font font=Minecraft.getInstance().font;int x=leftOf(font,left,width),y=top+(height-8)/2;
        if(selecting())g.fill(x+font.width(text.substring(0,low())),y-1,x+font.width(text.substring(0,high())),y+9,0xff3d6fb0);
        g.text(font,text,x,y,0xffffffff,false);
        if(!selecting()&&System.currentTimeMillis()/500%2==0){int cx=x+font.width(text.substring(0,caret));g.fill(cx,y-1,cx+1,y+9,0xffffffff);}
    }
}
