package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Point;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The handles of a box selection: one after the first click, two after the second. Each sits on the very extremity of the
 * bounds as a white cube. The one being worked on gets the interactive gizmo from the editor; the other is shown beside it.
 * The box is drawn in the selection style: as a single cube after the first click, and as the two handles make it while one is dragged.
 */
final class BoxHandles {
    private BoxHandles(){}

    static void draw(EditorScreen screen){
        var corners=ClientState.boxCorners;
        var o=ClientState.options;
        if(corners.isEmpty()){ // nothing clicked yet: the block a click would start the box on, as a single cube
            var hit=screen.toolActive()?screen.target():null;
            if(hit!=null)Overlay.box(new AABB(hit.getBlockPos()),o.hoverColor,(float)o.selectionWidth,o.hoverFill); // the Magic select hover colours
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
        // The box itself: one cube after the first click, and the changing box while a handle is dragged. A finished box is the selection.
        if(corners.size()==1||dragged!=null){
            Point a=dragged!=null&&selected==0?dragged:corners.get(0),b=corners.size()==1?a:dragged!=null&&selected==1?dragged:corners.get(1);
            Overlay.box(new AABB(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z()),Math.max(a.x(),b.x())+1,Math.max(a.y(),b.y())+1,Math.max(a.z(),b.z())+1),o.selectionColor,(float)o.selectionWidth,o.selectionFill);
        }
    }
}
