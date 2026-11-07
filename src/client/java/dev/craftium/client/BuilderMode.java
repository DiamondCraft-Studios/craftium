package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import net.minecraft.client.Minecraft;
import dev.craftium.client.compat.Gfx;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** A virtual tenth hotbar slot. No inventory slot or held item is replaced. */
public final class BuilderMode {
    private static boolean enabled,preview,clonePlaced;
    /** Set when a build (clone, move or stack) leaves the selection in place: the next left click then starts a new selection rather than moving the first corner. */
    private static boolean built;
    private static Point first,second,offset=new Point(0,0,0);
    private static int stackX,stackY,stackZ;
    private static List<Point> cells=List.of();
    private static double wheel;
    /** A Magic Wand search under way on its own thread, so a big one does not stop the game. */
    private static java.util.concurrent.CompletableFuture<MagicWand.Result> magic;
    private static long magicSince;
    /** A large magic selection waiting to be confirmed: its box, and how many blocks it found. */
    private static Point heldFirst,heldSecond;
    private static int heldCount;
    private static List<Point> heldCells;
    private static boolean heldGrow;
    /**
     * Whether the selection is the blocks magic select found, one by one, rather than the box between two corners: the middle
     * button then keeps magic selecting, whatever the tool, and {@link #cells} are the selection. And whether the search under
     * way adds what it finds to the selection instead of starting a new one.
     */
    private static boolean magicStarted,magicGrow,magicSubtract;
    private static java.util.concurrent.ExecutorService search=newSearch();
    private static final long SEARCH_TIMEOUT=30_000;
    private static java.util.concurrent.ExecutorService newSearch(){return java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Craftium magic select");t.setDaemon(true);return t;});}
    /** Drops a search that is stuck and starts over on a fresh thread, as the one thread is shared by every search after it. */
    private static void restartSearch(){var old=search;search=newSearch();old.shutdownNow();magic=null;}
    private BuilderMode(){}
    /** One of the builder's flags, a switch in the menu held open with Alt: its label, what it reads, what it flips, and what it does. */
    private record Flag(String label,java.util.function.Predicate<Options> get,java.util.function.Consumer<Options> flip,String hint){}
    private static final List<Flag> FLAGS=List.of(
        new Flag("Keep existing",o->o.builderKeepExisting,o->o.builderKeepExisting=!o.builderKeepExisting,"Blocks already where the selection lands are not overwritten"),
        new Flag("Copy entities",o->o.copyEntities,o->o.copyEntities=!o.copyEntities,"Entities in the selection are moved or copied with it"),
        new Flag("Copy air",o->o.pasteAir,o->o.pasteAir=!o.pasteAir,"Air in the selection clears the blocks where it lands"),
        new Flag("Ruler",o->o.builderRuler,o->o.builderRuler=!o.builderRuler,"After the first click, the gizmo ruler and a line to the first block follow what you aim at"));
    private static final int FLAG_W=112,FLAG_ROW=16,FLAG_STRIDE=17;
    // On the left of the hotbar, where the builder widget is on the other slots, mirroring the tool list on the right.
    private static int flagLeft(int guiWidth){return guiWidth/2-96-FLAG_W;}
    private static int flagTop(int guiHeight){return guiHeight-28-FLAGS.size()*FLAG_STRIDE;}
    private static int guiX(Minecraft mc){return (int)(mc.mouseHandler.xpos()*mc.getWindow().getGuiScaledWidth()/mc.getWindow().getScreenWidth());}
    private static int guiY(Minecraft mc){return (int)(mc.mouseHandler.ypos()*mc.getWindow().getGuiScaledHeight()/mc.getWindow().getScreenHeight());}
    /** The flag under the cursor, or -1. */
    private static int flagAt(Minecraft mc){
        int w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight(),x=guiX(mc),y=guiY(mc),left=flagLeft(w),top=flagTop(h);
        if(x<left||x>=left+FLAG_W||y<top)return -1;
        int row=(y-top)/FLAG_STRIDE;return row<FLAGS.size()&&(y-top)%FLAG_STRIDE<FLAG_ROW?row:-1;
    }
    public static boolean active(){var mc=Minecraft.getInstance();return enabled&&available()&&ClientCompat.screen(mc)==null;}
    /** Whether the builder can be used at all here: a creative player in a world with the mod on the server. */
    static boolean usable(){return available();}
    private static boolean available(){var mc=Minecraft.getInstance();return mc.player!=null&&mc.player.isCreative()&&mc.level!=null&&CraftiumClient.supported();}
    public static void reset(){enabled=false;clear();restartSearch();wheel=0;}
    private static void clear(){built=false;first=null;second=null;preview=false;clonePlaced=false;offset=new Point(0,0,0);cells=List.of();stackX=stackY=stackZ=0;var o=ClientState.options;o.builderRotation=0;o.builderFlipAxis=-1;magic=null;heldFirst=heldSecond=null;heldCells=null;magicStarted=magicGrow=magicMode=false;}
    /** Another tool keeps the selection, but not what was being done to it. */
    private static void retool(){preview=false;clonePlaced=false;offset=new Point(0,0,0);stackX=stackY=stackZ=0;var o=ClientState.options;o.builderRotation=0;o.builderFlipAxis=-1;}
    public static void enable(){if(!available())return;enabled=true;var mc=Minecraft.getInstance();mc.options.keyAttack.setDown(false);mc.options.keyUse.setDown(false);ClientState.notify("Builder: "+name()+" · Alt + wheel changes tool");}
    public static void select(Options.BuilderTool tool){enable();ClientState.options.builderTool=tool;retool();ClientState.save();} // saved, so the tool is still chosen after a reload
    private static String name(){return ClientState.options.builderTool.name().replace('_',' ');}
    public static boolean scroll(double delta){
        if(BuilderWidget.scroll(delta))return true;
        if(!available()||ClientCompat.screen(Minecraft.getInstance())!=null)return false;
        var mc=Minecraft.getInstance();wheel+=delta;int steps=(int)wheel;if(steps==0)return enabled;wheel-=steps;
        if(!enabled){int slot=mc.player.getInventory().getSelectedSlot();if(slot==8&&steps<0||slot==0&&steps>0){enable();return true;}return false;}
        if(CraftiumClient.held(CraftiumClient.contextKey)){var all=Options.BuilderTool.values();ClientState.options.builderTool=all[Math.floorMod(ClientState.options.builderTool.ordinal()-steps,all.length)];retool();ClientState.save();ClientState.notify("Builder: "+name());return true;}
        if(first==null||second==null){enabled=false;mc.player.getInventory().setSelectedSlot(steps>0?8:0);clear();return true;}
        if(mc.hasControlDown()&&!mc.hasShiftDown()){magicMode=!magicMode;ClientState.notify("Middle click: "+(magicMode?"Magic select (add, Ctrl subtracts)":"Extend the box"));return true;} // with a selection, Ctrl + wheel switches what the middle button does
        nudge(nudgeDirection(),steps,mc.hasControlDown());return true;
    }
    /** What the middle button does to a selection that is already made: magic select more blocks (Ctrl takes them away) or, when false, grow the box to the block. A magic selection starts in magic mode, a box in extend mode. */
    private static boolean magicMode;
    public static void nudge(Point axis,int steps){nudge(axis,steps,false);}
    /** With {@code grid} (Ctrl held) a step is the selection's own size along the axis, not one block, so a clone or move goes from cell to cell of the grid the selection makes. A stack already counts whole copies. */
    public static void nudge(Point axis,int steps,boolean grid){
        if(first==null||second==null)return;preview=true;built=false; // nudging begins a new operation on the selection
        if(grid&&ClientState.options.builderTool!=Options.BuilderTool.STACK){
            Point low=min(),high=max();int sx=high.x()-low.x()+1,sz=high.z()-low.z()+1;
            if(ClientState.options.builderRotation%2==1){int swap=sx;sx=sz;sz=swap;} // a quarter turn swaps the sides the grid is measured on
            offset=offset.add(axis.x()*steps*sx,axis.y()*steps*(high.y()-low.y()+1),axis.z()*steps*sz);
            return;
        }
        // No range limit: the server still refuses an edit past the world's edge, into unloaded chunks or over the block limit.
        if(ClientState.options.builderTool==Options.BuilderTool.STACK){stackX+=axis.x()*steps;stackY+=axis.y()*steps;stackZ+=axis.z()*steps;}
        else offset=offset.add(axis.x()*steps,axis.y()*steps,axis.z()*steps);
    }
    /** One step of the nudge: along every axis key held, towards where the player looks, or else along the face looked at most squarely. */
    private static Point nudgeDirection(){
        var look=Minecraft.getInstance().player.getLookAngle();boolean x=down(GLFW.GLFW_KEY_X),y=down(GLFW.GLFW_KEY_Y),z=down(GLFW.GLFW_KEY_Z);
        if(!x&&!y&&!z){Direction d=Direction.getApproximateNearest(look.x,look.y,look.z);return new Point(d.getStepX(),d.getStepY(),d.getStepZ());}
        return new Point(x?look.x>=0?1:-1:0,y?look.y>=0?1:-1:0,z?look.z>=0?1:-1:0);
    }
    /** The axes the gizmo shows, as bits (X is 1, Y is 2, Z is 4): the ones held down, or all of them when none is. */
    public static int gizmoAxes(){int held=(down(GLFW.GLFW_KEY_X)?1:0)|(down(GLFW.GLFW_KEY_Y)?2:0)|(down(GLFW.GLFW_KEY_Z)?4:0);return held==0?7:held;}
    private static Direction facing(){
        var look=Minecraft.getInstance().player.getLookAngle();if(down(GLFW.GLFW_KEY_X))return look.x>=0?Direction.EAST:Direction.WEST;if(down(GLFW.GLFW_KEY_Y))return look.y>=0?Direction.UP:Direction.DOWN;if(down(GLFW.GLFW_KEY_Z))return look.z>=0?Direction.SOUTH:Direction.NORTH;
        return Direction.getApproximateNearest(look.x,look.y,look.z);
    }
    private static boolean down(int key){return com.mojang.blaze3d.platform.InputConstants.isKeyDown(Minecraft.getInstance().getWindow(),key);}
    public static boolean key(int key,int action,boolean control){
        if(BuilderWidget.key(key,action))return true;
        if(!available()||ClientCompat.screen(Minecraft.getInstance())!=null)return false;
        if(key==GLFW.GLFW_KEY_0){if(action==GLFW.GLFW_PRESS)enable();return true;}
        if(!enabled)return false;
        if(CraftiumClient.contextKey.matches(new net.minecraft.client.input.KeyEvent(key,0,0)))return true;
        if(CraftiumClient.held(CraftiumClient.contextKey)&&key>=GLFW.GLFW_KEY_1&&key<GLFW.GLFW_KEY_1+Options.BuilderTool.values().length){ // Alt is held, so the tool list is up: a number picks the tool at that place in it
            if(action==GLFW.GLFW_PRESS)select(Options.BuilderTool.values()[key-GLFW.GLFW_KEY_1]);return true;
        }
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_9){enabled=false;clear();return false;}
        if(action==GLFW.GLFW_RELEASE)return key==GLFW.GLFW_KEY_X||key==GLFW.GLFW_KEY_Y||key==GLFW.GLFW_KEY_Z;
        if(heldFirst!=null){if(key==GLFW.GLFW_KEY_ENTER)acceptHeld();else if(key==GLFW.GLFW_KEY_ESCAPE)discardHeld();return true;}
        if(key==GLFW.GLFW_KEY_ESCAPE){enabled=false;clear();return true;}
        if(control&&(key==GLFW.GLFW_KEY_Z||key==GLFW.GLFW_KEY_Y)){CraftiumClient.send(key==GLFW.GLFW_KEY_Z?"undo":"redo",null,null);return true;}
        var o=ClientState.options;
        if(key==GLFW.GLFW_KEY_DELETE||key==GLFW.GLFW_KEY_BACKSPACE){if(o.builderTool==Options.BuilderTool.SYMMETRY){o.symmetryX=o.symmetryY=o.symmetryZ=false;o.symmetryRotations=1;ClientState.save();}else if(first!=null&&second!=null&&o.builderTool==Options.BuilderTool.ERASE)confirm();else clear();return true;}
        if(control&&(key==GLFW.GLFW_KEY_F||key==GLFW.GLFW_KEY_R)){
            if(o.builderTool==Options.BuilderTool.SYMMETRY){if(key==GLFW.GLFW_KEY_R)o.symmetryRotations=o.symmetryRotations==4?1:4;else switch(facing().getAxis()){case X->o.symmetryX=!o.symmetryX;case Y->o.symmetryY=!o.symmetryY;case Z->o.symmetryZ=!o.symmetryZ;}ClientState.save();ClientState.notify("Symmetry updated");}
            else if(second!=null){preview=true;if(key==GLFW.GLFW_KEY_R)o.builderRotation=(o.builderRotation+1)%4;else{int axis=facing().getAxis().ordinal();o.builderFlipAxis=o.builderFlipAxis==axis?-1:axis;}}return true;
        }
        if(key==GLFW.GLFW_KEY_ENTER){confirm();return true;}return key==GLFW.GLFW_KEY_X||key==GLFW.GLFW_KEY_Y||key==GLFW.GLFW_KEY_Z;
    }
    public static boolean mouse(int button,int action){
        if(BuilderWidget.mouse(button,action))return true;
        if(!active()||button>2)return false;if(action!=GLFW.GLFW_PRESS)return true;var mc=Minecraft.getInstance();
        if(CraftiumClient.held(CraftiumClient.contextKey)){
            if(button==0){int flag=flagAt(mc);if(flag>=0){var f=FLAGS.get(flag);f.flip().accept(ClientState.options);ClientState.save();ClientState.notify(f.label()+(f.get().test(ClientState.options)?": on":": off"));return true;}}
            if(button==0){double x=mc.mouseHandler.xpos()*mc.getWindow().getGuiScaledWidth()/mc.getWindow().getScreenWidth(),y=mc.mouseHandler.ypos()*mc.getWindow().getGuiScaledHeight()/mc.getWindow().getScreenHeight();int left=mc.getWindow().getGuiScaledWidth()/2+95,top=mc.getWindow().getGuiScaledHeight()-26-Options.BuilderTool.values().length*22;int row=(int)((y-top)/22);if(x>=left&&x<left+140&&y>=top&&row>=0&&row<Options.BuilderTool.values().length)select(Options.BuilderTool.values()[row]);}return true;
        }BlockHitResult hit=mc.hitResult instanceof BlockHitResult b&&b.getType()==HitResult.Type.BLOCK?b:null;
        if(heldFirst!=null){if(button==1)acceptHeld();else if(button==0)discardHeld();return true;}
        if(ClientState.status.busy()){ClientState.notify("Wait for the current edit");return true;}
        var o=ClientState.options;
        if(o.builderTool==Options.BuilderTool.SYMMETRY){if(hit!=null&&button==1){Vec3 at=hit.getLocation();double x=Math.rint(at.x*2)/2-.5,y=Math.rint(at.y*2)/2-.5,z=Math.rint(at.z*2)/2-.5;o.symmetryOrigin=new Point((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));o.symmetryHalfX=x!=Math.floor(x);o.symmetryHalfY=y!=Math.floor(y);o.symmetryHalfZ=z!=Math.floor(z);ClientState.save();ClientState.notify("Symmetry node set · Ctrl+F mirrors · Ctrl+R rotates");}return true;}
        if(o.builderTool==Options.BuilderTool.EXTRUDE){if(hit!=null&&button!=2){boolean prior=o.invert,smart=o.smartExtrude;o.invert=button==0;o.smartExtrude=mc.hasControlDown();CraftiumClient.send("builder_extrude",CraftiumClient.point(hit.getBlockPos()),CraftiumClient.normal(hit));o.invert=prior;o.smartExtrude=smart;}return true;}
        if(button!=0)built=false; // placing or extending the selection takes it out of the state a build left it in
        if(built&&button==0){ // a build left the selection in place: the next left click starts a new one at that block, with no second point
            if(hit!=null){first=CraftiumClient.point(hit.getBlockPos());second=null;magicStarted=magicMode=false;cells=List.of();cellBlocksFor=null;retool();built=false;dragButton=0;}
            return true;
        }
        if(first!=null&&second!=null&&preview){ // something is being done to the selection: left sets the first corner again (dropping what was being done), right places it; the middle button still places it elsewhere
            if(button==0){if(hit!=null){first=CraftiumClient.point(hit.getBlockPos());magicStarted=magicMode=false;retool();refresh();dragButton=0;}return true;}
            if(button==1){confirm();return true;}
        }
        if(hit==null)return true;Point at=CraftiumClient.point(hit.getBlockPos());
        if(button==2&&o.builderTool==Options.BuilderTool.STACK&&first!=null&&second!=null){stackTo(at);return true;} // middle click: as many copies as it takes to reach the block
        if(first!=null&&second!=null&&button!=2){ // nothing is being done to it yet: left and right move the first and second point
            if(button==0)first=at;else second=at;magicStarted=magicMode=false;offset=new Point(0,0,0);refresh();dragButton=button;return true;
        }
        if(o.builderTool==Options.BuilderTool.ERASE&&first==null&&button==1){CraftiumClient.send("builder_erase_connected",at,CraftiumClient.normal(hit));return true;}
        if(button==0&&clonePlaced){clear();return true;}
        if(button==0){first=at;second=null;preview=false;offset=new Point(0,0,0);cells=List.of();magicStarted=magicMode=false;dragButton=0;}
        else if(button==1){if(first==null)first=at;second=at;refresh();dragButton=1;}
        else if(first!=null&&second==null){second=at;refresh();} // middle click with only a first point: extend the selection to this block
        else if(preview&&o.builderTool==Options.BuilderTool.STACK){} // a stack only grows from the selection's own faces: nudge it
        else if(preview){Point min=min(),max=max();Point normal=CraftiumClient.normal(hit);Point mid=new Point(Math.floorDiv(min.x()+max.x(),2),Math.floorDiv(min.y()+max.y(),2),Math.floorDiv(min.z()+max.z(),2));offset=at.add(normal.x()-mid.x(),normal.y()-mid.y(),normal.z()-mid.z());} // anchored by the centre block
        else if(first==null)magicSelect(hit.getBlockPos(),false,false);
        else if(magicMode)magicSelect(hit.getBlockPos(),true,mc.hasControlDown()); // magic mode: the middle button goes on adding what it finds, and with Ctrl takes it away
        else{Point low=min(),high=max();first=new Point(Math.min(low.x(),at.x()),Math.min(low.y(),at.y()),Math.min(low.z(),at.z()));second=new Point(Math.max(high.x(),at.x()),Math.max(high.y(),at.y()),Math.max(high.z(),at.z()));magicStarted=magicMode=false;offset=new Point(0,0,0);refresh();} // extend mode: the selection becomes the box grown to take in the block // both corners set: the box grows to take in the block clicked
        return true;
    }
    /** Whether the selection is only the blocks found in it: what magic select found, or, unless Copy air is on, the solid blocks of a box, so the air inside an outlined shape is never selected. */
    private static boolean blocksOnly(){return magicStarted||!ClientState.options.pasteAir;}
    /** The Magic Wand's search, with the editor's settings, from the block clicked: its bounding box becomes the selection, or with {@code grow} is added to it. */
    private static void magicSelect(net.minecraft.core.BlockPos at,boolean grow,boolean subtract){
        var mc=Minecraft.getInstance();var o=ClientState.options;
        if(mc.level.getBlockState(at).isAir())return;
        // Following the editor, the Magic Wand's own settings decide; otherwise the Builder matches by its own choice, up to the largest selection, with the wand's plain defaults.
        var world=o.builderFollowsMagic?MagicPreview.world(mc,at,o):MagicPreview.world(mc,at,o,o.builderMagicCompare,true);
        var params=o.builderFollowsMagic?new MagicWand.Params(o.selectLimit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,Integer.MAX_VALUE,Selection.LIMIT*4)
            :new MagicWand.Params(Selection.LIMIT,1,false,true,true,true,true,Integer.MAX_VALUE,Selection.LIMIT*4);Point start=CraftiumClient.point(at);
        magicSince=System.currentTimeMillis();magicGrow=grow;magicSubtract=subtract;
        magic=java.util.concurrent.CompletableFuture.supplyAsync(()->MagicWand.select(start,world,params),search);
    }
    /** True once the search has been running long enough that the player should be told: a large area. */
    public static boolean selecting(){return magic!=null&&System.currentTimeMillis()-magicSince>ClientState.SELECTING_DELAY;}
    /** Takes the result of the search when it is done. */
    /** Frames to wait after undo or redo before the box reads the world again, so the blocks it covers have arrived. */
    private static int refreshIn;
    /** Undo and redo put the builder's box back where it was: "x1,y1,z1,x2,y2,z2", or nothing to leave it. */
    public static void restoreBox(String box){
        if(box==null||box.isEmpty()||!enabled)return;
        try{
            String[] v=box.split(",");if(v.length!=6)return;
            first=new Point(Integer.parseInt(v[0]),Integer.parseInt(v[1]),Integer.parseInt(v[2]));second=new Point(Integer.parseInt(v[3]),Integer.parseInt(v[4]),Integer.parseInt(v[5]));
            retool();offset=new Point(0,0,0);cells=List.of();cellBlocksFor=null;refreshIn=3;
        }catch(NumberFormatException ignored){}
    }
    /** The button (0 left, 1 right) that last set a corner of the box and is still held, so moving over the blocks drags that corner; -1 when none. */
    private static int dragButton=-1;
    /** Holding the button that set a corner drags it to each block the pointer crosses, and the box follows in real time. */
    private static void dragCorner(){
        if(dragButton<0)return;
        var mc=Minecraft.getInstance();int button=dragButton;
        boolean held=GLFW.glfwGetMouseButton(mc.getWindow().handle(),button==0?GLFW.GLFW_MOUSE_BUTTON_LEFT:GLFW.GLFW_MOUSE_BUTTON_RIGHT)==GLFW.GLFW_PRESS;
        if(!held||!active()||heldFirst!=null||magicStarted||first==null){dragButton=-1;return;}
        if(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return;
        Point at=CraftiumClient.point(hit.getBlockPos());
        Point current=button==0?first:second==null?first:second;
        if(at.equals(current))return;
        if(button==0)first=at;else second=at;
        offset=new Point(0,0,0);if(second!=null)refresh();
    }
    public static void tick(){
        dragCorner();
        if(refreshIn>0&&--refreshIn==0&&first!=null&&second!=null)refresh();
        var running=magic;if(running==null)return;
        if(!running.isDone()){if(System.currentTimeMillis()-magicSince>SEARCH_TIMEOUT){restartSearch();ClientState.notify("Magic select took too long and was stopped");}return;}
        magic=null;
        MagicWand.Result result;try{result=running.join();}catch(RuntimeException e){ClientState.notify("Magic select failed");return;}
        var found=result.found();if(found.isEmpty()||!active())return;
        if(magicSubtract){subtractBlocks(found);return;} // taking blocks away never needs the large-selection question
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(Point p:found){x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        Point low=new Point(x0,y0,z0),high=new Point(x1,y1,z1);
        if(ClientState.options.largeSelectionWarnings&&found.size()>=ClientState.options.largeSelectionThreshold){ // the editor's large selection warning, and the switch that turns it off
            heldFirst=low;heldSecond=high;heldCount=found.size();heldCells=List.copyOf(found);heldGrow=magicGrow;return;}
        selectBlocks(found,magicGrow);
    }
    /**
     * A magic selection taken: the blocks themselves are the selection, and from now on the middle button magic selects. The corners
     * are the box around them, which the tools use for size, turns and flips. With {@code grow} the blocks join the ones already selected.
     */
    private static void selectBlocks(Collection<Point> found,boolean grow){
        var all=new LinkedHashSet<Point>();
        if(grow&&first!=null&&second!=null)all.addAll(cells);
        all.addAll(found);
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(Point p:all){x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        magicStarted=magicMode=true;first=new Point(x0,y0,z0);second=new Point(x1,y1,z1);cells=List.copyOf(all);cellBlocksFor=null;offset=new Point(0,0,0);preview=false;
        ClientState.notify("Selected "+cells.size()+" blocks · scroll to nudge · right-click places · Esc cancels");
    }
    /** Takes the blocks a magic select found out of the selection, which is left as the box round what remains. */
    private static void subtractBlocks(Collection<Point> found){
        var remaining=new LinkedHashSet<Point>(cells);int before=remaining.size();
        remaining.removeAll(found);
        if(remaining.size()==before){ClientState.notify("None of those blocks are selected");return;}
        if(remaining.isEmpty()){clear();ClientState.notify("Selection cleared");return;}
        selectBlocks(remaining,false);
        ClientState.notify("Removed "+(before-remaining.size())+" blocks · "+remaining.size()+" selected");
    }
    private static void acceptHeld(){var blocks=heldCells;boolean grow=heldGrow;heldFirst=heldSecond=null;heldCells=null;selectBlocks(blocks,grow);}
    private static void discardHeld(){heldFirst=heldSecond=null;ClientState.notify("Selection cancelled");}
    /** Sets the stack to run along each axis as many whole copies as reach the block, the block's own copy included; zero where it is already inside the selection. */
    private static void stackTo(Point at){
        Point a=min(),b=max();
        stackX=Math.floorDiv(at.x()-a.x(),b.x()-a.x()+1);stackY=Math.floorDiv(at.y()-a.y(),b.y()-a.y()+1);stackZ=Math.floorDiv(at.z()-a.z(),b.z()-a.z()+1);
        preview=true;clonePlaced=false;
        ClientState.notify("Stack "+stackX+" × "+stackY+" × "+stackZ+" · right-click places · Esc cancels");
    }
    /** With one corner set and the live box on, the block aimed at, which is where the box reaches until the second corner is placed; otherwise null. */
    private static Point liveCorner(){
        if(first==null||second!=null||!ClientState.options.builderLiveBox||heldFirst!=null)return null;
        return Minecraft.getInstance().hitResult instanceof BlockHitResult aim&&aim.getType()==HitResult.Type.BLOCK?CraftiumClient.point(aim.getBlockPos()):null;
    }
    private static Point min(){Point b=second==null?first:second;return new Point(Math.min(first.x(),b.x()),Math.min(first.y(),b.y()),Math.min(first.z(),b.z()));}
    private static Point max(){Point b=second==null?first:second;return new Point(Math.max(first.x(),b.x()),Math.max(first.y(),b.y()),Math.max(first.z(),b.z()));}
    public static void corners(Point a,Point b){magicStarted=magicMode=false;first=a;second=b;offset=new Point(0,0,0);preview=false;refresh();}
    private static void refresh(){try{var mc=Minecraft.getInstance();if(!magicStarted)cells=Selection.box(first,second).stream().filter(p->!mc.level.getBlockState(new net.minecraft.core.BlockPos(p.x(),p.y(),p.z())).isAir()).toList();ClientState.notify("Selection ready · scroll to nudge · right-click places · Esc cancels");}catch(Exception e){ClientState.notify(e.getMessage());clear();}}
    public static void confirm(){
        if(first==null||second==null)return;var o=ClientState.options;
        String packed="";if(blocksOnly()){try{packed=SelectionCodec.encode(cells);}catch(IllegalArgumentException e){ClientState.notify(e.getMessage());return;}}
        if(o.builderTool==Options.BuilderTool.MOVE||o.builderTool==Options.BuilderTool.CLONE||o.builderTool==Options.BuilderTool.STACK||o.builderTool==Options.BuilderTool.ERASE){
            Point lo=min(),hi=max();Point size=hi.add(1-lo.x(),1-lo.y(),1-lo.z());
            BuilderPrediction.apply(o,lo,hi,blocksOnly()?cells:null,o.builderTool==Options.BuilderTool.STACK?stackSteps(size):List.of(offset));
        }
        Point prior1=o.position1,prior2=o.position2;o.position1=first;o.position2=second;o.builderOffset=offset;o.builderCells=packed;o.stackX=stackX;o.stackY=stackY;o.stackZ=stackZ;CraftiumClient.send("builder_commit",first,new Point(0,1,0));o.position1=prior1;o.position2=prior2;o.builderOffset=new Point(0,0,0);o.builderCells="";o.stackX=o.stackY=o.stackZ=0;
        if(o.builderTool==Options.BuilderTool.CLONE){preview=true;clonePlaced=true;built=true;}
        else if(o.builderTool==Options.BuilderTool.MOVE){follow();built=true;}
        else if(o.builderTool==Options.BuilderTool.STACK&&(stackX!=0||stackY!=0||stackZ!=0)){grow();built=true;}
        else clear();
    }
    /** The stack's copies as offsets from the selection, laid edge to edge so nothing is left between them; none of them is the selection itself. */
    private static List<Point> stackSteps(Point size){
        var steps=new ArrayList<Point>();
        for(int i=Math.min(0,stackX);i<=Math.max(0,stackX);i++)for(int j=Math.min(0,stackY);j<=Math.max(0,stackY);j++)for(int k=Math.min(0,stackZ);k<=Math.max(0,stackZ);k++)if(i!=0||j!=0||k!=0)steps.add(new Point(i*size.x(),j*size.y(),k*size.z()));
        return steps;
    }
    /** After a move the selection stays, where the blocks now are (turned or flipped as they were). */
    private static void follow(){
        var o=ClientState.options;Point min=min(),max=max();Point size=max.add(1-min.x(),1-min.y(),1-min.z());Point shift=min.add(offset.x(),offset.y(),offset.z());
        Point a=BuilderTransform.apply(new Point(0,0,0),size,o.builderRotation,o.builderFlipAxis),b=BuilderTransform.apply(new Point(size.x()-1,size.y()-1,size.z()-1),size,o.builderRotation,o.builderFlipAxis);
        var moved=new ArrayList<Point>(cells.size());
        for(Point at:cells)moved.add(BuilderTransform.apply(at.subtract(min),size,o.builderRotation,o.builderFlipAxis).add(shift.x(),shift.y(),shift.z()));
        first=new Point(Math.min(a.x(),b.x())+shift.x(),Math.min(a.y(),b.y())+shift.y(),Math.min(a.z(),b.z())+shift.z());
        second=new Point(Math.max(a.x(),b.x())+shift.x(),Math.max(a.y(),b.y())+shift.y(),Math.max(a.z(),b.z())+shift.z());
        cells=moved;cellBlocksFor=null;retool();
    }
    /** The selection with the whole stack in it. */
    private static Point[] stackBounds(){
        Point min=min(),max=max();return new Point[]{new Point(min.x()+Math.min(0,stackX)*(max.x()-min.x()+1),min.y()+Math.min(0,stackY)*(max.y()-min.y()+1),min.z()+Math.min(0,stackZ)*(max.z()-min.z()+1)),
            new Point(max.x()+Math.max(0,stackX)*(max.x()-min.x()+1),max.y()+Math.max(0,stackY)*(max.y()-min.y()+1),max.z()+Math.max(0,stackZ)*(max.z()-min.z()+1))};
    }
    /** After a stack is placed the selection becomes the whole stack, so stacking again repeats all of it. */
    private static void grow(){
        Point min=min(),max=max();Point size=max.add(1-min.x(),1-min.y(),1-min.z());var grown=new ArrayList<>(cells);
        for(Point step:stackSteps(size))for(Point at:cells)grown.add(at.add(step.x(),step.y(),step.z()));
        var bounds=stackBounds();first=bounds[0];second=bounds[1];cells=grown;
        stackX=stackY=stackZ=0;offset=new Point(0,0,0);preview=false;cellBlocksFor=null;
    }
    public static void draw(){
        var o=ClientState.options;if(!active())return;
        if(first!=null&&second==null&&o.builderRuler&&heldFirst==null&&Minecraft.getInstance().hitResult instanceof BlockHitResult aimed&&aimed.getType()==HitResult.Type.BLOCK){ // after the first click the ruler runs through the block aimed at, to read the distance off the grid
            var at=aimed.getBlockPos();Vec3 target=new Vec3(at.getX()+.5,at.getY()+.5,at.getZ()+.5);
            TransformGizmo.ruler(target);TransformGizmo.guideLine(new Vec3(first.x()+.5,first.y()+.5,first.z()+.5),target); // and a line from the first block to it
        }
        if(first!=null){Point min=min(),max=max();
            // Once both corners are set the area is filled in, until the blocks are shown moving out of it.
            boolean leaving=preview&&o.builderTool==Options.BuilderTool.MOVE;
            boolean stacking=preview&&second!=null&&o.builderTool==Options.BuilderTool.STACK;
            Point from=min,to=max;if(stacking){var b=stackBounds();from=b[0];to=b[1];} // the selection grows with the stack
            boolean live=false; // one corner set: the box to the block aimed at, following the aim
            Point liveAt=liveCorner();
            if(liveAt!=null){
                Point at=liveAt;
                from=new Point(Math.min(first.x(),at.x()),Math.min(first.y(),at.y()),Math.min(first.z(),at.z()));to=new Point(Math.max(first.x(),at.x()),Math.max(first.y(),at.y()),Math.max(first.z(),at.z()));live=true;
            }
            if(!stacking&&blocksOnly()&&second!=null&&!cells.isEmpty()&&cells.size()<=OUTLINE_LIMIT){ // the blocks themselves (while stacking, the one box round the whole stack), not the box round them
                if(leaving){if(cells!=shapeOf){selectionShape=OutlineShape.of(cells);shapeOf=cells;}
                    selectionShape.drawPlain(Vec3.ZERO,o.builderSelectionColor,(float)o.builderSelectionWidth,o.builderSelectionFill,false,false);}
                else drawInScene(o); // not moving: faces on the ground are coloured dimly, with no edges, and only what is in open air is outlined
            }else boxShape(from,to.add(1,1,1)).drawPlain(Vec3.ZERO,o.builderSelectionColor,(float)o.builderSelectionWidth,o.builderSelectionFill,(second!=null&&!leaving)||live,false); // in the style set in the preferences
            // With Copy air off the air inside the box is left out, so the box itself is shown as a plain white outline round what is copied. With it on, the whole box is the selection and is drawn as above.
            if(!o.pasteAir)boxShape(from,to.add(1,1,1)).drawPlain(Vec3.ZERO,0xffffffff,(float)o.builderSelectionWidth,0,(second!=null&&!leaving)||live,false);
            if(preview){Point size=max.add(1-min.x(),1-min.y(),1-min.z());List<Point> steps=o.builderTool==Options.BuilderTool.SMEAR?BrushMath.line(new Point(0,0,0),offset):o.builderTool==Options.BuilderTool.STACK?stackSteps(size):List.of(offset);
                if(stacking){drawStack(o,min,size);return;}
                var shown=preview(o,min,size,steps);
                if(leaving&&!cells.isEmpty()&&cells.size()<=PREVIEW_LIMIT)Overlay.blocks(cellBlocks(),o.removedTint); // the blocks that are moving, where they are now
                if(shown==null)boxShape(min.add(offset.x(),offset.y(),offset.z()),max.add(offset.x()+1,offset.y()+1,offset.z()+1)).drawPlain(Vec3.ZERO,o.previewColor,(float)o.builderPreviewWidth,0,false,false);
                else{shown.shape().drawPlain(Vec3.ZERO,o.previewColor,(float)o.builderPreviewWidth,o.previewFill,true,false);if(!shown.places().isEmpty())Overlay.blockStates(turned(shown.models(),o),shown.places(),o.previewTint);}
            }
        }
    }
    private static final Map<Object,OutlineShape> boxes=new HashMap<>();
    /** The outline of the box between two block corners, kept for as long as it is drawn so its silhouette is not worked out again every frame. */
    private static OutlineShape boxShape(Point lo,Point hi){
        if(boxes.size()>1024)boxes.clear();
        return boxes.computeIfAbsent(List.of(lo,hi),k->OutlineShape.box(lo,hi));
    }
    /** A magic selection bigger than this is drawn as the one box round it, as its outline costs more than a frame can spare. */
    private static final int OUTLINE_LIMIT=32_768;
    private static OutlineShape selectionShape=OutlineShape.EMPTY;
    private static List<Point> shapeOf;
    private static SceneShape sceneShape=SceneShape.EMPTY;
    private static List<Point> sceneOf;private static long sceneAt;
    /** The selection's blocks as the world hides them (see {@link SceneShape}); worked out again when the selection changes or, once a second, the world does. */
    private static void drawInScene(Options o){
        var level=Minecraft.getInstance().level;if(level==null)return;
        long second=level.getGameTime()/20;
        if(cells!=sceneOf||second!=sceneAt){sceneShape=SceneShape.of(level,cells,true);sceneOf=cells;sceneAt=second;}
        if(sceneShape.tooLarge()){boxShape(min(),max().add(1,1,1)).drawPlain(Vec3.ZERO,o.builderSelectionColor,(float)o.builderSelectionWidth,o.builderSelectionFill,true,false);return;}
        int fill=(o.builderSelectionFill>>>24)!=0?o.builderSelectionFill:o.builderSelectionColor&0xffffff|SceneShape.FACE_ALPHA;
        sceneShape.draw(fill,o.builderSelectionColor,(float)o.builderSelectionWidth,0);
    }
    /**
     * The stack's preview: the blocks' models where they land. The whole stack is one selection, drawn by {@link #draw()}, with no box round each copy; the models are kept until the stack, the selection or the turn changes.
     */
    private static void drawStack(Options o,Point min,Point size){
        long copies=((long)Math.abs(stackX)+1)*(Math.abs(stackY)+1)*(Math.abs(stackZ)+1)-1;
        if(cells.size()*copies>PREVIEW_LIMIT)return;
        Object[] key={min,size,o.builderRotation,o.builderFlipAxis,stackX,stackY,stackZ};
        if(stackFor==null||cells!=stackCells||!Arrays.equals(key,stackFor)){
            var models=new ArrayList<net.minecraft.core.BlockPos>();var places=new ArrayList<net.minecraft.core.BlockPos>();var seen=new HashSet<Point>();
            for(Point step:stackSteps(size))for(Point at:cells){
                Point q=BuilderTransform.apply(at.subtract(min),size,o.builderRotation,o.builderFlipAxis).add(min.x()+step.x(),min.y()+step.y(),min.z()+step.z());
                if(seen.add(q)){places.add(new net.minecraft.core.BlockPos(q.x(),q.y(),q.z()));models.add(new net.minecraft.core.BlockPos(at.x(),at.y(),at.z()));}
            }
            stackFor=key;stackCells=cells;stackModels=models;stackPlaces=places;
        }
        if(!stackPlaces.isEmpty())Overlay.blockStates(turned(stackModels,o),stackPlaces,o.previewTint);
    }
    /** The blocks at {@code models} as they will stand once the builder's turn and flip are applied, so oriented blocks are drawn facing their new way. */
    private static List<net.minecraft.world.level.block.state.BlockState> turned(List<net.minecraft.core.BlockPos> models,Options o){
        var level=Minecraft.getInstance().level;var states=new ArrayList<net.minecraft.world.level.block.state.BlockState>(models.size());
        for(var pos:models)states.add(dev.craftium.server.BuilderEdits.turn(level.getBlockState(pos),o.builderRotation,o.builderFlipAxis));
        return states;
    }
    private static Object[] stackFor;
    private static List<Point> stackCells;
    private static List<net.minecraft.core.BlockPos> stackModels=List.of(),stackPlaces=List.of();
    private static List<net.minecraft.core.BlockPos> cellBlocks;
    private static List<Point> cellBlocksFor;
    private static List<net.minecraft.core.BlockPos> cellBlocks(){
        if(cellBlocksFor!=cells){cellBlocks=cells.stream().map(p->new net.minecraft.core.BlockPos(p.x(),p.y(),p.z())).toList();cellBlocksFor=cells;}
        return cellBlocks;
    }
    /** Past this many blocks the preview is just a box around where they land. */
    private static final int PREVIEW_LIMIT=4096;
    /** Where the cells would land: one outline around them, plus each block's model drawn at its destination. */
    private record Preview(OutlineShape shape,List<net.minecraft.core.BlockPos> models,List<net.minecraft.core.BlockPos> places){}
    private static Preview cachedPreview;
    private static Object[] cachedFor;
    private static List<Point> cachedCells;

    /** Rebuilt only when something it depends on changes, because the outline costs more than a frame can spare. Null when too large. */
    private static Preview preview(Options o,Point min,Point size,List<Point> steps){
        Object[] key={min,size,offset,o.builderRotation,o.builderFlipAxis,o.builderTool,o.copies,stackX,stackY,stackZ};
        if(cachedFor!=null&&cells==cachedCells&&Arrays.equals(key,cachedFor))return cachedPreview;
        var landing=new LinkedHashMap<Point,Point>();
        outer:for(Point step:steps)for(Point at:cells){
            Point q=BuilderTransform.apply(at.subtract(min),size,o.builderRotation,o.builderFlipAxis).add(min.x()+step.x(),min.y()+step.y(),min.z()+step.z());
            if(landing.putIfAbsent(q,at)==null&&landing.size()>PREVIEW_LIMIT){landing=null;break outer;}
        }
        Preview built=null;
        if(landing!=null){
            var models=new ArrayList<net.minecraft.core.BlockPos>(landing.size());var places=new ArrayList<net.minecraft.core.BlockPos>(landing.size());
            landing.forEach((to,from)->{places.add(new net.minecraft.core.BlockPos(to.x(),to.y(),to.z()));models.add(new net.minecraft.core.BlockPos(from.x(),from.y(),from.z()));});
            built=new Preview(OutlineShape.of(landing.keySet()),models,places);
        }
        cachedFor=key;cachedCells=cells;cachedPreview=built;
        return built;
    }
    private static final int[] AXIS_COLORS={0xffff7777,0xff8bef9a,0xff78b9ff}; // the same red, green and blue as the transform gizmo's axes
    /** Draws the size and the offset centred on one line, each number in its axis colour. */
    private static void axisReadout(Gfx g,net.minecraft.client.gui.Font font,int y,String sizeLabel,String sizeJoin,int sx,int sy,int sz,String offsetLabel,String offsetJoin,int ox,int oy,int oz){
        String[] text={sizeLabel,String.valueOf(sx),sizeJoin,String.valueOf(sy),sizeJoin,String.valueOf(sz),offsetLabel,String.valueOf(ox),offsetJoin,String.valueOf(oy),offsetJoin,String.valueOf(oz)};
        int[] colors={0xffc7d6e5,AXIS_COLORS[0],0xffc7d6e5,AXIS_COLORS[1],0xffc7d6e5,AXIS_COLORS[2],0xffc7d6e5,AXIS_COLORS[0],0xffc7d6e5,AXIS_COLORS[1],0xffc7d6e5,AXIS_COLORS[2]};
        int total=0;for(String part:text)total+=font.width(part);
        int x=g.guiWidth()/2-total/2;
        for(int i=0;i<text.length;i++){g.text(font,text[i],x,y,colors[i],false);x+=font.width(text[i]);}
    }
    /** Where the transform handles are shown: the centre of the selection, where it would land. Null with no selection. */
    public static Vec3 gizmoAnchor(){
        var tool=ClientState.options.builderTool;
        // Only the tools that move blocks have anything to drag: erase, extrude and symmetry do not, and nor does a lone first point.
        if(!active()||first==null||second==null||tool!=Options.BuilderTool.MOVE&&tool!=Options.BuilderTool.CLONE&&tool!=Options.BuilderTool.STACK&&tool!=Options.BuilderTool.SMEAR)return null;
Vec3 c=center();return new Vec3(c.x+offset.x(),c.y+offset.y(),c.z+offset.z());}
    /** The middle of the selection's box, in world coordinates: the point the selection is anchored by. */
    private static Vec3 center(){Point a=min(),b=max();return new Vec3((a.x()+b.x()+1)/2d,(a.y()+b.y()+1)/2d,(a.z()+b.z()+1)/2d);}
    private static void drawFlags(Gfx g,Minecraft mc){
        int x=flagLeft(g.guiWidth()),top=flagTop(g.guiHeight()),over=flagAt(mc);var o=ClientState.options;
        g.fill(x-4,top-20,x+FLAG_W+4,g.guiHeight()-24,0xf510151c);g.text(mc.font,"Builder flags",x,top-14,0xffdce6ef,false);
        for(int i=0;i<FLAGS.size();i++){
            var f=FLAGS.get(i);int y=top+i*FLAG_STRIDE;boolean on=f.get().test(o),hover=i==over;
            g.fill(x,y,x+FLAG_W,y+FLAG_ROW,on?(hover?0xff3f7a63:0xff2f6350):(hover?0xff2f4260:0xff223247));
            g.text(mc.font,f.label(),x+6,y+4,on?0xffe9fff4:0xffdce6ef,false);
            g.fill(x+FLAG_W-12,y+5,x+FLAG_W-6,y+11,on?0xff8be3b5:0xff52667a);
        }
        if(over>=0){String hint=FLAGS.get(over).hint();g.text(mc.font,hint,Math.max(4,g.guiWidth()/2-mc.font.width(hint)/2),top-34,0xffe6cf8b,false);}
    }
    public static void hud(Gfx g){
        if(!available())return;var mc=Minecraft.getInstance();int x=g.guiWidth()/2+94,y=g.guiHeight()-23;
        // The builder slot beside the hotbar is always there; it is dim until the builder is on.
        boolean on=active();int tone=on?0xffffd66d:0xff6f7f8f;
        g.fill(x,y,x+23,y+23,0xff172330);g.outline(x,y,23,23,tone);g.text(mc.font,"0",x+9,y+8,tone,false);
        if(!on)return;
        if(selecting())g.text(mc.font,SelectingIndicator.spinner(),g.guiWidth()/2+8,g.guiHeight()/2+8,0xff6dc8ff,false);
        if(CraftiumClient.held(CraftiumClient.contextKey)){int left=g.guiWidth()/2+95,top=g.guiHeight()-26-Options.BuilderTool.values().length*22;var all=Options.BuilderTool.values();g.fill(left-4,top-20,left+144,top+all.length*22+4,0xf510151c);g.text(mc.font,"Builder tools",left,top-14,0xffdce6ef,false);for(int i=0;i<all.length;i++){g.fill(left,top+i*22,left+140,top+i*22+21,all[i]==ClientState.options.builderTool?0xff315b84:0xff223247);g.text(mc.font,(i+1)+"  "+all[i].name().replace('_',' '),left+6,top+i*22+6,0xffdce6ef,false);}drawFlags(g,mc);}
        String label=name()+"  ·  Hold Alt + wheel or 1-"+Options.BuilderTool.values().length+": tools";g.text(mc.font,label,g.guiWidth()/2-mc.font.width(label)/2,g.guiHeight()-69,0xffe6cf8b,false);
        if(heldFirst!=null){String ask="Selected "+heldCount+" blocks, confirm?  ·  RMB or Enter Confirm  ·  LMB or Esc Cancel";g.fill(g.guiWidth()/2-mc.font.width(ask)/2-8,g.guiHeight()/2+22,g.guiWidth()/2+mc.font.width(ask)/2+8,g.guiHeight()/2+42,0xf0141d29);g.text(mc.font,ask,g.guiWidth()/2-mc.font.width(ask)/2,g.guiHeight()/2+28,0xffffd66d,false);}
        String hint=ClientState.options.builderTool==Options.BuilderTool.EXTRUDE?"LMB Shrink  ·  RMB Extrude  ·  Hold Ctrl: Smart extrude (stairs, slabs, walls too)":ClientState.options.builderTool==Options.BuilderTool.SYMMETRY?"RMB Set node  ·  Ctrl+F Mirror  ·  Ctrl+R Rotate":first==null?"LMB First corner  ·  RMB Second corner  ·  MMB Magic select":second!=null&&!preview?(ClientState.options.builderTool==Options.BuilderTool.ERASE?"Enter Erase  ·  ":"")+"LMB Move 1st  ·  RMB Move 2nd  ·  "+(magicStarted?"MMB Magic add  ·  Ctrl+MMB Magic subtract":"MMB Extend")+"  ·  Wheel Nudge (Ctrl: by size)  ·  XYZ Axis  ·  Ctrl+R/F":second!=null?"LMB Move 1st  ·  RMB Place  ·  MMB "+(ClientState.options.builderTool==Options.BuilderTool.STACK?"Stack to block":"Face")+"  ·  Wheel Nudge (Ctrl: by size)  ·  XYZ Axis  ·  Ctrl+R/F":"LMB Restart  ·  RMB or MMB Second corner";
        g.text(mc.font,hint,g.guiWidth()/2-mc.font.width(hint)/2,g.guiHeight()-57,0xffc7d6e5,false);
        if(first!=null){
            // The size is that of the box as it is drawn: following the aim before the second corner is placed, and the whole stack while stacking.
            Point a=min(),b=max();var aim=liveCorner();
            if(aim!=null){a=new Point(Math.min(first.x(),aim.x()),Math.min(first.y(),aim.y()),Math.min(first.z(),aim.z()));b=new Point(Math.max(first.x(),aim.x()),Math.max(first.y(),aim.y()),Math.max(first.z(),aim.z()));}
            else if(second!=null&&preview&&ClientState.options.builderTool==Options.BuilderTool.STACK){var bounds=stackBounds();a=bounds[0];b=bounds[1];}
            axisReadout(g,mc.font,g.guiHeight()-81,"Size "," × ",b.x()-a.x()+1,b.y()-a.y()+1,b.z()-a.z()+1,"   Offset ",", ",offset.x(),offset.y(),offset.z());}
    }
}
