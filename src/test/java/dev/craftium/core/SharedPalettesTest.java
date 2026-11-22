package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedPalettesTest {
    @TempDir
    Path dir;

    private SharedPalettes store() {
        return new SharedPalettes(dir.resolve("palettes"));
    }

    @Test
    void savedPaletteComesBackWithWeightsAndOwner() {
        var store = store();
        store.save("Stone mix", "Alex", "u-1", List.of("minecraft:stone", "minecraft:andesite"), List.of(3d, 1d));
        var list = store.list();
        assertEquals(1, list.size());
        var p = list.get(0);
        assertEquals("Stone mix", p.name());
        assertEquals("Alex", p.owner());
        assertEquals(List.of("minecraft:stone", "minecraft:andesite"), p.blocks());
        assertEquals(List.of(3d, 1d), p.weights());
    }

    @Test
    void shortOrBadWeightsBecomeOnesAndAreClamped() {
        var p = store().save("a", "", "", List.of("x:a", "x:b", "x:c"), Arrays.asList(500d, Double.NaN));
        assertEquals(List.of(MaterialPalette.MAX_WEIGHT, 1d, 1d), p.weights());
    }

    @Test
    void saveReplacesANameIgnoringCaseAndKeepsTheFirstSpelling() {
        var store = store();
        store.save("Walls", "A", "1", List.of("x:a"), List.of());
        store.save("walls", "B", "2", List.of("x:b"), List.of());
        var list = store.list();
        assertEquals(1, list.size());
        assertEquals("Walls", list.get(0).name());
        assertEquals(List.of("x:b"), list.get(0).blocks());
        assertEquals("B", list.get(0).owner());
    }

    @Test
    void badNamesAndBlocksAreRefused() {
        var store = store();
        for (String name : new String[] {"", " ", "..", "a/b", "a\\b", "x".repeat(65), "a\tb"})
            assertThrows(IllegalArgumentException.class, () -> store.save(name, "", "", List.of("x:a"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.save("ok", "", "", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.save("ok", "", "", List.of("a,b"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.save("ok", "", "", List.of(" "), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("ok", "", "", Collections.nCopies(MaterialList.MAX + 1, "x:a"), List.of()));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void aPaletteTooBigForItsFileIsRefused() {
        var blocks = new ArrayList<String>();
        for (int i = 0; i < 100; i++) blocks.add("minecraft:" + "x".repeat(150) + i);
        assertThrows(IllegalArgumentException.class, () -> store().save("big", "", "", blocks, List.of()));
    }

    @Test
    void theLibraryHoldsAtMostTheLimitButStillReplaces() {
        var store = store();
        for (int i = 0; i < SharedPalettes.MAX_PALETTES; i++) store.save("p" + i, "", "", List.of("x:a"), List.of());
        assertThrows(IllegalArgumentException.class, () -> store.save("extra", "", "", List.of("x:a"), List.of()));
        store.save("p3", "", "", List.of("x:b"), List.of());
        assertEquals(List.of("x:b"), store.get("p3").blocks());
    }

    @Test
    void deleteRemovesAndNamesAMissingOne() {
        var store = store();
        store.save("a", "", "", List.of("x:a"), List.of());
        store.delete("A");
        assertTrue(store.list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.delete("a"));
    }

    @Test
    void aHandWrittenFileWorksAndTheFileNameIsTheName() throws Exception {
        Files.createDirectories(dir.resolve("palettes"));
        Files.writeString(dir.resolve("palettes/Hand.json"), "{\"name\":\"other\",\"blocks\":[\"x:a\",\"x:b\"]}");
        Files.writeString(dir.resolve("palettes/broken.json"), "{not json");
        Files.writeString(dir.resolve("palettes/empty.json"), "{\"blocks\":[]}");
        var list = store().list();
        assertEquals(1, list.size());
        assertEquals("Hand", list.get(0).name());
        assertEquals(List.of(1d, 1d), list.get(0).weights());
        assertEquals("", list.get(0).ownerId());
    }

    @Test
    void jsonRoundTripsAndStopsAtTheBudget() {
        var store = store();
        for (int i = 0; i < 5; i++) store.save("p" + i, "A", "1", List.of("minecraft:stone"), List.of(2d));
        var all = store.list();
        assertEquals(all, SharedPalettes.fromJson(SharedPalettes.toJson(all, 1_000_000)));
        assertEquals(2, SharedPalettes.fromJson(SharedPalettes.toJson(all, 250)).size());
        assertTrue(SharedPalettes.fromJson("nonsense").isEmpty());
        assertTrue(SharedPalettes.fromJson("[{\"name\":\"../x\",\"blocks\":[\"a\"]}]")
                .isEmpty());
    }
}
