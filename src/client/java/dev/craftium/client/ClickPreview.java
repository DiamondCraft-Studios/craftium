package dev.craftium.client;

import dev.craftium.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Outlines what an Extrude or Floodfill click would change, as the cursor moves, as a selection is outlined; a right click then builds it. It runs
 * the very search the server builds from ({@link SurfaceFill}), stopping at a cap so hovering stays cheap on a large face or an open space.
 */
final class ClickPreview {
    private static final int CAP=20_000,VISITS=60_000;
    private static Object key;
    private static OutlineShape shape=OutlineShape.EMPTY;
    private static boolean removing;
    private ClickPreview(){}

    static void draw(Minecraft mc,BlockHitResult hit){
        var o=ClientState.options;var tool=o.tool;
        if(hit==null||mc.level==null||tool!=Tool.EXTRUDE&&tool!=Tool.FLOODFILL||TransformGizmo.dragging()||PlacementPreview.active()||ClientState.hasPending()){key=null;shape=OutlineShape.EMPTY;return;}
        BlockPos at=hit.getBlockPos();Point normal=CraftiumClient.normal(hit);
        // The world can change under a still cursor, so the result is also refreshed about once a second.
        Object now=List.of(tool,at,normal,o.compare,o.selectLimit,o.strength,o.invert,o.floodLimit,o.floodUp,o.floodDown,o.floodHorizontal,o.floodCorners,mc.level.getGameTime()/20);
        if(!Objects.equals(key,now)){key=now;compute(mc,at,normal,o);}
        if(shape.isEmpty())return;
        int colour=removing?o.removedColor:o.hoverColor,fill=removing?o.removedFill:o.hoverFill;
        shape.drawEmphasised(Vec3.ZERO,colour,(float)o.selectionWidth,fill,true,false);
    }

    private static void compute(Minecraft mc,BlockPos at,Point normal,Options o){
        shape=OutlineShape.EMPTY;removing=false;
        var level=mc.level;Point centre=CraftiumClient.point(at);
        if(o.tool==Tool.EXTRUDE){
            if(level.getBlockState(at).isAir())return;
            var compare=o.compare==Options.Compare.NBT?Options.Compare.BLOCK:o.compare; // block entity data is not all known here
            var world=MagicPreview.world(mc,at,o,compare,false);
            var face=SurfaceFill.face(new SurfaceFill.Cells(){
                public boolean air(Point p){return world.air(p);}
                public boolean matches(Point p){return world.matches(p);}
            },centre,normal,Math.min(o.selectLimit,CAP));
            int count=Math.max(1,(int)o.strength);removing=o.invert;
            var cells=new LinkedHashSet<Point>();
            for(Point p:face)for(int step=1;step<=count;step++){int s=o.invert?1-step:step;cells.add(p.add(normal.x()*s,normal.y()*s,normal.z()*s));}
            shape=OutlineShape.of(cells);
        }else{
            Point start=level.getBlockState(at).isAir()?centre:centre.add(normal.x(),normal.y(),normal.z());
            var matching=level.getBlockState(new BlockPos(start.x(),start.y(),start.z()));
            var cells=SurfaceFill.flood(new SurfaceFill.Cells(){
                public boolean air(Point p){return level.getBlockState(new BlockPos(p.x(),p.y(),p.z())).isAir();}
                public boolean matches(Point p){return level.getBlockState(new BlockPos(p.x(),p.y(),p.z()))==matching;}
            },start,Math.min(o.floodLimit,CAP),VISITS,o.floodUp,o.floodDown,o.floodHorizontal,o.floodCorners);
            shape=OutlineShape.of(cells);
        }
    }
}
