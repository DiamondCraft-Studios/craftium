package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OptionsHeightRangeTest {
    @Test
    void noHeightLimitByDefault() {
        var o = new Options();
        o.validate();
        assertEquals(Options.NO_MIN_Y, o.minY);
        assertEquals(Options.NO_MAX_Y, o.maxY);
    }

    @Test
    void theOldVanillaDefaultReadsAsNoLimit() {
        var o = new Options();
        o.minY = -64;
        o.maxY = 319;
        o.validate();
        assertEquals(Options.NO_MIN_Y, o.minY);
        assertEquals(Options.NO_MAX_Y, o.maxY);
    }

    @Test
    void aRangeThePlayerSetIsKept() {
        var o = new Options();
        o.minY = -64;
        o.maxY = 1000;
        o.validate();
        assertEquals(-64, o.minY);
        assertEquals(1000, o.maxY);
    }
}
