package dev.craftium.core;

import java.util.*;

/**
 * Shapes that place more than one kind of block and turn stairs the right way on their own: roofs and flights of stairs. A model asked to
 * write a roof as rows of stairs turns them the wrong way round more often than not; here the facing follows from the shape, so it cannot.
 * Positions are whatever coordinates the caller uses; block names are given by the caller and get their state here.
 */
public final class BuildShapes {
    private BuildShapes() {}

    /** One block of a generated shape. */
    public record Placed(Point at, String block) {}

    enum Facing {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
        final int dx, dz;
        Facing(int dx, int dz) { this.dx = dx; this.dz = dz; }
        String id() { return name().toLowerCase(Locale.ROOT); }
        Facing opposite() { return values()[(ordinal() + 2) % 4]; }
        /** A quarter turn anticlockwise seen from above, as the game's Direction.getCounterClockWise. */
        Facing anticlockwise() { return values()[(ordinal() + 3) % 4]; }
        boolean sameAxis(Facing other) { return dx == 0 == (other.dx == 0); }
        static Facing toward(int dx, int dz) {
            for (var f : values()) if (f.dx == Integer.signum(dx) && f.dz == Integer.signum(dz)) return f;
            throw new IllegalArgumentException("not a horizontal direction");
        }
    }

    /** The blocks a roof is made of: stairs (a block id with no state), the full block for the ridge and gable ends, and a slab to cap the ridge (or null). */
    public record RoofBlocks(String stairs, String block, String slab) {}

    /**
     * A roof over the footprint {@code x0..x1} by {@code z0..z1}, its lowest course of stairs at {@code y}, sticking out {@code overhang} past the
     * walls on every side and rising one block for each block in. Every stair faces up the slope, so it needs no checking.
     * <ul>
     * <li>gable: two slopes meeting at a ridge along {@code ridge} ("x" or "z"), the triangle at each end filled with {@code blocks.block}.</li>
     * <li>hip: four slopes, with outer corner stairs where they meet.</li>
     * <li>shed: one slope rising toward {@code rises} (north, east, south or west), its two sides and its high wall filled.</li>
     * </ul>
     * Where the roof stands out past a wall, the wall is built up to meet it, so no gap shows under the eaves.
     */
    public static List<Placed> roof(String style, int x0, int z0, int x1, int z1, int y, int overhang, String ridge, String rises, RoofBlocks blocks) {
        if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
        if (z0 > z1) { int t = z0; z0 = z1; z1 = t; }
        return switch (style) {
            case "gable" -> gable(x0, z0, x1, z1, y, overhang, ridge == null ? (x1 - x0 >= z1 - z0 ? "x" : "z") : ridge, blocks);
            case "hip" -> hip(x0, z0, x1, z1, y, overhang, blocks);
            case "shed" -> shed(x0, z0, x1, z1, y, overhang, rises == null ? "north" : rises, blocks);
            default -> throw new IllegalArgumentException("\"style\" must be gable, hip or shed");
        };
    }

    /** Coordinates along and across the ridge, so one routine builds a ridge along x or along z. */
    private record Frame(boolean alongX) {
        Point at(int along, int y, int across) { return alongX ? new Point(along, y, across) : new Point(across, y, along); }
        /** The facing that points up the slope from the low side of {@code across} (positive) or the high side (negative). */
        Facing up(int sign) { return alongX ? (sign > 0 ? Facing.SOUTH : Facing.NORTH) : (sign > 0 ? Facing.EAST : Facing.WEST); }
    }

    private static List<Placed> gable(int x0, int z0, int x1, int z1, int y, int o, String ridge, RoofBlocks blocks) {
        if (!ridge.equals("x") && !ridge.equals("z")) throw new IllegalArgumentException("\"ridge\" must be \"x\" or \"z\"");
        var f = new Frame(ridge.equals("x"));
        int u0 = f.alongX ? x0 : z0, u1 = f.alongX ? x1 : z1, v0 = f.alongX ? z0 : x0, v1 = f.alongX ? z1 : x1;
        var walls = new LinkedHashMap<Point, String>();
        var stairs = new LinkedHashMap<Point, Facing>();
        var top = new LinkedHashMap<Point, String>();
        for (int k = 0; ; k++) {
            int low = v0 - o + k, high = v1 + o - k, at = y + k;
            if (low > high) break;
            if (low == high) { ridge(f, u0 - o, u1 + o, at, low, blocks, top); break; }
            for (int u = u0 - o; u <= u1 + o; u++) { stairs.put(f.at(u, at, low), f.up(1)); stairs.put(f.at(u, at, high), f.up(-1)); }
            for (int v = Math.max(low + 1, v0); v <= Math.min(high - 1, v1); v++) { walls.put(f.at(u0, at, v), blocks.block()); walls.put(f.at(u1, at, v), blocks.block()); }
            if (low < v0) for (int u = u0; u <= u1; u++) { walls.put(f.at(u, at, v0), blocks.block()); walls.put(f.at(u, at, v1), blocks.block()); }
            if (high - low == 2) { ridge(f, u0 - o, u1 + o, at, low + 1, blocks, top); break; }
            if (high - low == 1) break;
        }
        return assemble(walls, stairs, top, blocks);
    }

