package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.BrushMath;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * The brush under the cursor, drawn as the blocks it would reach: the outline of the very block set the server builds, in the style
 * {@link Options#brushPreviewStyle} picks (Outline by default). It replaces the smooth wireframe sphere, which showed the radius but
 * not which blocks were affected. The outline is kept and only rebuilt when the shape or its size changes.
 */
final class BrushPreview {
    private static final int COLOUR=0xffd6eaff;
    private static List<Object> key=List.of();
    private static OutlineShape shape=OutlineShape.EMPTY;

    private BrushPreview(){}

    static void draw(BlockPos at,Options o){
        var now=List.<Object>of(o.shape,o.radius,o.hollow,o.outlineThickness,o.pixelPerfect,o.strokeAlign,o.polygonSides,o.outline);
        if(!now.equals(key)){
            key=now;
            // A brush over the block limit is refused by the server too, so there is no set of blocks to outline.
            try{shape=OutlineShape.of(BrushMath.brush(new Point(0,0,0),o.radius,o.shape,o.hollow,o.outlineThickness,o.pixelPerfect,o.outlineShift(),o.polygonSides,o.outline));}
            catch(IllegalArgumentException e){shape=OutlineShape.EMPTY;}
        }
        if(shape.isEmpty()){Overlay.box(new AABB(at).inflate(o.radius),COLOUR,1.5f,0);return;} // too large to outline: the box it fits in
        shape.draw(new Vec3(at.getX(),at.getY(),at.getZ()),(a,b)->Overlay.line(a,b,COLOUR,1.2f),COLOUR,0,false,o.brushPreviewStyle);
    }
}
