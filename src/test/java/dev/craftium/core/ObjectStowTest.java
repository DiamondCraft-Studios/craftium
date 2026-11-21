package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The eye of a BuildObject: closing it takes its blocks out of the world and keeps them, and nothing puts them back until it is opened. */
class ObjectStowTest {
    private static final String STONE = "minecraft:stone", DIRT = "minecraft:dirt", GLASS = "minecraft:glass";

    private static Point p(int x) {
        return new Point(x, 0, 0);
    }

    private static boolean solid(String s) {
        return !ObjectMove.isAir(s) && !s.startsWith("minecraft:water");
    }

    private static PackedCells cells(Object... pairs) {
        var c = new PackedCells();
        for (int i = 0; i < pairs.length; i += 2) c.put((Point) pairs[i], (String) pairs[i + 1]);
        return c;
    }

    private static ObjectMove.Obj obj(int id, PackedCells dormant, PackedCells replaced, boolean stowed, Point... own) {
        return new ObjectMove.Obj(id, new HashSet<>(List.of(own)), dormant, replaced, false, stowed);
    }

    private static Function<Point, ObjectMove.Block> view(Map<Point, String> world) {
        return c -> ObjectMove.Block.of(world.get(c));
    }

    private static void apply(Map<Point, String> world, ObjectMove.Result r) {
        for (var e : r.writes().entrySet()) {
            if (e.getValue().isAir()) world.remove(e.getKey());
            else world.put(e.getKey(), e.getValue().state());
        }
    }

    @Test
    void closingTheEyeKeepsEveryBlockAndGivesBackWhatWasReplaced() {
        var world = new HashMap<>(Map.of(p(0), STONE, p(1), GLASS));
        var o = obj(1, new PackedCells(), cells(p(1), "minecraft:water"), false, p(0), p(1));
        var r = ObjectMove.stow(List.of(o), Set.of(1), view(world));
        apply(world, r);
        assertEquals(Map.of(p(1), "minecraft:water"), world, "air where it stood, the water it replaced given back");
        var after = r.objects().get(1);
        assertTrue(after.stowed());
        assertTrue(after.own().isEmpty());
        assertEquals(STONE, after.dormant().get(p(0)));
        assertEquals(GLASS, after.dormant().get(p(1)));
        assertTrue(after.replaced().isEmpty());
    }

    @Test
    void aBlockHiddenUnderItTakesTheCellAndTheReplacedBlockGoesWithIt() {
        var world = new HashMap<>(Map.of(p(0), STONE));
        var under = obj(1, cells(p(0), DIRT), new PackedCells(), false);
        var top = obj(2, new PackedCells(), cells(p(0), "minecraft:short_grass"), false, p(0));
        var r = ObjectMove.stow(List.of(under, top), Set.of(2), view(world));
        apply(world, r);
        assertEquals(DIRT, world.get(p(0)));
        var u = r.objects().get(1);
        assertEquals(Set.of(p(0)), u.own());
        assertTrue(u.dormant().isEmpty());
        assertEquals("minecraft:short_grass", u.replaced().get(p(0)), "given back when that one leaves");
        assertEquals(STONE, r.objects().get(2).dormant().get(p(0)));
    }

    @Test
    void aStowedObjectsBlocksStayDormantWhenTheCellComesFree() {
        // object 2 stands where stowed object 1 keeps a block: moving 2 away frees the cell, and 1 does not take it
        var world = new HashMap<>(Map.of(p(0), DIRT));
        var stowed = obj(1, cells(p(0), STONE), new PackedCells(), true);
        var mover = obj(2, new PackedCells(), new PackedCells(), false, p(0));
        var r = ObjectMove.shift(
                List.of(stowed, mover), Set.of(p(0)), c -> c.add(5, 0, 0), view(world), ObjectStowTest::solid, false);
        apply(world, r);
        assertNull(world.get(p(0)), "left as air");
        assertEquals(DIRT, world.get(p(5)));
        assertFalse(r.objects().containsKey(1), "the stowed one is untouched");
    }

    @Test
    void aStowedObjectNeverOutranksAndItsCarriedBlocksStayDormant() {
        var stowedLater = obj(2, cells(p(0), STONE), new PackedCells(), true);
        var holder = obj(1, new PackedCells(), new PackedCells(), false, p(0));
        assertTrue(ObjectMove.outranked(List.of(holder, stowedLater)).isEmpty());

        var world = new HashMap<String, String>();
        var carried = new ObjectMove.Obj(3, Set.of(), cells(p(0), STONE), new PackedCells(), true, true);
        var r = ObjectMove.shift(
                List.of(carried), Set.of(), c -> c.add(5, 0, 0), c -> null, ObjectStowTest::solid, false);
        assertTrue(r.writes().isEmpty(), "nothing is written for it");
        var after = r.objects().get(3);
        assertTrue(after.own().isEmpty());
        assertEquals(STONE, after.dormant().get(p(5)), "moved with it, still kept");
    }
}
