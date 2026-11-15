package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ColourMathTest {
    @Test
    void cielabD65WhiteAndBlack() {
        assertArrayEquals(new double[] {100, 0, 0}, ColourMath.cielab(0xffffff), .0001);
        assertArrayEquals(new double[] {0, 0, 0}, ColourMath.cielab(0), .0001);
    }

    @Test
    void rgbRoundTrip() {
        for (int rgb : new int[] {0, 0xffffff, 0xff0000, 0x00ff00, 0x0000ff, 0x946a35})
            assertEquals(rgb, ColourMath.rgb(ColourMath.lab(rgb)));
    }

    @Test
    void perceptualMidpoint() {
        assertEquals(0x636363, ColourMath.interpolate(0, 0xffffff, .5));
        assertEquals(0xff0000, ColourMath.interpolate(0xff0000, 0xff0000, .7));
    }
}
