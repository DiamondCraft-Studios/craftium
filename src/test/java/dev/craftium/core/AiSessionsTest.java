package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiSessionsTest {
    @Test void anIdIsKeptWhenPlainAndIsTheDefaultSessionOtherwise() {
        assertEquals("a1b2-C_3", AiSessions.cleanId("a1b2-C_3"));
        assertEquals("", AiSessions.cleanId(null));
        assertEquals("", AiSessions.cleanId(""));
        assertEquals("", AiSessions.cleanId("has space"));
        assertEquals("", AiSessions.cleanId("../x"));
        assertEquals("a".repeat(AiSessions.MAX_ID), AiSessions.cleanId("a".repeat(100)));
    }

    @Test void aLabelIsTidiedAndCut() {
        assertEquals("Egyptian pillar", AiSessions.cleanLabel("  Egyptian \n\t pillar  "));
        assertEquals("", AiSessions.cleanLabel(null));
        assertEquals("ab", AiSessions.cleanLabel("a\u0000b§"));
        assertEquals(AiSessions.MAX_LABEL, AiSessions.cleanLabel("x".repeat(500)).length());
    }

    @Test void aSessionExpiresAfterAnIdleHourUnlessItIsBusy() {
        long now = 10 * AiSessions.IDLE_MS;
        assertFalse(AiSessions.expired(now - AiSessions.IDLE_MS, now, false));
        assertTrue(AiSessions.expired(now - AiSessions.IDLE_MS - 1, now, false));
        assertFalse(AiSessions.expired(0, now, true));
    }

    @Test void regionsOverlapOnlyWhenTheyShareABlock() {
        var a = new AiSessions.Region(0, 0, 0, 9, 9, 9);
        assertTrue(a.intersects(new AiSessions.Region(9, 9, 9, 20, 20, 20)));
        assertFalse(a.intersects(new AiSessions.Region(10, 0, 0, 20, 9, 9)));
        assertFalse(a.intersects(new AiSessions.Region(0, 10, 0, 9, 20, 9)));
        assertEquals(10, a.sizeX());
        assertEquals("0,0,0 to 9,9,9", a.text());
    }

    @Test void aBoxAroundPoints() {
        assertNull(AiSessions.around(List.of()));
        var box = AiSessions.around(List.of(new Point(3, 5, -2), new Point(-1, 7, 4)));
        assertEquals(new AiSessions.Region(-1, 5, -2, 3, 7, 4), box);
    }
}
