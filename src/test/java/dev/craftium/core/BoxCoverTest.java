package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoxCoverTest {
    private static int at(int x, int y, int z, int sx, int sz) { return x + sx * (z + sz * y); }

    private static int[] grid(int sx, int sy, int sz, int fill) { var g = new int[sx * sy * sz]; Arrays.fill(g, fill); return g; }

    @Test void aSolidBlockInsideEmptySpaceIsOneBoxWhereverItSits() {
        var g = grid(20, 20, 20, -1);
        for (int x = 5; x < 15; x++) for (int y = 3; y < 13; y++) for (int z = 7; z < 17; z++) g[at(x, y, z, 20, 20)] = 0;
        var boxes = BoxCover.cover(g, 20, 20, 20, 100);
        assertEquals(1, boxes.size());
        var box = boxes.get(0);
        assertEquals(5, box.x0()); assertEquals(14, box.x1());
        assertEquals(3, box.y0()); assertEquals(12, box.y1());
        assertEquals(7, box.z0()); assertEquals(16, box.z1());
        assertEquals(1000, box.volume());
    }

    @Test void twoMaterialsAreTwoBoxesAndAnLShapeIsTwo() {
        var g = grid(4, 1, 4, -1);
        for (int x = 0; x < 4; x++) g[at(x, 0, 0, 4, 4)] = 1;          // a bar of 4
        for (int z = 1; z < 3; z++) g[at(0, 0, z, 4, 4)] = 1;          // and an arm of 2 below it
        var boxes = BoxCover.cover(g, 4, 1, 4, 100);
        assertEquals(2, boxes.size());
        assertEquals(6, boxes.stream().mapToLong(BoxCover.Box::volume).sum());
        var mixed = grid(2, 1, 1, -1);
        mixed[0] = 0; mixed[1] = 1;
        assertEquals(2, BoxCover.cover(mixed, 2, 1, 1, 100).size());
    }

    @Test void everyCellIsCoveredExactlyOnceWhateverTheShape() {
        var rnd = new Random(7);
        for (int round = 0; round < 25; round++) {
            int sx = 3 + rnd.nextInt(9), sy = 2 + rnd.nextInt(6), sz = 3 + rnd.nextInt(9);
            var g = new int[sx * sy * sz];
            for (int i = 0; i < g.length; i++) g[i] = rnd.nextInt(5) - 1; // -1 (left out) and four kinds
            var boxes = BoxCover.cover(g, sx, sy, sz, Integer.MAX_VALUE);
            var seen = new int[g.length];
            for (var b : boxes)
                for (int x = b.x0(); x <= b.x1(); x++) for (int y = b.y0(); y <= b.y1(); y++) for (int z = b.z0(); z <= b.z1(); z++) {
                    assertEquals(b.id(), g[at(x, y, z, sx, sz)], "a box must hold one kind only");
                    seen[at(x, y, z, sx, sz)]++;
                }
            for (int i = 0; i < g.length; i++) assertEquals(g[i] < 0 ? 0 : 1, seen[i], "cell " + i + " must be covered once, or not at all when left out");
        }
    }

    @Test void noiseGivesUpInsteadOfMakingAMillionBoxes() {
        var g = new int[30 * 30 * 30];
        var rnd = new Random(1);
        for (int i = 0; i < g.length; i++) g[i] = rnd.nextInt(2); // random noise: boxes cannot merge much
        assertNull(BoxCover.cover(g, 30, 30, 30, 500));
    }

    @Test void aMillionCellsOfOneKindIsOneBoxAndQuick() {
        var g = grid(100, 100, 100, 3);
        long t = System.nanoTime();
        var boxes = BoxCover.cover(g, 100, 100, 100, 10);
        assertEquals(1, boxes.size());
        assertEquals(1_000_000L, boxes.get(0).volume());
        assertTrue((System.nanoTime() - t) / 1_000_000 < 2_000, "covering a million cells should take well under two seconds");
    }

    @Test void aWrongSizedGridIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BoxCover.cover(new int[5], 2, 2, 2, 10));
    }
}