    private static void ridge(Frame f, int from, int to, int y, int across, RoofBlocks blocks, Map<Point, String> top) {
        for (int u = from; u <= to; u++) {
            top.put(f.at(u, y, across), blocks.block());
            if (blocks.slab() != null) top.put(f.at(u, y + 1, across), withState(blocks.slab(), "type=bottom"));
        }
    }

    private static List<Placed> hip(int x0, int z0, int x1, int z1, int y, int o, RoofBlocks blocks) {
        var walls = new LinkedHashMap<Point, String>();
        var stairs = new LinkedHashMap<Point, Facing>();
        var top = new LinkedHashMap<Point, String>();
        for (int k = 0; ; k++) {
            int xa = x0 - o + k, xb = x1 + o - k, za = z0 - o + k, zb = z1 + o - k, at = y + k;
            if (xa > xb || za > zb) break;
            if (xa == xb || za == zb) { for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) cap(new Point(x, at, z), blocks, top); break; }
            for (int x = xa; x <= xb; x++) { stairs.put(new Point(x, at, za), Facing.SOUTH); stairs.put(new Point(x, at, zb), Facing.NORTH); }
            for (int z = za + 1; z <= zb - 1; z++) { stairs.put(new Point(xa, at, z), Facing.EAST); stairs.put(new Point(xb, at, z), Facing.WEST); }
            if (k < o) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
                if (x == x0 || x == x1 || z == z0 || z == z1) walls.put(new Point(x, at, z), blocks.block());
            // The ring closes over one row (a ridge) or one block (a point) on the layer above it, kept level with this course.
            if (xb - xa == 2 || zb - za == 2) { for (int x = xa + 1; x <= xb - 1; x++) for (int z = za + 1; z <= zb - 1; z++) cap(new Point(x, at, z), blocks, top); break; }
            if (xb - xa == 1 || zb - za == 1) break;
        }
        return assemble(walls, stairs, top, blocks);
    }

    private static void cap(Point at, RoofBlocks blocks, Map<Point, String> top) {
        top.put(at, blocks.block());
        if (blocks.slab() != null) top.put(new Point(at.x(), at.y() + 1, at.z()), withState(blocks.slab(), "type=bottom"));
    }

    private static List<Placed> shed(int x0, int z0, int x1, int z1, int y, int o, String rises, RoofBlocks blocks) {
        Facing up;
        try { up = Facing.valueOf(rises.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("\"rises\" must be north, east, south or west"); }
        var f = new Frame(up.dx == 0); // rising north or south: courses run along x
        int sign = up == Facing.SOUTH || up == Facing.EAST ? 1 : -1;
        int u0 = f.alongX ? x0 : z0, u1 = f.alongX ? x1 : z1;
        int vLow = sign > 0 ? (f.alongX ? z0 : x0) : (f.alongX ? z1 : x1), vHigh = sign > 0 ? (f.alongX ? z1 : x1) : (f.alongX ? z0 : x0);
        var walls = new LinkedHashMap<Point, String>();
        var stairs = new LinkedHashMap<Point, Facing>();
        int courses = Math.abs(vHigh - vLow) + 2 * o + 1;
        for (int k = 0; k < courses; k++) {
            int row = vLow - sign * o + sign * k, at = y + k;
            for (int u = u0 - o; u <= u1 + o; u++) stairs.put(f.at(u, at, row), up);
            // Under each course: the two sides and the high wall, from the wall line up to the roof.
            for (int v = row + sign; sign > 0 ? v <= vHigh : v >= vHigh; v += sign) {
                if (sign > 0 ? v < vLow : v > vLow) continue;
                walls.put(f.at(u0, at, v), blocks.block());
                walls.put(f.at(u1, at, v), blocks.block());
                if (v == vHigh) for (int u = u0; u <= u1; u++) walls.put(f.at(u, at, v), blocks.block());
            }
            if (sign > 0 ? row < vLow : row > vLow) for (int u = u0; u <= u1; u++) walls.put(f.at(u, at, vLow), blocks.block());
        }
        return assemble(walls, stairs, new LinkedHashMap<>(), blocks);
    }

    /** Walls first, then the stairs with their shapes, then the ridge and its cap, each overwriting what is under it. */
    private static List<Placed> assemble(Map<Point, String> walls, Map<Point, Facing> stairs, Map<Point, String> top, RoofBlocks blocks) {
        var out = new LinkedHashMap<Point, String>();
        walls.forEach(out::put);
        var shapes = shapes(stairs, "bottom");
        for (var entry : stairs.entrySet())
            out.put(entry.getKey(), withState(blocks.stairs(), "facing=" + entry.getValue().id() + ",half=bottom,shape=" + shapes.get(entry.getKey())));
        top.forEach(out::put);
        var list = new ArrayList<Placed>();
        out.forEach((p, b) -> list.add(new Placed(p, b)));
        return list;
    }

    /**
     * The shape each stair takes from its neighbours on the same layer, worked out as the game does when stairs are placed by hand
     * (StairBlock.getStairsShape): a stair whose high side meets one turned across it is an outer corner, one whose low side meets one is an
     * inner corner, and otherwise it is straight. All the stairs here are the same half.
     */
    static Map<Point, String> shapes(Map<Point, Facing> stairs, String half) {
        var out = new HashMap<Point, String>();
        for (var entry : stairs.entrySet()) {
            Point p = entry.getKey();
            Facing facing = entry.getValue();
            String shape = "straight";
            Facing behind = stairs.get(step(p, facing));
            Facing front = stairs.get(step(p, facing.opposite()));
            if (behind != null && !behind.sameAxis(facing) && canTake(stairs, p, facing, behind.opposite()))
                shape = behind == facing.anticlockwise() ? "outer_left" : "outer_right";
            else if (front != null && !front.sameAxis(facing) && canTake(stairs, p, facing, front))
                shape = front == facing.anticlockwise() ? "inner_left" : "inner_right";
            out.put(p, shape);
        }
        return out;
    }

    private static boolean canTake(Map<Point, Facing> stairs, Point p, Facing facing, Facing side) {
        Facing neighbour = stairs.get(step(p, side));
        return neighbour == null || neighbour != facing;
    }

    private static Point step(Point p, Facing f) { return new Point(p.x() + f.dx, p.y(), p.z() + f.dz); }

    /**
     * A straight flight of stairs from {@code from} (the bottom or top step) to {@code to}: they must differ by as much in height as along one
     * horizontal axis. Each step faces the way you walk to climb it, the flight is {@code width} wide toward +x or +z (whichever runs across
     * it), and with {@code fill} each step stands on a solid column down to the lowest step's level.
     */
    public static List<Placed> stairs(Point from, Point to, int width, String stairsBlock, String fill) {
        int dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        if (dy == 0 || (dx != 0) == (dz != 0) || Math.abs(dx + dz) != Math.abs(dy))
            throw new IllegalArgumentException("\"from\" and \"to\" must differ by as much in height as along x or along z (a 45 degree flight), such as [0,0,0] to [0,4,4]");
        Facing run = Facing.toward(dx, dz);
        Facing climb = dy > 0 ? run : run.opposite();
        int steps = Math.abs(dy) + 1, ySign = Integer.signum(dy), bottom = Math.min(from.y(), to.y());
        int wx = run.dx == 0 ? 1 : 0, wz = run.dz == 0 ? 1 : 0;
        var out = new ArrayList<Placed>();
        for (int i = 0; i < steps; i++) {
            int x = from.x() + run.dx * i, y = from.y() + ySign * i, z = from.z() + run.dz * i;
            for (int w = 0; w < width; w++) {
                int sx = x + wx * w, sz = z + wz * w;
                if (fill != null) for (int fy = bottom; fy < y; fy++) out.add(new Placed(new Point(sx, fy, sz), fill));
                out.add(new Placed(new Point(sx, y, sz), withState(stairsBlock, "facing=" + climb.id() + ",half=bottom,shape=straight")));
            }
        }
        return out;
    }

    /** A block name with a state added, refusing one that already has a state of its own. */
    static String withState(String block, String state) {
        if (block.contains("[")) throw new IllegalArgumentException("\"" + block + "\" must be a block id without a [state]: its state is set for you");
        return block + "[" + state + "]";
    }
}
