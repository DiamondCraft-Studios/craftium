package dev.craftium.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoUpdatesTest {
    @Test void editsNeverNotifyNeighboursAndSkipTheBlocksOwnPlacement() {
        int flags = BlockSnapshot.NO_UPDATES;
        assertEquals(0, flags & 1, "no neighbour is told of the change");
        assertEquals(2, flags & 2, "clients still see the block");
        assertEquals(16, flags & 16, "no neighbour's shape is recalculated");
        assertEquals(32, flags & 32, "nothing drops");
        assertEquals(512, flags & 512, "the block's own onPlace, which schedules falling, flowing and redstone, is skipped");
    }
}
