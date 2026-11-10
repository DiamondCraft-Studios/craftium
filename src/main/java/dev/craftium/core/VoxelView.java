package dev.craftium.core;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Draws a box of blocks as a picture, in software: each exposed block face is a flat-shaded polygon seen through an orthographic
 * camera. It needs no game and no player, so an AI can look at a build from any side without touching anyone's view.
 */
public final class VoxelView {
    private VoxelView() {}

    /**
     * Where the camera is: the four isometric corners look down at the box from that corner, TOP looks straight down with north at
     * the top of the picture, and the four sides look at that side of the box from level height.
     */
    public enum Angle {
        SE, SW, NE, NW, TOP, NORTH, SOUTH, EAST, WEST;

        public static Angle parse(String name) {
            for (Angle a : values()) if (a.name().equalsIgnoreCase(name)) return a;
            throw new IllegalArgumentException("\"angle\" must be one of se, sw, ne, nw, top, north, south, east, west");
        }
    }

    /** The picture, how big a block is in it, and which way east and north point on screen. */
    public record Result(BufferedImage image, double scale, String east, String north) {}

    private static final int BACKGROUND = 0xff2a3441, PAD = 16;
    /** The look of each face: the top is lightest and the underside darkest, and the four sides differ so a corner reads clearly. */
    private static final double TOP = 1.0, BOTTOM = 0.45, EAST_FACE = 0.80, WEST_FACE = 0.62, SOUTH_FACE = 0.72, NORTH_FACE = 0.55;

    /**
     * @param colours the blocks, {@code x + sx * (z + sz * y)} from the box's minimum corner, as 0xAARRGGBB; 0 (or any colour with no
     *                alpha) is empty space
     */
    public static Result render(int sx, int sy, int sz, int[] colours, Angle angle, int maxWidth, int maxHeight) {
        return render(sx, sy, sz, colours, colours, colours, angle, maxWidth, maxHeight);
    }

