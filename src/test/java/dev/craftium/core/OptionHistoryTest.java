package dev.craftium.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The example in the design: paste A, radius B, fill C, shape D; Ctrl+Z undoes D, C, B, A in that order and Ctrl+Y brings them back in the other. */
class OptionHistoryTest {
    private static OptionHistory.Entry entry(String name, long time) {
        return new OptionHistory.Entry(name, Map.of("x", 1), Map.of("x", 2), time);
    }

    @Test void undoesInTheOrderThingsWereDoneAcrossBothHistories() {
        var h = new OptionHistory();
        long now = 10_000;
        // A (server) at 1000, B at 2000, C (server) at 3000, D at 4000
        h.record(entry("B", 2000));
        h.record(entry("D", 4000));
        // 1: the server's next edit is C, 7000 ms old; D is 6000 old, so D
        assertEquals("D", h.undo(now, 7000).name());
        // 2: C (7000 old) against B (8000 old): the server's
        assertNull(h.undo(now, 7000));
        // 3: the server's next is now A (9000 old); B is 8000 old: B
        assertEquals("B", h.undo(now, 9000).name());
        // 4: only the server's A is left
        assertNull(h.undo(now, 9000));
        // redo in the reverse order: A (server), B, C (server), D
        assertNull(h.redo(true));
        assertEquals("B", h.redo(true).name());
        assertNull(h.redo(true));
        assertEquals("D", h.redo(false).name());
    }

    @Test void aChangeWithNoEditOnTheServerIsUndoneHere() {
        var h = new OptionHistory();
        h.record(entry("B", 2000));
        assertEquals("B", h.undo(5000, -1).name());
        assertEquals("B", h.redo(false).name());
    }

    @Test void nothingToUndoHereLeavesItToTheServer() {
        var h = new OptionHistory();
        assertNull(h.undo(5000, 100));
        assertNull(h.undo(5000, -1));
    }

    @Test void aNewChangeEndsWhatWasUndone() {
        var h = new OptionHistory();
        h.record(entry("B", 2000));
        h.undo(5000, -1);
        h.record(entry("E", 6000));
        assertEquals(0, h.redoCount());
        assertNull(h.redo(false));
    }

    @Test void aNewServerEditEndsWhatWasUndone() {
        var h = new OptionHistory();
        h.record(entry("B", 2000));
        h.undo(5000, -1);
        h.clearRedo();
        assertEquals(0, h.redoCount());
    }

    @Test void keepsOnlyTheLimit() {
        var h = new OptionHistory();
        for (int i = 0; i < OptionHistory.LIMIT + 20; i++) h.record(entry("e" + i, i));
        assertEquals(OptionHistory.LIMIT, h.undoCount());
        assertEquals("e" + (OptionHistory.LIMIT + 19), h.undo(1_000_000, -1).name());
    }
}
