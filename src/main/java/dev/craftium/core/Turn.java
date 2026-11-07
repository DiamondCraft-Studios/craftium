package dev.craftium.core;

/**
 * A quarter turn and/or mirror of the horizontal plane, as a 2 by 2 matrix on (x, z): {@code x' = a x + b z}, {@code z' = c x + d z}.
 * Seen from above with north up, one clockwise quarter turn takes north to east, as the game's {@code Rotation.CLOCKWISE_90} does, so
 * a block state turned with the game's own rotate and mirror matches a position turned here.
 */
public record Turn(int a, int b, int c, int d) {
    public static final Turn NONE = new Turn(1, 0, 0, 1);
    /** One quarter turn clockwise seen from above: (x, z) to (-z, x). */
    public static final Turn CLOCKWISE = new Turn(0, -1, 1, 0);
    /** x to -x: east and west swap (the game's {@code Mirror.FRONT_BACK}). */
    public static final Turn MIRROR_X = new Turn(-1, 0, 0, 1);
    /** z to -z: north and south swap (the game's {@code Mirror.LEFT_RIGHT}). */
    public static final Turn MIRROR_Z = new Turn(1, 0, 0, -1);

    /** Mirrored first (along x, along z or not at all), then turned {@code degrees} clockwise. */
    public static Turn of(int degrees, String mirror) {
        if (degrees % 90 != 0) throw new IllegalArgumentException("\"rotate\" must be 0, 90, 180 or 270");
        Turn turn = mirror == null ? NONE : switch (mirror) {
            case "x" -> MIRROR_X;
            case "z" -> MIRROR_Z;
            default -> throw new IllegalArgumentException("\"mirror\" must be \"x\" or \"z\"");
        };
        for (int k = Math.floorMod(degrees / 90, 4); k > 0; k--) turn = CLOCKWISE.then(turn);
        return turn;
    }

    /** This turn applied after {@code first}. */
    public Turn then(Turn first) {
        return new Turn(a * first.a + b * first.c, a * first.b + b * first.d, c * first.a + d * first.c, c * first.b + d * first.d);
    }

    public int x(int x, int z) { return a * x + b * z; }
    public int z(int x, int z) { return c * x + d * z; }

    /** Whether the turn mirrors (it then equals {@link #MIRROR_X} followed by {@link #quarterTurns()} clockwise turns). */
    public boolean mirrored() { return a * d - b * c < 0; }

    /** The clockwise quarter turns left once the mirror (along x, if any) is taken out. */
    public int quarterTurns() {
        Turn rest = mirrored() ? then(MIRROR_X) : this; // MIRROR_X is its own inverse
        Turn k = NONE;
        for (int n = 0; n < 4; n++, k = CLOCKWISE.then(k)) if (k.equals(rest)) return n;
        throw new IllegalStateException("not a quarter turn: " + this);
    }

    /** The size of an sx by sz footprint once turned: an odd number of quarter turns swaps the sides. */
    public int[] size(int sx, int sz) { return b == 0 ? new int[]{sx, sz} : new int[]{sz, sx}; }

    /**
     * Where a cell of an sx by sz box (positions 0 to size-1) lands when the box is turned in place, so the turned box again starts at 0.
     * @return {x, z}
     */
    public int[] inBox(int x, int z, int sx, int sz) {
        int minX = Math.min(Math.min(x(0, 0), x(sx - 1, 0)), Math.min(x(0, sz - 1), x(sx - 1, sz - 1)));
        int minZ = Math.min(Math.min(z(0, 0), z(sx - 1, 0)), Math.min(z(0, sz - 1), z(sx - 1, sz - 1)));
        return new int[]{x(x, z) - minX, z(x, z) - minZ};
    }
}
