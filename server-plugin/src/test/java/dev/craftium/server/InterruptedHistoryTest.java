package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.craftium.core.Point;
import java.util.*;
import org.junit.jupiter.api.Test;

class InterruptedHistoryTest {
    private static Session.Change change(int x) {
        var snapshot = new BlockSnapshot(null, null);
        return new Session.Change(new Point(x, 0, 0), snapshot, snapshot);
    }

    @Test
    void interruptedUndoKeepsRemainingUndoAndCompletedRedo() {
        var a = change(1);
        var b = change(2);
        var session = new Session();
        var edit = new Session.Edit("Fill", List.of(a, b), List.of(), 2);
        EditorServer.rememberInterrupted(session, edit, List.of(a), true, true);
        assertEquals(List.of(b), session.undo.getFirst().changes());
        assertEquals(List.of(a), session.redo.getFirst().changes());
        assertTrue(session.historyDirty);
    }

    @Test
    void interruptedRedoKeepsRemainingRedoAndCompletedUndo() {
        var a = change(1);
        var b = change(2);
        var session = new Session();
        var edit = new Session.Edit("Fill", List.of(a, b), List.of(), 2);
        EditorServer.rememberInterrupted(session, edit, List.of(a), false, true);
        assertEquals(List.of(a), session.undo.getFirst().changes());
        assertEquals(List.of(b), session.redo.getFirst().changes());
    }

    @Test
    void revocationBeforeAnyHistoryWritesPreservesTheEntireEntry() {
        var a = change(1);
        var session = new Session();
        var edit = new Session.Edit("Fill", List.of(a), List.of(), 2);
        EditorServer.rememberInterrupted(session, edit, List.of(), true, true);
        assertEquals(List.of(a), session.undo.getFirst().changes());
        assertTrue(session.redo.isEmpty());
    }

    @Test
    void newPartialEditRecordsOnlyAppliedCells() {
        var a = change(1);
        var b = change(2);
        var session = new Session();
        var edit = new Session.Edit("Fill", List.of(a, b), List.of(), 2);
        EditorServer.rememberInterrupted(session, edit, List.of(a), false, false);
        assertEquals(List.of(a), session.undo.getFirst().changes());
        assertTrue(session.redo.isEmpty());
    }

    @Test
    void partialReplayKeepsHistoryWithinThePersistedEntryLimit() {
        var a = change(1);
        var b = change(2);
        var session = new Session();
        for (int i = 3; i < 35; i++)
            session.undo.addLast(new Session.Edit("Old " + i, List.of(change(i)), List.of(), 2));
        EditorServer.rememberInterrupted(
                session, new Session.Edit("Fill", List.of(a, b), List.of(), 2), List.of(a), false, true);
        assertEquals(32, session.undo.size());
        assertEquals(List.of(a), session.undo.getFirst().changes());
        assertEquals(List.of(b), session.redo.getFirst().changes());
    }
}
