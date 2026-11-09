package dev.craftium.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A request for showcase pictures, checked and worked out: every shot as a camera pose and a file path under the job's folder.
 * Pure data and arithmetic, so it is tested without the game.
 *
 * <p>A request is {@code {"job": "castle", "defaults": {...}, "rooms": [{"name": "throne-room", "from": [x,y,z], "to": [x,y,z], "shots": [...]}]}}.
 * A shot is a preset name ("hero", "south", "top"...) or an object. An object aims the camera at a target (the room's middle unless
 * "target" says otherwise) from a bearing ("azimuth": 0 north, 90 east, 180 south, 270 west, the side the camera stands on), a height
 * ("elevation" in degrees above level) and a distance (worked out from the room's size and the lens unless "distance" says so); or it
 * places the camera outright with "position" and either "look_at" or "yaw" and "pitch".
 */
public final class ShowcasePlan {
    private ShowcasePlan() {}

    public static final int MAX_SHOTS = 100, MAX_ROOMS = 50, MAX_NAME = 48;
    /** How much of the shorter half of the picture the room's bounding sphere fills when the distance is left to us. */
    static final double FILL = 0.7;
    static final double MIN_DISTANCE = 3, MAX_DISTANCE = 160;

    /** One shot to take: the camera, where its file goes (relative to the job's folder, always with forward slashes) and what it frames. */
    public record Entry(String room, String shot, ShotSpec spec, String path, Point aim) {}

    /** The whole request. {@code job} is a safe folder name. */
    public record Plan(String job, List<Entry> entries) {}

    /** The names of the presets: the eight compass sides and a few named looks. */
    public static final java.util.Map<String, double[]> PRESETS = java.util.Map.ofEntries(
        java.util.Map.entry("hero", new double[]{135, 28}), java.util.Map.entry("three_quarter", new double[]{225, 22}),
        java.util.Map.entry("front", new double[]{180, 6}), java.util.Map.entry("back", new double[]{0, 6}),
        java.util.Map.entry("south", new double[]{180, 6}), java.util.Map.entry("north", new double[]{0, 6}),
        java.util.Map.entry("east", new double[]{90, 6}), java.util.Map.entry("west", new double[]{270, 6}),
        java.util.Map.entry("se", new double[]{135, 30}), java.util.Map.entry("sw", new double[]{225, 30}),
        java.util.Map.entry("ne", new double[]{45, 30}), java.util.Map.entry("nw", new double[]{315, 30}),
        java.util.Map.entry("top", new double[]{180, 89}), java.util.Map.entry("ground", new double[]{150, -4}));

    /** Lowercase letters, digits, '-' and '_' only; anything else becomes '-'. Empty or all-symbol names are refused. */
    public static String safeName(String raw, String what) {
        if (raw == null) throw new IllegalArgumentException("A " + what + " needs a name");
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).replaceAll("-+$", "");
        if (s.isEmpty()) throw new IllegalArgumentException("The " + what + " name \"" + raw + "\" has no letters or digits to make a file name from");
        return s;
    }

    /** {@code now} names a job that was not given a name, such as "showcase-20261110-142501". */
    public static Plan parse(JsonObject request, String now) {
        var defaults = request.has("defaults") && request.get("defaults").isJsonObject() ? request.getAsJsonObject("defaults") : new JsonObject();
        String job = request.has("job") && request.get("job").isJsonPrimitive() ? safeName(request.get("job").getAsString(), "job") : "showcase-" + now;
        if (!request.has("rooms") || !request.get("rooms").isJsonArray() || request.getAsJsonArray("rooms").isEmpty())
            throw new IllegalArgumentException("\"rooms\" must list at least one room, each with a name, a box (\"from\" and \"to\") and its \"shots\"");
        var rooms = request.getAsJsonArray("rooms");
        if (rooms.size() > MAX_ROOMS) throw new IllegalArgumentException("A job can have at most " + MAX_ROOMS + " rooms; this has " + rooms.size());
        var entries = new ArrayList<Entry>();
        var roomNames = new HashSet<String>();
        for (int r = 0; r < rooms.size(); r++) {
            if (!rooms.get(r).isJsonObject()) throw new IllegalArgumentException("rooms[" + r + "] must be an object");
            var room = rooms.get(r).getAsJsonObject();
            String roomName = safeName(string(room, "name"), "room");
            if (!roomNames.add(roomName)) throw new IllegalArgumentException("Two rooms are both called \"" + roomName + "\"; give each its own name");
            Point a = point(room, "from", "rooms[" + r + "]"), b = point(room, "to", "rooms[" + r + "]");
            if (!room.has("shots") || !room.get("shots").isJsonArray() || room.getAsJsonArray("shots").isEmpty())
                throw new IllegalArgumentException("Room \"" + roomName + "\" needs \"shots\": a list of presets (" + String.join(", ", new java.util.TreeSet<>(PRESETS.keySet())) + ") or shot objects");
            var shotNames = new HashSet<String>();
            JsonArray shots = room.getAsJsonArray("shots");
            for (int s = 0; s < shots.size(); s++) {
                var entry = shot(roomName, a, b, shots.get(s), s, defaults, room);
                if (!shotNames.add(entry.shot())) throw new IllegalArgumentException("Room \"" + roomName + "\" has two shots called \"" + entry.shot() + "\"; name them differently");
                entries.add(entry);
                if (entries.size() > MAX_SHOTS) throw new IllegalArgumentException("A job can take at most " + MAX_SHOTS + " pictures; split it into several jobs");
            }
        }
        return new Plan(job, entries);
    }

    private static Entry shot(String roomName, Point a, Point b, JsonElement element, int index, JsonObject defaults, JsonObject room) {
        JsonObject o;
        String preset = null;
        if (element.isJsonPrimitive()) { preset = element.getAsString(); o = new JsonObject(); }
        else if (element.isJsonObject()) { o = element.getAsJsonObject(); if (o.has("preset")) preset = string(o, "preset"); }
        else throw new IllegalArgumentException("Each shot is a preset name or an object (room \"" + roomName + "\", shot " + index + ")");
        String name = o.has("name") ? safeName(string(o, "name"), "shot") : safeName(preset != null ? preset : "shot-" + (index + 1), "shot");
        String where = "room \"" + roomName + "\", shot \"" + name + "\"";

        int fov = (int) number(o, defaults, room, "fov", ShotSpec.DEFAULT_FOV, where);
        int width = (int) number(o, defaults, room, "width", ShotSpec.DEFAULT_WIDTH, where), height = (int) number(o, defaults, room, "height", ShotSpec.DEFAULT_HEIGHT, where);
        boolean hideHud = flag(o, defaults, room, "hide_hud", true);

        // The room's box, middle and size: what presets frame.
        double minX = Math.min(a.x(), b.x()), minY = Math.min(a.y(), b.y()), minZ = Math.min(a.z(), b.z());
        double sx = Math.abs(a.x() - b.x()) + 1, sy = Math.abs(a.y() - b.y()) + 1, sz = Math.abs(a.z() - b.z()) + 1;
        double cx = minX + sx / 2, cy = minY + sy / 2, cz = minZ + sz / 2;
        double radius = 0.5 * Math.sqrt(sx * sx + sy * sy + sz * sz);

        ShotSpec spec;
        double[] aimAt = {cx, cy, cz};
        if (o.has("position")) {
            double[] p = vec(o, "position", where);
            double yaw, pitch;
            if (o.has("look_at")) {
                double[] t = vec(o, "look_at", where);
                aimAt = t;
                double dx = t[0] - p[0], dy = t[1] - p[1], dz = t[2] - p[2], len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-6) throw new IllegalArgumentException("The camera and its \"look_at\" are the same point (" + where + ")");
                yaw = yawOf(dx, dz);
                pitch = Math.toDegrees(-Math.asin(dy / len));
            } else {
                yaw = o.has("yaw") ? number(o, "yaw", where) : 0;
                pitch = o.has("pitch") ? number(o, "pitch", where) : 0;
            }
            spec = new ShotSpec(name, p[0], p[1], p[2], normYaw(yaw), (float) pitch, fov, width, height, hideHud);
        } else {
            if (o.has("target")) aimAt = vec(o, "target", where);
            double azimuth, elevation;
            if (preset != null) {
                double[] pr = PRESETS.get(preset.toLowerCase(Locale.ROOT));
                if (pr == null) throw new IllegalArgumentException("Unknown preset \"" + preset + "\" (" + where + "); use one of " + String.join(", ", new java.util.TreeSet<>(PRESETS.keySet())) + ", or give \"azimuth\" and \"elevation\"");
                azimuth = pr[0]; elevation = pr[1];
            } else { azimuth = 135; elevation = 25; }
            if (o.has("azimuth")) azimuth = number(o, "azimuth", where);
            if (o.has("elevation")) elevation = number(o, "elevation", where);
            if (elevation < -89 || elevation > 89.9) throw new IllegalArgumentException("\"elevation\" is " + elevation + "; it must be -89 (looking up from below) to 89.9 (straight down) (" + where + ")");
            double distance;
            if (o.has("distance")) {
                distance = number(o, "distance", where);
                if (distance < 1 || distance > 512) throw new IllegalArgumentException("\"distance\" is " + distance + "; it must be 1 to 512 blocks (" + where + ")");
            } else distance = autoDistance(radius, fov, width, height);
            double az = Math.toRadians(azimuth), el = Math.toRadians(elevation);
            double px = aimAt[0] + Math.sin(az) * Math.cos(el) * distance, py = aimAt[1] + Math.sin(el) * distance, pz = aimAt[2] - Math.cos(az) * Math.cos(el) * distance;
            double dx = aimAt[0] - px, dy = aimAt[1] - py, dz = aimAt[2] - pz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Straight down has no horizontal direction to take a yaw from, so face away from the bearing's side (north-up picture).
            double yaw = Math.abs(el) > Math.toRadians(89.5) ? normYaw(azimuth + 180) : yawOf(dx, dz);
            spec = new ShotSpec(name, px, py, pz, normYaw(yaw), (float) Math.toDegrees(-Math.asin(dy / len)), fov, width, height, hideHud);
        }
        return new Entry(roomName, name, spec, roomName + "/" + roomName + "_" + name + ".png", new Point((int) Math.floor(aimAt[0]), (int) Math.floor(aimAt[1]), (int) Math.floor(aimAt[2])));
    }

    /** How far back the camera stands so the room's bounding sphere fills about {@link #FILL} of the shorter half of the picture. */
    public static double autoDistance(double radius, int fov, int width, int height) {
        double t = Math.tan(Math.toRadians(fov) / 2) * Math.min(1.0, (double) width / height);
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, radius / (FILL * t)));
    }

    /** The game's yaw for looking along (dx, dz): 0 faces +z (south), 90 faces -x (west). */
    static double yawOf(double dx, double dz) { return Math.toDegrees(Math.atan2(-dx, dz)); }

    static float normYaw(double yaw) { double y = ((yaw % 360) + 540) % 360 - 180; return (float) y; }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw new IllegalArgumentException("\"" + key + "\" must be given as text");
        return o.get(key).getAsString();
    }

    private static double number(JsonObject o, String key, String where) {
        try { return o.get(key).getAsDouble(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("\"" + key + "\" must be a number (" + where + ")"); }
    }

    /** A setting from the shot, else the room, else the job's defaults, else the built-in one. */
    private static double number(JsonObject shot, JsonObject defaults, JsonObject room, String key, double fallback, String where) {
        for (var source : new JsonObject[]{shot, room, defaults}) if (source.has(key)) return number(source, key, where);
        return fallback;
    }

    private static boolean flag(JsonObject shot, JsonObject defaults, JsonObject room, String key, boolean fallback) {
        for (var source : new JsonObject[]{shot, room, defaults})
            if (source.has(key) && source.get(key).isJsonPrimitive() && source.get(key).getAsJsonPrimitive().isBoolean()) return source.get(key).getAsBoolean();
        return fallback;
    }

    private static double[] vec(JsonObject o, String key, String where) {
        if (!o.has(key) || !o.get(key).isJsonArray() || o.getAsJsonArray(key).size() != 3) throw new IllegalArgumentException("\"" + key + "\" must be [x, y, z] (" + where + ")");
        var a = o.getAsJsonArray(key);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            try { out[i] = a.get(i).getAsDouble(); } catch (RuntimeException e) { throw new IllegalArgumentException("\"" + key + "\" must be [x, y, z] as numbers (" + where + ")"); }
        }
        return out;
    }

    private static Point point(JsonObject o, String key, String where) {
        double[] v = vec(o, key, where);
        for (double d : v) if (d != Math.rint(d)) throw new IllegalArgumentException(where + " \"" + key + "\" must be whole numbers");
        return new Point((int) v[0], (int) v[1], (int) v[2]);
    }

    /** Used by callers that need the unique job names a set has, e.g. to refuse overwriting; kept here so the rules live in one place. */
    public static Set<String> rooms(Plan plan) {
        var out = new java.util.LinkedHashSet<String>();
        for (var e : plan.entries()) out.add(e.room());
        return out;
    }
}
