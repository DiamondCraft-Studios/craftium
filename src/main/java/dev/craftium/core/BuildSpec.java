package dev.craftium.core;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * A build described as a short list of primitives, for an AI model to write and for Craftium to check before anything
 * touches the world. Coordinates are whole numbers in the world, so a spec uses the same numbers the AI read from the world; an
 * optional {@code "origin": [x,y,z]} is added to every coordinate, to write a spec near zero and place it anywhere. The area a
 * spec covers is worked out from its operations, so nothing has to be selected first. This class knows nothing about Minecraft: it
 * turns the description into block names at positions and the server resolves those names.
 *
 * <pre>
 * { "palette": {"sand": "minecraft:cut_sandstone"},
 *   "ops": [
 *     {"op": "cylinder", "base": [2,0,2], "radius": 2, "height": 9, "axis": "y", "block": "sand"},
 *     {"op": "box", "from": [0,9,0], "to": [4,9,4], "block": "minecraft:smooth_sandstone_slab[type=bottom]"} ] }
 * </pre>
 *
 * Operations: {@code box}, {@code sphere}, {@code cylinder}, {@code cone}, {@code pyramid}, {@code line}, {@code set} and
 * {@code replace}. Every operation may carry {@code "hollow": true} (shapes only) and {@code "repeat": {"count": n, "step": [x,y,z]}}.
 * Later operations overwrite earlier ones; {@code minecraft:air} carves.
 */
