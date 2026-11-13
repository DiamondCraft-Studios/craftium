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
    /**
     * What an AI preview describes. The client draws only as many models as the player allows, but an outline of all of it, so the
     * describing can go further than the drawing; the blocks that show come first, so a cut never leaves a hole in the outer surface.
     */
    static final int AI_LIMIT=49_152;
    /**
     * {@code layout} and {@code names} are only there for a jigsaw preview: one box per piece (x0, y0, z0, x1, y1, z1 and a hue, or -1 for the start piece)
     * and the template each piece comes from. {@code large} says the preview may hold more blocks than a frame can draw, so the client draws only as many
     * models as it allows for AI previews.
     */
    record Ghost(List<String> states,int[] blocks,int[] removed,int total,int[] layout,List<String> names,boolean large){
        Ghost(List<String> states,int[] blocks,int[] removed,int total){this(states,blocks,removed,total,null,null,false);}
    }
    /** The pieces of a jigsaw layout, as {@link Ghost#layout} and {@link Ghost#names} carry them. */
    record Layout(int[] boxes,List<String> names){}

    private GhostCodec(){}

    /** What an edit would place (as models) and remove (as positions). */
    static String encodeEdit(Session.Edit edit){return encodeEdit(edit,LIMIT);}

    /** As {@link #encodeEdit(Session.Edit)}, describing up to {@code limit} placed blocks and as many removed ones. */
    static String encodeEdit(Session.Edit edit,int limit){return Packets.JSON.toJson(describe(edit,limit));}

    /**
     * A jigsaw preview: the layout of its pieces, and the blocks too unless {@code blocks} is false (a layout alone is cheap at any size). It is flagged as large,
     * so the client draws a capped number of models, but it describes up to {@code limit} blocks for the outline.
     */
    static String encodeLayout(Session.Edit edit,int limit,Layout layout,boolean blocks){
        var base=blocks?describe(edit,limit):new Ghost(List.of(),new int[0],new int[0],describe(edit,0).total());
        return Packets.JSON.toJson(new Ghost(base.states(),base.blocks(),base.removed(),base.total(),layout.boxes(),layout.names(),true));
    }

    private static Ghost describe(Session.Edit edit,int limit){
        var palette=new LinkedHashMap<BlockState,Integer>();var blocks=new ArrayList<Integer>();var removed=new ArrayList<Integer>();
        int total=0,placedCount=0;
        for(var change:edit.changes()){
            if(change.before().state()==change.after().state()&&change.after().data()==null)continue;
            total++;if(!change.after().state().isAir())placedCount++;
        }
        // Only when the edit is cut does it matter which blocks are kept: then the ones with a face in the open go first.
        boolean cut=placedCount>limit;
        var placedAt=cut?new it.unimi.dsi.fastutil.longs.LongOpenHashSet(placedCount*2):null;
        if(cut)for(var change:edit.changes())if(!change.after().state().isAir())placedAt.add(key(change.pos()));
        var buried=new ArrayList<Session.Change>();
        for(var change:edit.changes()){
            var before=change.before().state();var after=change.after().state();
            if(before==after&&change.after().data()==null)continue;
            if(after.isAir()){
                if(removed.size()/3<limit){removed.add(change.pos().x());removed.add(change.pos().y());removed.add(change.pos().z());}
            }else if(cut&&hidden(placedAt,change.pos())){buried.add(change);}
            else if(blocks.size()/4<limit)place(blocks,palette,change);
        }
        for(var change:buried){if(blocks.size()/4>=limit)break;place(blocks,palette,change);}
        var states=new ArrayList<String>();for(var state:palette.keySet())states.add(BlockStateParser.serialize(state));
        return new Ghost(states,blocks.stream().mapToInt(Integer::intValue).toArray(),removed.stream().mapToInt(Integer::intValue).toArray(),total);
    }

    private static void place(List<Integer> blocks,Map<BlockState,Integer> palette,Session.Change change){
        blocks.add(change.pos().x());blocks.add(change.pos().y());blocks.add(change.pos().z());blocks.add(palette.computeIfAbsent(change.after().state(),k->palette.size()));
    }
    private static long key(Point p){return net.minecraft.core.BlockPos.asLong(p.x(),p.y(),p.z());}
    /** Whether every neighbour is also placed by the edit, so none of the block's faces can be seen. */
    private static boolean hidden(it.unimi.dsi.fastutil.longs.LongSet placedAt,Point p){
        return placedAt.contains(key(new Point(p.x()+1,p.y(),p.z())))&&placedAt.contains(key(new Point(p.x()-1,p.y(),p.z())))
            &&placedAt.contains(key(new Point(p.x(),p.y()+1,p.z())))&&placedAt.contains(key(new Point(p.x(),p.y()-1,p.z())))
            &&placedAt.contains(key(new Point(p.x(),p.y(),p.z()+1)))&&placedAt.contains(key(new Point(p.x(),p.y(),p.z()-1)));
    }

    /** The blocks a BuildObject keeps out of the world, with their states, at their own cells (not relative to anything), for a preview of dragging it. */
    static String encodeHidden(dev.craftium.core.PackedCells hidden,int limit){
        long[] keys=hidden.keysArray();var states=hidden.paletteList();int[] index=hidden.indexesArray();
        int n=Math.min(keys.length,limit);var blocks=new int[n*4];
        for(int i=0;i<n;i++){var at=dev.craftium.core.PackedCells.unpack(keys[i]);blocks[i*4]=at.x();blocks[i*4+1]=at.y();blocks[i*4+2]=at.z();blocks[i*4+3]=index[i];}
        return Packets.JSON.toJson(new Ghost(new ArrayList<>(states),blocks,new int[0],keys.length));
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
