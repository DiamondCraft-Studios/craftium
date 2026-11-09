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

    /**
     * One shot to take: the camera, where its file goes (relative to the job's folder, always with forward slashes) and what it frames.
     * {@code min} and {@code max} are the room's box (blocks, inclusive). {@code orbit} is {aimX, aimY, aimZ, azimuth, elevation, distance, distanceWasAuto, targetWasAuto} (the last two 1 or 0)
     * for a shot worked out from a bearing, so it can be re-aimed from higher up; null for a camera placed outright.
     */
    public record Entry(String room, String shot, ShotSpec spec, String path, Point aim, Point min, Point max, double[] orbit) {}

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

    /** The player's saved selections, as the game knows them. */
    public interface Selections {
        /** The box (min, max) of the saved selection called {@code name} (any case), or null if there is none or it is empty. */
        Point[] find(String name);

        /** The names the player has saved, for the error when one is not found. */
        List<String> names();
    }

    /**
     * A copy of the request in which every room that names a saved selection ({@code "selection": "Selection 1"}) has that selection's box as
     * its "from" and "to", and takes the selection's name for its own when it has none. A room gives a selection or a box, not both. The box is
     * the selection's bounding box: an irregular selection is framed by the solid blocks inside it, as any room is.
     */
    public static JsonObject resolveSelections(JsonObject request, Selections saved) {
        var out = request.deepCopy();
        if (!out.has("rooms") || !out.get("rooms").isJsonArray()) return out;
        var rooms = out.getAsJsonArray("rooms");
        for (int r = 0; r < rooms.size(); r++) {
            if (!rooms.get(r).isJsonObject() || !rooms.get(r).getAsJsonObject().has("selection")) continue;
            var room = rooms.get(r).getAsJsonObject();
            String where = "rooms[" + r + "]";
            if (!room.get("selection").isJsonPrimitive() || room.get("selection").getAsString().isBlank()) throw new IllegalArgumentException(where + " \"selection\" must be the name of one of the player's saved selections");
            if (room.has("from") || room.has("to")) throw new IllegalArgumentException(where + " gives both a saved selection and \"from\" and \"to\"; give one or the other");
            String name = room.get("selection").getAsString().trim();
            Point[] box = saved.find(name);
            if (box == null) {
                var names = saved.names();
                throw new IllegalArgumentException("The player has no saved selection called \"" + name + "\" with blocks in it" + (names.isEmpty() ? " (they have saved none: ask them to save one in the Selections window, or give \"from\" and \"to\")" : "; their saved selections are " + String.join(", ", names)));
            }
            var from = new JsonArray();
            var to = new JsonArray();
            from.add(box[0].x()); from.add(box[0].y()); from.add(box[0].z());
            to.add(box[1].x()); to.add(box[1].y()); to.add(box[1].z());
            room.add("from", from);
            room.add("to", to);
            room.remove("selection");
            if (!room.has("name")) room.addProperty("name", name);
        }
        return out;
    }

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
        double[] orbit = null;
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
            boolean autoTarget = !o.has("target");
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
            boolean autoDistance = !o.has("distance");
            double distance;
            if (o.has("distance")) {
                distance = number(o, "distance", where);
                if (distance < 1 || distance > 512) throw new IllegalArgumentException("\"distance\" is " + distance + "; it must be 1 to 512 blocks (" + where + ")");
            } else distance = fitDistance(minX, minY, minZ, minX + sx, minY + sy, minZ + sz, aimAt, azimuth, elevation, fov, width, height);
            spec = orbit(name, aimAt, azimuth, elevation, distance, fov, width, height, hideHud);
            orbit = new double[]{aimAt[0], aimAt[1], aimAt[2], azimuth, elevation, distance, autoDistance ? 1 : 0, autoTarget ? 1 : 0};
        }
        return new Entry(roomName, name, spec, roomName + "/" + roomName + "_" + name + ".png", new Point((int) Math.floor(aimAt[0]), (int) Math.floor(aimAt[1]), (int) Math.floor(aimAt[2])),
            new Point((int) minX, (int) minY, (int) minZ), new Point((int) (minX + sx - 1), (int) (minY + sy - 1), (int) (minZ + sz - 1)), orbit);
    }

    /** A camera standing on the side {@code azimuth} of {@code aim}, {@code elevation} degrees up and {@code distance} away, looking at it. */
    public static ShotSpec orbit(String name, double[] aim, double azimuth, double elevation, double distance, int fov, int width, int height, boolean hideHud) {
        double az = Math.toRadians(azimuth), el = Math.toRadians(elevation);
        double px = aim[0] + Math.sin(az) * Math.cos(el) * distance, py = aim[1] + Math.sin(el) * distance, pz = aim[2] - Math.cos(az) * Math.cos(el) * distance;
        double dx = aim[0] - px, dy = aim[1] - py, dz = aim[2] - pz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Straight down has no horizontal direction to take a yaw from, so face away from the bearing's side (north-up picture).
        double yaw = Math.abs(el) > Math.toRadians(89.5) ? normYaw(azimuth + 180) : yawOf(dx, dz);
        return new ShotSpec(name, px, py, pz, normYaw(yaw), (float) Math.toDegrees(-Math.asin(dy / len)), fov, width, height, hideHud);
    }

    /** The same shot from {@code elevation} degrees up instead, for getting over whatever is in the way; null for a camera that was placed outright. */
    public static Entry raised(Entry e, double elevation) {
        if (e.orbit() == null) return null;
        double[] o = e.orbit();
        var old = e.spec();
        var spec = orbit(old.name(), new double[]{o[0], o[1], o[2]}, o[3], elevation, o[5], old.fov(), old.width(), old.height(), old.hideHud());
        return new Entry(e.room(), e.shot(), spec, e.path(), e.aim(), e.min(), e.max(), new double[]{o[0], o[1], o[2], o[3], elevation, o[5], o[6], o[7]});
    }

    /**
     * How far back the camera stands so all eight corners of the box (from {@code (x0,y0,z0)} to {@code (x1,y1,z1)}, the outer faces of its
     * blocks) sit inside {@link #FILL} of the picture, as the lens really sees them: a wide low building is judged by its width, a tall one by
     * its height, and a cube by its corners, not by a sphere around it.
     */
    public static double fitDistance(double x0, double y0, double z0, double x1, double y1, double z1, double[] aim, double azimuth, double elevation, int fov, int width, int height) {
        return fitPoints(boxCorners(x0, y0, z0, x1, y1, z1), aim, azimuth, elevation, fov, width, height);
    }

    static double[][] boxCorners(double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] c = new double[8][];
        for (int i = 0; i < 8; i++) c[i] = new double[]{(i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1};
        return c;
    }

    /** As {@link #fitDistance}, for any set of points (such as the corners of the solid blocks a build is really made of). */
    public static double fitPoints(double[][] points, double[] aim, double azimuth, double elevation, int fov, int width, int height) {
        double tanV = Math.tan(Math.toRadians(fov) / 2), tanH = tanV * width / height;
        double lo = 1, hi = MAX_DISTANCE;
        if (!fits(points, aim, azimuth, elevation, hi, fov, width, height, tanH, tanV)) return MAX_DISTANCE;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (fits(points, aim, azimuth, elevation, mid, fov, width, height, tanH, tanV)) hi = mid; else lo = mid;
        }
        return Math.max(MIN_DISTANCE, hi);
    }

    private static boolean fits(double[][] points, double[] aim, double azimuth, double elevation, double d, int fov, int w, int h, double tanH, double tanV) {
        var cam = orbit("fit", aim, azimuth, elevation, d, fov, w, h, true);
        double yaw = Math.toRadians(cam.yaw()), pitch = Math.toRadians(cam.pitch());
        // Forward, right and up as the game turns them: yaw 0 faces +z, and the right hand of that is -x; positive pitch looks down.
        double[] f = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] r = {-Math.cos(yaw), 0, -Math.sin(yaw)};
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        for (double[] p : points) {
            double vx = p[0] - cam.x(), vy = p[1] - cam.y(), vz = p[2] - cam.z();
            double depth = vx * f[0] + vy * f[1] + vz * f[2];
            if (depth < 0.5) return false;
            if (Math.abs(vx * r[0] + vy * r[1] + vz * r[2]) / depth > FILL * tanH) return false;
            if (Math.abs(vx * u[0] + vy * u[1] + vz * u[2]) / depth > FILL * tanV) return false;
        }
        return true;
    }

    /**
     * The same shot framed on what is really there: {@code solid} holds the corner points of the box's solid blocks. When the shot's target
     * and distance were both left to us, the camera aims at the middle of those points and backs off just far enough to fit them, so an
     * L-shaped build is not framed as if it filled its whole box. A shot with a target or distance of the agent's own, or a placed camera,
     * is returned as it was; so is one with nothing solid to frame.
     */
    public static Entry refit(Entry e, double[][] solid) {
        if (e.orbit() == null || solid == null || solid.length == 0 || e.orbit()[6] == 0 || e.orbit()[7] == 0) return e;
        double[] o = e.orbit();
        double[] aim = {o[0], o[1], o[2]};
        {
            double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (double[] p : solid) for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], p[i]); hi[i] = Math.max(hi[i], p[i]); }
            for (int i = 0; i < 3; i++) aim[i] = (lo[i] + hi[i]) / 2;
        }
        double distance = fitPoints(solid, aim, o[3], o[4], e.spec().fov(), e.spec().width(), e.spec().height());
        var old = e.spec();
        var spec = orbit(old.name(), aim, o[3], o[4], distance, old.fov(), old.width(), old.height(), old.hideHud());
        return new Entry(e.room(), e.shot(), spec, e.path(), new Point((int) Math.floor(aim[0]), (int) Math.floor(aim[1]), (int) Math.floor(aim[2])), e.min(), e.max(), new double[]{aim[0], aim[1], aim[2], o[3], o[4], distance, o[6], o[7]});
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
