package dev.craftium.core;

import java.util.List;

/**
 * The size controls a shape has, and how they change the sizes the shape is built with.
 * A shape's size along each axis is a factor on its radius (see {@link Options#sizeX}); a control shows it as the number of blocks the shape spans,
 * {@code 2 * radius * size + 1}, which is what the scale handles measure too. Some controls move two axes together, such as the diameter of a cylinder.
 */
public final class ShapeSizes {
    private ShapeSizes() {}

    /** One control: its name and the axes it sets (0 X, 1 Y, 2 Z). */
    public record Row(String label, int[] axes) {}

    private static final Row X = new Row("Width (X)", new int[]{0}), Y = new Row("Height (Y)", new int[]{1}), Z = new Row("Depth (Z)", new int[]{2});

    /** The controls that mean something for the shape: a plane has a width and a depth, a cone a base and a height, a cube all three sides. */
    public static List<Row> rows(BrushMath.Shape shape) {
        return switch (shape) {
            case SPHERE, CUBE, OCTAHEDRON -> List.of(X, Y, Z);
            case CYLINDER, TUBE, TORUS -> List.of(new Row("Diameter", new int[]{0, 2}), new Row("Height", new int[]{1}));
            case CONE, PYRAMID, HEMISPHERE -> List.of(new Row("Base", new int[]{0, 2}), new Row("Height", new int[]{1}));
            case PLANE, DISK, POLYGON, IRREGULAR_POLYGON, STAR -> List.of(X, Z);
        };
    }

    public static double size(Options o, int axis) { return axis == 0 ? o.sizeX : axis == 1 ? o.sizeY : o.sizeZ; }
    private static void setSize(Options o, int axis, double value) {
        value = Math.clamp(value, .1, 8);
        if (axis == 0) o.sizeX = value; else if (axis == 1) o.sizeY = value; else o.sizeZ = value;
    }

    /** How many blocks the shape spans along the control's axis. */
    public static double count(Options o, Row row) { return 2 * Math.max(1, o.radius) * size(o, row.axes()[0]) + 1; }
    /** The most blocks a control can ask for: the largest size, and no more than a shape can reach. */
    public static double maxCount(Options o) { return Math.min(2 * Math.max(1, o.radius) * 8 + 1, 2 * BrushMath.MAX_REACH + 1); }
    public static double defaultCount(Options o) { return 2 * Math.max(1, o.radius) + 1; }

    /** Sets a control to span {@code count} blocks. With {@code o.sizeChain} the other controls follow by the same factor, so the proportions stay. */
    public static void set(Options o, List<Row> rows, Row row, double count) {
        double s = Math.clamp((count - 1) / (2 * Math.max(1, o.radius)), .1, 8);
        double before = size(o, row.axes()[0]), factor = before > 0 ? s / before : 1;
        for (Row other : rows)
            for (int axis : other.axes()) {
                if (other == row) setSize(o, axis, s);
                else if (o.sizeChain) setSize(o, axis, size(o, axis) * factor);
            }
        fit(o, rows);fitBlocks(o, rows);
    }

    /** Keeps the whole shape within the reach a shape may have, shrinking every used axis by the same factor. */
    public static void fit(Options o, List<Row> rows) {
        double largest = 0;
        for (Row row : rows) for (int axis : row.axes()) largest = Math.max(largest, size(o, axis));
        double allowed = BrushMath.MAX_REACH / Math.max(1, o.outerRadius());
        if (largest <= allowed || largest == 0) return;
        double k = allowed / largest;
        for (Row row : rows) for (int axis : row.axes()) setSize(o, axis, size(o, axis) * k);
    }

    /** Whether the shape, as scaled, makes no more blocks than an edit may hold and is not too big to be looked at. */
    private static boolean fitsBlocks(Options o, List<Row> rows) {
        double largest = 0, volume = 1;
        var seen = new boolean[3];
        for (Row row : rows) for (int axis : row.axes()) if (!seen[axis]) { seen[axis] = true; largest = Math.max(largest, size(o, axis)); volume *= size(o, axis); }
        if (o.sizeX == 1 && o.sizeY == 1 && o.sizeZ == 1) return true; // the radius alone was held to the limits already
        int outer = o.outerRadius(), extent = (int) Math.ceil(outer * largest);
        double cells = (2.0 * extent + 1) * (2.0 * extent + 1) * (2.0 * extent + 1);
        // A wall's blocks grow with the surface, a solid's with the volume.
        double base = ShapeLimits.blocks(o.shape, o.radius, o.hollow, o.outlineThickness, o.pixelPerfect, o.outlineShift(), o.polygonSides, o.outline);
        double blocks = base * (o.hollow || o.outline ? Math.pow(volume, 2.0 / 3) : volume);
        return cells <= ShapeLimits.SCAN_CELLS && blocks <= Selection.LIMIT;
    }

    /** Shrinks the sizes together, a little at a time, until the scaled shape stays within the limits on its blocks. */
    public static void fitBlocks(Options o, List<Row> rows) {
        for (int i = 0; i < 80 && !fitsBlocks(o, rows); i++)
            for (Row row : rows) for (int axis : row.axes()) setSize(o, axis, size(o, axis) * .92);
    }

    public static void reset(Options o) { o.sizeX = o.sizeY = o.sizeZ = 1; }
    public static boolean isDefault(Options o) { return o.sizeX == 1 && o.sizeY == 1 && o.sizeZ == 1; }
}
