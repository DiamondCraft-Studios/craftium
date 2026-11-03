package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.client.compat.Overlay;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class SelectionOverlay {
    private static final int STROKE=0xffffd66d,FILL=0x225d91ff,GHOST=0x805d91ff;
    /** Past this many blocks the real models are not drawn, because a frame would have to draw all of them. */
    private static final int MAX_GHOST_BLOCKS=4096;
    private static String encoded="";
    private static OutlineShape shape=OutlineShape.EMPTY;
    private static List<BlockPos> blocks=List.of();
    private SelectionOverlay(){}
    static void update(String data){
        if(Objects.equals(encoded,data))return;encoded=data;
        var points=SelectionCodec.decode(data);
        shape=OutlineShape.of(points);
        blocks=points.size()>MAX_GHOST_BLOCKS?List.of():points.stream().map(p->new BlockPos(p.x(),p.y(),p.z())).toList();
    }
    /**
     * Draws the selection as one outline around its outer surface, with a fill on each exterior face. While the
     * transform gizmo is being dragged only the outline is drawn, moved by the transform being applied.
     */
    static void draw(boolean transforming){
        if(shape.isEmpty())return;
        shape.draw(Vec3.ZERO,transforming?TransformGizmo::line:(a,b)->Overlay.line(a,b,STROKE,1),STROKE,FILL,!transforming);
        if(!transforming&&!blocks.isEmpty())Overlay.blocks(blocks,GHOST);
    }
}
