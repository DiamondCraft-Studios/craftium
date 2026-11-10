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

    @Test void aListingWithoutBlocksHasNoPicture() {
        assertNull(new BlueprintInfo("a.nbt", "a", "", "", 1, new Point(1, 1, 1), List.of()).voxels());
    }

    @Test void aListingReadsItsOwnBlocksBack() {
        int[] cells = {0, BlueprintVoxels.EMPTY, 1, 0};
        var info = new BlueprintInfo("a.nbt", "a", "", "", 3, new Point(2, 2, 1), List.of(), List.of("minecraft:stone", "minecraft:dirt"), BlueprintVoxels.encode(2, 2, 1, cells));
        assertArrayEquals(cells, info.voxels());
    }

    @Test void aCellPastTheEndOfThePaletteIsRefused() {
        var info = new BlueprintInfo("a.nbt", "a", "", "", 1, new Point(1, 1, 1), List.of(), List.of("minecraft:stone"), BlueprintVoxels.encode(1, 1, 1, new int[]{3}));
        assertNull(info.voxels());
    }
}
