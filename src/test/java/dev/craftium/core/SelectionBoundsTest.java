package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionBoundsTest {
    @Test
    void theCornersFollowEveryChange() {
        var s = new Selection();
        assertEquals(new Point(0, 0, 0), s.min());
        assertEquals(new Point(0, 0, 0), s.max());
        s.combine(List.of(new Point(1, 2, 3), new Point(-4, 5, 0)), Selection.Mode.REPLACE);
        assertEquals(new Point(-4, 2, 0), s.min());
        assertEquals(new Point(1, 5, 3), s.max());
        assertSame(s.min(), s.min(), "kept until the selection changes");
        s.combine(List.of(new Point(9, 9, 9)), Selection.Mode.ADD);
        assertEquals(new Point(9, 9, 9), s.max());
        s.restore(List.of(new Point(7, 7, 7)));
        assertEquals(new Point(7, 7, 7), s.min());
        s.clear();
        assertEquals(new Point(0, 0, 0), s.max());
    }
}
