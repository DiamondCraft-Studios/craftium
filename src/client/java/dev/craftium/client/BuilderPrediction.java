package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.server.BuilderEdits;
import dev.craftium.net.Packets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
    /**
     * One edit sent and not yet answered: the blocks it changed as they were and as predicted, and the name the server will
     * report it under. Edits are answered in the order they were sent, so the oldest is the one the next status is about.
     * An edit that was sent but not predicted is kept too, empty, so the order stays in step with the server's.
     */
    private record Pending(Map<BlockPos,BlockState> before,Map<BlockPos,BlockState> after,String name,long since){}
    private static final ArrayDeque<Pending> pending=new ArrayDeque<>();

    /**
     * Predicts a builder edit before it is sent. {@code cells} are the selection's blocks when only blocks are copied, or
     * else null for the whole box including air; {@code steps} are the offsets the copies land at (one for move and clone).
     */
    static void apply(Options o,Point min,Point max,Collection<Point> cells,List<Point> steps){
        var tool=o.builderTool;
        String name=(tool==Options.BuilderTool.ERASE?"Erase":tool.name().toLowerCase(Locale.ROOT))+" builder selection";
        var level=predicting()&&!o.copyEntities&&!o.builderKeepExisting?Minecraft.getInstance().level:null;
        if(level==null){queue(name,Map.of(),Map.of());return;}
        Point size=max.add(1-min.x(),1-min.y(),1-min.z());
        Collection<Point> points=cells!=null?cells:Selection.box(min,max);
        if((long)points.size()*(steps.size()+1)>LIMIT){queue(name,Map.of(),Map.of());return;}
        // What the server will do, in its order: read every source block first, clear them for a move, then lay each copy over the last.
        var source=new LinkedHashMap<Point,BlockState>();
        for(Point at:points)source.put(at,level.getBlockState(pos(at)));
        var result=new LinkedHashMap<BlockPos,BlockState>();
        if(tool==Options.BuilderTool.MOVE||tool==Options.BuilderTool.ERASE)for(Point at:points)result.put(pos(at),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        if(tool!=Options.BuilderTool.ERASE)for(Point step:steps)for(var e:source.entrySet()){
            BlockState state=BuilderEdits.turn(e.getValue(),o.builderRotation,o.builderFlipAxis);
            if(!o.pasteAir&&state.isAir())continue;
            Point q=BuilderTransform.apply(e.getKey().subtract(min),size,o.builderRotation,o.builderFlipAxis).add(min.x()+step.x(),min.y()+step.y(),min.z()+step.z());
            result.put(pos(q),state);
        }
        write(level,name,result);
    }
    /**
     * Predicts pasting the clipboard at {@code origin}. {@code clipboard} is the blocks the placement preview holds (null when
     * it cannot say exactly). Left to the server when the paste depends on anything the client cannot see: air that clears,
     * entities, merging, symmetry or Keep existing.
     */
    static void paste(Options o,Point origin,Map<Point,BlockState> clipboard){
        String name="Paste clipboard";
        boolean exact=clipboard!=null&&!o.pasteAir&&!o.mergeBlocks&&!o.keepExisting&&!o.symmetryX&&!o.symmetryY&&!o.symmetryZ&&o.symmetryRotations==1
            &&!(o.pasteEntities&&ClientState.status.clipboardEntityCount()>0)&&clipboard.size()<=LIMIT;
        var level=exact&&predicting()?Minecraft.getInstance().level:null;
        if(level==null){queue(name,Map.of(),Map.of());return;}
        var result=new LinkedHashMap<BlockPos,BlockState>();
        for(var e:clipboard.entrySet()){var at=e.getKey();result.put(pos(origin.add(at.x(),at.y(),at.z())),e.getValue());}
        write(level,name,result);
    }
    private static boolean predicting(){var mc=Minecraft.getInstance();return mc.level!=null&&mc.player!=null&&mc.player.isCreative()&&direct();}
    private static void write(net.minecraft.client.multiplayer.ClientLevel level,String name,Map<BlockPos,BlockState> result){
        var before=new HashMap<BlockPos,BlockState>();var after=new HashMap<BlockPos,BlockState>();
        for(var e:result.entrySet()){
            var at=e.getKey();
            if(!level.hasChunk(at.getX()>>4,at.getZ()>>4))continue;
            var old=level.getBlockState(at);
            if(old==e.getValue())continue;
            before.put(at,old);after.put(at,e.getValue());
            level.setBlock(at,e.getValue(),FLAGS);
        }
        queue(name,before,after);
    }
    private static void queue(String name,Map<BlockPos,BlockState> before,Map<BlockPos,BlockState> after){
        expire();pending.addLast(new Pending(before,after,name,System.currentTimeMillis()));
    }
    /** An edit the server never answered (a request it dropped) is forgotten, so it does not stand in front of the ones after it. */
    private static void expire(){
        long now=System.currentTimeMillis();
        while(!pending.isEmpty()&&now-pending.peekFirst().since()>WINDOW_MS)pending.removeFirst();
    }
    /** The server turned down the oldest unanswered edit, so its blocks go back as they were. */
    static void rejected(){reject(false);}
    /** The server turned down the newest edit without running it (its queue was full). */
    static void rejectedNewest(){reject(true);}
    private static void reject(boolean newest){
        expire();
        var rejected=newest?pending.pollLast():pending.pollFirst();
        var level=Minecraft.getInstance().level;
        if(rejected==null||level==null)return;
        for(var e:rejected.before().entrySet()){
            var at=e.getKey();
            // A later edit that predicted over this cell now stands on what this one left: it should go back to the original, not to this edit's blocks.
            boolean covered=false;
            for(var later:pending)if(later.before().containsKey(at)){later.before().put(at,e.getValue());covered=true;}
            if(!covered&&level.getBlockState(at)==rejected.after().get(at))level.setBlock(at,e.getValue(),FLAGS);
        }
    }
    /** The server finished an edit; its updates are the truth from here on. {@code message} is its status. */
    static void confirmed(String message){
        expire();
        for(var it=pending.iterator();it.hasNext();)if(message.startsWith("Applied "+it.next().name())){it.remove();return;}
    }
    /**
     * Only where the edit goes straight to Craftium on the server. Through the Paper companion (the bridge) the server can hold
     * region and world-border protection the client cannot see, so a predicted edit there would show and then snap back.
     */
    private static boolean direct(){return ClientPlayNetworking.canSend(Packets.Edit.TYPE);}
    private static BlockPos pos(Point p){return new BlockPos(p.x(),p.y(),p.z());}
}
