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
final class BrushPreview {
    /** More exposed faces than this are not coloured one by one; the outline of the whole brush is shown instead. */
    private static final int MAX_FACES=65_536;
    /** The preview shows brushes well past what can be built (see Selection.LIMIT), since it is only drawn; beyond this it falls back to the box around the brush. */
    private static final int MAX_BLOCKS=600_000;
    private static int shownBlocks;
    private static final float LIFT=.003f;
    private static List<Object> key=List.of();
    private static OutlineShape air=OutlineShape.EMPTY;
    private static List<Vec3[]> faces=List.of();
    private static boolean tooLarge;

    private BrushPreview(){}

    static void draw(Minecraft mc,BlockPos at,Options o){
        if(mc.level==null)return;
        var now=List.<Object>of(o.tool.brushUse(),o.shape,o.radius,o.hollow,o.outlineThickness,o.pixelPerfect,o.strokeAlign,o.polygonSides,o.outline,at,shownBlocks>Selection.LIMIT?0:mc.level.getGameTime()/20); // a huge brush is not worked out again every second
        if(!now.equals(key)){key=now;rebuild(mc,at,o);}
        if(tooLarge){Overlay.box(new AABB(at).inflate(o.radius),o.hoverColor,1.5f,0);return;} // too large to work out: the box it fits in
        int fill=o.hoverColor&0xffffff|0x66000000;
        for(var f:faces)Overlay.sceneFace(f[0],f[1],f[2],f[3],fill);
        air.drawPlain(Vec3.ZERO,o.hoverColor,(float)o.selectionWidth,0,false,true); // the selection's silhouette as it is, depth-tested
    }

    private static void rebuild(Minecraft mc,BlockPos at,Options o){
        air=OutlineShape.EMPTY;faces=List.of();tooLarge=false;
        List<Point> relative;
        // A brush over the block limit is refused by the server too, so there is no set of blocks to show.
        // Each tool is shown the way it builds: the picked shape, a line or path's small shape, or just a disc or a ball of the radius.
        var origin=new Point(0,0,0);
        try{
            relative=switch(o.tool.brushUse()){
                case SMALL_SHAPE->BrushMath.brush(origin,Math.min(o.radius,4),o.shape,o.hollow,o.outlineThickness,o.pixelPerfect);
                case DISC->BrushMath.brush(origin,o.radius,BrushMath.Shape.DISK,false,1,false,0,BrushMath.DEFAULT_SIDES,false,MAX_BLOCKS);
                case SPHERE->BrushMath.brush(origin,o.radius,BrushMath.Shape.SPHERE,false,1,false,0,BrushMath.DEFAULT_SIDES,false,MAX_BLOCKS);
                default->BrushMath.brush(origin,o.radius,o.shape,o.hollow,o.outlineThickness,o.pixelPerfect,o.outlineShift(),o.polygonSides,o.outline,MAX_BLOCKS);
            };
        }
        catch(IllegalArgumentException e){tooLarge=true;shownBlocks=MAX_BLOCKS;return;}
        shownBlocks=relative.size();
        var level=mc.level;var open=new ArrayList<Point>();var solid=new HashSet<Point>();
        for(Point r:relative){
            Point p=new Point(at.getX()+r.x(),at.getY()+r.y(),at.getZ()+r.z());
            if(level.getBlockState(new BlockPos(p.x(),p.y(),p.z())).isAir())open.add(p);else solid.add(p);
        }
        var found=new ArrayList<Vec3[]>();
        for(Point p:solid)for(var d:net.minecraft.core.Direction.values()){
            if(!level.getBlockState(new BlockPos(p.x()+d.getStepX(),p.y()+d.getStepY(),p.z()+d.getStepZ())).isAir())continue; // buried faces cannot be seen
            found.add(face(p,d));
            if(found.size()>MAX_FACES){tooLarge=true;return;}
        }
        faces=found;
        air=OutlineShape.of(open);
    }

    /** The face of the block at {@code p} on the side {@code d} points to, lifted off the block so it does not fight it for depth, counter-clockwise from outside. */
    private static Vec3[] face(Point p,net.minecraft.core.Direction d){
        int axis=d.getAxis().ordinal();boolean positive=d.getAxisDirection()==net.minecraft.core.Direction.AxisDirection.POSITIVE;
        double[] base={p.x(),p.y(),p.z()};base[axis]+=positive?1+LIFT:-LIFT;
        int u=(axis+1)%3,v=(axis+2)%3;
        if(!positive){int t=u;u=v;v=t;} // the other way round faces the other way
        return new Vec3[]{at(base,0,0,u,v),at(base,1,0,u,v),at(base,1,1,u,v),at(base,0,1,u,v)};
    }
    private static Vec3 at(double[] base,int a,int b,int u,int v){double[] c=base.clone();c[u]+=a;c[v]+=b;return new Vec3(c[0],c[1],c[2]);}
}
