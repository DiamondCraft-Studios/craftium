package dev.craftium.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * A record of what {@code Overlay} was asked to draw, one line per call, for the tests that pin what each tool shows in the world. It is off
 * ({@link #sink} is null) except while a test collects, so the calls cost a null check. Numbers are written to three places so a record is the
 * same on every run, and the same on every Minecraft version.
 */
public final class OverlayLog {
    private OverlayLog() {}

    /** Where the lines go; null when nothing is recording. Only the client thread reads and writes it. */
    public static List<String> sink;

    private static String n(double v) { return String.format(Locale.ROOT, "%.3f", v); }
    private static String v(Vec3 p) { return n(p.x) + "," + n(p.y) + "," + n(p.z); }

    public static void line(Vec3 from, Vec3 to, int color, float width, boolean scene) {
        if (sink != null) sink.add((scene ? "sceneLine " : "line ") + v(from) + " " + v(to) + " " + Integer.toHexString(color) + " " + n(width));
    }

    public static void box(AABB box, int stroke, float width, int fill) {
        if (sink != null) sink.add("box " + n(box.minX) + "," + n(box.minY) + "," + n(box.minZ) + " " + n(box.maxX) + "," + n(box.maxY) + "," + n(box.maxZ)
            + " " + Integer.toHexString(stroke) + " " + n(width) + " " + Integer.toHexString(fill));
    }

    public static void face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean scene) {
        if (sink != null) sink.add((scene ? "sceneFace " : "face ") + v(a) + " " + v(b) + " " + v(c) + " " + v(d) + " " + Integer.toHexString(color));
    }

    public static void point(Vec3 at, int color, float size) {
        if (sink != null) sink.add("point " + v(at) + " " + Integer.toHexString(color) + " " + n(size));
    }

    public static void text(String text, Vec3 at, int color, int background, float scale) {
        if (sink != null) sink.add("text '" + text + "' " + v(at) + " " + Integer.toHexString(color) + " " + Integer.toHexString(background) + " " + n(scale));
    }

    /** Block models: how many, and a number for where they are, in order. */
    public static void blocks(String kind, List<BlockPos> places, int tint) {
        if (sink != null) sink.add(kind + " " + places.size() + " " + Integer.toHexString(places.hashCode()) + " " + Integer.toHexString(tint));
    }
}
