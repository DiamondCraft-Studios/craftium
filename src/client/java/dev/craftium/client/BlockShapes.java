package dev.craftium.client;

import com.google.gson.*;
import dev.craftium.core.VoxelOrbit;
import net.minecraft.client.Minecraft;
import java.util.*;

/**
 * The shape of a block state as textured rectangles, read from the game's own resources (the block state file and the models it names, with the player's
 * resource packs), so a stair, a fence, a plant or a rotated jigsaw block is drawn as it is and not as a cube. Rotation of the model (x, y), of its
 * elements, their uv and tints are followed; what a model does that is not a box (entities such as chests, items) is not, and such a block has no shape here.
 */
final class BlockShapes {
    private static final Map<String, Optional<VoxelOrbit.Shape>> CACHE = new HashMap<>();
    private static final Map<String, JsonObject> JSON = new HashMap<>();
    private static final Map<String, Resolved> MODELS = new HashMap<>();
    private static Object resources;
    private BlockShapes() {}

    /** A model with its parents followed: the textures by name and the elements (the most derived list). */
    private record Resolved(Map<String, String> textures, JsonArray elements) {}

    private static final String[] DIRECTIONS = {"up", "down", "south", "north", "east", "west"};

    /** The shape of a block state such as {@code minecraft:oak_stairs[facing=east,half=bottom]}, or null when it has none to read. */
    static VoxelOrbit.Shape of(String state) {
        var mc = Minecraft.getInstance();
        if (resources != mc.getResourceManager()) { resources = mc.getResourceManager(); CACHE.clear(); JSON.clear(); MODELS.clear(); }
        return CACHE.computeIfAbsent(state, key -> {
            try { return Optional.ofNullable(build(key)); }
            catch (Exception | StackOverflowError e) { return Optional.empty(); }
        }).orElse(null);
    }

