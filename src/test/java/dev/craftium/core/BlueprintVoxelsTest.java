package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlueprintVoxelsTest {
    @Test void cellsSurviveEncodingAndDecoding() {
        int[] cells = new int[3 * 2 * 4];
        java.util.Arrays.fill(cells, BlueprintVoxels.EMPTY);
        cells[BlueprintVoxels.index(3, 4, 0, 0, 0)] = 0;
        cells[BlueprintVoxels.index(3, 4, 2, 1, 3)] = 5;
        cells[BlueprintVoxels.index(3, 4, 1, 1, 2)] = 5;
        assertArrayEquals(cells, BlueprintVoxels.decode(BlueprintVoxels.encode(3, 2, 4, cells), 3, 2, 4));
    }

    @Test void aBoxThatIsTooBigOrTheWrongSizeHasNoText() {
        assertNull(BlueprintVoxels.encode(100, 100, 100, new int[1_000_000]));
        assertNull(BlueprintVoxels.encode(2, 2, 2, new int[7]));
    }

    @Test void textThatDoesNotFitTheBoxDecodesToNothing() {
        var cells = new int[2 * 2 * 2];
        String text = BlueprintVoxels.encode(2, 2, 2, cells);
        assertNull(BlueprintVoxels.decode(text, 3, 3, 3));
        assertNull(BlueprintVoxels.decode("not base64 or gzip", 2, 2, 2));
        assertNull(BlueprintVoxels.decode(null, 2, 2, 2));
    }

    private static BlueprintVoxels.Packed pack(BlueprintVoxels.Builder builder, int sx, int sy, int sz) { return builder.pack(sx, sy, sz); }

    @Test void aSmallBlueprintKeepsEveryBlock() {
        var builder = new BlueprintVoxels.Builder();
        builder.add(0, 0, 0, "minecraft:stone"); builder.add(2, 1, 3, "minecraft:dirt");
        var packed = pack(builder, 3, 2, 4);
        assertEquals(1, packed.scale());
        var info = new BlueprintInfo("a.nbt", "a", "", "", 2, new Point(3, 2, 4), List.of()).withVoxels(packed);
        int[] cells = info.voxels();
        assertEquals(0, cells[BlueprintVoxels.index(3, 4, 0, 0, 0)]);
        assertEquals(1, cells[BlueprintVoxels.index(3, 4, 2, 1, 3)]);
    }

    @Test void aBigBlueprintIsShrunkSoItStillHasAPicture() {
        var builder = new BlueprintVoxels.Builder();
        // a 200 x 40 x 200 box of walls, each block a different state of variety
        for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) for (int y = 0; y < 40; y++)
            if (x == 0 || z == 0 || x == 199 || z == 199 || y == 0) builder.add(x, y, z, "minecraft:block_" + ((x * 31 + y * 7 + z * 13) % 120));
        var packed = pack(builder, 200, 40, 200);
        assertNotNull(packed);
        assertTrue(packed.scale() > 1);
        assertTrue(packed.cells().length() <= BlueprintVoxels.MAX_ENCODED);
        var info = new BlueprintInfo("big.nbt", "big", "", "", builder.count(), new Point(200, 40, 200), List.of()).withVoxels(packed);
        int[] cells = info.voxels();
        assertNotNull(cells);
        int filled = 0;
        for (int c : cells) if (c >= 0) filled++;
        assertTrue(filled > 0);
    }

    @Test void morePaletteEntriesThanTheLimitStillPack() {
        var builder = new BlueprintVoxels.Builder();
        for (int i = 0; i < 400; i++) builder.add(i % 20, i / 400, i / 20, "minecraft:b" + i);
        var packed = pack(builder, 20, 1, 20);
        assertNotNull(packed);
        assertTrue(packed.palette().size() <= BlueprintVoxels.MAX_PALETTE);
    }

    @Test void aListingWithoutBlocksHasNoPicture() {
        assertNull(new BlueprintInfo("a.nbt", "a", "", "", 1, new Point(1, 1, 1), List.of()).voxels());
    }

    @Test void aListingReadsItsOwnBlocksBack() {
        int[] cells = {0, BlueprintVoxels.EMPTY, 1, 0};
        var info = new BlueprintInfo("a.nbt", "a", "", "", 3, new Point(2, 2, 1), List.of(), List.of("minecraft:stone", "minecraft:dirt"), BlueprintVoxels.encode(2, 2, 1, cells), 1);
        assertArrayEquals(cells, info.voxels());
    }

    @Test void aCellPastTheEndOfThePaletteIsRefused() {
        var info = new BlueprintInfo("a.nbt", "a", "", "", 1, new Point(1, 1, 1), List.of(), List.of("minecraft:stone"), BlueprintVoxels.encode(1, 1, 1, new int[]{3}), 1);
        assertNull(info.voxels());
    }
}
