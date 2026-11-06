package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.server.BuilderEdits;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Client-side prediction for the builder's move, clone and stack: the result is written into the client world the moment
 * the edit is sent, so it shows without waiting for the server. The server's own block updates then confirm it, and a
 * rejected edit puts the blocks back (see {@link #rejected()}).
 *
 * Only edits the client can work out exactly are predicted: no entity copying and no Keep existing, whose outcome
 * depends on the server's view of the world. Anything else is left to the server as before.
 */
public final class BuilderPrediction {
    private BuilderPrediction(){}
    /** Past this many changed blocks the edit is left to the server rather than stalling a frame. */
    private static final int LIMIT=32_768;
    /** Client-only update: no neighbour reactions, so nothing is pushed around on the client that the server would not do. */
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
    private static final long WINDOW_MS=10_000;
    private static Map<BlockPos,BlockState> undo=Map.of();
    private static long since;

    /**
     * Predicts a builder edit before it is sent. {@code cells} are the selection's blocks when only blocks are copied, or
     * else null for the whole box including air; {@code steps} are the offsets the copies land at (one for move and clone).
     */
    static void apply(Options o,Point min,Point max,Collection<Point> cells,List<Point> steps){
        undo=Map.of();
        var tool=o.builderTool;
        if(tool!=Options.BuilderTool.MOVE&&tool!=Options.BuilderTool.CLONE&&tool!=Options.BuilderTool.STACK)return;
        if(o.copyEntities||o.builderKeepExisting)return;
        var mc=Minecraft.getInstance();var level=mc.level;if(level==null||mc.player==null||!mc.player.isCreative())return;
        Point size=max.add(1-min.x(),1-min.y(),1-min.z());
        Collection<Point> points=cells!=null?cells:Selection.box(min,max);
        if((long)points.size()*(steps.size()+1)>LIMIT)return;
        // What the server will do, in its order: read every source block first, clear them for a move, then lay each copy over the last.
        var source=new LinkedHashMap<Point,BlockState>();
        for(Point at:points)source.put(at,level.getBlockState(pos(at)));
        var result=new LinkedHashMap<BlockPos,BlockState>();
        if(tool==Options.BuilderTool.MOVE)for(Point at:points)result.put(pos(at),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        for(Point step:steps)for(var e:source.entrySet()){
            BlockState state=BuilderEdits.turn(e.getValue(),o.builderRotation,o.builderFlipAxis);
            if(!o.pasteAir&&state.isAir())continue;
            Point q=BuilderTransform.apply(e.getKey().subtract(min),size,o.builderRotation,o.builderFlipAxis).add(min.x()+step.x(),min.y()+step.y(),min.z()+step.z());
            result.put(pos(q),state);
        }
        var before=new HashMap<BlockPos,BlockState>();
        for(var e:result.entrySet()){
            var at=e.getKey();
            if(!level.hasChunk(at.getX()>>4,at.getZ()>>4))continue;
            var old=level.getBlockState(at);
            if(old==e.getValue())continue;
            before.put(at,old);
            level.setBlock(at,e.getValue(),FLAGS);
        }
        undo=before;since=System.currentTimeMillis();
    }
    /** The server turned the edit down, so the blocks go back as they were. Only the cells the server has not itself changed since. */
    static void rejected(){
        var level=Minecraft.getInstance().level;
        if(level!=null&&System.currentTimeMillis()-since<WINDOW_MS)for(var e:undo.entrySet())level.setBlock(e.getKey(),e.getValue(),FLAGS);
        undo=Map.of();
    }
    /** The server finished the edit; its updates are the truth from here on. */
    static void confirmed(){undo=Map.of();}
    private static BlockPos pos(Point p){return new BlockPos(p.x(),p.y(),p.z());}
}
