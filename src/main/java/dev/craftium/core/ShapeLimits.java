package dev.craftium.core;

/**
 * How big a shape can be. Two things bound it: the blocks it makes, which may not pass {@link Selection#LIMIT}, and the cells that are
 * looked at to find them, which {@link #SCAN_CELLS} keeps to what a server tick budget can stand. A flat disk or a hollow ring makes few
 * blocks and looks at few cells, so it can be far wider than a filled sphere, which is what the largest radius here works out per shape.
 * The counts are the formulas for the shapes, a block or two off the real number, so a shape near the limit may still be refused by the
 * builder, which counts exactly.
 */
public final class ShapeLimits {
    private ShapeLimits() {}
    /** The most cells a shape may have looked at to make it: about a second of work, and the size of the grid a turned shape is held in. */
    public static final long SCAN_CELLS = 1L << 27;

    /** The blocks a solid shape of this radius is made of. */
    public static double solid(BrushMath.Shape shape, int radius, int sides) {
        if (radius < 0) return 0;
        double r = radius, reach = r + .5, side = 2 * r + 1, disk = Math.PI * reach * reach;
        return switch (shape) {
            case SPHERE -> 4.0 / 3 * Math.PI * reach * reach * reach;
            case CUBE -> side * side * side;
            case OCTAHEDRON -> side * (2 * r * r + 2 * r + 3) / 3;
            case CYLINDER -> disk * side;
            case PLANE -> side * side;
            case DISK -> disk;
            case POLYGON -> disk * (Math.max(3, sides) * Math.sin(2 * Math.PI / Math.max(3, sides)) / (2 * Math.PI));
            case PYRAMID -> side * (side + 1) * (2 * side + 1) / 6;
            case CONE -> disk * side / 3;
            case HEMISPHERE -> 2.0 / 3 * Math.PI * reach * reach * reach;
            case TORUS -> { double tube = .25 * r + .5; yield 2 * Math.PI * Math.PI * .75 * r * tube * tube; }
            case TUBE -> Math.PI * (reach * reach - r * r / 4) * side;
        };
    }

    /** The cells looked at to make a shape whose outer edge is {@code outer} blocks from its centre. */
    public static double cells(BrushMath.Shape shape, int outer) {
        double side = 2.0 * outer + 1, reach = outer + .5;
        return switch (shape) {
            case PLANE, DISK, POLYGON -> side * side; // one layer
            case SPHERE -> 4.0 / 3 * Math.PI * reach * reach * reach + side * side;
            case HEMISPHERE -> 2.0 / 3 * Math.PI * reach * reach * reach + side * side;
            case TORUS -> Math.PI * reach * reach * (.5 * outer + 2) + side * side;
            case CYLINDER, CONE, TUBE -> Math.PI * reach * reach * side + side * side;
            default -> side * side * side; // a cube, octahedron or pyramid is looked at all through
        };
    }

    /** The blocks the brush holds before it is done: for a pixel perfect wall that is the whole solid, which is then thinned. */
    public static double blocks(BrushMath.Shape shape, int radius, boolean hollow, int thickness, boolean pixelPerfect, int shift, int sides, boolean outline) {
        int outer = Math.min(radius + (hollow && !outline ? Math.max(0, shift) : 0), BrushMath.MAX_REACH);
        if (outline) return Math.min(solid(shape, outer, sides), 24.0 * outer + 24); // the rim of the shape, in all three planes
        if (!hollow || pixelPerfect) return solid(shape, outer, sides);
        int wall = Math.clamp(thickness, 1, Math.max(1, radius));
        double shell = solid(shape, outer, sides) - solid(shape, outer - wall, sides);
        // The inner copy of a ring is not inside the outer one all the way round, so its wall is about its surface, which the difference of volumes misses.
        if (shape == BrushMath.Shape.TORUS) shell = Math.max(shell, 4 * Math.PI * Math.PI * .75 * outer * (.25 * outer + .5));
        // Shapes whose inner copy is not exactly inside the outer one (a ring, a tube) leave a thicker wall than the volumes say: a little is added, so a wall is never guessed short.
        return Math.min(shell * 1.15, solid(shape, outer, sides));
    }

    /** Whether a brush shape with these settings stays within both limits. */
    public static boolean fits(BrushMath.Shape shape, int radius, boolean hollow, int thickness, boolean pixelPerfect, int shift, int sides, boolean outline) {
        int outer = Math.min(radius + (hollow && !outline ? Math.max(0, shift) : 0), BrushMath.MAX_REACH);
        return blocks(shape, radius, hollow, thickness, pixelPerfect, shift, sides, outline) <= Selection.LIMIT && cells(shape, outer) <= SCAN_CELLS;
    }

    /** The largest radius a shape with the options' settings can have, from 1 to {@link BrushMath#MAX_RADIUS}. */
    public static int maxRadius(Options o) {
        var shape = o.shape == null ? BrushMath.Shape.SPHERE : o.shape;
        // Another kind of shape is held to what a sphere may do, and its cells are looked at all through.
        boolean plain = o.shapeKind == null || o.shapeKind == Options.ShapeKind.BRUSH;
        int lo = 1, hi = BrushMath.MAX_RADIUS;
        while (lo < hi) { // the limits only tighten as the radius grows, so the largest radius that fits can be found by halving
            int mid = (lo + hi + 1) >>> 1;
            boolean ok = plain ? fits(shape, mid, o.hollow, o.outlineThickness, o.pixelPerfect, o.outlineShift(mid), o.polygonSides, o.outline)
                : fits(BrushMath.Shape.CUBE, mid, false, 1, false, 0, 3, false) && solid(BrushMath.Shape.SPHERE, mid, 3) <= Selection.LIMIT * (o.hollow ? 8.0 : 1);
            if (ok) lo = mid; else hi = mid - 1;
        }
        return lo;
    }
}
