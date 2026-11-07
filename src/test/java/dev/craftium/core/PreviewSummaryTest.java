package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewSummaryTest {
    @Test void countsPlacedAndClearedBlocksAndTheBoxTheyFill() {
        var summary = new PreviewSummary();
        summary.add(10, 64, -5, "minecraft:stone", null, false);
        summary.add(12, 66, -3, "minecraft:stone", null, false);
        summary.add(11, 65, -4, "minecraft:air", null, true);
        var json = summary.toJson();
        assertEquals(2, json.get("placed").getAsLong());
        assertEquals(1, json.get("cleared").getAsLong());
        assertEquals("[10,64,-5]", json.get("min").toString());
        assertEquals("[12,66,-3]", json.get("max").toString());
        assertEquals("[3,3,3]", json.get("size").toString());
        assertEquals(1, json.get("chunks").getAsInt());
        assertEquals(1, json.getAsJsonArray("blocks").size(), "air is counted as cleared, not listed as a block");
    }

    @Test void facingIsBrokenDownSoAWrongRoofShowsAtOnce() {
        var summary = new PreviewSummary();
        for (int x = 0; x < 4; x++) {
            summary.add(x, 70, 0, "minecraft:spruce_stairs", "south", false);
            summary.add(x, 70, 6, "minecraft:spruce_stairs", "north", false);
        }
        var stairs = summary.toJson().getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals(8, stairs.get("count").getAsLong());
        assertEquals("{\"north\":4,\"south\":4}", stairs.get("facing").toString());
    }

    @Test void commonestBlocksComeFirstAndTheTailIsCountedTogether() {
        var summary = new PreviewSummary();
        for (int i = 0; i < PreviewSummary.LISTED + 3; i++)
            for (int n = 0; n <= i; n++) summary.add(i, n, 0, "minecraft:block_" + i, null, false);
        var json = summary.toJson();
        var blocks = json.getAsJsonArray("blocks");
        assertEquals(PreviewSummary.LISTED, blocks.size());
        assertEquals("minecraft:block_" + (PreviewSummary.LISTED + 2), blocks.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals(3, json.get("otherKinds").getAsInt());
        assertEquals(1 + 2 + 3, json.get("otherBlocks").getAsLong());
    }

    @Test void chunksAreCountedAcrossNegativeCoordinates() {
        var summary = new PreviewSummary();
        summary.add(-1, 0, -1, "minecraft:stone", null, false);
        summary.add(0, 0, 0, "minecraft:stone", null, false);
        summary.add(15, 0, 15, "minecraft:stone", null, false);
        assertEquals(2, summary.toJson().get("chunks").getAsInt());
    }

    @Test void anEmptySummaryHasNoBox() {
        var json = new PreviewSummary().toJson();
        assertEquals(0, json.get("placed").getAsLong());
        assertFalse(json.has("min"));
    }
}
