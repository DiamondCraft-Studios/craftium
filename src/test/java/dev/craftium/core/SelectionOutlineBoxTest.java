package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionOutlineBoxTest {
    @Test void aBoxHasTwelveEdgesCreasesAndSixFaces() {
        var box = SelectionOutline.box(new Point(0, 0, 0), new Point(3, 2, 4));
        assertEquals(12, box.edges().size());
        assertEquals(12, box.creases().size());
        assertEquals(6, box.faces().size());
    }
    @Test void itMatchesTheOutlineOfItsBlocks() {
        var blocks = new java.util.ArrayList<Point>();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 2; z++) blocks.add(new Point(x, y, z));
        var made = SelectionOutline.of(blocks);
        var direct = SelectionOutline.box(new Point(0, 0, 0), new Point(2, 3, 2));
        assertEquals(new java.util.HashSet<>(made.edges()), new java.util.HashSet<>(direct.edges()));
        assertEquals(new java.util.HashSet<>(made.creases()), new java.util.HashSet<>(direct.creases()));
        assertEquals(new java.util.HashSet<>(made.faces()), new java.util.HashSet<>(direct.faces()));
    }
}
