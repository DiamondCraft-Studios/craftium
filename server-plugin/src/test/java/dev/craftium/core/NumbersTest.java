package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NumbersTest {
    @Test void wholeNumbersStayWhole(){
        assertEquals("3",Numbers.trim(3));
        assertEquals("3",Numbers.trim(3.0));
        assertEquals("10",Numbers.trim(10.001));
        assertEquals("0",Numbers.trim(-0.001));
    }
    @Test void decimalsAreRoundedToTwoWithoutTrailingZeros(){
        assertEquals("1.8",Numbers.trim(1.80));
        assertEquals("1.24",Numbers.trim(1.240));
        assertEquals("1.24",Numbers.trim(1.2361));
        assertEquals("2.45",Numbers.trim(2.449999));
        assertEquals("5.74",Numbers.trim(Math.sqrt(33)));
        assertEquals("-1.5",Numbers.trim(-1.5));
    }
}