    private static VoxelOrbit.Shape build(String state) throws Exception {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        var properties = new HashMap<String, String>();
        if (bracket >= 0) for (var pair : state.substring(bracket + 1, state.length() - 1).split(",")) { int eq = pair.indexOf('='); if (eq > 0) properties.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip()); }
        var entity = BlockEntityShapes.of(id, properties);
        if (entity != null) return entity; // a chest, a bed: drawn by the game from an entity model, not from block model files
        var key = dev.craftium.compat.Compat.parse(id);
        var blockstate = json(key.getNamespace(), "blockstates/" + key.getPath() + ".json");
        var applies = new ArrayList<JsonObject>();
        if (blockstate.has("variants")) {
            var variants = blockstate.getAsJsonObject("variants");
            for (var entry : variants.entrySet()) if (matchesVariant(entry.getKey(), properties)) { applies.add(first(entry.getValue())); break; }
        }
        if (blockstate.has("multipart"))
            for (var part : blockstate.getAsJsonArray("multipart")) {
                var o = part.getAsJsonObject();
                if (!o.has("when") || matchesWhen(o.getAsJsonObject("when"), properties)) applies.add(first(o.get("apply")));
            }
        int tint = BlockColours.tint(id);
        var quads = new ArrayList<VoxelOrbit.Quad>();
        boolean cube = false;
        for (var apply : applies) {
            if (apply == null || !apply.has("model")) continue;
            var model = resolve(apply.get("model").getAsString(), 0);
            if (model.elements() == null) continue;
            int rx = apply.has("x") ? apply.get("x").getAsInt() : 0, ry = apply.has("y") ? apply.get("y").getAsInt() : 0;
            for (var element : model.elements()) cube |= elementQuads(element.getAsJsonObject(), model.textures(), rx, ry, tint, quads);
        }
        if (quads.isEmpty()) return null;
        var entry = BlockColours.get(id);
        return new VoxelOrbit.Shape(quads.toArray(new VoxelOrbit.Quad[0]), cube && entry != null && entry.full() && entry.opaque());
    }

    private static JsonObject first(JsonElement e) { return e == null ? null : e.isJsonArray() ? (e.getAsJsonArray().isEmpty() ? null : e.getAsJsonArray().get(0).getAsJsonObject()) : e.getAsJsonObject(); }

    private static boolean matchesVariant(String key, Map<String, String> properties) {
        if (key.isEmpty() || key.equals("normal")) return true;
        for (var pair : key.split(",")) { int eq = pair.indexOf('='); if (eq > 0 && !pair.substring(eq + 1).equals(properties.get(pair.substring(0, eq)))) return false; }
        return true;
    }

    private static boolean matchesWhen(JsonObject when, Map<String, String> properties) {
        for (var entry : when.entrySet()) {
            String name = entry.getKey();
            if (name.equals("OR")) { boolean any = false; for (var part : entry.getValue().getAsJsonArray()) any |= matchesWhen(part.getAsJsonObject(), properties); if (!any) return false; }
            else if (name.equals("AND")) { for (var part : entry.getValue().getAsJsonArray()) if (!matchesWhen(part.getAsJsonObject(), properties)) return false; }
            else if (!List.of(entry.getValue().getAsString().split("\\|")).contains(properties.get(name))) return false;
        }
        return true;
    }

    private static JsonObject json(String namespace, String path) throws Exception {
        String full = namespace + ":" + path;
        var known = JSON.get(full);
        if (known != null) return known;
        try (var reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(dev.craftium.compat.Compat.id(namespace, path)).openAsReader()) {
            var parsed = JsonParser.parseReader(reader).getAsJsonObject();
            JSON.put(full, parsed);
            return parsed;
        }
    }

    private static Resolved resolve(String model, int depth) throws Exception {
        var known = MODELS.get(model);
        if (known != null) return known;
        if (depth > 16) return new Resolved(new HashMap<>(), null);
        var key = dev.craftium.compat.Compat.parse(model);
        var json = json(key.getNamespace(), "models/" + key.getPath() + ".json");
        var textures = new LinkedHashMap<String, String>();
        JsonArray elements = null;
        if (json.has("parent") && !json.get("parent").getAsString().startsWith("builtin/")) { var parent = resolve(json.get("parent").getAsString(), depth + 1); textures.putAll(parent.textures()); elements = parent.elements(); }
        if (json.has("textures")) for (var entry : json.getAsJsonObject("textures").entrySet()) textures.put(entry.getKey(), entry.getValue().getAsString());
        if (json.has("elements")) elements = json.getAsJsonArray("elements");
        var out = new Resolved(textures, elements);
        MODELS.put(model, out);
        return out;
    }

    // ---- faces

    // the corner of each side a texture starts from and the way it runs, as for VoxelOrbit: origin, a (right), b (up, or towards +z for the top)
    private static float[] f(float... v) { return v; }

    /** Adds the rectangles of one element; true when it is a whole cube that is not turned or rotated. */
    private static boolean elementQuads(JsonObject element, Map<String, String> textures, int rx, int ry, int tint, List<VoxelOrbit.Quad> out) {
        var from = element.getAsJsonArray("from"); var to = element.getAsJsonArray("to");
        float x1 = from.get(0).getAsFloat() / 16, y1 = from.get(1).getAsFloat() / 16, z1 = from.get(2).getAsFloat() / 16;
        float x2 = to.get(0).getAsFloat() / 16, y2 = to.get(1).getAsFloat() / 16, z2 = to.get(2).getAsFloat() / 16;
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        boolean whole = x1 == 0 && y1 == 0 && z1 == 0 && x2 == 1 && y2 == 1 && z2 == 1 && !element.has("rotation");
        if (!element.has("faces")) return false;
        var faces = element.getAsJsonObject("faces");
        for (int d = 0; d < 6; d++) {
            if (!faces.has(DIRECTIONS[d])) continue;
            var face = faces.getAsJsonObject(DIRECTIONS[d]);
            String texture = face.has("texture") ? face.get("texture").getAsString() : "";
            for (int n = 0; texture.startsWith("#") && n < 16; n++) texture = textures.getOrDefault(texture.substring(1), "");
            if (texture.isEmpty() || texture.startsWith("#")) continue;
            int[] pixels = BlockColours.texture(texture);
            if (pixels.length == 0) continue;
            if (face.has("tintindex") && tint != -1) pixels = tinted(pixels, tint);
            float[] o, a, b;
            float[] uv; // u1, v1, u2, v2
            switch (d) {
                case VoxelOrbit.UP -> { o = f(x1, y2, z1); a = f(dx, 0, 0); b = f(0, 0, dz); uv = f(x1 * 16, z1 * 16, x2 * 16, z2 * 16); }
                case VoxelOrbit.DOWN -> { o = f(x1, y1, z2); a = f(dx, 0, 0); b = f(0, 0, -dz); uv = f(x1 * 16, 16 - z2 * 16, x2 * 16, 16 - z1 * 16); }
                case VoxelOrbit.SOUTH -> { o = f(x1, y1, z2); a = f(dx, 0, 0); b = f(0, dy, 0); uv = f(x1 * 16, 16 - y2 * 16, x2 * 16, 16 - y1 * 16); }
                case VoxelOrbit.NORTH -> { o = f(x2, y1, z1); a = f(-dx, 0, 0); b = f(0, dy, 0); uv = f(16 - x2 * 16, 16 - y2 * 16, 16 - x1 * 16, 16 - y1 * 16); }
                case VoxelOrbit.EAST -> { o = f(x2, y1, z2); a = f(0, 0, -dz); b = f(0, dy, 0); uv = f(16 - z2 * 16, 16 - y2 * 16, 16 - z1 * 16, 16 - y1 * 16); }
                default -> { o = f(x1, y1, z1); a = f(0, 0, dz); b = f(0, dy, 0); uv = f(z1 * 16, 16 - y2 * 16, z2 * 16, 16 - y1 * 16); }
            }
            if (face.has("uv")) { var u = face.getAsJsonArray("uv"); uv = f(u.get(0).getAsFloat(), u.get(1).getAsFloat(), u.get(2).getAsFloat(), u.get(3).getAsFloat()); }
            // the texel at the origin, and how it moves along a and b: the sides run up the picture, the top and underside down it
            boolean flat = d < 2;
            float u0 = uv[0], v0 = flat ? uv[1] : uv[3], ua = uv[2] - uv[0], va = 0, ub = 0, vb = flat ? uv[3] - uv[1] : uv[1] - uv[3];
            int turn = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt() / 90, 4) : 0;
            if (turn != 0) { // the picture is turned on the face: sample from where the turn leads
                float[][] at = new float[3][];
                float[][] s = {sample(turn, 0, 0), sample(turn, 1, 0), sample(turn, 0, 1)};
                for (int i = 0; i < 3; i++) at[i] = f(u0 + ua * s[i][0] + ub * s[i][1], v0 + va * s[i][0] + vb * s[i][1]);
                u0 = at[0][0]; v0 = at[0][1]; ua = at[1][0] - at[0][0]; va = at[1][1] - at[0][1]; ub = at[2][0] - at[0][0]; vb = at[2][1] - at[0][1];
            }
            float[] normal = f(DXS[d], DYS[d], DZS[d]);
            if (element.has("rotation")) {
                var r = element.getAsJsonObject("rotation");
                var at = r.getAsJsonArray("origin");
                float[] pivot = f(at.get(0).getAsFloat() / 16, at.get(1).getAsFloat() / 16, at.get(2).getAsFloat() / 16);
                String axis = r.get("axis").getAsString();
                double angle = Math.toRadians(r.get("angle").getAsDouble());
                boolean rescale = r.has("rescale") && r.get("rescale").getAsBoolean();
                float scale = rescale ? (float) (1 / Math.cos(angle)) : 1;
                int k = axis.equals("x") ? 0 : axis.equals("y") ? 1 : 2;
                o = turnPoint(o, pivot, k, angle, scale);
                a = turnVector(a, k, angle, scale);
                b = turnVector(b, k, angle, scale);
                normal = turnVector(normal, k, angle, 1);
            }
            int cull = face.has("cullface") ? side(face.get("cullface").getAsString()) : -1;
            if (cull >= 0 && (rx != 0 || ry != 0)) cull = snap(rotateModel(f(DXS[cull], DYS[cull], DZS[cull]), rx, ry, false));
            if (rx != 0 || ry != 0) {
                o = rotateModel(o, rx, ry, true);
                a = rotateModel(a, rx, ry, false);
                b = rotateModel(b, rx, ry, false);
                normal = rotateModel(normal, rx, ry, false);
            }
            out.add(new VoxelOrbit.Quad(o, a, b, normal, u0, v0, ua, va, ub, vb, pixels, VoxelOrbit.TEX, VoxelOrbit.TEX, cull, snap(normal)));
        }
        return whole;
    }

    private static final int[] DXS = {0, 0, 0, 0, 1, -1}, DYS = {1, -1, 0, 0, 0, 0}, DZS = {0, 0, 1, -1, 0, 0};

    private static float[] sample(int turn, float a, float b) {
        return switch (turn) { case 1 -> f(1 - b, a); case 2 -> f(1 - a, 1 - b); default -> f(b, 1 - a); };
    }

    private static int side(String name) {
        for (int i = 0; i < 6; i++) if (DIRECTIONS[i].equals(name)) return i;
        return -1;
    }

    /** Which side a direction mostly points at. */
    private static int snap(float[] v) {
        float ax = Math.abs(v[0]), ay = Math.abs(v[1]), az = Math.abs(v[2]);
        if (ay >= ax && ay >= az) return v[1] >= 0 ? VoxelOrbit.UP : VoxelOrbit.DOWN;
        if (az >= ax) return v[2] >= 0 ? VoxelOrbit.SOUTH : VoxelOrbit.NORTH;
        return v[0] >= 0 ? VoxelOrbit.EAST : VoxelOrbit.WEST;
    }

    /** The model turned as the block state says: x first, then y, both clockwise seen from the positive end of the axis, about the middle of the block. */
    private static float[] rotateModel(float[] p, int rx, int ry, boolean point) {
        float[] v = point ? f(p[0] - 0.5f, p[1] - 0.5f, p[2] - 0.5f) : p.clone();
        v = spin(v, 0, -Math.toRadians(rx));
        v = spin(v, 1, -Math.toRadians(ry));
        return point ? f(v[0] + 0.5f, v[1] + 0.5f, v[2] + 0.5f) : v;
    }

    private static float[] turnPoint(float[] p, float[] pivot, int axis, double angle, float scale) {
        float[] v = turnVector(f(p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]), axis, angle, scale);
        return f(v[0] + pivot[0], v[1] + pivot[1], v[2] + pivot[2]);
    }

    private static float[] turnVector(float[] v, int axis, double angle, float scale) {
        float[] r = spin(v, axis, angle);
        if (scale != 1) for (int i = 0; i < 3; i++) if (i != axis) r[i] *= scale;
        return r;
    }

    /** A right-handed turn about an axis. */
    private static float[] spin(float[] v, int axis, double angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return switch (axis) {
            case 0 -> f(v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c);
            case 1 -> f(v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c);
            default -> f(v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]);
        };
    }

    private static int[] tinted(int[] pixels, int tint) {
        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) { int c = pixels[i]; out[i] = (c & 0xff000000) | (((c >> 16) & 255) * ((tint >> 16) & 255) / 255) << 16 | (((c >> 8) & 255) * ((tint >> 8) & 255) / 255) << 8 | (c & 255) * (tint & 255) / 255; }
        return out;
    }
}
