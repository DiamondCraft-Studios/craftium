package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Options;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The floating builder widget: shown while the context key (Alt) is held on hotbar slots 1 to 9, instead of opening the whole editor.
 * The cursor is freed while it shows, so its elements can be clicked. On the tenth slot Alt opens the builder tool menu instead.
 */
public final class BuilderWidget {
    /** One quick toggle: its label, what it reads, what it flips, and what it does. */
    private record Toggle(String label,Predicate<Options> get,Consumer<Options> flip,String hint){}
    private static final List<Toggle> TOGGLES=List.of(
        new Toggle("Fast place",o->o.fastPlace,o->o.fastPlace=!o.fastPlace,"Sweep the view to place on every new block"),
        new Toggle("Replace mode",o->o.replaceMode,o->o.replaceMode=!o.replaceMode,"Placing replaces the clicked block"),
        new Toggle("Bulldozer",o->o.bulldozer,o->o.bulldozer=!o.bulldozer,"Hold left click, then sweep to break blocks"),
        new Toggle("Force place",o->o.forcePlace,o->o.forcePlace=!o.forcePlace,"Place where a block could not survive"),
        new Toggle("No updates",o->o.noUpdates,o->o.noUpdates=!o.noUpdates,"Placed blocks do not update neighbours"),
        new Toggle("Angel place",o->o.angelPlacement,o->o.angelPlacement=!o.angelPlacement,"Place four blocks ahead in the air"),
        new Toggle("Tinker",o->o.tinker,o->o.tinker=!o.tinker,"Empty hand cycles a block property"),
        new Toggle("Instant flight",o->o.enhancedFlight,o->o.instantFlight(!o.enhancedFlight),"Fly with no interpolation: stop dead on release (sets flight momentum to 0)"));
    private static final int WIDTH=112,ROW=16,GAP=1,HEADER=14,BOTTOM=28; // BOTTOM keeps clear of the hotbar row and the offhand slot
    private BuilderWidget(){}

