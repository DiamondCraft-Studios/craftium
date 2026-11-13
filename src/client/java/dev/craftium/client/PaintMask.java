package dev.craftium.client;

import com.mojang.brigadier.StringReader;
import dev.craftium.core.MaskExpression;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.server.BlockMask;
import dev.craftium.server.MissingBlocks;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.Predicate;

/**
 * The tool masks as the server applies them to a painted block, worked out from the client's own view of the world, so the brush preview shows only
 * what a stroke would paint: the height range, the block mask, the mask block and the mask expression. The mask script (Lua) only runs on the
 * server, so it is not part of this. Made fresh for each preview, since it reads the settings as they are then.
 */
final class PaintMask {
    private final Level level;
    private final Options o;
    private final BlockMask blocks;
    private final BlockState maskBlock;
    private final Predicate<Point> expression;
    private final Map<String,BlockState> parsed=new HashMap<>();

    PaintMask(Level level,Options o){
        this.level=level;this.o=o;
        blocks=BlockMask.of(o);
        maskBlock=o.mask.isBlank()?null:parse(o.mask);
        Predicate<Point> compiled;
        try{
            compiled=MaskExpression.compile(o.maskExpression,new MaskExpression.Context(){
                public boolean block(Point p,String s){var expected=parse(s);if(expected==null)return false;var here=state(p);return s.contains("[")?here==expected:here.is(expected.getBlock());}
                public boolean selection(Point p){return SelectionOverlay.contains(pos(p));}
                public boolean sky(Point p){return level.canSeeSky(pos(p));}
                public boolean surface(Point p){return !allSolidAround(p);}
                public double angle(Point p){double x=(solid(p.add(-1,0,0))?1:0)-(solid(p.add(1,0,0))?1:0),y=(solid(p.add(0,-1,0))?1:0)-(solid(p.add(0,1,0))?1:0),z=(solid(p.add(0,0,-1))?1:0)-(solid(p.add(0,0,1))?1:0);double len=Math.sqrt(x*x+y*y+z*z);return len==0?0:Math.toDegrees(Math.acos(y/len));}
            });
        }catch(RuntimeException e){compiled=p->true;} // an expression the server would refuse paints nothing there; the preview just does not apply it
        expression=compiled;
    }

    /** Whether a stroke would paint the block at {@code p}, as far as the masks go. */
    boolean allows(Point p){
        if(p.y()<o.minY||p.y()>o.maxY)return false;
        var s=state(p);
        var missing=MissingBlocks.at(level,pos(p),s);
        if(!blocks.allows(s,missing==null?null:missing.entry()))return false;
        if(maskBlock!=null&&!(o.mask.contains("[")?s==maskBlock:s.is(maskBlock.getBlock())))return false;
        try{return expression.test(p);}catch(RuntimeException e){return true;}
    }

    boolean solid(Point p){var s=state(p);return !s.isAir()&&s.getFluidState().isEmpty();}
    /** Whether all six neighbours are solid: such a block is not on the surface. */
    boolean allSolidAround(Point p){return solid(p.add(1,0,0))&&solid(p.add(-1,0,0))&&solid(p.add(0,1,0))&&solid(p.add(0,-1,0))&&solid(p.add(0,0,1))&&solid(p.add(0,0,-1));}

    private BlockState state(Point p){return level.getBlockState(pos(p));}
    private static BlockPos pos(Point p){return new BlockPos(p.x(),p.y(),p.z());}
    private BlockState parse(String text){
        return parsed.computeIfAbsent(text,t->{
            try{return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK),new StringReader(t),false).blockState();}
            catch(Exception e){return null;}
        });
    }
}
