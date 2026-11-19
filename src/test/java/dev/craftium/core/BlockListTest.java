package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockListTest {
    @Test
    void aListComesBackAsItWent() {
        var points = List.of(
                new Point(0, 0, 0),
                new Point(1, 0, 0),
                new Point(-30_000_000, -64, 29_999_999),
                new Point(5, 319, -5),
                new Point(5, 319, -5));
        assertEquals(points, BlockList.decode(BlockList.encode(points), 100));
    }

    @Test
    void noBlocksIsAnEmptyText() {
        assertEquals("", BlockList.encode(List.of()));
        assertTrue(BlockList.decode("", 10).isEmpty());
        assertTrue(BlockList.decode(null, 10).isEmpty());
    }

    @Test
    void neighbouringBlocksAreCheap() {
        var points = new ArrayList<Point>();
        for (int i = 0; i < 1000; i++) points.add(new Point(100 + i % 30, 64 + i % 3, 200 + i / 30));
        assertTrue(BlockList.encode(points).length() < 4 * 1000 + 8, "three bytes a block at most for small steps");
    }

    @Test
    void moreThanTheLimitIsRefused() {
        var text = BlockList.encode(List.of(new Point(1, 1, 1), new Point(2, 2, 2), new Point(3, 3, 3)));
        assertEquals(3, BlockList.decode(text, 3).size());
        assertThrows(IllegalArgumentException.class, () -> BlockList.decode(text, 2));
    }

    @Test
    void textThatIsNotAListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BlockList.decode("!!!", 10));
        assertThrows(IllegalArgumentException.class, () -> BlockList.decode("gA", 10), "a number cut short");
        assertThrows(IllegalArgumentException.class, () -> BlockList.decode("AAA", 10), "a block cut short");
    }
}