public final class BuildSpec {
    public static final int MAX_OPS = 256, MAX_REPEAT = 64, MAX_EXTENT = 256, MAX_RADIUS = 128, MAX_BLOCKS = 131_072, MAX_PALETTE = 64;
    /** Cells examined across the whole spec, so a pile of large operations cannot stall the server thread. */
    private static final long WORK_BUDGET = 40_000_000L;
    private static final Pattern BLOCK = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[[a-z0-9_=,]*])?|[a-z0-9_./-]+(\\[[a-z0-9_=,]*])?");
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /**
     * An edit of the player's selection: every block in it (or every block that is {@code find}) becomes {@code block}, keeping the
     * properties named in {@code keep} (facing, half, shape...) so stairs stay the way they face. {@code keep} null keeps every property
     * both blocks have. Applied by the server after the shapes, to a selection this class knows nothing about.
     */
    public record SelectionOp(String block, String find, List<String> keep) {}

    /** One block to place. {@code only}, when set, limits the cell to positions currently holding that block (the replace operation). */
    public record Cell(String block, String only) {}
    /** The size of the area in blocks: relative positions from 0 to size-1 on each axis are inside it. */
    public record Bounds(int sx, int sy, int sz) {
        public boolean contains(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz; }
    }
    /**
     * Where a spec's blocks go: the world position of the lowest corner of everything it describes, how large that is, and the shift
     * that moves its coordinates so that corner is (0, 0, 0), which is how {@link #build(Placement)} works on it.
     */
    public record Placement(Point min, Bounds bounds, int[] shift) {}
    /** The cells to place, and how many generated cells fell outside the area and were dropped (shapes are only generated inside the area, so this undercounts them). */
    public record Result(LinkedHashMap<Point, Cell> cells, int clipped, int operations) {}

    /** The most a coordinate can be: the edge of the world. */
    private static final int COORDINATE_LIMIT = 30_000_000;
    private final List<JsonObject> ops = new ArrayList<>();
    private final List<SelectionOp> selectionOps = new ArrayList<>();
    private final Map<String, String> palette = new LinkedHashMap<>();
    private final int[] origin = {0, 0, 0};

    private BuildSpec() {}

    /** Reads and checks a spec. Every failure names the operation at fault, since the message goes back to the model. */
    public static BuildSpec parse(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Build spec is empty");
        if (json.length() > 400_000) throw new IllegalArgumentException("Build spec exceeds 400 KB");
        JsonElement root;
        try { root = JsonParser.parseString(json); } catch (JsonParseException e) { throw new IllegalArgumentException("Build spec is not valid JSON: " + e.getMessage()); }
        if (!root.isJsonObject()) throw new IllegalArgumentException("Build spec must be a JSON object with an \"ops\" list");
        var spec = new BuildSpec();
        var object = root.getAsJsonObject();
        if (object.has("palette")) {
            if (!object.get("palette").isJsonObject()) throw new IllegalArgumentException("\"palette\" must map names to block ids");
            var entries = object.getAsJsonObject("palette").entrySet();
            if (entries.size() > MAX_PALETTE) throw new IllegalArgumentException("Palette has more than " + MAX_PALETTE + " entries");
            for (var entry : entries) {
                if (!ALIAS.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("Palette name \"" + entry.getKey() + "\" must be letters, digits, _ or -");
                spec.palette.put(entry.getKey(), block(entry.getValue(), "palette." + entry.getKey()));
            }
        }
        if (object.has("origin")) {
            var at = object.get("origin");
            if (!at.isJsonArray() || at.getAsJsonArray().size() != 3) throw new IllegalArgumentException("\"origin\" must be [x,y,z]");
            for (int n = 0; n < 3; n++) {
                var part = at.getAsJsonArray().get(n);
                if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber() || part.getAsDouble() != Math.rint(part.getAsDouble()) || Math.abs(part.getAsDouble()) > COORDINATE_LIMIT)
                    throw new IllegalArgumentException("\"origin\" must hold whole numbers within the world");
                spec.origin[n] = (int) part.getAsDouble();
            }
        }
        if (!object.has("ops") || !object.get("ops").isJsonArray()) throw new IllegalArgumentException("Build spec needs an \"ops\" list");
        var list = object.getAsJsonArray("ops");
        if (list.isEmpty()) throw new IllegalArgumentException("\"ops\" is empty");
        if (list.size() > MAX_OPS) throw new IllegalArgumentException("Build spec has more than " + MAX_OPS + " operations");
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) throw new IllegalArgumentException("ops[" + i + "] must be an object");
            var op = list.get(i).getAsJsonObject();
            if (op.has("op") && op.get("op").isJsonPrimitive() && "replace_selection".equals(op.get("op").getAsString())) spec.selectionOps.add(spec.selectionOp(op, i));
            else {
                // Selection operations run after every shape, so one listed before a shape would not run in the order it is written in.
                if (!spec.selectionOps.isEmpty())
                    throw fail(i, op.has("op") && op.get("op").isJsonPrimitive() ? op.get("op").getAsString() : "?", "comes after a replace_selection. List replace_selection after all the shapes, because it works on the blocks they leave in the selection");
                spec.ops.add(op);
            }
        }
        return spec;
    }

    /** Reads a replace_selection operation: {"op":"replace_selection","block":B,"find":B (optional),"keep":["facing","half"] (optional)}. */
    private SelectionOp selectionOp(JsonObject op, int i) {
        String kind = "replace_selection";
        if (op.has("repeat") || op.has("hollow")) throw fail(i, kind, "\"repeat\" and \"hollow\" do not apply: it works on the player's selection");
        String block = resolve(op, "block", i);
        String find = op.has("find") ? resolve(op, "find", i) : null;
        List<String> keep = null;
        if (op.has("keep")) {
            if (!op.get("keep").isJsonArray() || op.getAsJsonArray("keep").size() > 16) throw fail(i, kind, "\"keep\" must be a list of property names such as [\"facing\", \"half\", \"shape\"]");
            keep = new ArrayList<>();
            for (var each : op.getAsJsonArray("keep")) {
                if (!each.isJsonPrimitive() || !each.getAsJsonPrimitive().isString() || !each.getAsString().matches("[a-z_]{1,32}"))
                    throw fail(i, kind, "\"keep\" must be a list of property names such as [\"facing\", \"half\", \"shape\"]");
                keep.add(each.getAsString());
            }
        }
        return new SelectionOp(block, find, keep == null ? null : List.copyOf(keep));
    }

    /** The operations on the player's selection, in order, which the server applies after the shapes. */
    public List<SelectionOp> selectionOps() { return List.copyOf(selectionOps); }

    /** Whether the spec has any shapes at all; a spec of selection operations alone has none, and so no area of its own. */
    public boolean hasShapes() { return !ops.isEmpty(); }

    /** Every distinct block name the spec can place, resolved through the palette, so the server can reject unknown ones up front. */
    public Set<String> blocks() {
        var names = new LinkedHashSet<String>();
        for (int i = 0; i < ops.size(); i++) {
            var op = ops.get(i);
            if (op.has("block")) names.add(resolve(op, "block", i));
            if (op.has("find")) names.add(resolve(op, "find", i));
        }
        for (var op : selectionOps) { names.add(op.block()); if (op.find() != null) names.add(op.find()); }
        return names;
    }

    /**
     * The operations that use a block name (one of {@link #blocks()}), labelled the way errors label them ({@code ops[2] (box)}), so a
     * message about the block can say where it is used. Palette names are resolved first, so a palette entry is found through the ops using it.
     */
    public List<String> usesOf(String name) {
        var out = new ArrayList<String>();
        for (int i = 0; i < ops.size(); i++) {
            var op = ops.get(i);
            if (op.has("block") && resolve(op, "block", i).equals(name) || op.has("find") && resolve(op, "find", i).equals(name))
                out.add("ops[" + i + "] (" + text(op, "op", i, null) + ")");
        }
        for (int k = 0; k < selectionOps.size(); k++) {
            var op = selectionOps.get(k);
            if (op.block().equals(name) || name.equals(op.find())) out.add("ops[" + (ops.size() + k) + "] (replace_selection)");
        }
        return out;
    }

    /**
     * Where the spec's blocks go and how much room they need, from its operations alone. Each operation's extent is worked out from
     * its numbers, repeats included, so a spec that reaches further than the build limits is refused here with a message.
     */
    public Placement place() {
        if (ops.isEmpty()) throw new IllegalArgumentException("The spec has no shapes, only operations on the selection, so it has no area of its own");
        long[] low = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE}, high = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        for (int i = 0; i < ops.size(); i++) {
            var op = ops.get(i);
            String kind = text(op, "op", i, null);
            long[] extent = extent(kind, op, i);
            int count = 1;
            int[] step = {0, 0, 0};
            if (op.has("repeat")) {
                if (!op.get("repeat").isJsonObject()) throw fail(i, kind, "\"repeat\" must be {\"count\": n, \"step\": [x,y,z]}");
                var repeat = op.getAsJsonObject("repeat");
                count = integer(repeat, "count", i, kind, 1, MAX_REPEAT);
                step = vector(repeat, "step", i, kind);
            }
            for (int axis = 0; axis < 3; axis++) {
                long last = (long) step[axis] * (count - 1);
                low[axis] = Math.min(low[axis], Math.min(extent[axis], extent[axis] + last));
                high[axis] = Math.max(high[axis], Math.max(extent[axis + 3], extent[axis + 3] + last));
            }
        }
        String[] names = {"x", "y", "z"};
        for (int axis = 0; axis < 3; axis++)
            if (high[axis] - low[axis] + 1 > MAX_EXTENT)
                throw new IllegalArgumentException("The build spans " + (high[axis] - low[axis] + 1) + " blocks along " + names[axis] + "; at most " + MAX_EXTENT + " are allowed, so split it into builds that fit");
        return new Placement(new Point((int) (low[0] + origin[0]), (int) (low[1] + origin[1]), (int) (low[2] + origin[2])),
                new Bounds((int) (high[0] - low[0] + 1), (int) (high[1] - low[1] + 1), (int) (high[2] - low[2] + 1)),
                new int[]{(int) -low[0], (int) -low[1], (int) -low[2]});
    }

    /** The cells the spec places, at positions relative to {@code placement.min()}. */
    public Result build(Placement placement) { return build(placement.bounds(), placement.shift()); }

    /** Works out the cells the spec places inside {@code bounds}, with positions in the spec taken as relative to the area's lowest corner. */
    public Result build(Bounds bounds) { return build(bounds, new int[]{0, 0, 0}); }

    private Result build(Bounds bounds, int[] shift) {
        if (bounds.sx() < 1 || bounds.sy() < 1 || bounds.sz() < 1) throw new IllegalArgumentException("The build area is empty");
        if (bounds.sx() > MAX_EXTENT || bounds.sy() > MAX_EXTENT || bounds.sz() > MAX_EXTENT)
            throw new IllegalArgumentException("The build area may be at most " + MAX_EXTENT + " blocks along each side; select a smaller area");
        var cells = new LinkedHashMap<Point, Cell>();
        long[] budget = {WORK_BUDGET};
        int clipped = 0;
        for (int i = 0; i < ops.size(); i++) {
            var op = ops.get(i);
            String kind = text(op, "op", i, null);
            int count = 1;
            int[] step = {0, 0, 0};
            if (op.has("repeat")) {
                if (!op.get("repeat").isJsonObject()) throw fail(i, kind, "\"repeat\" must be {\"count\": n, \"step\": [x,y,z]}");
                var repeat = op.getAsJsonObject("repeat");
                count = integer(repeat, "count", i, kind, 1, MAX_REPEAT);
                step = vector(repeat, "step", i, kind);
            }
            for (int n = 0; n < count; n++) {
                var shape = shape(kind, op, i, bounds, step[0] * n + shift[0], step[1] * n + shift[1], step[2] * n + shift[2], budget);
                String block = resolve(op, "block", i);
                String only = kind.equals("replace") ? resolve(op, "find", i) : null;
                for (var p : shape) {
                    var cell = new Cell(block, only);
                    if (!bounds.contains(p.x(), p.y(), p.z())) { clipped++; continue; }
                    var earlier = cells.get(p);
                    if (cell.only() != null && earlier != null) {
                        // A replace acts on what the spec has placed so far, then stands as a plain block; where that is something else it leaves it be.
                        if (!matches(earlier.block(), cell.only())) continue;
                        cell = new Cell(cell.block(), earlier.only());
                    }
                    cells.remove(p); // a later operation goes last, as well as on top
                    cells.put(p, cell);
                    if (cells.size() > MAX_BLOCKS) throw new IllegalArgumentException("Build places more than " + MAX_BLOCKS + " blocks");
                }
            }
        }
        return new Result(cells, clipped, ops.size());
    }

    // ---- shapes -------------------------------------------------------------------------------------------------

    private Collection<Point> shape(String kind, JsonObject op, int i, Bounds bounds, int dx, int dy, int dz, long[] budget) {
        boolean hollow = op.has("hollow") && flag(op, "hollow", i, kind);
        switch (kind) {
            case "set" -> {
                var out = new ArrayList<Point>();
                if (op.has("at") && op.get("at").isJsonArray() && !op.getAsJsonArray("at").isEmpty() && op.getAsJsonArray("at").get(0).isJsonArray()) {
                    var list = op.getAsJsonArray("at");
                    if (list.size() > 4096) throw fail(i, kind, "\"at\" lists more than 4096 positions");
                    for (var each : list) { var v = ints(each, "at", i, kind); out.add(new Point(v[0] + dx, v[1] + dy, v[2] + dz)); }
                } else { var v = vector(op, "at", i, kind); out.add(new Point(v[0] + dx, v[1] + dy, v[2] + dz)); }
                return out;
            }
            case "box", "replace" -> {
                var a = vector(op, "from", i, kind);
                var b = vector(op, "to", i, kind);
                int x0 = Math.min(a[0], b[0]) + dx, x1 = Math.max(a[0], b[0]) + dx, y0 = Math.min(a[1], b[1]) + dy, y1 = Math.max(a[1], b[1]) + dy, z0 = Math.min(a[2], b[2]) + dz, z1 = Math.max(a[2], b[2]) + dz;
                long total = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
                if (total > 4L * MAX_BLOCKS) throw fail(i, kind, "box covers more than " + 4 * MAX_BLOCKS + " blocks; keep it within the build area");
                var out = new ArrayList<Point>();
                for (int x = x0; x <= x1; x++)
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++) {
                            spend(budget);
                            if (hollow && !kind.equals("replace") && x > x0 && x < x1 && y > y0 && y < y1 && z > z0 && z < z1) continue;
                            out.add(new Point(x, y, z));
                        }
                return out;
            }
            case "sphere" -> {
                var c = vector(op, "center", i, kind);
                double[] r = radii(op, i, kind);
                return solid(c[0] + dx, c[1] + dy, c[2] + dz, (int) Math.ceil(r[0]), (int) Math.ceil(r[1]), (int) Math.ceil(r[2]), bounds, budget, hollow,
                        (x, y, z) -> sq(x / (r[0] + .5)) + sq(y / (r[1] + .5)) + sq(z / (r[2] + .5)) <= 1);
            }
            case "cylinder", "cone" -> {
                var base = vector(op, "base", i, kind);
                int radius = integer(op, "radius", i, kind, 0, MAX_RADIUS), height = integer(op, "height", i, kind, 1, MAX_EXTENT);
                int axis = axis(op, i, kind);
                boolean cone = kind.equals("cone");
                return axial(base[0] + dx, base[1] + dy, base[2] + dz, axis, radius, height, bounds, budget, hollow, (along, u, v) -> {
                    double limit = cone ? radius * (1 - (double) along / height) : radius;
                    return u * u + v * v <= (limit + .5) * (limit + .5);
                });
            }
            case "pyramid" -> {
                var base = vector(op, "base", i, kind);
                int radius = integer(op, "radius", i, kind, 0, MAX_RADIUS), height = integer(op, "height", i, kind, 1, MAX_EXTENT);
                int axis = axis(op, i, kind);
                return axial(base[0] + dx, base[1] + dy, base[2] + dz, axis, radius, height, bounds, budget, hollow, (along, u, v) -> {
                    double limit = radius * (1 - (double) along / height);
                    return Math.abs(u) <= Math.floor(limit + .5) && Math.abs(v) <= Math.floor(limit + .5);
                });
            }
            case "line" -> {
                var a = vector(op, "from", i, kind);
                var b = vector(op, "to", i, kind);
                int thickness = op.has("thickness") ? integer(op, "thickness", i, kind, 1, 5) : 1;
                return line(new Point(a[0] + dx, a[1] + dy, a[2] + dz), new Point(b[0] + dx, b[1] + dy, b[2] + dz), thickness, bounds, budget);
            }
            default -> throw fail(i, kind, "unknown operation; use box, sphere, cylinder, cone, pyramid, line, set or replace");
        }
    }

    /** The lowest and highest cell an operation can reach, as {x0, y0, z0, x1, y1, z1}, before any repeat. */
    private long[] extent(String kind, JsonObject op, int i) {
        switch (kind) {
            case "set" -> {
                var list = new ArrayList<int[]>();
                if (op.has("at") && op.get("at").isJsonArray() && !op.getAsJsonArray("at").isEmpty() && op.getAsJsonArray("at").get(0).isJsonArray()) {
                    var all = op.getAsJsonArray("at");
                    if (all.size() > 4096) throw fail(i, kind, "\"at\" lists more than 4096 positions");
                    for (var each : all) list.add(ints(each, "at", i, kind));
                } else list.add(vector(op, "at", i, kind));
                long[] out = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
                for (int[] v : list) for (int a = 0; a < 3; a++) { out[a] = Math.min(out[a], v[a]); out[a + 3] = Math.max(out[a + 3], v[a]); }
                return out;
            }
            case "box", "replace" -> {
                var a = vector(op, "from", i, kind);
                var b = vector(op, "to", i, kind);
                return new long[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
            }
            case "sphere" -> {
                var c = vector(op, "center", i, kind);
                double[] r = radii(op, i, kind);
                int[] reach = {(int) Math.ceil(r[0]), (int) Math.ceil(r[1]), (int) Math.ceil(r[2])};
                return new long[]{c[0] - reach[0], c[1] - reach[1], c[2] - reach[2], c[0] + reach[0], c[1] + reach[1], c[2] + reach[2]};
            }
            case "cylinder", "cone", "pyramid" -> {
                var base = vector(op, "base", i, kind);
                int radius = integer(op, "radius", i, kind, 0, MAX_RADIUS), height = integer(op, "height", i, kind, 1, MAX_EXTENT);
                int axis = axis(op, i, kind);
                long[] out = {base[0] - radius, base[1] - radius, base[2] - radius, base[0] + radius, base[1] + radius, base[2] + radius};
                out[axis] = base[axis];
                out[axis + 3] = (long) base[axis] + height - 1;
                return out;
            }
            case "line" -> {
                var a = vector(op, "from", i, kind);
                var b = vector(op, "to", i, kind);
                int thickness = op.has("thickness") ? integer(op, "thickness", i, kind, 1, 5) : 1;
                int r = (int) Math.ceil((thickness - 1) / 2.0);
                return new long[]{Math.min(a[0], b[0]) - r, Math.min(a[1], b[1]) - r, Math.min(a[2], b[2]) - r, Math.max(a[0], b[0]) + r, Math.max(a[1], b[1]) + r, Math.max(a[2], b[2]) + r};
            }
            default -> throw fail(i, kind, "unknown operation; use box, sphere, cylinder, cone, pyramid, line, set or replace");
        }
    }

    private interface Inside { boolean test(double x, double y, double z); }
    private interface Slice { boolean test(int along, double u, double v); }

    private static List<Point> solid(int cx, int cy, int cz, int rx, int ry, int rz, Bounds bounds, long[] budget, boolean hollow, Inside inside) {
        var kept = new HashSet<Point>();
        for (int x = Math.max(cx - rx, 0); x <= Math.min(cx + rx, bounds.sx() - 1); x++)
            for (int y = Math.max(cy - ry, 0); y <= Math.min(cy + ry, bounds.sy() - 1); y++)
                for (int z = Math.max(cz - rz, 0); z <= Math.min(cz + rz, bounds.sz() - 1); z++) {
                    spend(budget);
                    if (inside.test(x - cx, y - cy, z - cz)) kept.add(new Point(x, y, z));
                }
        return hollow ? shell(kept, (x, y, z) -> inside.test(x - cx, y - cy, z - cz)) : new ArrayList<>(kept);
    }

    private static List<Point> axial(int bx, int by, int bz, int axis, int radius, int height, Bounds bounds, long[] budget, boolean hollow, Slice slice) {
        int[] origin = {bx, by, bz}, size = {bounds.sx(), bounds.sy(), bounds.sz()};
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        var kept = new HashSet<Point>();
        int[] cell = new int[3];
        for (int along = 0; along < height; along++) {
            cell[axis] = origin[axis] + along;
            if (cell[axis] < 0 || cell[axis] >= size[axis]) continue;
            for (int a = Math.max(origin[u] - radius, 0); a <= Math.min(origin[u] + radius, size[u] - 1); a++)
                for (int b = Math.max(origin[v] - radius, 0); b <= Math.min(origin[v] + radius, size[v] - 1); b++) {
                    spend(budget);
                    if (!slice.test(along, a - origin[u], b - origin[v])) continue;
                    cell[u] = a;
                    cell[v] = b;
                    kept.add(new Point(cell[0], cell[1], cell[2]));
                }
        }
        // Whether a neighbour counts as filled is decided from the slice rule, so an open edge of the area still leaves a wall.
        if (!hollow) return new ArrayList<>(kept);
        var out = new ArrayList<Point>();
        for (var p : kept) {
            int[] at = {p.x(), p.y(), p.z()};
            boolean buried = true;
            for (int d = 0; d < 3 && buried; d++)
                for (int s = -1; s <= 1; s += 2) {
                    int[] next = at.clone();
                    next[d] += s;
                    int along = next[axis] - origin[axis];
                    if (along < 0 || along >= height || !slice.test(along, next[u] - origin[u], next[v] - origin[v])) { buried = false; break; }
                }
            if (!buried) out.add(p);
        }
        return out;
    }

    /** The cells of {@code kept} with at least one face-neighbour outside the shape; {@code inside} takes absolute positions. */
    private static List<Point> shell(Set<Point> kept, Inside inside) {
        var out = new ArrayList<Point>();
        for (var p : kept) {
            boolean buried = true;
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}})
                if (!inside.test(p.x() + d[0], p.y() + d[1], p.z() + d[2])) { buried = false; break; }
            if (!buried) out.add(p);
        }
        return out;
    }

    private static List<Point> line(Point a, Point b, int thickness, Bounds bounds, long[] budget) {
        int steps = Math.max(Math.abs(b.x() - a.x()), Math.max(Math.abs(b.y() - a.y()), Math.abs(b.z() - a.z())));
        if (steps > 4 * MAX_EXTENT) throw new IllegalArgumentException("Line is longer than " + 4 * MAX_EXTENT + " blocks");
        var out = new LinkedHashSet<Point>();
        double reach = (thickness - 1) / 2.0;
        int r = (int) Math.ceil(reach);
        for (int s = 0; s <= steps; s++) {
            double t = steps == 0 ? 0 : (double) s / steps;
            int x = (int) Math.round(a.x() + (b.x() - a.x()) * t), y = (int) Math.round(a.y() + (b.y() - a.y()) * t), z = (int) Math.round(a.z() + (b.z() - a.z()) * t);
            for (int ox = -r; ox <= r; ox++)
                for (int oy = -r; oy <= r; oy++)
                    for (int oz = -r; oz <= r; oz++) {
                        spend(budget);
                        if (ox * ox + oy * oy + oz * oz <= (reach + .5) * (reach + .5)) out.add(new Point(x + ox, y + oy, z + oz));
                    }
        }
        return new ArrayList<>(out);
    }

    /** Whether a placed block is the one a replace is looking for: its exact state where the search names one, else any state of the block. */
    public static boolean matches(String block, String find) {
        return find.contains("[") ? block.equals(find) : block.split("\\[", 2)[0].equals(find);
    }

    private static void spend(long[] budget) {
        if (--budget[0] < 0) throw new IllegalArgumentException("Build spec is too expensive; use fewer or smaller operations");
    }
    private static double sq(double v) { return v * v; }

    // ---- field readers ------------------------------------------------------------------------------------------

    private String resolve(JsonObject op, String key, int i) {
        String kind = op.has("op") && op.get("op").isJsonPrimitive() ? op.get("op").getAsString() : "?";
        if (!op.has(key)) throw fail(i, kind, "missing \"" + key + "\"");
        var element = op.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw fail(i, kind, "\"" + key + "\" must be a block id or a palette name");
        String value = element.getAsString().trim();
        String mapped = palette.get(value);
        if (mapped != null) return mapped;
        if (!BLOCK.matcher(value).matches() || value.length() > 256) throw fail(i, kind, "\"" + value + "\" is not a block id or a palette name");
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static String block(JsonElement element, String where) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(where + " must be a block id");
        String value = element.getAsString().trim();
        if (!BLOCK.matcher(value).matches() || value.length() > 256) throw new IllegalArgumentException(where + ": \"" + value + "\" is not a block id");
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static String text(JsonObject op, String key, int i, String kind) {
        if (!op.has(key) || !op.get(key).isJsonPrimitive() || !op.get(key).getAsJsonPrimitive().isString()) throw fail(i, kind == null ? "?" : kind, "missing \"" + key + "\"");
        return op.get(key).getAsString();
    }

    private static boolean flag(JsonObject op, String key, int i, String kind) {
        var e = op.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw fail(i, kind, "\"" + key + "\" must be true or false");
        return e.getAsBoolean();
    }

    private static int integer(JsonObject op, String key, int i, String kind, int min, int max) {
        if (!op.has(key) || !op.get(key).isJsonPrimitive() || !op.get(key).getAsJsonPrimitive().isNumber()) throw fail(i, kind, "missing number \"" + key + "\"");
        double v = op.get(key).getAsDouble();
        if (v != Math.rint(v) || v < min || v > max) throw fail(i, kind, "\"" + key + "\" must be a whole number from " + min + " to " + max);
        return (int) v;
    }

    private static int[] vector(JsonObject op, String key, int i, String kind) {
        if (!op.has(key)) throw fail(i, kind, "missing \"" + key + "\" as [x,y,z]");
        return ints(op.get(key), key, i, kind);
    }

    private static int[] ints(JsonElement e, String key, int i, String kind) {
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) throw fail(i, kind, "\"" + key + "\" must be [x,y,z]");
        int[] out = new int[3];
        for (int n = 0; n < 3; n++) {
            var part = e.getAsJsonArray().get(n);
            if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber()) throw fail(i, kind, "\"" + key + "\" must hold numbers");
            double v = part.getAsDouble();
            if (v != Math.rint(v) || Math.abs(v) > COORDINATE_LIMIT) throw fail(i, kind, "\"" + key + "\" must hold whole numbers within the world (±30000000)");
            out[n] = (int) v;
        }
        return out;
    }

    private static double[] radii(JsonObject op, int i, String kind) {
        if (op.has("radii")) {
            var v = vector(op, "radii", i, kind);
            for (int r : v) if (r < 0 || r > MAX_RADIUS) throw fail(i, kind, "\"radii\" must be from 0 to " + MAX_RADIUS);
            return new double[]{v[0], v[1], v[2]};
        }
        int r = integer(op, "radius", i, kind, 0, MAX_RADIUS);
        return new double[]{r, r, r};
    }

    private static int axis(JsonObject op, int i, String kind) {
        if (!op.has("axis")) return 1;
        String value = text(op, "axis", i, kind);
        return switch (value) { case "x" -> 0; case "y" -> 1; case "z" -> 2; default -> throw fail(i, kind, "\"axis\" must be x, y or z"); };
    }

    private static IllegalArgumentException fail(int i, String kind, String message) {
        return new IllegalArgumentException("ops[" + i + "] (" + kind + "): " + message);
    }
}
