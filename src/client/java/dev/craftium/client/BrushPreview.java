package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.BrushMath;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The brush under the cursor, as the blocks it would reach, depth-tested so terrain in front of it hides it. Where the brush sits in
 * solid blocks it colours the faces of those blocks you can see, with no outline; where it sits in open air, in front of the terrain,
 * it is the outline of those blocks, drawn like the selection's silhouette, as it is and without its extra edges. Uses the hover preview's colours.
 * It is worked out again when the shape, the block under the cursor or, once a second, the world changes.
 */
public final class BrushPreview {
    /** The preview shows brushes well past what can be built (see Selection.LIMIT), since it is only drawn; beyond this it falls back to the box around the brush. */
    private static final int MAX_BLOCKS=600_000;
    /** The most cells the preview looks at to draw a shape: it is worked out on the frame that needs it, so it gets a good deal less than a build is allowed. A bigger shape is drawn as its box. */
    private static final double PREVIEW_CELLS=24e6;
    private static int shownBlocks;
    private static List<Object> key=List.of();
    private static SceneShape shape=SceneShape.EMPTY;
    private static boolean tooLarge;

    private BrushPreview(){}

    public static void draw(Minecraft mc,BlockPos at,Options o){
        if(mc.level==null)return;
        var now=List.<Object>of(o.tool.brushUse(),o.shape,o.radius,o.hollow,o.outlineThickness,o.pixelPerfect,o.strokeAlign,o.polygonSides,o.polygonSeed,o.starInner,o.outline,o.sizeX,o.sizeY,o.sizeZ,at,shownBlocks>Selection.LIMIT?0:mc.level.getGameTime()/20); // a huge brush is not worked out again every second
        if(!now.equals(key)){key=now;rebuild(mc,at,o);}
        if(tooLarge){Overlay.box(new AABB(at).inflate(o.radius),o.hoverColor,1.5f,0);return;} // too large to work out: the box it fits in
        int fill=o.hoverColor&0xffffff|SceneShape.FACE_ALPHA;
        shape.draw(fill,o.hoverColor,(float)o.selectionWidth,0); // dim faces on the ground, the selection's silhouette in the air
    }

    private static void rebuild(Minecraft mc,BlockPos at,Options o){
        shape=SceneShape.EMPTY;tooLarge=false;
        List<Point> relative;
        // A brush over the block limit is refused by the server too, so there is no set of blocks to show.
        // Each tool is shown the way it builds: the picked shape, a line or path's small shape, or just a disc or a ball of the radius.
        var origin=new Point(0,0,0);
        var use=o.tool.brushUse();
        double cells=use==dev.craftium.core.Tool.BrushUse.SMALL_SHAPE?0:dev.craftium.core.ShapeSizes.isDefault(o)||use==dev.craftium.core.Tool.BrushUse.DISC||use==dev.craftium.core.Tool.BrushUse.SPHERE
            ?dev.craftium.core.ShapeLimits.cells(use==dev.craftium.core.Tool.BrushUse.DISC?BrushMath.Shape.DISK:use==dev.craftium.core.Tool.BrushUse.SPHERE?BrushMath.Shape.SPHERE:o.shape,Math.min(o.outerRadius(),BrushMath.MAX_REACH))
            :Math.pow(2*Math.ceil(o.outerRadius()*Math.max(o.sizeX,Math.max(o.sizeY,o.sizeZ)))+1,3);
        if(cells>PREVIEW_CELLS){tooLarge=true;shownBlocks=MAX_BLOCKS;return;}
        try{
            relative=switch(o.tool.brushUse()){
                case SMALL_SHAPE->BrushMath.brush(origin,Math.min(o.radius,4),o.shape,o.hollow,o.outlineThickness,o.pixelPerfect);
                case DISC->BrushMath.brush(origin,o.radius,BrushMath.Shape.DISK,false,1,false,0,BrushMath.DEFAULT_SIDES,false,MAX_BLOCKS);
                case SPHERE->BrushMath.brush(origin,o.radius,BrushMath.Shape.SPHERE,false,1,false,0,BrushMath.DEFAULT_SIDES,false,MAX_BLOCKS);
                default->dev.craftium.core.ShapeSizes.isDefault(o)?BrushMath.brush(origin,o.radius,o.shape,o.hollow,o.outlineThickness,o.pixelPerfect,o.outlineShift(),o.polygonSides,o.outline,MAX_BLOCKS,o.form()):dev.craftium.core.Geometry.shape(origin,o); // a shape with its own sizes is made the way the server makes it
            };
        }
        catch(IllegalArgumentException e){tooLarge=true;shownBlocks=MAX_BLOCKS;return;}
        shownBlocks=relative.size();
        var absolute=new ArrayList<Point>(relative.size());
        for(Point r:relative)absolute.add(new Point(at.getX()+r.x(),at.getY()+r.y(),at.getZ()+r.z()));
        shape=SceneShape.of(mc.level,absolute);
        tooLarge=shape.tooLarge();
    }
}
