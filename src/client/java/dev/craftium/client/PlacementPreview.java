package dev.craftium.client;

import dev.craftium.core.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A pending placement never changes the world until the user confirms it. */
public final class PlacementPreview {
    public static Point origin;
    static int axis;
    private static String data="";
    static OutlineShape shape=OutlineShape.EMPTY;
    private PlacementPreview(){}
    static void update(String value){if(Objects.equals(data,value))return;data=value;shape=OutlineShape.of(SelectionCodec.decode(value));}
    /** The pending placement as one outline, moved by the transform in progress; filled while nothing is being dragged. */
    static void draw(){
        if(origin==null)return;
        shape.draw(new Vec3(origin.x(),origin.y(),origin.z()),TransformGizmo::line,0xff8be3b5,0x2247d29b,!TransformGizmo.dragging());
    }
    public static void begin(Point at){
        if(ClientState.status.clipboardSize()==0&&ClientState.status.clipboardEntityCount()==0){ClientState.notify("Copy a selection or load a blueprint first");return;}
        origin=at;axis=0;ClientState.notify("Placement ready · drag an axis · arrows / Page Up / Page Down · Enter to place · Esc to cancel");
    }
    public static boolean active(){return origin!=null;}
    static void cancel(){origin=null;}
    static void nudge(int amount){if(origin!=null)origin=origin.add(axis==0?amount:0,axis==1?amount:0,axis==2?amount:0);}
    public static void confirm(){if(origin!=null&&!ClientState.status.busy()){CraftiumClient.send("paste_at",origin,new Point(0,1,0));origin=null;}}
    public static void pasteCopy(){if(origin!=null&&!ClientState.status.busy())CraftiumClient.send("paste_at",origin,new Point(0,1,0));}
    public static void pasteAndSelect(){if(origin!=null&&!ClientState.status.busy()){CraftiumClient.send("paste_select",origin,new Point(0,1,0));origin=null;}}
    public static void snapToGround(){
        var mc=net.minecraft.client.Minecraft.getInstance();if(origin==null||mc.level==null)return;var bottom=new HashMap<Long,Point>();
        for(Point p:SelectionCodec.decode(data)){long key=((long)p.x()<<32)|(p.z()&0xffffffffL);var old=bottom.get(key);if(old==null||p.y()<old.y())bottom.put(key,p);}
        int drop=256;for(Point p:bottom.values()){int distance=0;for(;distance<drop;distance++){var at=new net.minecraft.core.BlockPos(origin.x()+p.x(),origin.y()+p.y()-distance-1,origin.z()+p.z());if(at.getY()<mc.level.getMinY()||!mc.level.hasChunk(at.getX()>>4,at.getZ()>>4)||!mc.level.getBlockState(at).getCollisionShape(mc.level,at).isEmpty())break;}drop=Math.min(drop,distance);}
        if(!bottom.isEmpty())origin=origin.add(0,-drop,0);
    }
}
