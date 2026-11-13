package dev.craftium.client;

import dev.craftium.core.FluidFlow;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;

/**
 * Fluid Ball's and Fluid Block's hover: the blocks a click would place, found by running the same flow the server builds from
 * ({@link FluidFlow}) over the blocks the player sees, drawn like the other hover previews. It is worked out again when the cursor, the
 * tool's settings or, once a second, the world changes.
 */
public final class FluidPreview {
    private static List<Object> key=List.of();
    private static SceneShape shape=SceneShape.EMPTY;
    private FluidPreview(){}

    public static boolean handles(Tool tool){return tool==Tool.FLUID_BALL||tool==Tool.FLUID_BLOCK;}

    public static void draw(Minecraft mc,BlockHitResult hit,Options o){
        if(mc.level==null||hit==null)return;
        BlockPos at=hit.getBlockPos();Point normal=CraftiumClient.normal(hit);
        var now=List.<Object>of(o.tool,at,normal,o.radius,o.flowLength,o.fluidType,o.fluidQuality,o.requireSupport,o.fillEdges,o.infiniteSources,o.removeFluidsWithoutSources,mc.level.getGameTime()/20);
        if(!now.equals(key)){key=now;rebuild(mc,at,normal,o);}
        shape.draw(o.hoverColor&0xffffff|SceneShape.FACE_ALPHA,o.hoverColor,(float)o.selectionWidth,0);
    }

    private static void rebuild(Minecraft mc,BlockPos at,Point normal,Options o){
        var level=mc.level;var placed=new LinkedHashSet<Point>();var solidPlaced=new HashSet<Point>();
        boolean block=o.tool==Tool.FLUID_BLOCK;
        var cells=new FluidFlow.Cells(){
            public boolean solid(Point p){
                if(solidPlaced.contains(p))return true;
                var s=level.getBlockState(new BlockPos(p.x(),p.y(),p.z()));return !s.isAir()&&s.getFluidState().isEmpty();
            }
            public int minY(){return level.getMinY();}
            public void fluid(Point p,int l){placed.add(p);}
            public void snow(Point p,int l){placed.add(p);solidPlaced.add(p);}
            public void pour(Point p){placed.add(p);solidPlaced.add(p);}
        };
        try{
            Point origin=CraftiumClient.point(at).add(normal.x(),normal.y(),normal.z());
            if(block)FluidFlow.powder(cells,o,origin);else FluidFlow.ball(cells,o,origin);
            shape=SceneShape.of(level,placed);
        }catch(IllegalArgumentException e){shape=SceneShape.EMPTY;} // over the limit, as the server would refuse it
    }
}