    /**
     * As above, with a colour for the top, the sides and the underside of each block, so a grass block is green on top and brown at the sides. {@code colours}
     * (the sides) says where the blocks are; the top and the underside fall back to it where they have no colour.
     */
    public static Result render(int sx, int sy, int sz, int[] top, int[] colours, int[] bottom, Angle angle, int maxWidth, int maxHeight) {
        if (sx < 1 || sy < 1 || sz < 1 || colours.length != sx * sy * sz || top.length != colours.length || bottom.length != colours.length) throw new IllegalArgumentException("The blocks do not fill the box");
        double[] d = direction(angle);
        double[] right, up;
        if (angle == Angle.TOP) { right = new double[]{1, 0, 0}; up = new double[]{0, 0, -1}; }
        else { right = norm(cross(d, new double[]{0, 1, 0})); up = cross(right, d); }

        // The size of the picture: the box's eight corners, seen through the camera.
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double[] c = {(i & 1) * sx, ((i >> 1) & 1) * sy, ((i >> 2) & 1) * sz};
            double u = dot(c, right), v = dot(c, up);
            minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
        }
        double scale = Math.min(32, Math.min((maxWidth - 2 * PAD) / (maxU - minU), (maxHeight - 2 * PAD) / (maxV - minV)));
        if (scale < 1) throw new IllegalArgumentException("The box is too large to draw");
        int width = (int) Math.ceil((maxU - minU) * scale) + 2 * PAD, height = (int) Math.ceil((maxV - minV) * scale) + 2 * PAD;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(BACKGROUND));
        g.fillRect(0, 0, width, height);
        var screen = new Screen(right, up, minU, maxV, scale);

        // The box's edges, behind everything, so the area is clear where it is empty.
        g.setColor(new Color(0x66ffffff, true));
        for (int i = 0; i < 8; i++)
            for (int axis = 0; axis < 3; axis++) {
                if (((i >> axis) & 1) != 0) continue;
                double[] a = {(i & 1) * sx, ((i >> 1) & 1) * sy, ((i >> 2) & 1) * sz}, b = a.clone();
                b[axis] += axis == 0 ? sx : axis == 1 ? sy : sz;
                g.drawLine(screen.x(a), screen.y(a), screen.x(b), screen.y(b));
            }

        // Faces towards the camera: along each axis the one the camera looks against. Blocks are drawn far to near on every axis.
        int fx = d[0] < 0 ? 1 : d[0] > 0 ? -1 : 0, fy = d[1] < 0 ? 1 : d[1] > 0 ? -1 : 0, fz = d[2] < 0 ? 1 : d[2] > 0 ? -1 : 0;
        boolean outline = scale >= 5;
        for (int yi = 0; yi < sy; yi++) {
            int y = d[1] > 0 ? sy - 1 - yi : yi;
            for (int zi = 0; zi < sz; zi++) {
                int z = d[2] > 0 ? sz - 1 - zi : zi;
                for (int xi = 0; xi < sx; xi++) {
                    int x = d[0] > 0 ? sx - 1 - xi : xi;
                    int index = x + sx * (z + sz * y);
                    int colour = colours[index];
                    if ((colour >>> 24) == 0) continue;
                    if (fx != 0 && empty(colours, sx, sy, sz, x + fx, y, z)) face(g, screen, x, y, z, 0, fx, colour, fx > 0 ? EAST_FACE : WEST_FACE, outline);
                    if (fz != 0 && empty(colours, sx, sy, sz, x, y, z + fz)) face(g, screen, x, y, z, 2, fz, colour, fz > 0 ? SOUTH_FACE : NORTH_FACE, outline);
                    if (fy != 0 && empty(colours, sx, sy, sz, x, y + fy, z)) face(g, screen, x, y, z, 1, fy, painted(fy > 0 ? top[index] : bottom[index], colour), fy > 0 ? TOP : BOTTOM, outline);
                }
            }
        }
        g.dispose();
        return new Result(image, scale, bearing(1, 0, right, up), bearing(0, -1, right, up));
    }

    /** The camera's looking direction, from the camera into the box. */
    private static double[] direction(Angle angle) {
        double elevation = Math.toRadians(35.264), h = Math.cos(elevation) / Math.sqrt(2), v = -Math.sin(elevation);
        return switch (angle) {
            case SE -> new double[]{-h, v, -h};
            case SW -> new double[]{h, v, -h};
            case NE -> new double[]{-h, v, h};
            case NW -> new double[]{h, v, h};
            case TOP -> new double[]{0, -1, 0};
            case NORTH -> new double[]{0, 0, 1};
            case SOUTH -> new double[]{0, 0, -1};
            case EAST -> new double[]{-1, 0, 0};
            case WEST -> new double[]{1, 0, 0};
        };
    }

    private static int painted(int wanted, int fallback) { return (wanted >>> 24) == 0 ? fallback : wanted; }

    private static boolean empty(int[] colours, int sx, int sy, int sz, int x, int y, int z) {
        return x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || (colours[x + sx * (z + sz * y)] >>> 24) == 0;
    }

    /** One face of the block at (x, y, z): the one on the {@code side} (+1 or -1) of {@code axis}. */
    private static void face(Graphics2D g, Screen s, int x, int y, int z, int axis, int side, int colour, double shade, boolean outline) {
        double[] origin = {x + (axis == 0 && side > 0 ? 1 : 0), y + (axis == 1 && side > 0 ? 1 : 0), z + (axis == 2 && side > 0 ? 1 : 0)};
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        var polygon = new Polygon();
        for (int[] corner : new int[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
            double[] p = origin.clone();
            p[a] += corner[0];
            p[b] += corner[1];
            polygon.addPoint(s.x(p), s.y(p));
        }
        var fill = new Color(shaded(colour, shade));
        g.setColor(fill);
        g.fillPolygon(polygon);
        g.setColor(outline ? new Color(shaded(colour, shade * 0.78)) : fill); // without an outline the edge is drawn in the fill, so seams do not show
        g.drawPolygon(polygon);
    }

    private static int shaded(int colour, double factor) {
        int r = (int) Math.min(255, ((colour >> 16) & 255) * factor), gr = (int) Math.min(255, ((colour >> 8) & 255) * factor), b = (int) Math.min(255, (colour & 255) * factor);
        return r << 16 | gr << 8 | b;
    }

    /** Where a direction on the ground points on screen, as a word. */
    private static String bearing(double dx, double dz, double[] right, double[] up) {
        double[] v = {dx, 0, dz};
        double u = dot(v, right), w = dot(v, up);
        if (Math.hypot(u, w) < 0.2) return "straight towards or away from the viewer";
        String[] names = {"right", "up-right", "up", "up-left", "left", "down-left", "down", "down-right"};
        int index = (int) Math.floorMod(Math.round(Math.atan2(w, u) / (Math.PI / 4)), 8L);
        return names[index];
    }

    /** Block units to pixels for one camera. */
    private record Screen(double[] right, double[] up, double minU, double maxV, double scale) {
        int x(double[] p) { return (int) Math.round((dot(p, right) - minU) * scale) + PAD; }
        int y(double[] p) { return (int) Math.round((maxV - dot(p, up)) * scale) + PAD; }
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double[] norm(double[] a) { double l = Math.sqrt(dot(a, a)); return new double[]{a[0] / l, a[1] / l, a[2] / l}; }
}
