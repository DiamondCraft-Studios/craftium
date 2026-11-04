package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockRunsTest {
    @Test void joinsEqualNeighboursIntoRuns() {
        var runs = new BlockRuns();
        for (String name : new String[]{"stone", "stone", "air", "stone", "stone", "stone"}) runs.add(name);
        assertEquals(java.util.List.of("stone", "air"), runs.palette());
        var out = runs.runs();
        assertEquals(3, out.size());
        assertArrayEquals(new int[]{0, 2}, out.get(0));
        assertArrayEquals(new int[]{1, 1}, out.get(1));
        assertArrayEquals(new int[]{0, 3}, out.get(2));
    }

    @Test void reportsNothingWhenEmpty() {
        var runs = new BlockRuns();
        assertTrue(runs.runs().isEmpty());
        assertTrue(runs.palette().isEmpty());
    }

    @Test void readingTheRunsTwiceDoesNotRepeatThem() {
        var runs = new BlockRuns();
        runs.add("stone");
        runs.add("stone");
        assertEquals(1, runs.runs().size());
        assertEquals(1, runs.runs().size());
    }

    @Test void indexOfSharesThePalette() {
        var runs = new BlockRuns();
        runs.add("stone");
        assertEquals(0, runs.indexOf("stone"));
        assertEquals(1, runs.indexOf(BlockRuns.OUTSIDE));
        assertEquals(java.util.List.of("stone", BlockRuns.OUTSIDE), runs.palette());
    }
}
