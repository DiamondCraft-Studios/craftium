package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WindowFoldTest {
    // canvas 400 high: the bottom edge is 375

    @Test
    void aWindowNearTheBottomFoldsOntoTheEdge() {
        assertEquals(375 - 19, WindowFold.foldedY(255, 120, 400)); // lower edge exactly on the edge
        assertEquals(375 - 19, WindowFold.foldedY(250, 120, 400)); // 5 above it
        assertEquals(375 - 19, WindowFold.foldedY(300, 120, 400)); // past it
    }

    @Test
    void aWindowFarFromTheBottomFoldsInPlace() {
        assertEquals(100, WindowFold.foldedY(100, 120, 400));
        assertEquals(240, WindowFold.foldedY(240, 120, 400)); // lower edge 360, more than 12 above 375
    }

    @Test
    void anUnfoldedWindowIsMovedUpOffTheBottomEdge() {
        assertEquals(375 - 120, WindowFold.unfoldedY(356, 120, 400));
        assertEquals(100, WindowFold.unfoldedY(100, 120, 400)); // fits: stays
        assertEquals(255, WindowFold.unfoldedY(255, 120, 400)); // just fits
    }

    @Test
    void aWindowTallerThanTheCanvasStopsUnderTheMenuBar() {
        assertEquals(27, WindowFold.unfoldedY(200, 500, 400));
    }

    @Test
    void windowsInTheSameColumnOverlapByMoreThanHalfOfTheNarrower() {
        assertTrue(WindowFold.sameColumn(0, 100, 10, 100));
        assertFalse(WindowFold.sameColumn(0, 100, 90, 100));
        assertFalse(WindowFold.sameColumn(0, 100, 100, 100));
    }

    @Test
    void foldedWindowsInARowDoNotCoverEachOther() {
        int[][] others = {{0, 100}};
        assertEquals(106, WindowFold.rowX(20, 100, others, 600, 6));
        assertEquals(300, WindowFold.rowX(300, 100, others, 600, 6)); // clear already
    }

    @Test
    void aRowThatRunsOutOfCanvasGoesLeftInstead() {
        int[][] others = {{500, 90}};
        assertEquals(500 - 6 - 100, WindowFold.rowX(520, 100, others, 600, 6));
    }

    @Test
    void severalNeighboursAreAllStepped() {
        int[][] others = {{0, 100}, {106, 100}};
        assertEquals(212, WindowFold.rowX(10, 100, others, 600, 6));
    }
}
