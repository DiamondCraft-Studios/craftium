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
    private static boolean magicStarted,magicGrow;
    private static java.util.concurrent.ExecutorService search=newSearch();
    private static final long SEARCH_TIMEOUT=30_000;
    private static java.util.concurrent.ExecutorService newSearch(){return java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Craftium magic select");t.setDaemon(true);return t;});}
    /** Drops a search that is stuck and starts over on a fresh thread, as the one thread is shared by every search after it. */
    private static void restartSearch(){var old=search;search=newSearch();old.shutdownNow();magic=null;}
    private BuilderMode(){}
    /** One of the builder's flags, a switch in the menu held open with Alt: its label, what it reads, what it flips, and what it does. */
    private record Flag(String label,java.util.function.Predicate<Options> get,java.util.function.Consumer<Options> flip,String hint){}
    private static final List<Flag> FLAGS=List.of(
        new Flag("Keep existing",o->o.keepExisting,o->o.keepExisting=!o.keepExisting,"Blocks already where the selection lands are not overwritten"),
        new Flag("Copy entities",o->o.copyEntities,o->o.copyEntities=!o.copyEntities,"Entities in the selection are moved or copied with it"),
        new Flag("Copy air",o->o.pasteAir,o->o.pasteAir=!o.pasteAir,"Air in the selection clears the blocks where it lands"));
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
    private static void clear(){first=null;second=null;preview=false;clonePlaced=false;offset=new Point(0,0,0);cells=List.of();stackX=stackY=stackZ=0;var o=ClientState.options;o.builderRotation=0;o.builderFlipAxis=-1;magic=null;heldFirst=heldSecond=null;heldCells=null;magicStarted=magicGrow=false;}
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
        nudge(nudgeDirection(),steps);return true;
    }
    public static void nudge(Point axis,int steps){
        if(first==null||second==null)return;preview=true;
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
        if(o.builderTool==Options.BuilderTool.EXTRUDE){if(hit!=null&&button!=2){boolean prior=o.invert;o.invert=button==0;CraftiumClient.send("builder_extrude",CraftiumClient.point(hit.getBlockPos()),CraftiumClient.normal(hit));o.invert=prior;}return true;}
        if(first!=null&&second!=null&&preview){ // something is being done to the selection: left sets the first corner again (dropping what was being done), right places it; the middle button still places it elsewhere
            if(button==0){if(hit!=null){first=CraftiumClient.point(hit.getBlockPos());magicStarted=false;retool();refresh();}return true;}
            if(button==1){confirm();return true;}
        }
        if(hit==null)return true;Point at=CraftiumClient.point(hit.getBlockPos());
        if(button==2&&o.builderTool==Options.BuilderTool.STACK&&first!=null&&second!=null){stackTo(at);return true;} // middle click: as many copies as it takes to reach the block
        if(first!=null&&second!=null&&button!=2){ // nothing is being done to it yet: left and right move the first and second point
            if(button==0)first=at;else second=at;magicStarted=false;offset=new Point(0,0,0);refresh();return true;
        }
        if(o.builderTool==Options.BuilderTool.ERASE&&first==null&&button==1){CraftiumClient.send("builder_erase_connected",at,CraftiumClient.normal(hit));return true;}
        if(button==0&&clonePlaced){clear();return true;}
        if(button==0){first=at;second=null;preview=false;offset=new Point(0,0,0);cells=List.of();magicStarted=false;}
        else if(button==1){if(first==null)first=at;second=at;refresh();}
        else if(first!=null&&second==null){second=at;refresh();} // middle click with only a first point: extend the selection to this block
        else if(preview&&o.builderTool==Options.BuilderTool.STACK){} // a stack only grows from the selection's own faces: nudge it
        else if(preview){Point min=min();Point normal=CraftiumClient.normal(hit);offset=at.add(normal.x()-min.x(),normal.y()-min.y(),normal.z()-min.z());}
        else if(first==null)magicSelect(hit.getBlockPos(),false);
        else if(magicStarted)magicSelect(hit.getBlockPos(),true); // begun with magic select: the middle button goes on adding what it finds
        else{Point low=min(),high=max();first=new Point(Math.min(low.x(),at.x()),Math.min(low.y(),at.y()),Math.min(low.z(),at.z()));second=new Point(Math.max(high.x(),at.x()),Math.max(high.y(),at.y()),Math.max(high.z(),at.z()));offset=new Point(0,0,0);refresh();} // both corners set: the box grows to take in the block clicked
        return true;
    }
    /** The Magic Wand's search, with the editor's settings, from the block clicked: its bounding box becomes the selection, or with {@code grow} is added to it. */
    private static void magicSelect(net.minecraft.core.BlockPos at,boolean grow){
        var mc=Minecraft.getInstance();var o=ClientState.options;
        if(mc.level.getBlockState(at).isAir())return;
        var world=MagicPreview.world(mc,at,o);var params=new MagicWand.Params(o.selectLimit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,Integer.MAX_VALUE,Selection.LIMIT*4);Point start=CraftiumClient.point(at);
        magicSince=System.currentTimeMillis();magicGrow=grow;
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
    public static void tick(){
        if(refreshIn>0&&--refreshIn==0&&first!=null&&second!=null)refresh();
        var running=magic;if(running==null)return;
        if(!running.isDone()){if(System.currentTimeMillis()-magicSince>SEARCH_TIMEOUT){restartSearch();ClientState.notify("Magic select took too long and was stopped");}return;}
        magic=null;
        MagicWand.Result result;try{result=running.join();}catch(RuntimeException e){ClientState.notify("Magic select failed");return;}
        var found=result.found();if(found.isEmpty()||!active())return;
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(Point p:found){x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        Point low=new Point(x0,y0,z0),high=new Point(x1,y1,z1);
        if(found.size()>=ClientState.LARGE_SELECTION){heldFirst=low;heldSecond=high;heldCount=found.size();heldCells=List.copyOf(found);heldGrow=magicGrow;return;}
        selectBlocks(found,magicGrow);
    }
    /**
     * A magic selection taken: the blocks themselves are the selection, and from now on the middle button magic selects. The corners
     * are the box around them, which the tools use for size, turns and flips. With {@code grow} the blocks join the ones already selected.
     */
    private static void selectBlocks(Collection<Point> found,boolean grow){
        var all=new LinkedHashSet<Point>();
        if(grow&&magicStarted&&first!=null&&second!=null)all.addAll(cells);
        all.addAll(found);
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(Point p:all){x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        magicStarted=true;first=new Point(x0,y0,z0);second=new Point(x1,y1,z1);cells=List.copyOf(all);cellBlocksFor=null;offset=new Point(0,0,0);preview=false;
        ClientState.notify("Selected "+cells.size()+" blocks · scroll to nudge · right-click places · Esc cancels");
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
    private static Point min(){Point b=second==null?first:second;return new Point(Math.min(first.x(),b.x()),Math.min(first.y(),b.y()),Math.min(first.z(),b.z()));}
    private static Point max(){Point b=second==null?first:second;return new Point(Math.max(first.x(),b.x()),Math.max(first.y(),b.y()),Math.max(first.z(),b.z()));}
    public static void corners(Point a,Point b){magicStarted=false;first=a;second=b;offset=new Point(0,0,0);preview=false;refresh();}
    private static void refresh(){try{var mc=Minecraft.getInstance();if(!magicStarted)cells=Selection.box(first,second).stream().filter(p->!mc.level.getBlockState(new net.minecraft.core.BlockPos(p.x(),p.y(),p.z())).isAir()).toList();ClientState.notify("Selection ready · scroll to nudge · right-click places · Esc cancels");}catch(Exception e){ClientState.notify(e.getMessage());clear();}}
    public static void confirm(){
        if(first==null||second==null)return;var o=ClientState.options;
        String packed="";if(magicStarted){try{packed=SelectionCodec.encode(cells);}catch(IllegalArgumentException e){ClientState.notify(e.getMessage());return;}}
        Point prior1=o.position1,prior2=o.position2;o.position1=first;o.position2=second;o.builderOffset=offset;o.builderCells=packed;o.stackX=stackX;o.stackY=stackY;o.stackZ=stackZ;CraftiumClient.send("builder_commit",first,new Point(0,1,0));o.position1=prior1;o.position2=prior2;o.builderOffset=new Point(0,0,0);o.builderCells="";o.stackX=o.stackY=o.stackZ=0;
        if(o.builderTool==Options.BuilderTool.CLONE){preview=true;clonePlaced=true;}
        else if(o.builderTool==Options.BuilderTool.MOVE)follow();
        else if(o.builderTool==Options.BuilderTool.STACK&&(stackX!=0||stackY!=0||stackZ!=0))grow();
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
        if(first!=null){Point min=min(),max=max();
            // Once both corners are set the area is filled in, until the blocks are shown moving out of it.
            boolean leaving=preview&&o.builderTool==Options.BuilderTool.MOVE;
            boolean stacking=preview&&second!=null&&o.builderTool==Options.BuilderTool.STACK;
            Point from=min,to=max;if(stacking){var b=stackBounds();from=b[0];to=b[1];} // the selection grows with the stack
            if(magicStarted&&second!=null&&!cells.isEmpty()&&cells.size()<=OUTLINE_LIMIT){ // the blocks themselves, not the box round them
                if(cells!=shapeOf){selectionShape=OutlineShape.of(cells);shapeOf=cells;}
                selectionShape.draw(Vec3.ZERO,(a,b)->Overlay.line(a,b,o.builderSelectionColor,(float)o.builderSelectionWidth),o.builderSelectionColor,o.builderSelectionFill,!leaving);
            }else Overlay.box(new AABB(from.x(),from.y(),from.z(),to.x()+1,to.y()+1,to.z()+1),o.builderSelectionColor,(float)o.builderSelectionWidth,second!=null&&!leaving?o.builderSelectionFill:0);
            if(preview){Point size=max.add(1-min.x(),1-min.y(),1-min.z());List<Point> steps=o.builderTool==Options.BuilderTool.SMEAR?BrushMath.line(new Point(0,0,0),offset):o.builderTool==Options.BuilderTool.STACK?stackSteps(size):List.of(offset);
                if(stacking){drawStack(o,min,size);return;}
                var shown=preview(o,min,size,steps);
                if(leaving&&!cells.isEmpty()&&cells.size()<=PREVIEW_LIMIT)Overlay.blocks(cellBlocks(),o.builderMovingTint); // the blocks that are moving, where they are now
                if(shown==null)Overlay.box(new AABB(min.x()+offset.x(),min.y()+offset.y(),min.z()+offset.z(),max.x()+offset.x()+1,max.y()+offset.y()+1,max.z()+offset.z()+1),o.builderPreviewColor,(float)o.builderPreviewWidth,0);
                else{shown.shape().draw(Vec3.ZERO,(a,b)->Overlay.line(a,b,o.builderPreviewColor,(float)o.builderPreviewWidth),o.builderPreviewColor,o.builderPreviewFill,true);if(!shown.places().isEmpty())Overlay.blocks(shown.models(),shown.places(),o.builderPreviewTint);}
            }
        }
    }
    /** A magic selection bigger than this is drawn as the one box round it, as its outline costs more than a frame can spare. */
    private static final int OUTLINE_LIMIT=32_768;
    private static OutlineShape selectionShape=OutlineShape.EMPTY;
    private static List<Point> shapeOf;
    /** Past this many copies the stack is shown as the one box around all of it. */
    private static final int STACK_BOX_LIMIT=512;
    /**
     * The stack's preview: a box for each copy, and the blocks' models where they land. The cost of an outline round every copy
     * made scrolling the wheel stutter, so none is made; the models are kept until the stack, the selection or the turn changes.
     */
    private static void drawStack(Options o,Point min,Point size){
        long copies=((long)Math.abs(stackX)+1)*(Math.abs(stackY)+1)*(Math.abs(stackZ)+1)-1;
        if(copies<=STACK_BOX_LIMIT)for(Point step:stackSteps(size))
            Overlay.box(new AABB(min.x()+step.x(),min.y()+step.y(),min.z()+step.z(),min.x()+step.x()+size.x(),min.y()+step.y()+size.y(),min.z()+step.z()+size.z()),o.builderPreviewColor,(float)o.builderPreviewWidth,o.builderPreviewFill);
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
        if(!stackPlaces.isEmpty())Overlay.blocks(stackModels,stackPlaces,o.builderPreviewTint);
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
    /** Where the transform handles are shown: the corner of the selection, where it would land. Null with no selection. */
    public static Vec3 gizmoAnchor(){
        var tool=ClientState.options.builderTool;
        // Only the tools that move blocks have anything to drag: erase, extrude and symmetry do not, and nor does a lone first point.
        if(!active()||first==null||second==null||tool!=Options.BuilderTool.MOVE&&tool!=Options.BuilderTool.CLONE&&tool!=Options.BuilderTool.STACK&&tool!=Options.BuilderTool.SMEAR)return null;
Point m=min();return new Vec3(m.x()+offset.x(),m.y()+offset.y(),m.z()+offset.z());}
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
        if(CraftiumClient.held(CraftiumClient.contextKey)){int left=g.guiWidth()/2+95,top=g.guiHeight()-26-Options.BuilderTool.values().length*22;var all=Options.BuilderTool.values();g.fill(left-4,top-20,left+144,top+all.length*22+4,0xf510151c);g.text(mc.font,"Builder tools",left,top-14,0xffdce6ef,false);for(int i=0;i<all.length;i++){g.fill(left,top+i*22,left+140,top+i*22+21,all[i]==ClientState.options.builderTool?0xff315b84:0xff223247);g.text(mc.font,all[i].name().replace('_',' '),left+6,top+i*22+6,0xffdce6ef,false);}drawFlags(g,mc);}
        String label=name()+"  ·  Hold Alt + wheel: tools";g.text(mc.font,label,g.guiWidth()/2-mc.font.width(label)/2,g.guiHeight()-69,0xffe6cf8b,false);
        if(heldFirst!=null){String ask="Selected "+heldCount+" blocks, confirm?  ·  RMB or Enter Confirm  ·  LMB or Esc Cancel";g.fill(g.guiWidth()/2-mc.font.width(ask)/2-8,g.guiHeight()/2+22,g.guiWidth()/2+mc.font.width(ask)/2+8,g.guiHeight()/2+42,0xf0141d29);g.text(mc.font,ask,g.guiWidth()/2-mc.font.width(ask)/2,g.guiHeight()/2+28,0xffffd66d,false);}
        String hint=ClientState.options.builderTool==Options.BuilderTool.EXTRUDE?"LMB Shrink  ·  RMB Extrude":ClientState.options.builderTool==Options.BuilderTool.SYMMETRY?"RMB Set node  ·  Ctrl+F Mirror  ·  Ctrl+R Rotate":first==null?"LMB First corner  ·  RMB Second corner  ·  MMB Magic select":second!=null&&!preview?(ClientState.options.builderTool==Options.BuilderTool.ERASE?"Enter Erase  ·  ":"")+"LMB Move 1st  ·  RMB Move 2nd  ·  "+(magicStarted?"MMB Magic add":"MMB Extend")+"  ·  Wheel Nudge  ·  XYZ Axis  ·  Ctrl+R/F":second!=null?"LMB Move 1st  ·  RMB Place  ·  MMB "+(ClientState.options.builderTool==Options.BuilderTool.STACK?"Stack to block":"Face")+"  ·  Wheel Nudge  ·  XYZ Axis  ·  Ctrl+R/F":"LMB Restart  ·  RMB or MMB Second corner";
        g.text(mc.font,hint,g.guiWidth()/2-mc.font.width(hint)/2,g.guiHeight()-57,0xffc7d6e5,false);
        if(first!=null){Point a=min(),b=max();axisReadout(g,mc.font,g.guiHeight()-81,"Size "," × ",b.x()-a.x()+1,b.y()-a.y()+1,b.z()-a.z()+1,"   Offset ",", ",offset.x(),offset.y(),offset.z());}
    }
}
