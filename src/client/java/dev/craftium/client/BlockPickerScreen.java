package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import java.util.*;
import java.util.function.Consumer;

/**
 * Chooser for one block slot: Air first, then every placeable parent block in the order the game registers its items,
 * which is how the creative inventory and JEI group them. Blocks without an item of their own (water, lava, fire, some plants) follow
 * at the end. Wall variants, internal blocks and operator-only blocks are left out, and no block is listed as a state.
 */
public final class BlockPickerScreen extends CraftiumScreen {
    private static final int COLS=12,ROWS=7,CELL=34;
    private static final Set<String> EXCLUDED=Set.of("command_block","chain_command_block","repeating_command_block","structure_block","structure_void","jigsaw","barrier","light","test_block","test_instance_block","spawner","trial_spawner","vault","end_portal_frame","budding_amethyst","reinforced_deepslate","debug_stick");
    /** Blocks the game uses internally: they are placed by something else, not by hand. */
    private static final Set<String> TECHNICAL=Set.of("moving_piston","piston_head","end_gateway","end_portal","nether_portal");
    private static List<String> all;
    private final Screen parent;
    private final String heading,current;
    private final Consumer<String> chosen;
    private List<String> shown=List.of();
    private int row,left,top,cw,ch,offsetX,offsetY;
    private boolean dragging,scrolling;
    private EditBox search;
    private FlatButton cancel;
    private float scale=1;
    private String query="",hover="";
    /** A cell pressed to be dragged out to the editor's slots: which, where, and whether the pointer has moved far enough for it to be a drag rather than a click. */
    private int pressed=-1;private double pressX,pressY;private boolean pulled;
    public BlockPickerScreen(Screen parent,String heading,String current,Consumer<String> chosen){super(Component.literal("Craftium Block"));this.parent=parent;this.heading=heading;this.current=current;this.chosen=chosen;}
    /** Air, then the parent blocks in item registration order. Built once; the registries do not change while the game runs. */
    static synchronized List<String> all(){
        if(all==null){
            var ids=new LinkedHashSet<String>();ids.add("minecraft:air");
            for(var item:BuiltInRegistries.ITEM)if(item instanceof BlockItem blockItem){var key=BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());if(!(key.getNamespace().equals("minecraft")&&EXCLUDED.contains(key.getPath())))ids.add(key.toString());}
            // Then the blocks that have no item of their own but can be placed: water, lava and the like. Plain block ids only, never one state of them.
            for(var block:BuiltInRegistries.BLOCK){
                var key=BuiltInRegistries.BLOCK.getKey(block);
                if(block.asItem()!=net.minecraft.world.item.Items.AIR||ids.contains(key.toString())||key.getPath().equals("air")||key.getPath().endsWith("_air"))continue;
                if(key.getNamespace().equals("minecraft")&&(EXCLUDED.contains(key.getPath())||TECHNICAL.contains(key.getPath())))continue;
                if(key.getPath().contains("wall_"))continue; // a wall torch, sign or banner is the same block as its standing form, turned
                ids.add(key.toString());
            }
            all=List.copyOf(ids);
        }
        return all;
    }
    /** An item icon for the block; Air has none, so it is drawn as a label. */
    static void icon(Gfx g,Font font,String block,int x,int y){
        var id=dev.craftium.compat.Compat.tryParse(block.split("\\[",2)[0]);if(id==null)return;
        if(id.getPath().equals("air")){g.text(font,"Air",x+8-font.width("Air")/2,y+4,0xff9db5cb,false);return;}
        BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b->{
            var stack=b.asItem().getDefaultInstance();
            if(!stack.isEmpty())g.item(stack,x,y);
            else if(b==net.minecraft.world.level.block.Blocks.WATER)liquid(g,x,y,0xff3f76e4); // no item of its own: a liquid is drawn as its block
            else if(b==net.minecraft.world.level.block.Blocks.LAVA)liquid(g,x,y,0xffe4660f);
            else{String tag=b.getName().getString();tag=tag.substring(0,Math.min(2,tag.length()));g.text(font,tag,x+8-font.width(tag)/2,y+4,0xff9db5cb,false);}
        });
    }
    /** A 16 by 16 liquid block: its colour with a lit top and a shaded side, so it reads as a cube like the other cells. */
    private static void liquid(Gfx g,int x,int y,int colour){
        g.fill(x,y,x+16,y+16,colour);
        g.fill(x,y,x+16,y+4,lighter(colour,.28f));
        g.fill(x+13,y+4,x+16,y+16,darker(colour,.78f));
        g.outline(x,y,16,16,darker(colour,.6f));
    }
    private static int lighter(int c,float by){return shade(c,by,true);}
    private static int darker(int c,float by){return shade(c,by,false);}
    private static int shade(int c,float f,boolean up){
        int r=c>>16&255,gr=c>>8&255,b=c&255;
        if(up){r+=(int)((255-r)*f);gr+=(int)((255-gr)*f);b+=(int)((255-b)*f);}else{r=(int)(r*f);gr=(int)(gr*f);b=(int)(b*f);}
        return 0xff000000|r<<16|gr<<8|b;
    }
    @Override protected void init(){
        scale=Math.min(1f,Math.min(width/(COLS*CELL+60f),height/(ROWS*CELL+150f)));cw=Math.round(width/scale);ch=Math.round(height/scale);clearWidgets();
        search=new EditBox(font,0,0,COLS*CELL,21,Component.literal("Search blocks"));search.setMaxLength(128);search.setValue(query);search.setResponder(s->{query=s;row=0;filter();});addRenderableWidget(search);setInitialFocus(search);filter();
        cancel=new FlatButton(0,0,80,22,"Cancel",this::onClose);addRenderableWidget(cancel);place();
    }
    /** Positions the window, kept on screen, and the widgets that sit in it. */
    private void place(){
        offsetX=Math.clamp(offsetX,-(cw-COLS*CELL)/2+8,(cw-COLS*CELL)/2-8);offsetY=Math.clamp(offsetY,-(ch-ROWS*CELL)/2+40,(ch-ROWS*CELL)/2-40);
        left=(cw-COLS*CELL)/2+offsetX;top=(ch-ROWS*CELL)/2+offsetY;
        search.setX(left);search.setY(top+4);cancel.setX(left+COLS*CELL-80);cancel.setY(top+ROWS*CELL+36);
    }
    private int trackX(){return left+COLS*CELL+3;}
    private int trackTop(){return top+34;}
    private int trackHeight(){return ROWS*CELL-2;}
    private int thumbHeight(){int rows=Math.max(1,(shown.size()+COLS-1)/COLS);return Math.max(20,trackHeight()*ROWS/Math.max(ROWS,rows));}
    private int thumbY(){return maxRow()==0?trackTop():trackTop()+(trackHeight()-thumbHeight())*row/maxRow();}
    private void scrollTo(double y){int travel=trackHeight()-thumbHeight();if(travel>0)row=Math.clamp((int)Math.round((y-trackTop()-thumbHeight()/2.0)/travel*maxRow()),0,maxRow());}
    private void filter(){
        String needle=query.toLowerCase(Locale.ROOT).trim();
        shown=needle.isEmpty()?all():all().stream().filter(id->id.toLowerCase(Locale.ROOT).contains(needle)||name(id).toLowerCase(Locale.ROOT).contains(needle)).toList();
        row=Math.clamp(row,0,maxRow());
    }
    private int maxRow(){return Math.max(0,(shown.size()+COLS-1)/COLS-ROWS);}
    private static String name(String block){var id=dev.craftium.compat.Compat.tryParse(block);return id==null?block:BuiltInRegistries.BLOCK.getOptional(id).map(b->b.getName().getString()).orElse(block);}
    private int cellAt(double x,double y){int c=(int)Math.floor((x-left)/CELL),r=(int)Math.floor((y-top-34)/CELL);if(x<left||y<top+34||c<0||c>=COLS||r<0||r>=ROWS)return -1;int i=(row+r)*COLS+c;return i<shown.size()?i:-1;}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        // The editor stays visible underneath, but frozen and out of reach of the pointer.
        if(parent instanceof EditorScreen editor)editor.suspended=true;
        drawUnder(parent,g,-1,-1,delta);
        int mx=(int)(mouseX/scale),my=(int)(mouseY/scale);g.pose().pushMatrix();g.pose().scale(scale,scale);
        g.fill(left-12,top-26,left+COLS*CELL+12,top+ROWS*CELL+68,0xff172330);
        g.text(font,"Select block",left,top-17,0xffb5dcfc,false);g.text(font,heading,left+COLS*CELL-font.width(heading),top-17,0xff9db5cb,false);
        int hovered=cellAt(mx,my);hover=hovered<0?"":shown.get(hovered);
        for(int r=0;r<ROWS;r++)for(int c=0;c<COLS;c++){
            int i=(row+r)*COLS+c;if(i>=shown.size())break;
            int x=left+c*CELL,y=top+34+r*CELL;String block=shown.get(i);
            g.fill(x,y,x+CELL-2,y+CELL-2,i==hovered?0xff365576:0xff223247);if(block.equals(current))g.outline(x,y,CELL-2,CELL-2,0xff7cc4ff);
            icon(g,font,block,x+9,y+9);
        }
        if(maxRow()>0){g.fill(trackX(),trackTop(),trackX()+6,trackTop()+trackHeight(),0xff1b2838);g.fill(trackX(),thumbY(),trackX()+6,thumbY()+thumbHeight(),scrolling?0xff7cc4ff:0xff4a6585);}
        drawWidgets(g,mx,my,delta);
        g.text(font,hover.isEmpty()?shown.size()+" blocks":font.plainSubstrByWidth(name(hover)+"  ·  "+hover,COLS*CELL-90),left,top+ROWS*CELL+42,hover.isEmpty()?0xff9db5cb:0xffdce6ef,false);
        if(parent instanceof EditorScreen)g.text(font,"Alt + click a block in the world to take that exact block",left,top+ROWS*CELL+54,0xff6f8499,false);
        if(pressed>=0&&pulled&&pressed<shown.size()){g.fill(mx-13,my-13,mx+13,my+13,0xdd1b2838);g.outline(mx-13,my-13,26,26,0xff7cc4ff);icon(g,font,shown.get(pressed),mx-8,my-8);} // the cell being carried
        g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
        double x=event.x()/scale,y=event.y()/scale;int i=cellAt(x,y);
        // Alt + click on the world outside this window takes that exact block, state and all, instead of one from the list.
        boolean outside=!(x>=left-12&&x<left+COLS*CELL+12&&y>=top-26&&y<top+ROWS*CELL+68);
        if(event.button()==0&&outside&&altDown()&&parent instanceof EditorScreen editor){
            var hit=editor.pipetteTarget(event.x(),event.y());
            if(hit!=null&&minecraft.level!=null){chosen.accept(BlockStateScreen.describe(minecraft.level.getBlockState(hit.getBlockPos())));ClientCompat.setScreen(minecraft,parent);}
            return true;
        }
        if(event.button()==0&&maxRow()>0&&x>=trackX()-3&&x<trackX()+9&&y>=trackTop()&&y<trackTop()+trackHeight()){scrolling=true;scrollTo(y);return true;}
        if(event.button()==0&&x>=left-12&&x<left+COLS*CELL+12&&y>=top-26&&y<top+2){dragging=true;return true;}
        if(i>=0&&event.button()==0){pressed=i;pressX=event.x();pressY=event.y();pulled=false;return true;} // chosen when released, unless it is dragged out to a slot
        return super.mouseClicked(new MouseButtonEvent(x,y,event.buttonInfo()),twice);
    }
    private boolean altDown(){
        long window=minecraft.getWindow().handle();
        return org.lwjgl.glfw.GLFW.glfwGetKey(window,org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT)==org.lwjgl.glfw.GLFW.GLFW_PRESS||org.lwjgl.glfw.GLFW.glfwGetKey(window,org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT)==org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(pressed>=0){if(!pulled&&Math.hypot(event.x()-pressX,event.y()-pressY)>5)pulled=true;return true;}
        if(scrolling){scrollTo(event.y()/scale);return true;}
        if(dragging){offsetX+=(int)Math.round(dx/scale);offsetY+=(int)Math.round(dy/scale);place();return true;}
        return super.mouseDragged(new MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()),dx/scale,dy/scale);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){
        if(pressed>=0&&event.button()==0){
            int i=pressed;boolean drag=pulled;pressed=-1;pulled=false;
            if(i>=shown.size())return true;
            if(!drag){chosen.accept(shown.get(i));ClientCompat.setScreen(minecraft,parent);return true;}
            // Dragged out: only onto the editor's slots behind this window; the chooser stays open for the next one.
            double x=event.x()/scale,y=event.y()/scale;boolean inside=x>=left-12&&x<left+COLS*CELL+12&&y>=top-26&&y<top+ROWS*CELL+68;
            if(!inside&&parent instanceof EditorScreen editor&&editor.dropBlock(event.x(),event.y(),shown.get(i)))ClientState.notify(name(shown.get(i))+" placed");
            return true;
        }
        if(scrolling||dragging){scrolling=dragging=false;return true;}
        return super.mouseReleased(new MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()));
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){row=Math.clamp(row+(int)-Math.signum(vertical),0,maxRow());return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
