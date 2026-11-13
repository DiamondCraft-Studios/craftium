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
    static void update(String value){if(Objects.equals(data,value))return;data=value;var cells=SelectionCodec.decode(value);points=cells.size()<=SCENE_LIMIT?List.copyOf(cells):List.of();shape=OutlineShape.of(cells);sceneFor=null;}
    /** A placement of more blocks than this is drawn as one outline, as working out what the world hides of it costs more than a frame can spare. */
    private static final int SCENE_LIMIT=32_768;
    private static List<Point> points=List.of();
    private static SceneShape scene=SceneShape.EMPTY;
    private static Point sceneAt;private static long sceneSecond;private static Object sceneFor;
    /** The placement as the world hides it, like the selection (see {@link SceneShape}): worked out again when it moves or, once a second, the world changes. Null when it is too large or the world is not there. */
    private static SceneShape sceneAt(){
        var level=net.minecraft.client.Minecraft.getInstance().level;if(level==null||points.isEmpty())return null;
        long second=level.getGameTime()/20;
        if(sceneFor!=points||!origin.equals(sceneAt)||second!=sceneSecond){var moved=new ArrayList<Point>(points.size());for(var p:points)moved.add(p.add(origin.x(),origin.y(),origin.z()));scene=sceneFor==points&&origin.equals(sceneAt)?SceneShape.again(level,moved,scene):SceneShape.of(level,moved,true);sceneFor=points;sceneAt=origin;sceneSecond=second;}
        return scene.tooLarge()?null:scene;
    }
    /** The pending placement as one outline, moved by the transform in progress; filled while nothing is being dragged. */
    static void draw(){
        if(origin==null)return;
        var o=ClientState.options;var hidden=TransformGizmo.dragging()?null:sceneAt(); // a drag moves the lines with the gizmo, so only a placement at rest is depth-tested
        if(hidden!=null)hidden.draw((o.previewFill>>>24)!=0?o.previewFill:o.previewColor&0xffffff|SceneShape.FACE_ALPHA,o.previewColor,(float)o.selectionWidth,o.previewFill);
        else shape.draw(new Vec3(origin.x(),origin.y(),origin.z()),TransformGizmo::shapeLine,o.previewColor,o.previewFill,!TransformGizmo.dragging());
        if(!TransformGizmo.dragging())ghost.draw(origin,false); // the real blocks, so the preview shows what will be placed
    }
    /** When set, confirming the placement also makes a BuildObject of the pasted blocks: its name, and the one it goes after (0 for the end). Set by a duplicate or a paste from the Hierarchy. */
    static String asObject;static int asObjectAfter;
    public static void begin(Point at){
        asObject=null;
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
    static void cancel(){origin=null;asObject=null;}
    static void nudge(int amount){if(origin!=null)origin=origin.add(axis==0?amount:0,axis==1?amount:0,axis==2?amount:0);}
    /** Places the clipboard where the ghost is, and the pasted blocks become the selection, so the transform tools can take hold of them; {@link #pasteCopy} keeps the selection as it is. */
    public static void confirm(){pasteAndSelect();}
    public static void pasteCopy(){if(origin!=null&&!ClientState.status.busy()){BuilderPrediction.paste(ClientState.options,origin,ghost.placed());CraftiumClient.send("paste_at",origin,new Point(0,1,0));}}
    public static void pasteAndSelect(){
        if(origin==null||ClientState.status.busy())return;
        BuilderPrediction.paste(ClientState.options,origin,ghost.placed());
        var o=ClientState.options;String was=o.pasteAsObject;int wasAfter=o.pasteAsObjectAfter;
        if(asObject!=null){o.pasteAsObject=asObject;o.pasteAsObjectAfter=asObjectAfter;} // for this one request only
        try{CraftiumClient.send("paste_select",origin,new Point(0,1,0));}finally{o.pasteAsObject=was;o.pasteAsObjectAfter=wasAfter;}
        origin=null;asObject=null;
    }
    public static void snapToGround(){
        var mc=net.minecraft.client.Minecraft.getInstance();if(origin==null||mc.level==null)return;var bottom=new HashMap<Long,Point>();
        for(Point p:SelectionCodec.decode(data)){long key=((long)p.x()<<32)|(p.z()&0xffffffffL);var old=bottom.get(key);if(old==null||p.y()<old.y())bottom.put(key,p);}
        int drop=256;for(Point p:bottom.values()){int distance=0;for(;distance<drop;distance++){var at=new net.minecraft.core.BlockPos(origin.x()+p.x(),origin.y()+p.y()-distance-1,origin.z()+p.z());if(at.getY()<mc.level.getMinY()||!mc.level.hasChunk(at.getX()>>4,at.getZ()>>4)||!mc.level.getBlockState(at).getCollisionShape(mc.level,at).isEmpty())break;}drop=Math.min(drop,distance);}
        if(!bottom.isEmpty())origin=origin.add(0,-drop,0);
    }
}
