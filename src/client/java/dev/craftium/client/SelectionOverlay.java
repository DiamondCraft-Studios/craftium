package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.client.compat.Overlay;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class SelectionOverlay {
    /** Past this many blocks the real models are not drawn, because a frame would have to draw all of them. */
    private static final int MAX_GHOST_BLOCKS=4096;
    private static String encoded="";
    private static OutlineShape shape=OutlineShape.EMPTY;
    private static List<BlockPos> blocks=List.of(),moved=List.of();
    private static Point movedBy;
    private static List<Point> selected=List.of();
    /** The selected blocks as last received; replaced, never changed, so the same list means the same selection. */
    static List<Point> points(){return selected;}
    private SelectionOverlay(){}
    static void update(String data){
        if(Objects.equals(encoded,data))return;encoded=data;
        var points=SelectionCodec.decode(data);selected=List.copyOf(points);
        shape=OutlineShape.of(points);
        blocks=points.size()>MAX_GHOST_BLOCKS?List.of():points.stream().map(p->new BlockPos(p.x(),p.y(),p.z())).toList();
    }
    /**
     * Draws the selection as one outline around its outer surface, with a fill on each exterior face. While the
     * transform gizmo is being dragged only the outline is drawn, moved by the transform being applied.
     */
    static void draw(boolean transforming){
        if(shape.isEmpty())return;
        int colour=ClientState.options.selectionColor;float width=(float)ClientState.options.selectionWidth;
        shape.draw(Vec3.ZERO,transforming?TransformGizmo::line:(a,b)->Overlay.line(a,b,colour,width),colour,ClientState.options.selectionFill,!transforming);
        if(blocks.isEmpty())return;
        Point moving=TransformGizmo.moveDelta();
        if(moving!=null){ // the blocks being dragged, shown where they would land
            if(!moving.equals(movedBy)){movedBy=moving;moved=blocks.stream().map(b->b.offset(moving.x(),moving.y(),moving.z())).toList();}
            Overlay.blocks(blocks,moved,ClientState.options.previewTint);
        }else if(!transforming&&!ClientState.hasPending()) // a waiting edit shows its own blocks
            Overlay.blocks(blocks,ClientState.options.selectionTint);
    }
}