    /** Whether the widget is showing: Alt is held in the world, in a hotbar slot, with the builder available. */
    public static boolean active(){
        var mc=Minecraft.getInstance();
        return BuilderMode.usable()&&!BuilderMode.active()&&ClientCompat.screen(mc)==null&&(pinned||CraftiumClient.held(CraftiumClient.contextKey));
    }
    /**
     * Clicking a text field keeps the widget up after Alt is let go (so there is time to type). It goes away once Alt is held again and let go.
     * {@code rearmed} says Alt has been pressed again since the widget was pinned.
     */
    private static boolean pinned,rearmed,prevHeld;
    private static void pin(){if(!pinned){pinned=true;rearmed=false;}}
    /** The flight speed slider under the toggles, 100% to 999%. The editor camera flies at the same speed. */
    private static final int MIN_PERCENT=100,MAX_PERCENT=999;
    private static boolean dragging;
    private static int left(int guiWidth){return guiWidth/2-96-WIDTH;}
    private static int top(int guiHeight){return guiHeight-BOTTOM-HEADER-TOGGLES.size()*(ROW+GAP)-ROW-GAP;}
    private static int sliderY(int guiHeight){return top(guiHeight)+HEADER+TOGGLES.size()*(ROW+GAP);}
    private static void setSpeed(int mouseX,int x){
        int percent=MIN_PERCENT+Math.round((float)Math.clamp((mouseX-x-2)/(double)(WIDTH-5),0,1)*(MAX_PERCENT-MIN_PERCENT));
        ClientState.options.flySpeed=percent/100f;
    }
    // Hotbar pages: a page is five hotbars stacked above the real one, and has a name. The wheel moves through the hotbars (and on to the next page),
    // the arrows and the page number move between pages, and letting go of Alt copies the selected hotbar into the one in use.
    private static final int ROWS=HotbarPages.ROWS;
    private static int page=1,row;
    /** Whether a hotbar was chosen since the widget opened: only then does letting go change the hotbar in use. */
    private static boolean chosen,wasActive,naming,pageFocus,deleting;
    private static final StringBuilder typed=new StringBuilder();
    private static int rowX(int w){return w/2-91;}
    /** The top of a page hotbar's picture. They overlap by their borders so only the outer edges of the stack have one: each is 20 tall inside. */
    private static int rowY(int h,int k){return h-43-20*k;}
    private static int controlsX(int w){return w/2+96;} // the mirror of the builder window on the left, which ends 96 from the middle
    private static int nameY(int h){return rowY(h,ROWS-1)-15;}
    private static final int NAME_WIDTH=126;
    private static boolean inside(int mx,int my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
    private static void choose(int nextPage,int nextRow){page=Math.clamp(nextPage,1,HotbarPages.COUNT);row=Math.clamp(nextRow,0,ROWS-1);chosen=true;}
    private static void step(int pages){choose(page+pages,row);typed.setLength(0);}
    private static void unfocus(Minecraft mc){naming=false;pageFocus=false;typed.setLength(0);HotbarPages.flush(mc);}
    /** The item being carried on the cursor, and where it was lifted from (row -1 is the hotbar in use). */
    private static ItemStack carried=ItemStack.EMPTY;
    private static int carriedPage,carriedRow,carriedSlot;
    private static ItemStack get(Minecraft mc,int pg,int r,int slot){
        if(r<0)return mc.player.getInventory().getItem(slot);
        var stacks=HotbarPages.row(pg,r);return slot<stacks.size()?stacks.get(slot):ItemStack.EMPTY;
    }
    private static void put(Minecraft mc,int pg,int r,int slot,ItemStack stack){if(r<0)HotbarPages.setHotbar(mc,slot,stack);else HotbarPages.set(mc,pg,r,slot,stack);}
    /** Which slot is under the cursor, as its row (-1 for the hotbar in use) and place in the row. Null when none. */
    private static int[] slotAt(int w,int h,int mx,int my){
        int x=rowX(w);
        for(int k=-1;k<ROWS;k++){
            if(toolbox&&k>=0)continue;
            int y=k<0?h-22:rowY(h,k)+1,height=k<0?22:20;
            if(inside(mx,my,x,y,182,height)){int i=(mx-x-1)/20;if(mx<x+1||i<0||i>=HotbarPages.SLOTS)return null;return new int[]{k,i};}
        }
        return null;
    }
    /** A click on a slot works as in a chest: pick up or put down, left for all and right for half or one, middle for a full copy. */
    private static void click(Minecraft mc,int r,int slot,int button){
        var here=get(mc,page,r,slot);
        if(deleting){if(!carried.isEmpty())carried=ItemStack.EMPTY;else if(!here.isEmpty())put(mc,page,r,slot,ItemStack.EMPTY);return;}
        if(button==2){if(carried.isEmpty()&&!here.isEmpty()){carried=here.copy();carried.setCount(carried.getMaxStackSize());carriedPage=page;carriedRow=r;carriedSlot=slot;}return;}
        if(carried.isEmpty()){
            if(here.isEmpty())return;
            var take=here.copy();if(button==1){int half=(here.getCount()+1)/2;take.setCount(half);var rest=here.copy();rest.shrink(half);put(mc,page,r,slot,rest);}else put(mc,page,r,slot,ItemStack.EMPTY);
            carried=take;carriedPage=page;carriedRow=r;carriedSlot=slot;return;
        }
        if(here.isEmpty()){
            var drop=carried.copy();if(button==1){drop.setCount(1);carried.shrink(1);}else carried=ItemStack.EMPTY;
            put(mc,page,r,slot,drop);return;
        }
        if(ItemStack.isSameItemSameComponents(here,carried)){
            int room=here.getMaxStackSize()-here.getCount(),move=Math.min(room,button==1?1:carried.getCount());
            if(move>0){var merged=here.copy();merged.grow(move);carried.shrink(move);put(mc,page,r,slot,merged);}
            return;
        }
        if(button==0){var swapped=carried;carried=here.copy();put(mc,page,r,slot,swapped);}
    }
    /** Anything still on the cursor goes back where it came from, or is let go if that slot is taken. */
    private static void putBack(Minecraft mc){
        if(carried.isEmpty())return;
        if(get(mc,carriedPage,carriedRow,carriedSlot).isEmpty())put(mc,carriedPage,carriedRow,carriedSlot,carried);
        else ClientState.notify("The item you were holding was let go");
        carried=ItemStack.EMPTY;
    }

    /** Called each tick: keeps the pin, and the moment the widget closes, a chosen hotbar is copied into the hotbar in use. */
    public static void tick(){
        var mc=Minecraft.getInstance();boolean held=CraftiumClient.held(CraftiumClient.contextKey);
        if(!BuilderMode.usable()||BuilderMode.active()||ClientCompat.screen(mc)!=null)pinned=false;
        if(pinned){if(held&&!prevHeld)rearmed=true;if(!held&&prevHeld&&rearmed){pinned=false;rearmed=false;}}
        prevHeld=held;
        boolean now=active();
        if(now&&!wasActive){HotbarPages.load(mc);typed.setLength(0);chosen=false;naming=false;pageFocus=false;deleting=false;}
        if(!now&&wasActive){
            typed.setLength(0);naming=false;pageFocus=false;deleting=false;pinned=false;HotbarPages.flush(mc);
            if(mc.player!=null&&mc.gameMode!=null)putBack(mc);carried=ItemStack.EMPTY;
            if(chosen&&mc.player!=null&&mc.gameMode!=null&&mc.player.isCreative()){
                String name=HotbarPages.name(page).isBlank()?"Page "+page:HotbarPages.name(page);
                if(HotbarPages.empty(page,row))ClientState.notify(name+", hotbar "+(row+1)+" is empty: your hotbar is unchanged");
                else{HotbarPages.apply(mc,page,row);ClientState.notify(name+", hotbar "+(row+1)+" copied to your hotbar");}
            }
            chosen=false;dragging=false;
        }
        wasActive=now;
    }
    /** The wheel moves up and down through the hotbars, carrying on into the next page at the end of one. */
    public static boolean scroll(double delta){
        if(!active())return false;
        if(toolbox)return true;
        if(delta>0){if(row<ROWS-1)choose(page,row+1);else if(page<HotbarPages.COUNT)choose(page+1,0);}
        else if(delta<0){if(row>0)choose(page,row-1);else if(page>1)choose(page-1,ROWS-1);}
        return true;
    }
    /** The character a key types into the name, or 0 when it types none. */
    private static char character(int key,boolean shift){
        if(key>=GLFW.GLFW_KEY_A&&key<=GLFW.GLFW_KEY_Z)return (char)((shift?'A':'a')+key-GLFW.GLFW_KEY_A);
        if(key>=GLFW.GLFW_KEY_0&&key<=GLFW.GLFW_KEY_9&&!shift)return (char)('0'+key-GLFW.GLFW_KEY_0);
        if(key>=GLFW.GLFW_KEY_KP_0&&key<=GLFW.GLFW_KEY_KP_9)return (char)('0'+key-GLFW.GLFW_KEY_KP_0);
        return switch(key){case GLFW.GLFW_KEY_SPACE->' ';case GLFW.GLFW_KEY_MINUS->shift?'_':'-';case GLFW.GLFW_KEY_PERIOD->'.';case GLFW.GLFW_KEY_COMMA->',';case GLFW.GLFW_KEY_APOSTROPHE->'\'';case GLFW.GLFW_KEY_SLASH->shift?'?':'/';default->0;};
    }
    private static int digitOf(int key){return key>=GLFW.GLFW_KEY_0&&key<=GLFW.GLFW_KEY_9?key-GLFW.GLFW_KEY_0:key>=GLFW.GLFW_KEY_KP_0&&key<=GLFW.GLFW_KEY_KP_9?key-GLFW.GLFW_KEY_KP_0:-1;}
    /**
     * Typing goes into whichever field was clicked, the name or the page number. With neither, a number key swaps the slot under the cursor
     * with that hotbar slot, as in the inventory; with the cursor over no slot it is left to the game.
     */
    public static boolean key(int key,int action){
        if(!active())return false;
        var mc=Minecraft.getInstance();
        boolean alt=key==GLFW.GLFW_KEY_LEFT_ALT||key==GLFW.GLFW_KEY_RIGHT_ALT;
        if(naming){
            if(alt)return false;if(action==GLFW.GLFW_RELEASE)return true;
            if(key==GLFW.GLFW_KEY_ESCAPE||key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){unfocus(mc);return true;}
            String name=HotbarPages.name(page);
            if(key==GLFW.GLFW_KEY_BACKSPACE){if(!name.isEmpty())HotbarPages.rename(page,name.substring(0,name.length()-1));return true;}
            boolean shift=GLFW.glfwGetKey(mc.getWindow().handle(),GLFW.GLFW_KEY_LEFT_SHIFT)==GLFW.GLFW_PRESS||GLFW.glfwGetKey(mc.getWindow().handle(),GLFW.GLFW_KEY_RIGHT_SHIFT)==GLFW.GLFW_PRESS;
            char c=character(key,shift);if(c!=0&&name.length()<HotbarPages.NAME_LIMIT)HotbarPages.rename(page,name+c);
            return true;
        }
        if(pageFocus){
            if(alt)return false;if(action==GLFW.GLFW_RELEASE)return true;
            if(key==GLFW.GLFW_KEY_ESCAPE||key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){unfocus(mc);return true;}
            if(key==GLFW.GLFW_KEY_BACKSPACE&&!typed.isEmpty())typed.setLength(typed.length()-1);
            else if(digitOf(key)>=0){if(typed.length()>=2)typed.setLength(0);typed.append(digitOf(key));}
            if(!typed.isEmpty()){int number=Integer.parseInt(typed.toString());if(number>=1){String keep=typed.toString();choose(number,row);typed.setLength(0);typed.append(keep);}}
            return true;
        }
        int digit=digitOf(key);
        if(digit<1)return false;
        var win=mc.getWindow();int[] slot=slotAt(win.getGuiScaledWidth(),win.getGuiScaledHeight(),mouseX(mc),mouseY(mc));
        if(slot==null)return false;
        if(action==GLFW.GLFW_PRESS&&mc.player!=null&&mc.gameMode!=null&&!(slot[0]<0&&slot[1]==digit-1)){
            var there=get(mc,page,slot[0],slot[1]).copy();var mine=mc.player.getInventory().getItem(digit-1).copy();
            put(mc,page,slot[0],slot[1],mine);HotbarPages.setHotbar(mc,digit-1,there);
        }
        return true;
    }
    private static void commitSpeed(){ClientState.save();if(CraftiumClient.supported())CraftiumClient.send("flight",null,null);}

    public static void draw(Gfx g){
        if(!active())return;var mc=Minecraft.getInstance();var o=ClientState.options;
        int x=left(g.guiWidth()),y=top(g.guiHeight()),mx=mouseX(mc),my=mouseY(mc);
        if(dragging){if(GLFW.glfwGetMouseButton(mc.getWindow().handle(),GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS)setSpeed(mx,x);else{dragging=false;commitSpeed();}}
        g.fill(x-4,y-4,x+WIDTH+4,g.guiHeight()-BOTTOM+4,0xf510151c);
        g.text(mc.font,"Builder",x,y+1,0xffdce6ef,false);
        String hover=null;
        for(int i=0;i<TOGGLES.size();i++){
            var t=TOGGLES.get(i);int ry=y+HEADER+i*(ROW+GAP);boolean on=t.get().test(o),over=mx>=x&&mx<x+WIDTH&&my>=ry&&my<ry+ROW;
            g.fill(x,ry,x+WIDTH,ry+ROW,on?(over?0xff3f7a63:0xff2f6350):(over?0xff2f4260:0xff223247));
            g.text(mc.font,t.label(),x+6,ry+4,on?0xffe9fff4:0xffdce6ef,false);
            g.fill(x+WIDTH-12,ry+5,x+WIDTH-6,ry+11,on?0xff8be3b5:0xff52667a);
            if(over)hover=t.hint();
        }
        int sy=sliderY(g.guiHeight());boolean overSlider=mx>=x&&mx<x+WIDTH&&my>=sy&&my<sy+ROW;
        int percent=Math.round(o.flySpeed*100),filled=Math.clamp((percent-MIN_PERCENT)/(float)(MAX_PERCENT-MIN_PERCENT),0,1)*WIDTH>0?(int)(Math.clamp((percent-MIN_PERCENT)/(float)(MAX_PERCENT-MIN_PERCENT),0,1)*WIDTH):0;
        g.fill(x,sy,x+WIDTH,sy+ROW,overSlider||dragging?0xff2f4260:0xff223247);g.fill(x,sy,x+filled,sy+ROW,0xff315b84);
        g.text(mc.font,"Flight speed "+percent+"%",x+6,sy+4,0xffdce6ef,false);
        knob(g,x,sy,WIDTH,ROW,(percent-MIN_PERCENT)/(double)(MAX_PERCENT-MIN_PERCENT));
        if(overSlider||dragging)hover="Drag to set the flight speed, also used by the editor camera";
        String item=drawPages(g,mc,mx,my);if(item!=null)hover=item;
        if(hover!=null)g.text(mc.font,hover,Math.max(4,g.guiWidth()/2-mc.font.width(hover)/2),y-18,0xffe6cf8b,false);
        drawCarried(g,mc,mx,my);
    }

    /** A slider's knob: a tall narrow plain rectangle, sitting where the value is. The knob is 5 wide, so the value runs over the width less that. */
    private static void knob(Gfx g,int x,int y,int width,int height,double fraction){
        int kx=x+(int)Math.round(Math.clamp(fraction,0,1)*(width-5));
        g.fill(kx,y,kx+5,y+height,0xffdce6ef);
    }
    /** The toolbox: while it is on, the settings below replace the hotbar pages. */
    private static boolean toolbox;
    private static int settingDrag=-1;
    /** One setting: a slider from 0 to 1, shown as a percentage, or a switch (0 off, 1 on). */
    private record Setting(String label,String hint,boolean slider,java.util.function.ToDoubleFunction<Options> get,java.util.function.ObjDoubleConsumer<Options> set,Predicate<Options> locked){
        Setting(String label,String hint,boolean slider,java.util.function.ToDoubleFunction<Options> get,java.util.function.ObjDoubleConsumer<Options> set){this(label,hint,slider,get,set,o->false);}
    }
    private static final List<Setting> SETTINGS=List.of(
        new Setting("Min brightness","The world is lit at least this much: 100% is full bright",true,o->o.minimumBrightness,(o,v)->o.minimumBrightness=v),
        // Momentum is stored up to 98% (at 100% you could never stop), so the slider runs over that range.
        new Setting("Flight momentum","How long you keep drifting after letting go of the keys · locked at 0% by Instant flight",true,o->o.flightMomentum/.98,(o,v)->o.flightMomentum=v*.98,o->o.enhancedFlight),
        new Setting("Expand block hitboxes","Every block is targeted as a full cube for now, so torches and panes are easy to hit",false,o->o.expandHitboxes?1:0,(o,v)->o.expandHitboxes=v>0),
        new Setting("Solid fluid hitboxes","Water and lava can be targeted, so they can be broken by hand",false,o->o.solidFluids?1:0,(o,v)->o.solidFluids=v>0),
        new Setting("Prevent block interactions","Chests, crafting tables and the like do not open when clicked",false,o->o.preventInteractions?1:0,(o,v)->o.preventInteractions=v>0));
    private static final int PANEL_HEADER=14,SETTING_ROW=15,SETTING_STRIDE=17;
    private static int settingY(int h,int i){return rowY(h,ROWS-1)+PANEL_HEADER+i*SETTING_STRIDE;}
    private static void dragSetting(int i,int mx,int w){
        var setting=SETTINGS.get(i);double value=Math.round(Math.clamp((mx-(rowX(w)+4)-2)/169.0,0,1)*100)/100.0;
        if(setting.locked().test(ClientState.options))return;
        setting.set().accept(ClientState.options,value);
    }
    private static String drawSettings(Gfx g,Minecraft mc,int mx,int my){
        int w=g.guiWidth(),h=g.guiHeight(),x=rowX(w),top=rowY(h,ROWS-1);String hint=null;var o=ClientState.options;
        if(settingDrag>=0){if(GLFW.glfwGetMouseButton(mc.getWindow().handle(),GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS)dragSetting(settingDrag,mx,w);else{settingDrag=-1;ClientState.save();}}
        g.fill(x,top,x+182,h-22,0xf510151c);g.outline(x,top,182,h-22-top,0xff000000);
        g.text(mc.font,"Toolbox",x+6,top+5,0xffdce6ef,false);
        for(int i=0;i<SETTINGS.size();i++){
            var setting=SETTINGS.get(i);int ry=settingY(h,i);boolean over=inside(mx,my,x+4,ry,174,SETTING_ROW)||settingDrag==i;double value=setting.get().applyAsDouble(o);
            g.fill(x+4,ry,x+178,ry+SETTING_ROW,over?0xff2f4260:0xff223247);
            if(setting.slider()){g.fill(x+4,ry,x+4+2+(int)Math.round(value*169),ry+SETTING_ROW,0xff315b84);g.text(mc.font,setting.label()+" "+Math.round(value*100)+"%",x+10,ry+4,0xffdce6ef,false);knob(g,x+4,ry,174,SETTING_ROW,value);}
            else{boolean on=value>0;g.fill(x+4,ry,x+178,ry+SETTING_ROW,on?0xff2f6350:over?0xff2f4260:0xff223247);g.text(mc.font,setting.label(),x+10,ry+4,0xffdce6ef,false);g.fill(x+164,ry+5,x+170,ry+11,on?0xff8be3b5:0xff52667a);}
            if(setting.locked().test(o))g.fill(x+4,ry,x+178,ry+SETTING_ROW,0x90101820);
            if(over)hint=setting.hint();
        }
        int[] slot=slotAt(w,h,mx,my);
        if(slot!=null){g.fill(x+2+slot[1]*20,h-20,x+2+slot[1]*20+18,h-2,0x80ffffff);var stack=get(mc,page,slot[0],slot[1]);if(!stack.isEmpty()&&carried.isEmpty())hint=stack.getHoverName().getString();}
        return hint;
    }
    private static void drawToolbox(Gfx g,int mx,int my){
        int w=g.guiWidth(),h=g.guiHeight(),tx=controlsX(w),ty=h-BOTTOM-18;boolean over=inside(mx,my,tx,ty,24,18);
        g.fill(tx,ty,tx+24,ty+18,toolbox?0xff315b84:over?0xff2f4260:0xff223247);
        int c=toolbox?0xffffd66d:0xffdce6ef;
        g.outline(tx+9,ty+3,6,5,c);g.fill(tx+5,ty+6,tx+19,ty+15,c);g.fill(tx+5,ty+10,tx+19,ty+11,0xff223247);g.fill(tx+11,ty+9,tx+13,ty+12,0xff223247); // a handle over a box with a latch
    }
    // The game's selection sprite is 24 by 23 with a frame 4 pixels thick (3 at the bottom) round a clear middle. Its corners are kept and its
    // edges are repeated along the sides, so a frame of any size looks like the one round a hotbar slot, and the middle is left clear.
    private static final int SEL_W=24,SEL_H=23,SEL_LEFT=4,SEL_TOP=4,SEL_RIGHT=4,SEL_BOTTOM=3;
    private static void selectionFrame(Gfx g,int x,int y,int w,int h){
        String s="hud/hotbar_selection";
        g.spritePiece(s,SEL_W,SEL_H,0,0,x,y,SEL_LEFT,SEL_TOP);
        g.spritePiece(s,SEL_W,SEL_H,SEL_W-SEL_RIGHT,0,x+w-SEL_RIGHT,y,SEL_RIGHT,SEL_TOP);
        g.spritePiece(s,SEL_W,SEL_H,0,SEL_H-SEL_BOTTOM,x,y+h-SEL_BOTTOM,SEL_LEFT,SEL_BOTTOM);
        g.spritePiece(s,SEL_W,SEL_H,SEL_W-SEL_RIGHT,SEL_H-SEL_BOTTOM,x+w-SEL_RIGHT,y+h-SEL_BOTTOM,SEL_RIGHT,SEL_BOTTOM);
        int tile=SEL_W-SEL_LEFT-SEL_RIGHT,tall=SEL_H-SEL_TOP-SEL_BOTTOM;
        for(int dx=SEL_LEFT;dx<w-SEL_RIGHT;dx+=tile){int len=Math.min(tile,w-SEL_RIGHT-dx);
            g.spritePiece(s,SEL_W,SEL_H,SEL_LEFT,0,x+dx,y,len,SEL_TOP);
            g.spritePiece(s,SEL_W,SEL_H,SEL_LEFT,SEL_H-SEL_BOTTOM,x+dx,y+h-SEL_BOTTOM,len,SEL_BOTTOM);}
        for(int dy=SEL_TOP;dy<h-SEL_BOTTOM;dy+=tall){int len=Math.min(tall,h-SEL_BOTTOM-dy);
            g.spritePiece(s,SEL_W,SEL_H,0,SEL_TOP,x,y+dy,SEL_LEFT,len);
            g.spritePiece(s,SEL_W,SEL_H,SEL_W-SEL_RIGHT,SEL_TOP,x+w-SEL_RIGHT,y+dy,SEL_RIGHT,len);}
    }
    private static void triangle(Gfx g,int cx,int top,boolean up,int color){
        for(int i=0;i<6;i++){int half=up?1+i:6-i;g.fill(cx-half,top+i,cx+half,top+i+1,color);}
    }
    private static String drawPages(Gfx g,Minecraft mc,int mx,int my){
        String hint=toolbox?drawSettings(g,mc,mx,my):drawStack(g,mc,mx,my);
        drawToolbox(g,mx,my);
        if(inside(mx,my,controlsX(g.guiWidth()),g.guiHeight()-BOTTOM-18,24,18))hint=toolbox?"Back to the hotbar pages":"Toolbox";
        return hint;
    }
    private static String drawStack(Gfx g,Minecraft mc,int mx,int my){
        int w=g.guiWidth(),h=g.guiHeight(),x=rowX(w),cx=controlsX(w);String hint=null;int[] over=slotAt(w,h,mx,my);
        for(int k=0;k<ROWS;k++){
            int y=rowY(h,k);
            g.enableScissor(x,k==ROWS-1?y:y+1,x+182,y+21); // the borders between hotbars are cut off, leaving the stack's outer edge
            g.sprite("hud/hotbar",x,y,182,22);
            g.disableScissor();
        }
        selectionFrame(g,x-1,rowY(h,row),184,22); // the whole selected hotbar, framed with the game's own selection sprite
        for(int k=0;k<ROWS;k++){
            int y=rowY(h,k);var stacks=HotbarPages.row(page,k);
            for(int i=0;i<HotbarPages.SLOTS&&i<stacks.size();i++){var stack=stacks.get(i);if(stack.isEmpty())continue;g.item(stack,x+3+i*20,y+3);g.itemDecorations(mc.font,stack,x+3+i*20,y+3);}
        }
        if(over!=null){ // the slot under the cursor is lit, and its item named
            int y=over[0]<0?h-22:rowY(h,over[0]);g.fill(x+2+over[1]*20,y+2,x+2+over[1]*20+18,y+20,deleting?0x80ff4040:0x80ffffff);
            var stack=get(mc,page,over[0],over[1]);if(!stack.isEmpty()&&carried.isEmpty())hint=(deleting?"Delete ":"")+stack.getHoverName().getString();
        }
        // The name sits on the top left of the topmost hotbar, with the delete switch after it; the page controls sit together at the top beside the stack.
        int ny=nameY(h);String name=HotbarPages.name(page);boolean overName=inside(mx,my,x,ny,NAME_WIDTH,13);
        g.fill(x,ny,x+NAME_WIDTH,ny+13,0xf0141d29);g.outline(x,ny,NAME_WIDTH,13,naming?0xffffd66d:overName?0xffffffff:0xff52667a);
        if(name.isEmpty()&&!naming)g.text(mc.font,"Page "+page,x+4,ny+3,0xff6f7f8f,false);
        else g.text(mc.font,name+(naming&&System.currentTimeMillis()/500%2==0?"_":""),x+4,ny+3,0xffffd66d,false);
        if(overName&&hint==null)hint="Click to name this page · the widget stays until you hold and release Alt again";
        int dx=x+NAME_WIDTH+4;boolean overDelete=inside(mx,my,dx,ny,13,13);
        g.fill(dx,ny,dx+13,ny+13,deleting?0xffb03030:overDelete?0xff2f4260:0xff223247);g.outline(dx,ny,13,13,deleting?0xffff8080:0xff52667a);g.text(mc.font,"X",dx+4,ny+3,deleting?0xffffffff:0xffdce6ef,false);
        if(overDelete)hint=deleting?"Delete mode on: click a slot to delete its item":"Delete mode: turn on, then click slots to delete their items";
        int top=rowY(h,ROWS-1);
        boolean overUp=inside(mx,my,cx,top,24,18),overBox=inside(mx,my,cx,top+18,24,16),overDown=inside(mx,my,cx,top+34,24,18);
        g.fill(cx,top,cx+24,top+18,overUp?0xff2f4260:0xff223247);triangle(g,cx+12,top+6,true,0xffdce6ef);
        String number=pageFocus?typed+"_":Integer.toString(page);
        g.fill(cx,top+18,cx+24,top+34,0xf0141d29);g.outline(cx,top+18,24,16,pageFocus?0xffffd66d:overBox?0xffffffff:0xff52667a);
        g.text(mc.font,number,cx+12-mc.font.width(number)/2,top+22,pageFocus?0xffffd66d:0xffdce6ef,false);
        g.fill(cx,top+34,cx+24,top+52,overDown?0xff2f4260:0xff223247);triangle(g,cx+12,top+40,false,0xffdce6ef);
        if(overUp)hint="Next page";if(overDown)hint="Previous page";
        if(overBox)hint="Click, then type a page number from 1 to 99 · scroll to move through the hotbars";
        return hint;
    }
    private static void drawCarried(Gfx g,Minecraft mc,int mx,int my){
        if(carried.isEmpty())return;g.item(carried,mx-8,my-8);g.itemDecorations(mc.font,carried,mx-8,my-8);
    }

    /** A click on the widget. True while it is showing, so the click does not reach the world. */
    public static boolean mouse(int button,int action){
        if(!active())return false;
        if(action!=GLFW.GLFW_PRESS||button>2)return true;
        var mc=Minecraft.getInstance();var win=mc.getWindow();int w=win.getGuiScaledWidth(),h=win.getGuiScaledHeight();
        int x=left(w),y=top(h),mx=mouseX(mc),my=mouseY(mc);
        int[] slot=slotAt(w,h,mx,my);if(slot!=null){unfocus(mc);click(mc,slot[0],slot[1],button);return true;}
        if(button!=0)return true;
        if(inside(mx,my,controlsX(w),h-BOTTOM-18,24,18)){unfocus(mc);toolbox=!toolbox;settingDrag=-1;return true;}
        if(toolbox){
            unfocus(mc);
            for(int i=0;i<SETTINGS.size();i++)if(inside(mx,my,rowX(w)+4,settingY(h,i),174,SETTING_ROW)){
                var setting=SETTINGS.get(i);
                if(setting.slider()){settingDrag=i;dragSetting(i,mx,w);}
                else{setting.set().accept(ClientState.options,setting.get().applyAsDouble(ClientState.options)>0?0:1);ClientState.save();}
                return true;
            }
        }
        int rx=rowX(w),cx=controlsX(w),top=rowY(h,ROWS-1),ny=nameY(h);
        if(!toolbox&&inside(mx,my,rx,ny,NAME_WIDTH,13)){unfocus(mc);naming=true;pin();return true;}
        if(!toolbox&&inside(mx,my,cx,top+18,24,16)){unfocus(mc);pageFocus=true;pin();return true;}
        unfocus(mc);
        if(!toolbox&&inside(mx,my,rx+NAME_WIDTH+4,ny,13,13)){deleting=!deleting;return true;}
        if(!toolbox&&inside(mx,my,cx,top,24,18)){step(1);return true;}
        if(!toolbox&&inside(mx,my,cx,top+34,24,18)){step(-1);return true;}
        int sy=sliderY(h);if(mx>=x&&mx<x+WIDTH&&my>=sy&&my<sy+ROW){dragging=true;setSpeed(mx,x);return true;}
        for(int i=0;i<TOGGLES.size();i++){
            int ry=y+HEADER+i*(ROW+GAP);
            if(mx>=x&&mx<x+WIDTH&&my>=ry&&my<ry+ROW){var t=TOGGLES.get(i);t.flip().accept(ClientState.options);ClientState.save();ClientState.notify(t.label()+(t.get().test(ClientState.options)?": on":": off"));break;}
        }
        return true;
    }
    private static int mouseX(Minecraft mc){return (int)(mc.mouseHandler.xpos()*mc.getWindow().getGuiScaledWidth()/mc.getWindow().getScreenWidth());}
    private static int mouseY(Minecraft mc){return (int)(mc.mouseHandler.ypos()*mc.getWindow().getGuiScaledHeight()/mc.getWindow().getScreenHeight());}
}
