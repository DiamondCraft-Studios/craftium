package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The Gradient Painter's ordered bands, its blend, and the colour stops of the Gradient window. */
class GradientTest {
    @Test
    void blendZeroGivesOrderedBandsWhateverTheThreshold() {
        int last = 0;
        for (int step = 0; step <= 100; step++) {
            double t = step / 100d;
            int low = MaterialList.index(t, List.of(), 4, 0, 0);
            int high = MaterialList.index(t, List.of(), 4, .999, 0);
            assertEquals(low, high, "no mixing at Blend 0, at " + t);
            assertTrue(low >= last, "the bands only go forward, at " + t);
            last = low;
        }
        assertEquals(0, MaterialList.index(0, List.of(), 4, .5, 0));
        assertEquals(3, MaterialList.index(1, List.of(), 4, .5, 0));
    }

    @Test
    void blendMixesNeighboursOnly() {
        // Halfway between the second and third entry, a full blend may give either of them and nothing else
        double t = 1.5 / 3;
        for (double r = 0; r < 1; r += .05) {
            int i = MaterialList.index(t, List.of(), 4, r, 1);
            assertTrue(i == 1 || i == 2, "neighbours only, got " + i);
        }
    }

    @Test
    void orderedThresholdsStayInRangeAndUseTheWholePattern() {
        var seen = new java.util.HashSet<Double>();
        for (int x = -4; x < 4; x++)
            for (int y = -4; y < 4; y++)
                for (int z = -4; z < 4; z++) {
                    double v = MaterialList.ordered(x, y, z);
                    assertTrue(v > 0 && v < 1);
                    seen.add(v);
                }
        assertEquals(16, seen.size());
    }

    @Test
    void colourAtFollowsTheStopsInAnyOrder() {
        var stops = List.of(
                new GradientStop(1, 0xffffff, ""),
                new GradientStop(0, 0x000000, ""),
                new GradientStop(.5, 0xff0000, ""));
        assertEquals(0x000000, GradientStop.colourAt(stops, 0));
        assertEquals(0xff0000, GradientStop.colourAt(stops, .5));
        assertEquals(0xffffff, GradientStop.colourAt(stops, 1));
        assertEquals(ColourMath.interpolate(0x000000, 0xff0000, .5), GradientStop.colourAt(stops, .25));
    }

    @Test
    void colourAtHoldsTheEndColoursOutsideTheStops() {
        var stops = List.of(new GradientStop(.25, 0x123456, ""), new GradientStop(.75, 0x654321, ""));
        assertEquals(0x123456, GradientStop.colourAt(stops, 0));
        assertEquals(0x654321, GradientStop.colourAt(stops, 1));
        assertEquals(0x123456, GradientStop.colourAt(List.of(stops.getFirst()), .9));
    }

    @Test
    void stopsAreKeptInRange() {
        assertEquals(1, new GradientStop(4, 0, "").at());
        assertEquals(0, new GradientStop(Double.NaN, 0, null).at());
        assertEquals("", new GradientStop(0, 0, null).block());
        assertEquals(0xabcdef, new GradientStop(0, 0xffabcdef, "").rgb());
    }

    @Test
    void generatedRunsBecomeWeightedEntries() {
        var o = new Options();
        MaterialPalette.setRuns(
                o,
                List.of(
                        "minecraft:stone",
                        "minecraft:stone",
                        "minecraft:andesite",
                        "minecraft:stone",
                        "minecraft:tuff"));
        assertEquals(
                List.of("minecraft:stone", "minecraft:andesite", "minecraft:stone", "minecraft:tuff"),
                MaterialPalette.blocks(o));
        assertEquals(List.of(2d, 1d, 1d, 1d), o.materialWeights);
    }

    @Test
    void positionsMatchWhereTheGradientPaintsEachEntry() {
        var o = new Options();
        MaterialPalette.setRuns(o, List.of("minecraft:a", "minecraft:a", "minecraft:b", "minecraft:c"));
        int n = MaterialPalette.blocks(o).size();
        assertEquals(0, MaterialPalette.position(o, 0));
        assertEquals(1, MaterialPalette.position(o, n - 1));
        for (int i = 0; i < n; i++)
            assertEquals(i, MaterialList.index(MaterialPalette.position(o, i), o.materialWeights, n, .5, 0));
    }

    @Test
    void gradientAnchorsAreTheirOwnListOfAtMostTwo() {
        var o = new Options();
        o.nodes.add(new Point(1, 2, 3));
        o.gradientAnchors.add(new Point(0, 0, 0));
        o.gradientAnchors.add(new Point(9, 0, 0));
        o.validate();
        assertEquals(List.of(new Point(1, 2, 3)), o.nodes, "a stroke never touches the line tools' points");
        o.gradientAnchors.add(new Point(5, 0, 0));
        assertThrows(IllegalArgumentException.class, o::validate);
    }

    @Test
    void optionsRefuseTooManyStopsAndBoundTheBlend() {
        var o = new Options();
        o.gradientBlend = 7;
        o.validate();
        assertEquals(1, o.gradientBlend);
        for (int i = 0; i < GradientStop.MAX; i++) o.gradientStops.add(new GradientStop(0, 0, ""));
        assertThrows(IllegalArgumentException.class, o::validate);
    }
}
