package dev.craftium.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionReportTest {
    private static final String CONCRETE = "minecraft:white_concrete", AIR = "minecraft:air";

    private static Set<Point> flat(int x0, int z0, int x1, int z1, int y) {
        var points = new LinkedHashSet<Point>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) points.add(new Point(x, y, z));
        return points;
    }

    /** A world of concrete up to {@code ground} and air above, with some blocks put in on top. */
    private static SelectionReport.World world(int ground, Map<Point, String> extra) {
        return (x, y, z) -> {
            String put = extra.get(new Point(x, y, z));
            if (put != null) return put;
            return y <= ground ? CONCRETE : AIR;
        };
    }

    @Test void aFlatSolidBoxOfConcreteWithAirAboveIsDescribedAsSuch() {
        var report = SelectionReport.describe(flat(689, -9, 729, 31, -59), world(-59, Map.of()), 24, null);
        assertTrue(report.get("ok").getAsBoolean());
        assertEquals(41 * 41, report.get("selected").getAsInt());
        assertEquals("box", report.get("shape").getAsString());
        assertTrue(report.get("flat").getAsBoolean());
        assertEquals(41 * 41, report.get("columns").getAsInt());
        assertEquals(CONCRETE, report.getAsJsonArray("blocks").get(0).getAsJsonObject().get("block").getAsString());
        var above = report.getAsJsonObject("above");
        assertEquals(41 * 41, above.get("clearColumns").getAsInt());
        assertEquals(24, above.get("minClear").getAsInt());
        assertTrue(report.get("summary").getAsString().contains("all minecraft:white_concrete"));
        assertTrue(report.get("summary").getAsString().contains("flat at y=-59"));
    }

    @Test void anLShapeIsIrregularAndItsColumnsAreCounted() {
        var points = flat(0, 0, 4, 1, 10); // a 5 x 2 bar
        points.addAll(flat(0, 2, 1, 4, 10)); // and a 2 x 3 arm
        var report = SelectionReport.describe(points, null, 0, null);
        assertEquals("irregular", report.get("shape").getAsString());
        assertEquals(16, report.get("selected").getAsInt());
        assertEquals(16, report.get("columns").getAsInt());
        assertEquals(5, report.getAsJsonArray("dimensions").get(0).getAsInt());
        assertEquals(5, report.getAsJsonArray("dimensions").get(2).getAsInt());
    }

    @Test void aTreeAboveOneColumnIsReportedAsAnObstruction() {
        var points = flat(0, 0, 2, 2, 5);
        var tree = Map.of(new Point(1, 8, 1), "minecraft:oak_log"); // 2 clear blocks (y 6 and 7), then wood at y 8
        var report = SelectionReport.describe(points, world(5, tree), 24, null);
        var above = report.getAsJsonObject("above");
        assertEquals(8, above.get("clearColumns").getAsInt());
        assertEquals(2, above.get("minClear").getAsInt());
        var first = above.getAsJsonArray("obstructions").get(0).getAsJsonObject();
        assertEquals("minecraft:oak_log", first.get("block").getAsString());
        assertEquals(1, first.get("columns").getAsInt());
        assertTrue(report.get("summary").getAsString().contains("1 of 9 columns"));
    }

    @Test void aSlopedSelectionIsNotFlatAndReportsItsTopRange() {
        var points = flat(0, 0, 1, 0, 5);
        points.addAll(flat(2, 0, 3, 0, 6));
        var report = SelectionReport.describe(points, null, 0, null);
        assertFalse(report.get("flat").getAsBoolean());
        assertEquals(2, report.get("layerCount").getAsInt());
        var top = report.getAsJsonObject("topSurface");
        assertEquals(5, top.get("lowest").getAsInt());
        assertEquals(6, top.get("highest").getAsInt());
        assertFalse(top.get("level").getAsBoolean());
    }

    @Test void withoutAWorldOnlyTheShapeIsDescribed() {
        var report = SelectionReport.describe(flat(0, 0, 3, 3, 0), null, SelectionReport.HEADROOM, "too big to read");
        assertFalse(report.has("blocks"));
        assertFalse(report.has("above"));
        assertEquals("too big to read", report.get("note").getAsString());
    }

    @Test void theWorldsHeightLimitCountsAsInTheWay() {
        SelectionReport.World capped = (x, y, z) -> y <= 10 ? CONCRETE : y <= 12 ? AIR : "craftium:outside_world";
        var report = SelectionReport.describe(flat(0, 0, 0, 0, 10), capped, 24, null);
        var above = report.getAsJsonObject("above");
        assertEquals(0, above.get("clearColumns").getAsInt());
        assertEquals(2, above.get("minClear").getAsInt());
        assertEquals("(the world's height limit)", above.getAsJsonArray("obstructions").get(0).getAsJsonObject().get("block").getAsString());
    }

    @Test void aMillionBlockSelectionIsDescribedQuicklyAndExactly() {
        var points = new HashSet<Point>(1_400_000);
        for (int x = 0; x < 1000; x++) for (int z = 0; z < 1000; z++) points.add(new Point(x, 64, z)); // the largest selection the editor allows
        long start = System.nanoTime();
        var report = SelectionReport.describe(points, world(64, Map.of(new Point(500, 66, 500), "minecraft:oak_log")), SelectionReport.HEADROOM, null);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(1_000_000, report.get("selected").getAsInt());
        assertEquals(1_000_000, report.get("columns").getAsInt());
        assertTrue(report.get("flat").getAsBoolean());
        assertEquals(999_999, report.getAsJsonObject("above").get("clearColumns").getAsInt());
        assertEquals(1, report.getAsJsonObject("above").get("minClear").getAsInt());
        assertTrue(ms < 15_000, "describing a million blocks took " + ms + " ms");
    }

    @Test void columnsWhoseSpaceAboveWasNotReadAreLeftOutOfTheFigures() {
        var points = flat(0, 0, 3, 0, 5); // four columns at x = 0..3
        SelectionReport.World world = (x, y, z) -> y <= 5 ? CONCRETE : (x >= 2 ? null : AIR); // the chunk over x >= 2 is not loaded
        var above = SelectionReport.describe(points, world, 24, null).getAsJsonObject("above");
        assertEquals(2, above.get("columns").getAsInt(), "only the two columns that could be read count");
        assertEquals(2, above.get("clearColumns").getAsInt());
        assertEquals(24, above.get("minClear").getAsInt(), "an unread column must not pull minClear down");
        assertEquals(2, above.get("unreadColumns").getAsInt());
        assertFalse(above.has("partlyUnread"));
        var summary = SelectionReport.describe(points, world, 24, null).get("summary").getAsString();
        assertTrue(summary.contains("nothing in the way") && summary.contains("2 of 4 columns could not be checked"), summary);
    }

    @Test void aSelectionWhoseSpaceAboveIsAllUnreadSaysSoInsteadOfClaimingObstructions() {
        SelectionReport.World world = (x, y, z) -> y <= 5 ? CONCRETE : null;
        var report = SelectionReport.describe(flat(0, 0, 2, 2, 5), world, 24, null);
        assertEquals(0, report.getAsJsonObject("above").get("columns").getAsInt());
        assertTrue(report.get("summary").getAsString().contains("could not be checked"), report.get("summary").getAsString());
        assertFalse(report.get("summary").getAsString().contains("have something"));
    }

    @Test void anEmptySelectionSaysSoAndHowToCarryOn() {
        JsonObject report = SelectionReport.describe(Set.of(), null, 0, null);
        assertEquals(0, report.get("selected").getAsInt());
        assertTrue(report.get("message").getAsString().contains("Nothing is selected"));
    }
}
