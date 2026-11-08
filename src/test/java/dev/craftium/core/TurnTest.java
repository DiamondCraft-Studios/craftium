package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurnTest {
    @Test void aClockwiseQuarterTurnTakesNorthToEastAsTheGameDoes() {
        // North is -z, east is +x.
        assertEquals(1, Turn.CLOCKWISE.x(0, -1));
        assertEquals(0, Turn.CLOCKWISE.z(0, -1));
        assertEquals(1, Turn.of(90, null).quarterTurns());
        assertEquals(3, Turn.of(270, null).quarterTurns());
        assertEquals(Turn.NONE, Turn.of(360, null));
    }

    @Test void mirrorsComeApartIntoMirrorAlongXThenATurn() {
        var t = Turn.of(90, "x");
        assertTrue(t.mirrored());
        assertEquals(1, t.quarterTurns());
        // Mirroring along z is mirroring along x and then a half turn.
        assertEquals(Turn.MIRROR_Z, Turn.of(180, "x"));
        assertTrue(Turn.MIRROR_Z.mirrored());
        assertEquals(2, Turn.MIRROR_Z.quarterTurns());
        assertFalse(Turn.of(180, null).mirrored());
    }

    @Test void aTurnedBoxStillStartsAtZero() {
        // A 3 by 1 row along x turned a quarter clockwise runs along z.
        assertArrayEquals(new int[]{0, 0}, Turn.CLOCKWISE.inBox(0, 0, 3, 1));
        assertArrayEquals(new int[]{0, 2}, Turn.CLOCKWISE.inBox(2, 0, 3, 1));
        assertArrayEquals(new int[]{1, 3}, Turn.CLOCKWISE.size(3, 1));
        // Mirrored along x, the ends swap.
        assertArrayEquals(new int[]{2, 0}, Turn.MIRROR_X.inBox(0, 0, 3, 1));
        assertArrayEquals(new int[]{0, 0}, Turn.MIRROR_X.inBox(2, 0, 3, 1));
    }

    @Test void badAnglesAndMirrorsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Turn.of(45, null));
        assertThrows(IllegalArgumentException.class, () -> Turn.of(0, "y"));
    }
}
