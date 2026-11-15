package dev.craftium.paper;

import static org.junit.jupiter.api.Assertions.*;

import dev.craftium.core.Point;
import dev.craftium.paper.api.CraftiumEditEvent;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProtectionEventTest {
    @Test
    void biomeViewCoversEveryBlockAcrossNegativeCellEdges() {
        var event = new CraftiumEditEvent(
                null, null, List.of(), List.of(new Point(-1, 2, 0), new Point(0, 2, 0)), List.of());
        var blocks = event.getBiomeBlocks();
        assertEquals(128, blocks.size());
        assertEquals(128, new HashSet<>(blocks).size());
        assertTrue(blocks.contains(new Point(-4, 8, 0)));
        assertTrue(blocks.contains(new Point(3, 11, 3)));
        assertFalse(blocks.contains(new Point(4, 8, 0)));
        assertThrows(UnsupportedOperationException.class, () -> blocks.add(new Point(9, 9, 9)));
    }

    @Test
    void freezesInputsAndSupportsCancellationReason() {
        var blocks = new ArrayList<>(List.of(new Point(1, 2, 3)));
        var event = new CraftiumEditEvent(null, null, blocks, List.of(), List.of());
        blocks.clear();
        assertEquals(1, event.getBlocks().size());
        assertFalse(event.isCancelled());
        event.setReason("Private plot");
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertEquals("Private plot", event.getReason());
    }
}
