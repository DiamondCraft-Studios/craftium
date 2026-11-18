package dev.craftium.core;

import java.util.function.ToIntFunction;

/**
 * Which blocks of a Rock's brush the boulder keeps. The server builds the boulder with it and the editor's hover preview draws
 * it, so the preview shows the rock that will actually be built.
 */
public final class RockShape {
    private static final Point[] NEIGHBOURS = {
        new Point(1, 0, 0),
        new Point(-1, 0, 0),
        new Point(0, 1, 0),
        new Point(0, -1, 0),
        new Point(0, 0, 1),
        new Point(0, 0, -1)
    };

    private RockShape() {}

    /**
     * Whether the boulder centred on {@code c} takes the block at {@code p}.
     *
     * @param adjacent how many of the six blocks around {@code p} are solid in the world, which melds the rock into its surroundings
     */
    public static boolean keeps(Point p, Point c, Options o, NoiseField noise, ToIntFunction<Point> adjacent) {
        double distance = Math.sqrt(p.distanceSquared(c)) / o.radius;
        double rockNoise = noise.sample(p);
        if (o.denoise) {
            for (Point direction : NEIGHBOURS)
                rockNoise += noise.sample(p.add(direction.x(), direction.y(), direction.z()));
            rockNoise /= 7;
        }
        return distance < .72 + (rockNoise - .5) * o.strength * .35 + adjacent.applyAsInt(p) / 6d * .1 * o.meldStrength;
    }
}
