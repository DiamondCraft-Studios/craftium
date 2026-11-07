package dev.craftium.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockViewTest {
    private static final String AFTER = "Anything not listed is air.";
    private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:oak_log", "minecraft:spruce_stairs[facing=north,half=bottom]");

    private static int[] woodInAir(int side, int from, int to) {
        var ids = new int[side * side * side];
        for (int x = from; x <= to; x++) for (int y = from; y <= to; y++) for (int z = from; z <= to; z++) ids[x + side * (z + side * y)] = 1;
        return ids;
    }

    @Test void aSolidBoxOfWoodIsOneEntryWhereverItSitsAndInWorldCoordinates() {
        var view = BlockView.describe(100, 64, -20, 20, 20, 20, woodInAir(20, 5, 14), PALETTE, Set.of(0), "auto", AFTER);
        assertEquals("boxes", view.get("format").getAsString());
        assertEquals(1, view.get("entries").getAsInt());
        var wood = view.getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals("minecraft:oak_log", wood.get("block").getAsString());
        assertEquals(1000, wood.get("count").getAsInt());
        var box = wood.getAsJsonArray("boxes").get(0).getAsJsonArray();
        assertEquals("[105,69,-15,114,78,-6]", box.toString()); // the corners, moved by the box's own corner
        assertTrue(view.get("summary").getAsString().startsWith("1,000 blocks in 1 boxes: 1000 oak_log"));
    }

    @Test void theSameBlockAsRunsNeedsAboutTwoHundredEntries() {
        var view = BlockView.describe(0, 0, 0, 20, 20, 20, woodInAir(20, 5, 14), PALETTE, Set.of(0), "runs", AFTER);
        assertEquals("runs", view.get("format").getAsString());
        assertTrue(view.get("entries").getAsInt() > 150, "runs along x split the block into a run per row: " + view.get("entries"));
    }

    @Test void airIsLeftOutOfTheBoxesButEveryPositionIsInTheRuns() {
        var boxes = BlockView.describe(0, 0, 0, 20, 20, 20, woodInAir(20, 5, 14), PALETTE, Set.of(0), "boxes", AFTER);
        assertEquals(1, boxes.getAsJsonArray("blocks").size());
        var runs = BlockView.describe(0, 0, 0, 20, 20, 20, woodInAir(20, 5, 14), PALETTE, Set.of(0), "runs", AFTER);
        long cells = 0;
        for (var run : runs.getAsJsonArray("runs")) cells += run.getAsJsonArray().get(1).getAsLong();
        assertEquals(8000, cells);
    }

    @Test void noiseFallsBackToRunsInAutoAndIsRefusedWhenBoxesAreAsked() {
        var noise = new int[30 * 30 * 30];
        var rnd = new Random(2);
        for (int i = 0; i < noise.length; i++) noise[i] = 1 + rnd.nextInt(2); // random noise: boxes cannot merge much
        var auto = BlockView.describe(0, 0, 0, 30, 30, 30, noise, PALETTE, Set.of(0), "auto", AFTER);
        assertEquals("runs", auto.get("format").getAsString());
        assertThrows(IllegalArgumentException.class, () -> BlockView.describe(0, 0, 0, 30, 30, 30, noise, PALETTE, Set.of(0), "boxes", AFTER));
    }

    @Test void cellsOutsideTheViewReadAsUnchangedInRunsAndAreAbsentInBoxes() {
        var ids = new int[]{-1, -1, 2, 2, -1, -1};
        var runs = BlockView.describe(0, 0, 0, 6, 1, 1, ids, PALETTE, Set.of(), "runs", "Anything not listed is not changed by the preview.");
        assertTrue(runs.getAsJsonArray("palette").toString().contains(BlockView.UNCHANGED));
        var boxes = BlockView.describe(0, 0, 0, 6, 1, 1, ids, PALETTE, Set.of(), "boxes", "Anything not listed is not changed by the preview.");
        assertEquals("[2,0,0,3,0,0]", boxes.getAsJsonArray("blocks").get(0).getAsJsonObject().getAsJsonArray("boxes").get(0).toString());
        assertTrue(boxes.get("boxFormat").getAsString().contains("not changed by the preview"));
    }

    @Test void aViewWithNothingToListSaysSo() {
        JsonObject view = BlockView.describe(0, 0, 0, 3, 3, 3, new int[27], PALETTE, Set.of(0), "auto", AFTER);
        assertEquals(0, view.getAsJsonArray("blocks").size());
        assertTrue(view.get("summary").getAsString().startsWith("No blocks listed"));
    }

    @Test void stairsThatFaceDifferentWaysStayApartAsDifferentBlocks() {
        var palette = List.of("minecraft:air", "minecraft:spruce_stairs[facing=north,half=bottom]", "minecraft:spruce_stairs[facing=south,half=bottom]");
        var ids = new int[10 * 2 * 1];
        for (int x = 0; x < 10; x++) { ids[x] = 1; ids[x + 10] = 2; } // two rows along x, one facing each way
        var view = BlockView.describe(0, 0, 0, 10, 1, 2, ids, palette, Set.of(0), "boxes", AFTER); // asked for boxes: two rows are two runs, which "auto" would prefer
        assertEquals(2, view.getAsJsonArray("blocks").size());
        assertTrue(view.toString().contains("facing=north") && view.toString().contains("facing=south"));
    }

    @Test void aBadFormatIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BlockView.describe(0, 0, 0, 1, 1, 1, new int[1], PALETTE, Set.of(), "pretty", AFTER));
    }

    @Test void autoPicksRunsWhenBoxesAreFewerButLonger() {
        // 8 x 1 x 2 cells in stripes along z: 16 runs (two numbers each) but 8 boxes (six each), so boxes are fewer yet three times the words.
        var ids = new int[8 * 2];
        for (int i = 0; i < ids.length; i++) ids[i] = 1 + (i % 8) % 2;
        var runs = BlockView.describe(0, 0, 0, 8, 1, 2, ids, PALETTE, Set.of(0), "runs", AFTER);
        var boxes = BlockView.describe(0, 0, 0, 8, 1, 2, ids, PALETTE, Set.of(0), "boxes", AFTER);
        assertEquals(16, runs.get("entries").getAsInt());
        assertEquals(8, boxes.get("entries").getAsInt());
        var auto = BlockView.describe(0, 0, 0, 8, 1, 2, ids, PALETTE, Set.of(0), "auto", AFTER);
        assertEquals("runs", auto.get("format").getAsString(), "8 boxes cost 48 numbers and 16 runs cost 32");
        assertTrue(auto.toString().length() < boxes.toString().length() * 2, "and the reply is not longer than the boxes would have been");
    }

    @Test void autoStillPicksBoxesWhenTheyAreFarFewer() {
        // 3 boxes (18 numbers) against about 60 runs (120 numbers).
        var ids = new int[10 * 10 * 1];
        for (int z = 0; z < 10; z++) for (int x = 0; x < 10; x++) ids[x + 10 * z] = x < 5 ? 1 : 2;
        var auto = BlockView.describe(0, 0, 0, 10, 1, 10, ids, PALETTE, Set.of(0), "auto", AFTER);
        assertEquals("boxes", auto.get("format").getAsString());
    }
}
