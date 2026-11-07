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
 * {@code replace}; {@code roof} and {@code stairs}, which set the facing of their stairs themselves (see {@link BuildShapes}); and
 * {@code group}, a list of operations turned, mirrored or moved as one. Every operation may carry {@code "hollow": true} (shapes only) and
 * {@code "repeat": {"count": n, "step": [x,y,z]}}. Later operations overwrite earlier ones; {@code minecraft:air} carves.
 * <p>
 * A block may be a mix, {@code {"mix": [["stone_bricks", 6], ["mossy_stone_bricks", 3]], "seed": 1}}, picked for each position from its
 * coordinates so the same spec always makes the same pattern; and a name may be a material and a kind, {@code spruce@stairs}, which the
 * server turns into the block of that kind ({@link MaterialNames}). With {@code "material": "spruce"} on the spec or an operation,
 * {@code "@stairs"} is enough.
 */
public final class BuildSpec {
    public static final int MAX_OPS = 256, MAX_REPEAT = 64, MAX_EXTENT = 256, MAX_RADIUS = 128, MAX_BLOCKS = 131_072, MAX_PALETTE = 64, MAX_DEPTH = 4, MAX_MIX = 16;
    /** Cells examined across the whole spec, so a pile of large operations cannot stall the server thread. */
    private static final long WORK_BUDGET = 40_000_000L;
    private static final Pattern BLOCK = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+(@[a-z_]+)?(\\[[a-z0-9_=,]*])?");
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern MATERIAL = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]{1,64}");

    /**
     * A block, or a weighted mix of blocks picked for each position. The pick is a hash of the position and the seed, so it is the same each
     * time the spec is built, and a mix written for a wall looks scattered rather than striped.
     */
    public record Choice(List<String> blocks, int[] cumulative, long seed) {
        static Choice of(String block) { return new Choice(List.of(block), new int[]{1}, 0); }
        boolean single() { return blocks.size() == 1; }
        String at(long x, long y, long z) {
            if (single()) return blocks.getFirst();
            long h = seed * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L + y * 0x94D049BB133111EBL + z * 0xD6E8FEB86659FD93L;
            h ^= h >>> 31; h *= 0x7FB5D329728EA185L; h ^= h >>> 27; h *= 0x81DADEF4BC2DD44DL; h ^= h >>> 33;
            int pick = (int) Math.floorMod(h, (long) cumulative[cumulative.length - 1]);
            for (int n = 0; n < cumulative.length; n++) if (pick < cumulative[n]) return blocks.get(n);
            return blocks.getLast();
        }
    }

    /**
     * An edit of the player's selection: every block in it (or every block that is {@code find}) becomes {@code block}, keeping the
     * properties named in {@code keep} (facing, half, shape...) so stairs stay the way they face. {@code keep} null keeps every property
     * both blocks have. Applied by the server after the shapes, to a selection this class knows nothing about.
     */
    public record SelectionOp(String block, String find, List<String> keep) {}

    /**
     * One block to place. {@code only}, when set, limits the cell to positions currently holding that block (the replace operation).
     * {@code turn} is how a group turned or mirrored it, for the server to turn the block's state the same way (a stair's facing, a log's axis).
     */
    public record Cell(String block, String only, Turn turn) {
        public Cell(String block, String only) { this(block, only, Turn.NONE); }
    }
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
    public record Result(LinkedHashMap<Point, Cell> cells, int clipped, int operations, List<String> warnings) {
        public Result(LinkedHashMap<Point, Cell> cells, int clipped, int operations) { this(cells, clipped, operations, List.of()); }
    }

    /** The most a coordinate can be: the edge of the world. */
    private static final int COORDINATE_LIMIT = 30_000_000;
    private final List<JsonObject> ops = new ArrayList<>();
    private final List<SelectionOp> selectionOps = new ArrayList<>();
    private final Map<String, Choice> palette = new LinkedHashMap<>();
    private final int[] origin = {0, 0, 0};
    /** The spec's material, for names written as "@kind"; an operation may name its own. */
    private String material;

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
        if (object.has("material")) spec.material = materialName(object.get("material"), "\"material\"");
        if (object.has("palette")) {
            if (!object.get("palette").isJsonObject()) throw new IllegalArgumentException("\"palette\" must map names to block ids");
            var entries = object.getAsJsonObject("palette").entrySet();
            if (entries.size() > MAX_PALETTE) throw new IllegalArgumentException("Palette has more than " + MAX_PALETTE + " entries");
            for (var entry : entries) {
                if (!ALIAS.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("Palette name \"" + entry.getKey() + "\" must be letters, digits, _ or -");
                String where = "palette." + entry.getKey();
                spec.palette.put(entry.getKey(), spec.choice(entry.getValue(), spec.material, where, false));
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
        if (count(spec.ops, 0) > MAX_OPS) throw new IllegalArgumentException("Build spec has more than " + MAX_OPS + " operations, counting those inside groups");
        return spec;
    }

    /** Operations in a list and the groups inside it, checking how deep groups go and that each holds a list of operations. */
    private static int count(List<JsonObject> list, int depth) {
        int total = 0;
        for (int i = 0; i < list.size(); i++) {
            total++;
            var op = list.get(i);
            if (!op.has("op") || !op.get("op").isJsonPrimitive() || !"group".equals(op.get("op").getAsString())) continue;
            if (depth + 1 >= MAX_DEPTH) throw fail(i, "group", "groups go at most " + MAX_DEPTH + " deep");
            total += count(children(op, i), depth + 1);
        }
        return total;
    }

    private static List<JsonObject> children(JsonObject group, int i) {
        if (!group.has("ops") || !group.get("ops").isJsonArray() || group.getAsJsonArray("ops").isEmpty()) throw fail(i, "group", "needs an \"ops\" list of operations");
        var out = new ArrayList<JsonObject>();
        var list = group.getAsJsonArray("ops");
        for (int n = 0; n < list.size(); n++) {
            if (!list.get(n).isJsonObject()) throw fail(i, "group", "ops[" + n + "] must be an object");
            var child = list.get(n).getAsJsonObject();
            if (child.has("op") && child.get("op").isJsonPrimitive() && "replace_selection".equals(child.get("op").getAsString()))
                throw fail(i, "group", "replace_selection works on the player's selection and cannot be in a group");
            out.add(child);
        }
        return out;
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
        names(ops, "", (label, name) -> names.add(name));
        for (var op : selectionOps) { names.add(op.block()); if (op.find() != null) names.add(op.find()); }
        return names;
    }

    /**
     * The operations that use a block name, labelled the way errors label them ({@code ops[2] (box)}, or {@code ops[1] (group) ops[0] (roof)}
     * inside a group), so a message about the block can say where it is used. A name is matched without its state, since a roof or a flight
     * of stairs adds the state itself; palette names are resolved first, so a palette entry is found through the ops using it.
     */
    public List<String> usesOf(String name) {
        String wanted = stateless(name);
        var out = new LinkedHashSet<String>();
        names(ops, "", (label, each) -> { if (stateless(each).equals(wanted)) out.add(label); });
        for (int k = 0; k < selectionOps.size(); k++) {
            var op = selectionOps.get(k);
            if (stateless(op.block()).equals(wanted) || op.find() != null && stateless(op.find()).equals(wanted)) out.add("ops[" + (ops.size() + k) + "] (replace_selection)");
        }
        return new ArrayList<>(out);
    }

    private static String stateless(String name) { int bracket = name.indexOf('['); return bracket < 0 ? name : name.substring(0, bracket); }

    /** Every block name each operation in a list names, with the operation's label, going into groups. */
    private void names(List<JsonObject> list, String prefix, java.util.function.BiConsumer<String, String> sink) {
        for (int i = 0; i < list.size(); i++) {
            var op = list.get(i);
            String kind = text(op, "op", i, null), label = prefix + "ops[" + i + "] (" + kind + ")";
            switch (kind) {
                case "group" -> names(children(op, i), label + " ", sink);
                case "roof" -> { var blocks = roofBlocks(op, i); sink.accept(label, blocks.stairs()); sink.accept(label, blocks.block()); if (blocks.slab() != null) sink.accept(label, blocks.slab()); }
                case "stairs" -> { sink.accept(label, stairsBlock(op, i)); if (op.has("fill")) sink.accept(label, resolve(op, "fill", i)); }
                default -> {
                    if (op.has("block")) for (String name : choice(op, "block", i).blocks()) sink.accept(label, name);
                    if (op.has("find")) sink.accept(label, resolve(op, "find", i));
                }
            }
        }
    }

    /**
     * Where the spec's blocks go and how much room they need, from its operations alone. Each operation's extent is worked out from
     * its numbers, repeats included, so a spec that reaches further than the build limits is refused here with a message.
     */
    public Placement place() {
        if (ops.isEmpty()) throw new IllegalArgumentException("The spec has no shapes, only operations on the selection, so it has no area of its own");
        long[] span = span(ops);
        long[] low = {span[0], span[1], span[2]}, high = {span[3], span[4], span[5]};
        String[] names = {"x", "y", "z"};
        for (int axis = 0; axis < 3; axis++)
            if (high[axis] - low[axis] + 1 > MAX_EXTENT)
                throw new IllegalArgumentException("The build spans " + (high[axis] - low[axis] + 1) + " blocks along " + names[axis] + "; at most " + MAX_EXTENT + " are allowed, so split it into builds that fit");
        return new Placement(new Point((int) (low[0] + origin[0]), (int) (low[1] + origin[1]), (int) (low[2] + origin[2])),
                new Bounds((int) (high[0] - low[0] + 1), (int) (high[1] - low[1] + 1), (int) (high[2] - low[2] + 1)),
                new int[]{(int) -low[0], (int) -low[1], (int) -low[2]});
    }

    /** The lowest and highest cell a list of operations can reach, repeats included, as {x0, y0, z0, x1, y1, z1}. */
    private long[] span(List<JsonObject> list) {
        long[] out = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        for (int i = 0; i < list.size(); i++) {
            var op = list.get(i);
            String kind = text(op, "op", i, null);
            long[] extent = extent(kind, op, i);
            int count = repeatCount(op, i, kind);
            int[] step = repeatStep(op, i, kind);
            for (int axis = 0; axis < 3; axis++) {
                long last = (long) step[axis] * (count - 1);
                out[axis] = Math.min(out[axis], Math.min(extent[axis], extent[axis] + last));
                out[axis + 3] = Math.max(out[axis + 3], Math.max(extent[axis + 3], extent[axis + 3] + last));
            }
        }
        return out;
    }

    private static int repeatCount(JsonObject op, int i, String kind) {
        if (!op.has("repeat")) return 1;
        if (!op.get("repeat").isJsonObject()) throw fail(i, kind, "\"repeat\" must be {\"count\": n, \"step\": [x,y,z]}");
        return integer(op.getAsJsonObject("repeat"), "count", i, kind, 1, MAX_REPEAT);
    }

    private static int[] repeatStep(JsonObject op, int i, String kind) {
        if (!op.has("repeat")) return new int[]{0, 0, 0};
        if (!op.get("repeat").isJsonObject()) throw fail(i, kind, "\"repeat\" must be {\"count\": n, \"step\": [x,y,z]}");
        return vector(op.getAsJsonObject("repeat"), "step", i, kind);
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
        int[] clipped = {0};
        // A mix is picked from world positions, so the pattern stays put when other operations change the spec's area.
        int[] pattern = {origin[0] - shift[0], origin[1] - shift[1], origin[2] - shift[2]};
        topCells = cells;
        owner = new HashMap<>();
        placedBy = new int[ops.size()];
        try { emit(ops, bounds, shift, pattern, budget, cells, clipped); }
        finally { topCells = null; top = -1; }
        return new Result(cells, clipped[0], ops.size(), covered(cells));
    }

    // Which top-level operation placed each cell, while a build runs, so one whose blocks all ended under later ones can be named.
    private Map<Point, Cell> topCells;
    private Map<Point, Integer> owner;
    private int[] placedBy;
    private int top = -1;

    /** Operations that placed blocks, every one of which a later operation then replaced: usually two operations written in the wrong order. */
    private List<String> covered(Map<Point, Cell> cells) {
        int[] left = new int[ops.size()];
        for (var p : cells.keySet()) { Integer i = owner.get(p); if (i != null) left[i]++; }
        var out = new ArrayList<String>();
        for (int i = 0; i < ops.size(); i++)
            if (placedBy[i] > 0 && left[i] == 0)
                out.add("ops[" + i + "] (" + text(ops.get(i), "op", i, null) + ") places nothing that is left: the operations after it replace all " + placedBy[i] + " of its blocks. If it should show, move it after them");
        return out;
    }

    /**
     * Places a list of operations into {@code cells}, whose positions run from 0 to the size of {@code bounds}: a spec position plus
     * {@code shift}. {@code pattern} turns a cell's position back into the one its mix is picked by.
     */
    private void emit(List<JsonObject> list, Bounds bounds, int[] shift, int[] pattern, long[] budget, Map<Point, Cell> cells, int[] clipped) {
        for (int i = 0; i < list.size(); i++) {
            var op = list.get(i);
            String kind = text(op, "op", i, null);
            int count = repeatCount(op, i, kind);
            int[] step = repeatStep(op, i, kind);
            if (cells == topCells && list == ops) top = i;
            for (int n = 0; n < count; n++) {
                int dx = step[0] * n + shift[0], dy = step[1] * n + shift[1], dz = step[2] * n + shift[2];
                switch (kind) {
                    case "group" -> group(op, i, bounds, dx, dy, dz, pattern, budget, cells, clipped);
                    case "roof", "stairs" -> {
                        for (var placed : generated(kind, op, i)) {
                            spend(budget);
                            var p = new Point(placed.at().x() + dx, placed.at().y() + dy, placed.at().z() + dz);
                            put(cells, p, new Cell(placed.block(), null), bounds, clipped);
                        }
                    }
                    default -> {
                        var shape = shape(kind, op, i, bounds, dx, dy, dz, budget);
                        var choice = choice(op, "block", i);
                        String only = kind.equals("replace") ? resolve(op, "find", i) : null;
                        for (var p : shape) put(cells, p, new Cell(choice.at(p.x() + pattern[0], p.y() + pattern[1], p.z() + pattern[2]), only), bounds, clipped);
                    }
                }
            }
        }
    }

    /** Puts one cell over what the operations before it placed: a later operation goes last, as well as on top. */
    private void put(Map<Point, Cell> cells, Point p, Cell cell, Bounds bounds, int[] clipped) {
        if (!bounds.contains(p.x(), p.y(), p.z())) { clipped[0]++; return; }
        var earlier = cells.get(p);
        if (cell.only() != null && earlier != null) {
            // A replace acts on what the spec has placed so far, then stands as a plain block; where that is something else it leaves it be.
            if (!matches(earlier.block(), cell.only())) return;
            cell = new Cell(cell.block(), earlier.only(), cell.turn());
        }
        cells.remove(p);
        cells.put(p, cell);
        if (cells == topCells && top >= 0) { owner.put(p, top); placedBy[top]++; }
        if (cells.size() > MAX_BLOCKS) throw new IllegalArgumentException("Build places more than " + MAX_BLOCKS + " blocks");
    }

    /**
     * A group: its operations built on their own, then turned and mirrored in place (or moved to "at"), each block's state turned with
     * them. A failure inside it names the group as well as the operation.
     */
    private void group(JsonObject op, int i, Bounds bounds, int dx, int dy, int dz, int[] pattern, long[] budget, Map<Point, Cell> cells, int[] clipped) {
        var kids = children(op, i);
        var inner = new LinkedHashMap<Point, Cell>();
        long[] span;
        try {
            span = span(kids);
            int sx = (int) (span[3] - span[0] + 1), sy = (int) (span[4] - span[1] + 1), sz = (int) (span[5] - span[2] + 1);
            if (sx > MAX_EXTENT || sy > MAX_EXTENT || sz > MAX_EXTENT) throw new IllegalArgumentException("its operations span more than " + MAX_EXTENT + " blocks along a side");
            int[] local = {(int) -span[0], (int) -span[1], (int) -span[2]};
            emit(kids, new Bounds(sx, sy, sz), local, new int[]{(int) span[0] + origin[0], (int) span[1] + origin[1], (int) span[2] + origin[2]}, budget, inner, new int[1]);
        } catch (IllegalArgumentException e) {
            throw fail(i, "group", e.getMessage());
        }
        var turn = turn(op, i);
        int sx = (int) (span[3] - span[0] + 1), sz = (int) (span[5] - span[2] + 1);
        int[] at = op.has("at") ? vector(op, "at", i, "group") : new int[]{(int) span[0], (int) span[1], (int) span[2]};
        for (var entry : inner.entrySet()) {
            var q = entry.getKey();
            int[] turned = turn.inBox(q.x(), q.z(), sx, sz);
            var p = new Point(at[0] + turned[0] + dx, at[1] + q.y() + dy, at[2] + turned[1] + dz);
            var cell = entry.getValue();
            put(cells, p, new Cell(cell.block(), cell.only(), turn.then(cell.turn())), bounds, clipped);
        }
    }

    private static Turn turn(JsonObject op, int i) {
        int degrees = op.has("rotate") ? integer(op, "rotate", i, "group", 0, 270) : 0;
        String mirror = op.has("mirror") ? text(op, "mirror", i, "group") : null;
        try { return Turn.of(degrees, mirror); } catch (IllegalArgumentException e) { throw fail(i, "group", e.getMessage()); }
    }

    /** The blocks of a roof or a flight of stairs, at spec positions. */
    private List<BuildShapes.Placed> generated(String kind, JsonObject op, int i) {
        try {
            if (kind.equals("roof")) {
                var a = vector(op, "from", i, kind);
                var b = vector(op, "to", i, kind);
                if (a[1] != b[1]) throw new IllegalArgumentException("\"from\" and \"to\" are corners of the roof's footprint and must be at the same y: the height of its lowest course of stairs");
                String style = op.has("style") ? text(op, "style", i, kind) : "gable";
                int overhang = op.has("overhang") ? integer(op, "overhang", i, kind, 0, 4) : 1;
                String ridge = op.has("ridge") ? text(op, "ridge", i, kind) : null, rises = op.has("rises") ? text(op, "rises", i, kind) : null;
                if (Math.abs(a[0] - b[0]) + 2 * overhang >= MAX_EXTENT || Math.abs(a[2] - b[2]) + 2 * overhang >= MAX_EXTENT) throw new IllegalArgumentException("the roof spans more than " + MAX_EXTENT + " blocks");
                return BuildShapes.roof(style, a[0], a[2], b[0], b[2], a[1], overhang, ridge, rises, roofBlocks(op, i));
            }
            var from = vector(op, "from", i, kind);
            var to = vector(op, "to", i, kind);
            int width = op.has("width") ? integer(op, "width", i, kind, 1, 16) : 1;
            return BuildShapes.stairs(new Point(from[0], from[1], from[2]), new Point(to[0], to[1], to[2]), width, stairsBlock(op, i), op.has("fill") ? resolve(op, "fill", i) : null);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            throw message != null && message.startsWith("ops[") ? e : fail(i, kind, message);
        }
    }

    /** A roof's stairs, ridge block and cap slab: named one by one, or from its material ("spruce" gives spruce stairs, planks and slab). */
    private BuildShapes.RoofBlocks roofBlocks(JsonObject op, int i) {
        String own = op.has("material") ? materialName(op.get("material"), "ops[" + i + "] (roof): \"material\"") : material;
        String stairs = op.has("stairs") ? resolve(op, "stairs", i) : own == null ? null : name(own + "@stairs", own, i, "roof");
        String block = op.has("block") ? resolve(op, "block", i) : own == null ? null : name(own + "@block", own, i, "roof");
        if (stairs == null || block == null) throw fail(i, "roof", "give a \"material\" (such as \"spruce\"), or \"stairs\" and \"block\"");
        boolean cap = !op.has("cap") || flag(op, "cap", i, "roof");
        String slab = !cap ? null : op.has("slab") ? resolve(op, "slab", i) : own == null ? null : name(own + "@slab", own, i, "roof");
        return new BuildShapes.RoofBlocks(stairs, block, slab);
    }

    /** A flight's stairs: its "block", or the stairs of its material. */
    private String stairsBlock(JsonObject op, int i) {
        if (op.has("block")) return resolve(op, "block", i);
        String own = op.has("material") ? materialName(op.get("material"), "ops[" + i + "] (stairs): \"material\"") : material;
        if (own == null) throw fail(i, "stairs", "give a \"block\" (stairs, such as \"oak_stairs\") or a \"material\"");
        return name(own + "@stairs", own, i, "stairs");
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
            default -> throw fail(i, kind, "unknown operation; use box, sphere, cylinder, cone, pyramid, line, set, replace, roof, stairs or group");
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
            case "roof", "stairs" -> {
                long[] out = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
                for (var placed : generated(kind, op, i)) {
                    int[] v = {placed.at().x(), placed.at().y(), placed.at().z()};
                    for (int axis = 0; axis < 3; axis++) { out[axis] = Math.min(out[axis], v[axis]); out[axis + 3] = Math.max(out[axis + 3], v[axis]); }
                }
                return out;
            }
            case "group" -> {
                long[] span;
                try { span = span(children(op, i)); } catch (IllegalArgumentException e) { throw e.getMessage() != null && e.getMessage().startsWith("ops[" + i + "] (group)") ? e : fail(i, "group", e.getMessage()); }
                int[] size = turn(op, i).size((int) (span[3] - span[0] + 1), (int) (span[5] - span[2] + 1));
                long[] at = op.has("at") ? toLong(vector(op, "at", i, kind)) : new long[]{span[0], span[1], span[2]};
                return new long[]{at[0], at[1], at[2], at[0] + size[0] - 1, at[1] + span[4] - span[1], at[2] + size[1] - 1};
            }
            default -> throw fail(i, kind, "unknown operation; use box, sphere, cylinder, cone, pyramid, line, set, replace, roof, stairs or group");
        }
    }

    private static long[] toLong(int[] v) { return new long[]{v[0], v[1], v[2]}; }

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

    /** A field that must name one block: a block id, a material@kind name or a palette name that is not a mix. */
    private String resolve(JsonObject op, String key, int i) {
        var choice = choice(op, key, i);
        if (!choice.single()) throw fail(i, kindOf(op), "\"" + key + "\" must be one block, not a mix");
        return choice.blocks().getFirst();
    }

    /** A field that names a block or a mix: a block id, a material@kind name, a palette name or {"mix": [[block, weight], ...]}. */
    private Choice choice(JsonObject op, String key, int i) {
        String kind = kindOf(op);
        if (!op.has(key)) throw fail(i, kind, "missing \"" + key + "\"");
        String own = op.has("material") ? materialName(op.get("material"), "ops[" + i + "] (" + kind + "): \"material\"") : material;
        try { return choice(op.get(key), own, "\"" + key + "\"", true); }
        catch (IllegalArgumentException e) { throw fail(i, kind, e.getMessage()); }
    }

    private static String kindOf(JsonObject op) { return op.has("op") && op.get("op").isJsonPrimitive() ? op.get("op").getAsString() : "?"; }

    /**
     * A block or a mix written in a spec. {@code usePalette} lets a name stand for a palette entry (inside the palette itself it may not,
     * so one entry cannot be defined by another).
     */
    private Choice choice(JsonElement element, String own, String where, boolean usePalette) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString().trim();
            if (usePalette && palette.containsKey(value)) return palette.get(value);
            return Choice.of(nameOrThrow(value, own, where));
        }
        if (!element.isJsonObject() || !element.getAsJsonObject().has("mix") || !element.getAsJsonObject().get("mix").isJsonArray())
            throw new IllegalArgumentException(where + " must be a block id, a palette name or {\"mix\": [[block, weight], ...]}");
        var object = element.getAsJsonObject();
        var list = object.getAsJsonArray("mix");
        if (list.isEmpty() || list.size() > MAX_MIX) throw new IllegalArgumentException(where + ": a mix holds 1 to " + MAX_MIX + " blocks");
        var blocks = new ArrayList<String>();
        int[] cumulative = new int[list.size()];
        int total = 0;
        for (int n = 0; n < list.size(); n++) {
            var entry = list.get(n);
            String block;
            int weight = 1;
            if (entry.isJsonArray() && entry.getAsJsonArray().size() == 2) {
                var pair = entry.getAsJsonArray();
                if (!pair.get(1).isJsonPrimitive() || !pair.get(1).getAsJsonPrimitive().isNumber() || pair.get(1).getAsDouble() != Math.rint(pair.get(1).getAsDouble()) || pair.get(1).getAsInt() < 1 || pair.get(1).getAsInt() > 1000)
                    throw new IllegalArgumentException(where + ": each weight in a mix is a whole number from 1 to 1000");
                weight = pair.get(1).getAsInt();
                entry = pair.get(0);
            }
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(where + ": a mix lists [block, weight] pairs, such as [[\"stone_bricks\", 6], [\"mossy_stone_bricks\", 3]]");
            String value = entry.getAsString().trim();
            if (usePalette && palette.containsKey(value)) {
                var named = palette.get(value);
                if (!named.single()) throw new IllegalArgumentException(where + ": \"" + value + "\" is a mix itself; a mix lists single blocks");
                block = named.blocks().getFirst();
            } else block = nameOrThrow(value, own, where);
            blocks.add(block);
            total += weight;
            cumulative[n] = total;
        }
        long seed = 0;
        if (object.has("seed")) {
            var s = object.get("seed");
            if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isNumber() || s.getAsDouble() != Math.rint(s.getAsDouble())) throw new IllegalArgumentException(where + ": \"seed\" must be a whole number");
            seed = s.getAsLong();
        }
        return new Choice(List.copyOf(blocks), cumulative, seed);
    }

    /** A block name as the server reads it: with a namespace, "@kind" filled in from the material, and material@kind checked. */
    private static String nameOrThrow(String value, String own, String where) {
        if (value.startsWith("@")) {
            if (own == null) throw new IllegalArgumentException(where + ": \"" + value + "\" needs a \"material\" on the spec or the operation, or write it as material" + value + " (such as spruce" + value + ")");
            value = own + value;
        }
        if (!BLOCK.matcher(value).matches() || value.length() > 256) throw new IllegalArgumentException(where + ": \"" + value + "\" is not a block id or a palette name");
        int bracket = value.indexOf('[');
        String id = bracket < 0 ? value : value.substring(0, bracket);
        if (id.contains("@")) {
            String kind = id.substring(id.indexOf('@') + 1);
            if (!MaterialNames.KINDS.contains(kind)) throw new IllegalArgumentException(where + ": \"" + kind + "\" is not a kind of block; use one of " + String.join(", ", MaterialNames.KINDS));
        }
        return id.contains(":") ? value : "minecraft:" + value;
    }

    private String name(String value, String own, int i, String kind) {
        try { return nameOrThrow(value, own, "\"" + value + "\""); } catch (IllegalArgumentException e) { throw fail(i, kind, e.getMessage()); }
    }

    private static String materialName(JsonElement element, String where) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || !MATERIAL.matcher(element.getAsString().trim()).matches())
            throw new IllegalArgumentException(where + " must be a material such as \"spruce\", \"stone_bricks\" or \"deepslate_tile\"");
        return element.getAsString().trim();
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
