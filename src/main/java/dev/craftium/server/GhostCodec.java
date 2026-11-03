package dev.craftium.server;

import dev.craftium.core.Point;
import dev.craftium.net.Packets;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Describes blocks that are not in the world yet, so the client can draw them as a ghost: a palette of block
 * states and, for each block, its position and palette index. Only the first {@link #LIMIT} blocks are described,
 * with {@code total} saying how many there really are.
 */
final class GhostCodec {
    /** More blocks than this are not drawn as models, because a frame would have to draw all of them. */
    static final int LIMIT=4096;
    record Ghost(List<String> states,int[] blocks,int[] removed,int total){}

    private GhostCodec(){}

    /** What an edit would place (as models) and remove (as positions). */
    static String encodeEdit(Session.Edit edit){
        var palette=new LinkedHashMap<BlockState,Integer>();var blocks=new ArrayList<Integer>();var removed=new ArrayList<Integer>();
        int total=0;
        for(var change:edit.changes()){
            var before=change.before().state();var after=change.after().state();
            if(before==after&&change.after().data()==null)continue;
            total++;
            boolean placed=!after.isAir();
            if(placed&&blocks.size()/4<LIMIT){blocks.add(change.pos().x());blocks.add(change.pos().y());blocks.add(change.pos().z());blocks.add(palette.computeIfAbsent(after,k->palette.size()));}
            else if(!placed&&removed.size()/3<LIMIT){removed.add(change.pos().x());removed.add(change.pos().y());removed.add(change.pos().z());}
        }
        return json(palette,blocks,removed,total);
    }

    /** The clipboard's non-air blocks, positioned relative to where it will be pasted. */
    static String encodeClipboard(Map<Point,BlockSnapshot> clipboard){
        var palette=new LinkedHashMap<BlockState,Integer>();var blocks=new ArrayList<Integer>();int total=0;
        for(var entry:clipboard.entrySet()){
            var state=entry.getValue().state();if(state.isAir())continue;
            total++;
            if(blocks.size()/4<LIMIT){var at=entry.getKey();blocks.add(at.x());blocks.add(at.y());blocks.add(at.z());blocks.add(palette.computeIfAbsent(state,k->palette.size()));}
        }
        return json(palette,blocks,List.of(),total);
    }

    private static String json(LinkedHashMap<BlockState,Integer> palette,List<Integer> blocks,List<Integer> removed,int total){
        var states=new ArrayList<String>();for(var state:palette.keySet())states.add(BlockStateParser.serialize(state));
        return Packets.JSON.toJson(new Ghost(states,blocks.stream().mapToInt(Integer::intValue).toArray(),removed.stream().mapToInt(Integer::intValue).toArray(),total));
    }
}
