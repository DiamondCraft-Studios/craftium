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
    private static GhostBlocks ghost=GhostBlocks.EMPTY;
    static void updateGhost(String json){ghost=GhostBlocks.parse(json);}
    private PlacementPreview(){}
    static void update(String value){if(Objects.equals(data,value))return;data=value;shape=OutlineShape.of(SelectionCodec.decode(value));}
    /** The pending placement as one outline, moved by the transform in progress; filled while nothing is being dragged. */
    static void draw(){
        if(origin==null)return;
        shape.draw(new Vec3(origin.x(),origin.y(),origin.z()),TransformGizmo::line,ClientState.options.previewColor,ClientState.options.previewFill,!TransformGizmo.dragging());
        if(!TransformGizmo.dragging())ghost.draw(origin,false); // the real blocks, so the preview shows what will be placed
    }
    public static void begin(Point at){
        if(ClientState.status.clipboardSize()==0&&ClientState.status.clipboardEntityCount()==0){ClientState.notify("Copy a selection or load a blueprint first");return;}
        origin=at;axis=0;ClientState.notify("Placement ready · drag an axis · arrows / Page Up / Page Down · Enter to place · Esc to cancel");
    }
    /** Starts a placement where the clipboard's blocks were copied from, ready to nudge and confirm; nothing is built until Enter. */
    public static void beginInPlace(){
        var from=ClientState.status.clipboardOrigin();
        if(from==null){ClientState.notify("These blocks were not copied from the world, so there is no place to put them back to");return;}
        begin(from);
    }
    public static boolean active(){return origin!=null;}
    static void cancel(){origin=null;}
    static void nudge(int amount){if(origin!=null)origin=origin.add(axis==0?amount:0,axis==1?amount:0,axis==2?amount:0);}
    /** Places the clipboard where the ghost is, and the pasted blocks become the selection, so the transform tools can take hold of them; {@link #pasteCopy} keeps the selection as it is. */
    public static void confirm(){pasteAndSelect();}
    public static void pasteCopy(){if(origin!=null&&!ClientState.status.busy()){BuilderPrediction.paste(ClientState.options,origin,ghost.placed());CraftiumClient.send("paste_at",origin,new Point(0,1,0));}}
    public static void pasteAndSelect(){if(origin!=null&&!ClientState.status.busy()){BuilderPrediction.paste(ClientState.options,origin,ghost.placed());CraftiumClient.send("paste_select",origin,new Point(0,1,0));origin=null;}}
    public static void snapToGround(){
        var mc=net.minecraft.client.Minecraft.getInstance();if(origin==null||mc.level==null)return;var bottom=new HashMap<Long,Point>();
        for(Point p:SelectionCodec.decode(data)){long key=((long)p.x()<<32)|(p.z()&0xffffffffL);var old=bottom.get(key);if(old==null||p.y()<old.y())bottom.put(key,p);}
        int drop=256;for(Point p:bottom.values()){int distance=0;for(;distance<drop;distance++){var at=new net.minecraft.core.BlockPos(origin.x()+p.x(),origin.y()+p.y()-distance-1,origin.z()+p.z());if(at.getY()<mc.level.getMinY()||!mc.level.hasChunk(at.getX()>>4,at.getZ()>>4)||!mc.level.getBlockState(at).getCollisionShape(mc.level,at).isEmpty())break;}drop=Math.min(drop,distance);}
        if(!bottom.isEmpty())origin=origin.add(0,-drop,0);
    }
}
