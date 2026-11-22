package dev.craftium.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One colour stop of the Gradient window: where it sits along the gradient ({@code at}, 0 to 1), its colour, and the block it was taken from,
 * or an empty string for a colour that was picked on its own.
 */
public record GradientStop(double at, int rgb, String block) {
    /** The most stops a gradient holds. */
    public static final int MAX = 32;

    public GradientStop {
        at = Double.isFinite(at) ? Math.clamp(at, 0, 1) : 0;
        rgb &= 0xffffff;
        block = block == null ? "" : block;
    }

    /** Stone grey to deepslate grey, the two blocks the Colour Studio starts with. */
    public static List<GradientStop> defaults() {
        return new ArrayList<>(List.of(
                new GradientStop(0, 0x7d7d7d, "minecraft:stone"),
                new GradientStop(1, 0x505053, "minecraft:deepslate")));
    }

    /** The colour of the gradient at {@code t} (0 to 1), mixed in OKLab between the stops on either side; before the first stop and after the last it is theirs. */
    public static int colourAt(List<GradientStop> stops, double t) {
        if (stops.isEmpty()) return 0;
        var sorted = sorted(stops);
        if (t <= sorted.getFirst().at()) return sorted.getFirst().rgb();
        for (int i = 1; i < sorted.size(); i++) {
            var a = sorted.get(i - 1);
            var b = sorted.get(i);
            if (t <= b.at()) {
                double span = b.at() - a.at();
                return span <= 0 ? b.rgb() : ColourMath.interpolate(a.rgb(), b.rgb(), (t - a.at()) / span);
            }
        }
        return sorted.getLast().rgb();
    }

    /** The stops from the start of the gradient to its end. */
    public static List<GradientStop> sorted(List<GradientStop> stops) {
        var sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingDouble(GradientStop::at));
        return sorted;
    }

    public GradientStop at(double where) {
        return new GradientStop(where, rgb, block);
    }

    public GradientStop coloured(int colour, String from) {
        return new GradientStop(at, colour, from);
    }
}
