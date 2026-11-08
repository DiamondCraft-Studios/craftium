package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.client.interaction.EditorContext;
import dev.craftium.core.Point;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The handles of a box selection: one after the first click, two after the second. Each sits on the very extremity of the
 * bounds as a white cube. The one being worked on gets the interactive gizmo from the editor; the other is shown beside it.
 * The box is drawn in the selection style: as a single cube after the first click, and as the two handles make it while one is dragged.
 */
public final class BoxHandles {
    private BoxHandles(){}

    private static final java.util.Map<Point,SceneShape> cubeShapes=new java.util.HashMap<>();
    private static long cubeSecond=-1;
    /**
     * One block the way the world hides it: where it sits in solid ground its visible faces are coloured and its edges outlined (hidden where the world is in front), so a block
     * flush in the ground reads as a surface; where it is in open air it is outlined in the given colour.
     */
    private static void cube(Point at,int colour,int fill){
        var level=net.minecraft.client.Minecraft.getInstance().level;if(level==null)return;
        long second=level.getGameTime()/20; // the world can change under a still cursor
        if(second!=cubeSecond||cubeShapes.size()>16){cubeShapes.clear();cubeSecond=second;}
        cubeShapes.computeIfAbsent(at,p->SceneShape.of(level,java.util.List.of(p),true)).draw(colour&0xffffff|((fill>>>24)!=0?fill&0xff000000:SceneShape.FACE_ALPHA),colour,(float)ClientState.options.selectionWidth,fill);
    }

    public static void draw(EditorContext screen){
        var corners=ClientState.boxCorners;
        var o=ClientState.options;
        if(corners.isEmpty()){ // nothing clicked yet: the block a click would start the box on, as a single cube
            var hit=screen.toolActive()?screen.target():null;
            if(hit!=null)cube(CraftiumClient.point(hit.getBlockPos()),o.hoverColor,o.hoverFill); // the Magic select hover colours
            return;
        }
        Vec3 live=TransformGizmo.liveNode();
        Point dragged=TransformGizmo.liveCorner();
        int selected=screen.handleIndex();
        for(int i=0;i<corners.size();i++){
            Vec3 at=live!=null&&i==selected?live:TransformGizmo.cornerAnchor(i); // the corner under the gizmo moves with the drag
            TransformGizmo.cornerCube(at);
            if(i!=selected)TransformGizmo.display(at);
        }
        // The box itself: one cube after the first click that grows to the block aimed at, and the changing box while a handle is dragged. A finished box is the selection.
        Point aimed=null; // after the first click the box follows the block aimed at, until the second click sets it
        if(corners.size()==1&&dragged==null&&screen.toolActive()){var hit=screen.target();if(hit!=null)aimed=CraftiumClient.point(hit.getBlockPos());}
        if(corners.size()==1&&dragged==null&&(aimed==null||aimed.equals(corners.get(0))))cube(corners.get(0),o.selectionColor,o.selectionFill);
        else if(corners.size()==1||dragged!=null){
            Point a=dragged!=null&&selected==0?dragged:corners.get(0),b=corners.size()==1?aimed!=null?aimed:a:dragged!=null&&selected==1?dragged:corners.get(1);
            Overlay.box(new AABB(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z()),Math.max(a.x(),b.x())+1,Math.max(a.y(),b.y())+1,Math.max(a.z(),b.z())+1),o.selectionColor,(float)o.selectionWidth,o.selectionFill);
        }
        if(aimed!=null&&!aimed.equals(corners.get(0)))cube(aimed,o.hoverColor,o.hoverFill); // the block the second click would set, as the first was shown before it
    }
}
