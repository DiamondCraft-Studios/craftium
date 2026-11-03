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
    private BuilderMode(){}
    public static boolean active(){var mc=Minecraft.getInstance();return enabled&&available()&&ClientCompat.screen(mc)==null;}
    private static boolean available(){var mc=Minecraft.getInstance();return mc.player!=null&&mc.player.isCreative()&&mc.level!=null&&CraftiumClient.supported();}
    public static void reset(){enabled=false;clear();}
    private static void clear(){first=null;second=null;preview=false;clonePlaced=false;offset=new Point(0,0,0);cells=List.of();stackX=stackY=stackZ=0;var o=ClientState.options;o.builderRotation=0;o.builderFlipAxis=-1;}
    public static void enable(){if(!available())return;enabled=true;var mc=Minecraft.getInstance();mc.options.keyAttack.setDown(false);mc.options.keyUse.setDown(false);ClientState.notify("Builder: "+name()+" · Alt + wheel changes tool");}
    public static void select(Options.BuilderTool tool){enable();ClientState.options.builderTool=tool;clear();}
    private static String name(){return ClientState.options.builderTool.name().replace('_',' ');}
    public static boolean scroll(double delta){
        if(!available()||ClientCompat.screen(Minecraft.getInstance())!=null)return false;
        var mc=Minecraft.getInstance();wheel+=delta;int steps=(int)wheel;if(steps==0)return enabled;wheel-=steps;
        if(!enabled){int slot=mc.player.getInventory().getSelectedSlot();if(slot==8&&steps<0||slot==0&&steps>0){enable();return true;}return false;}
        if(CraftiumClient.held(CraftiumClient.contextKey)){var all=Options.BuilderTool.values();ClientState.options.builderTool=all[Math.floorMod(ClientState.options.builderTool.ordinal()-steps,all.length)];clear();ClientState.notify("Builder: "+name());return true;}
        if(first==null||second==null){enabled=false;mc.player.getInventory().setSelectedSlot(steps>0?8:0);clear();return true;}
        Direction direction=facing();Point d=new Point(direction.getStepX(),direction.getStepY(),direction.getStepZ());nudge(d,steps);return true;
    }
    public static void nudge(Point axis,int steps){
        if(first==null||second==null)return;preview=true;
        if(ClientState.options.builderTool==Options.BuilderTool.STACK){stackX=Math.clamp(stackX+axis.x()*steps,-16,16);stackY=Math.clamp(stackY+axis.y()*steps,-16,16);stackZ=Math.clamp(stackZ+axis.z()*steps,-16,16);offset=new Point(stackX*(Math.abs(first.x()-second.x())+1),stackY*(Math.abs(first.y()-second.y())+1),stackZ*(Math.abs(first.z()-second.z())+1));}
        else offset=new Point(Math.clamp(offset.x()+axis.x()*steps,-128,128),Math.clamp(offset.y()+axis.y()*steps,-128,128),Math.clamp(offset.z()+axis.z()*steps,-128,128));
    }
    private static Direction facing(){
        var look=Minecraft.getInstance().player.getLookAngle();if(down(GLFW.GLFW_KEY_X))return look.x>=0?Direction.EAST:Direction.WEST;if(down(GLFW.GLFW_KEY_Y))return look.y>=0?Direction.UP:Direction.DOWN;if(down(GLFW.GLFW_KEY_Z))return look.z>=0?Direction.SOUTH:Direction.NORTH;
        return Direction.getApproximateNearest(look.x,look.y,look.z);
    }
    private static boolean down(int key){return com.mojang.blaze3d.platform.InputConstants.isKeyDown(Minecraft.getInstance().getWindow(),key);}
    public static boolean key(int key,int action,boolean control){
        if(!available()||ClientCompat.screen(Minecraft.getInstance())!=null)return false;
        if(key==GLFW.GLFW_KEY_0){if(action==GLFW.GLFW_PRESS)enable();return true;}
        if(!enabled)return false;
        if(CraftiumClient.contextKey.matches(new net.minecraft.client.input.KeyEvent(key,0,0)))return true;
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_9){enabled=false;clear();return false;}
        if(action==GLFW.GLFW_RELEASE)return key==GLFW.GLFW_KEY_X||key==GLFW.GLFW_KEY_Y||key==GLFW.GLFW_KEY_Z;
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
        if(!active()||button>2)return false;if(action!=GLFW.GLFW_PRESS)return true;var mc=Minecraft.getInstance();
        if(CraftiumClient.held(CraftiumClient.contextKey)){
            if(button==0){double x=mc.mouseHandler.xpos()*mc.getWindow().getGuiScaledWidth()/mc.getWindow().getScreenWidth(),y=mc.mouseHandler.ypos()*mc.getWindow().getGuiScaledHeight()/mc.getWindow().getScreenHeight();int left=mc.getWindow().getGuiScaledWidth()/2+100,top=mc.getWindow().getGuiScaledHeight()-205;int row=(int)((y-top)/22);if(x>=left&&x<left+140&&y>=top&&row>=0&&row<Options.BuilderTool.values().length)select(Options.BuilderTool.values()[row]);}return true;
        }BlockHitResult hit=mc.hitResult instanceof BlockHitResult b&&b.getType()==HitResult.Type.BLOCK?b:null;
        if(ClientState.status.busy()){ClientState.notify("Wait for the current edit");return true;}
        var o=ClientState.options;
        if(o.builderTool==Options.BuilderTool.SYMMETRY){if(hit!=null&&button==1){Vec3 at=hit.getLocation();double x=Math.rint(at.x*2)/2-.5,y=Math.rint(at.y*2)/2-.5,z=Math.rint(at.z*2)/2-.5;o.symmetryOrigin=new Point((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));o.symmetryHalfX=x!=Math.floor(x);o.symmetryHalfY=y!=Math.floor(y);o.symmetryHalfZ=z!=Math.floor(z);ClientState.save();ClientState.notify("Symmetry node set · Ctrl+F mirrors · Ctrl+R rotates");}return true;}
        if(o.builderTool==Options.BuilderTool.EXTRUDE){if(hit!=null&&button!=2){boolean prior=o.invert;o.invert=button==0;CraftiumClient.send("builder_extrude",CraftiumClient.point(hit.getBlockPos()),CraftiumClient.normal(hit));o.invert=prior;}return true;}
        if(preview&&button==1){confirm();return true;}
        if(hit==null)return true;Point at=CraftiumClient.point(hit.getBlockPos());
        if(o.builderTool==Options.BuilderTool.ERASE&&first==null&&button==1){CraftiumClient.send("builder_erase_connected",at,CraftiumClient.normal(hit));return true;}
        if(button==0&&clonePlaced){clear();return true;}
        if(button==0){first=at;second=null;preview=false;offset=new Point(0,0,0);cells=List.of();}
        else if(button==1){if(first==null)first=at;second=at;refresh();}
        else if(preview){Point min=min();Point normal=CraftiumClient.normal(hit);offset=at.add(normal.x()-min.x(),normal.y()-min.y(),normal.z()-min.z());}
        else if(first==null){first=at;second=at;refresh();}
        else{Point low=min(),high=max();Direction face=hit.getDirection();int axis=face.getAxis().ordinal();int value=axis==0?at.x():axis==1?at.y():at.z();if(face.getAxisDirection()==Direction.AxisDirection.POSITIVE)high=replace(high,axis,value);else low=replace(low,axis,value);first=low;second=high;refresh();}
        return true;
    }
    private static Point replace(Point p,int axis,int value){return new Point(axis==0?value:p.x(),axis==1?value:p.y(),axis==2?value:p.z());}
    private static Point min(){Point b=second==null?first:second;return new Point(Math.min(first.x(),b.x()),Math.min(first.y(),b.y()),Math.min(first.z(),b.z()));}
    private static Point max(){Point b=second==null?first:second;return new Point(Math.max(first.x(),b.x()),Math.max(first.y(),b.y()),Math.max(first.z(),b.z()));}
    public static void corners(Point a,Point b){first=a;second=b;offset=new Point(0,0,0);preview=false;refresh();}
    private static void refresh(){try{var mc=Minecraft.getInstance();cells=Selection.box(first,second).stream().filter(p->!mc.level.getBlockState(new net.minecraft.core.BlockPos(p.x(),p.y(),p.z())).isAir()).toList();ClientState.notify("Selection ready · scroll to nudge · right-click confirms preview");}catch(Exception e){ClientState.notify(e.getMessage());clear();}}
    public static void confirm(){
        if(first==null||second==null)return;var o=ClientState.options;Point prior1=o.position1,prior2=o.position2;int x=o.offsetX,y=o.offsetY,z=o.offsetZ;o.position1=first;o.position2=second;o.offsetX=offset.x();o.offsetY=offset.y();o.offsetZ=offset.z();CraftiumClient.send("builder_commit",first,new Point(0,1,0));o.position1=prior1;o.position2=prior2;o.offsetX=x;o.offsetY=y;o.offsetZ=z;if(o.builderTool==Options.BuilderTool.CLONE){preview=true;clonePlaced=true;}else clear();
    }
    public static void draw(){
        var o=ClientState.options;if(!active())return;
        if(first!=null){Point min=min(),max=max();Overlay.box(new AABB(min.x(),min.y(),min.z(),max.x()+1,max.y()+1,max.z()+1),0xffffd66d,2,0);
            if(preview){Point size=max.add(1-min.x(),1-min.y(),1-min.z());List<Point> steps=o.builderTool==Options.BuilderTool.SMEAR?BrushMath.line(new Point(0,0,0),offset):o.builderTool==Options.BuilderTool.STACK?java.util.stream.IntStream.rangeClosed(1,o.copies).mapToObj(i->new Point(offset.x()*i,offset.y()*i,offset.z()*i)).toList():List.of(offset);
                var shown=preview(o,min,size,steps);
                if(shown==null)Overlay.box(new AABB(min.x()+offset.x(),min.y()+offset.y(),min.z()+offset.z(),max.x()+offset.x()+1,max.y()+offset.y()+1,max.z()+offset.z()+1),0xff8be3b5,2,0);
                else{shown.shape().draw(Vec3.ZERO,(a,b)->Overlay.line(a,b,0xff8be3b5,1),0xff8be3b5,0x333dd29b,true);if(!shown.places().isEmpty())Overlay.blocks(shown.models(),shown.places(),0x803dd29b);}
            }
        }
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
        Object[] key={min,size,offset,o.builderRotation,o.builderFlipAxis,o.builderTool,o.copies};
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
    public static void hud(Gfx g){
        if(!active())return;var mc=Minecraft.getInstance();int x=g.guiWidth()/2+94,y=g.guiHeight()-23;g.fill(x,y,x+23,y+23,0xff172330);g.outline(x,y,23,23,0xffffd66d);g.text(mc.font,"0",x+9,y+8,0xffffd66d,false);
        if(CraftiumClient.held(CraftiumClient.contextKey)){int left=g.guiWidth()/2+100,top=g.guiHeight()-205;var all=Options.BuilderTool.values();g.fill(left-4,top-20,left+144,top+all.length*22+4,0xf510151c);g.text(mc.font,"Builder tools",left,top-14,0xffdce6ef,false);for(int i=0;i<all.length;i++){g.fill(left,top+i*22,left+140,top+i*22+21,all[i]==ClientState.options.builderTool?0xff315b84:0xff223247);g.text(mc.font,all[i].name().replace('_',' '),left+6,top+i*22+6,0xffdce6ef,false);}}
        String label=name()+"  ·  Hold Alt + wheel: tools";g.text(mc.font,label,g.guiWidth()/2-mc.font.width(label)/2,g.guiHeight()-69,0xffe6cf8b,false);
        String hint=ClientState.options.builderTool==Options.BuilderTool.EXTRUDE?"LMB Shrink  ·  RMB Extrude":ClientState.options.builderTool==Options.BuilderTool.SYMMETRY?"RMB Set node  ·  Ctrl+F Mirror  ·  Ctrl+R Rotate":first==null?"LMB First corner  ·  RMB Second corner  ·  MMB Face":preview?"RMB Confirm  ·  Wheel Nudge  ·  X/Y/Z Axis  ·  Ctrl+R/F Transform":"RMB Second corner  ·  MMB Expand face  ·  Wheel Preview";
        g.text(mc.font,hint,g.guiWidth()/2-mc.font.width(hint)/2,g.guiHeight()-57,0xffc7d6e5,false);
        if(first!=null){Point a=min(),b=max();String info="Size "+(b.x()-a.x()+1)+" × "+(b.y()-a.y()+1)+" × "+(b.z()-a.z()+1)+"   Offset "+offset.coordinates();g.text(mc.font,info,g.guiWidth()/2-mc.font.width(info)/2,g.guiHeight()-81,0xffc7d6e5,false);}
    }
}
