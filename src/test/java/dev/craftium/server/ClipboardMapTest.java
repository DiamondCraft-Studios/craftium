package dev.craftium.server;

import dev.craftium.core.Point;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardMapTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static LinkedHashMap<Point, BlockSnapshot> blocks(int n) {
        var stone = new BlockSnapshot(Blocks.STONE.defaultBlockState());
        var glass = new BlockSnapshot(Blocks.GLASS.defaultBlockState());
        var chestData = new CompoundTag(); chestData.putString("id", "minecraft:chest");
        var chest = new BlockSnapshot(Blocks.CHEST.defaultBlockState(), chestData);
        var map = new LinkedHashMap<Point, BlockSnapshot>();
        for (int i = 0; i < n; i++) map.put(new Point(i % 50, (i / 50) % 40, i / 2000), i % 97 == 0 ? chest : i % 3 == 0 ? glass : stone);
        return map;
    }

    private static ClipboardMap filled(LinkedHashMap<Point, BlockSnapshot> source) {
        var clipboard = new ClipboardMap();
        clipboard.putAll(source);
        return clipboard;
    }

    @Test void packedReadsTheSameAsTheMapItReplaces() {
        var source = blocks(10_000);
        var clipboard = filled(source);
        clipboard.pack();
        assertTrue(clipboard.packed());
        assertEquals(source.size(), clipboard.size());
        assertEquals(new ArrayList<>(source.entrySet()), new ArrayList<>(clipboard.entrySet()), "entries in the same order");
        assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(clipboard.keySet()));
        assertEquals(source, clipboard);
        for (var e : source.entrySet()) assertSame(e.getValue(), clipboard.get(e.getKey()));
        assertNull(clipboard.get(new Point(-5, -5, -5)));
        assertTrue(clipboard.containsKey(new Point(3, 2, 1)));
        assertTrue(source.keySet().equals(clipboard.keySet()), "a key set compares equal, as the duplicate check needs");
    }

    @Test void keepsBlockEntityData() {
        var clipboard = filled(blocks(1_000));
        clipboard.pack();
        assertEquals("minecraft:chest", clipboard.get(new Point(0, 0, 0)).data().getStringOr("id", ""));
    }

    @Test void changingAPackedClipboardUnpacksItAndPacksAgain() {
        var source = blocks(2_000);
        var clipboard = filled(source);
        clipboard.pack();
        var air = new BlockSnapshot(Blocks.AIR.defaultBlockState());
        clipboard.put(new Point(99, 99, 99), air);
        assertFalse(clipboard.packed());
        assertEquals(2_001, clipboard.size());
        assertSame(air, clipboard.get(new Point(99, 99, 99)));
        clipboard.remove(new Point(99, 99, 99));
        assertEquals(source, clipboard);
        clipboard.pack();
        assertTrue(clipboard.packed());
        assertEquals(source, clipboard);
    }

    @Test void clearEmptiesItWhetherPackedOrNot() {
        var clipboard = filled(blocks(1_000));
        clipboard.pack();
        clipboard.clear();
        assertTrue(clipboard.isEmpty());
        assertFalse(clipboard.packed());
        clipboard.putAll(blocks(10));
        assertEquals(10, clipboard.size());
    }

    @Test void aSmallClipboardIsNotPacked() {
        var clipboard = filled(blocks(ClipboardMap.MIN_PACK - 1));
        clipboard.pack();
        assertFalse(clipboard.packed());
    }

    @Test void costsAFractionOfTheObjects() {
        var clipboard = filled(blocks(100_000));
        long loose = clipboard.bytes();
        clipboard.pack();
        assertTrue(clipboard.bytes() * 6 < loose, "packed " + clipboard.bytes() + " against " + loose);
    }
}
