package dev.craftium.server;

import dev.craftium.core.PackedCells;
import dev.craftium.core.Point;
import dev.craftium.net.Packets;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The hidden blocks of a BuildObject sent with their block states, to preview a drag with the blocks themselves. */
class GhostCodecHiddenTest {
    private record Ghost(List<String> states,int[] blocks,int[] removed,int total){}

    @Test void theBlocksAreSentAtTheirOwnCellsWithTheirStates(){
        var hidden=new PackedCells();hidden.put(new Point(5,-3,7),"minecraft:stone");hidden.put(new Point(-2,10,0),"minecraft:oak_stairs[facing=north]");hidden.put(new Point(6,-3,7),"minecraft:stone");
        var ghost=Packets.JSON.fromJson(GhostCodec.encodeHidden(hidden,100),Ghost.class);
        assertEquals(3,ghost.total());assertEquals(12,ghost.blocks().length);
        var seen=new HashMap<Point,String>();
        for(int i=0;i<ghost.blocks().length;i+=4)seen.put(new Point(ghost.blocks()[i],ghost.blocks()[i+1],ghost.blocks()[i+2]),ghost.states().get(ghost.blocks()[i+3]));
        assertEquals(Map.of(new Point(5,-3,7),"minecraft:stone",new Point(6,-3,7),"minecraft:stone",new Point(-2,10,0),"minecraft:oak_stairs[facing=north]"),seen);
        assertEquals(2,ghost.states().size(),"each state once");
    }
    @Test void moreThanTheLimitAreCountedButNotDescribed(){
        var hidden=new PackedCells();for(int i=0;i<10;i++)hidden.put(new Point(i,0,0),"minecraft:stone");
        var ghost=Packets.JSON.fromJson(GhostCodec.encodeHidden(hidden,4),Ghost.class);
        assertEquals(10,ghost.total());assertEquals(16,ghost.blocks().length,"four blocks of four numbers: the client can tell that it was cut short");
    }
}
