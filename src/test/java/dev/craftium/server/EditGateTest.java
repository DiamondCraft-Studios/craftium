package dev.craftium.server;

import dev.craftium.core.Options;
import dev.craftium.core.Selection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditGateTest {
    @Test void anEditThatFitsTheHistoryIsAdmitted() {
        assertDoesNotThrow(() -> EditGate.checkHistory(0));
        assertDoesNotThrow(() -> EditGate.checkHistory(Selection.HISTORY_BYTES));
    }

    @Test void anEditPastTheHistoryBudgetIsRefusedWithTheWordsThePlayerAlwaysSaw() {
        var e = assertThrows(IllegalArgumentException.class, () -> EditGate.checkHistory(Selection.HISTORY_BYTES + 1L));
        assertEquals("Edit exceeds the 160 MiB history budget; use a smaller region", e.getMessage());
    }

    @Test void thePlayersBlockLimitIsWhatAPlanFromOutsideThePlannerIsHeldTo() {
        var o = new Options();
        o.blockLimit = 500;
        assertEquals(500, EditGate.limit(o));
        o.blockLimit = 0;
        assertEquals(1, EditGate.limit(o));
        o.blockLimit = Integer.MAX_VALUE;
        assertEquals(Selection.LIMIT, EditGate.limit(o));
    }

    @Test void aPlanOverTheLimitIsRefusedAndOneAtItIsNot() {
        assertDoesNotThrow(() -> EditGate.checkLimit(500, 500));
        assertDoesNotThrow(() -> EditGate.checkLimit(0, 500));
        var e = assertThrows(IllegalArgumentException.class, () -> EditGate.checkLimit(501, 500));
        assertEquals("The plan changes 501 blocks, past the block limit of 500", e.getMessage());
    }
}
