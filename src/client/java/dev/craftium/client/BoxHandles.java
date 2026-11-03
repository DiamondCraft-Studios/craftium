package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Point;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The handles of a box selection: one after the first click, two after the second. The one being worked on gets
 * the interactive gizmo from the editor; the other is shown beside it, and the box between them follows a drag.
 */
final class BoxHandles {
    private static final int BOX=0xffffd66d,CELL=0xffffffff;
    private BoxHandles(){}

    static void draw(EditorScreen screen){
        var corners=ClientState.boxCorners;
        if(corners.isEmpty())return;
        Vec3 live=TransformGizmo.liveNode();
        Point selected=screen.handlePoint();
        var at=new Vec3[corners.size()];
        for(int i=0;i<at.length;i++){
            var c=corners.get(i);at[i]=new Vec3(c.x(),c.y(),c.z());
            if(live!=null&&c.equals(selected)){at[i]=live;} // the corner under the gizmo moves with the drag
            Vec3 cell=new Vec3(Math.floor(at[i].x),Math.floor(at[i].y),Math.floor(at[i].z));
            Overlay.box(new AABB(cell,cell.add(1,1,1)),CELL,2,0x33ffffff);
            if(!corners.get(i).equals(selected))TransformGizmo.display(at[i]);
        }
        if(at.length==2&&live!=null){ // resizing: show the box the two handles make as it changes
            Vec3 a=cornerCell(at[0]),b=cornerCell(at[1]);
            Overlay.box(new AABB(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z),Math.max(a.x,b.x)+1,Math.max(a.y,b.y)+1,Math.max(a.z,b.z)+1),BOX,2,0x225d91ff);
        }
    }
    private static Vec3 cornerCell(Vec3 v){return new Vec3(Math.floor(v.x+.5),Math.floor(v.y+.5),Math.floor(v.z+.5));}
}
