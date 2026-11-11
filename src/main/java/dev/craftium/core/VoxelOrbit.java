package dev.craftium.core;

/**
 * Draws a box of textured blocks from any side, in software, for a preview that can be turned, panned and zoomed. Each exposed block face is a
 * textured square seen through an orthographic camera, drawn with a depth buffer, so it needs no game, no GPU and no player.
 */
public final class VoxelOrbit {
    private VoxelOrbit() {}

    /** The side length of the square texture of a block face. */
    public static final int TEX = 16;
    public static final int TOP = 0, SIDE = 1, BOTTOM = 2;

    /**
     * The blocks of a preview.
     *
     * @param cells one byte per cell, {@code x + sx * (z + sz * y)}: 0 is empty, otherwise a slot of {@code faces}
     * @param faces by slot (index 0 unused): the top, side and bottom textures of that block, each {@code TEX * TEX} pixels of 0xAARRGGBB
     */
    public static final class Scene {
        public final int sx, sy, sz;
        final byte[] cells;
        final int[][][] faces;
        /** The exposed faces, each as {@code cell * 6 + direction}. */
        final int[] visible;

        public Scene(int sx, int sy, int sz, byte[] cells, int[][][] faces) {
            if (sx < 1 || sy < 1 || sz < 1 || cells.length != sx * sy * sz) throw new IllegalArgumentException("The blocks do not fill the box");
            this.sx = sx; this.sy = sy; this.sz = sz; this.cells = cells; this.faces = faces;
            int count = 0;
            int[] list = new int[16];
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++)
                    for (int x = 0; x < sx; x++) {
                        int cell = x + sx * (z + sz * y);
                        if (!solid(cell)) continue;
                        for (int d = 0; d < 6; d++) {
                            int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                            if (nx >= 0 && ny >= 0 && nz >= 0 && nx < sx && ny < sy && nz < sz && solid(nx + sx * (nz + sz * ny))) continue;
                            if (count == list.length) list = java.util.Arrays.copyOf(list, count * 2);
                            list[count++] = cell * 6 + d;
                        }
                    }
            visible = java.util.Arrays.copyOf(list, count);
        }

        private boolean solid(int cell) { int slot = cells[cell] & 255; return slot > 0 && slot < faces.length && faces[slot] != null; }
        public int cell(int x, int y, int z) { return x + sx * (z + sz * y); }
        public boolean has(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz && solid(cell(x, y, z)); }
    }

    /** Where the camera is: the angle around the box, the angle above it, how far in it is, and how far the picture has been moved (in pixels). */
    public static final class View {
        public double yaw = 45, pitch = 30, zoom = 1, panX, panY;

        public void reset() { yaw = 45; pitch = 30; zoom = 1; panX = 0; panY = 0; }
        /** Straight down, north at the top of the picture. */
        public void top() { yaw = 0; pitch = 90; zoom = 1; panX = 0; panY = 0; }
        /** Level, looking at the south side. */
        public void front() { yaw = 0; pitch = 0; zoom = 1; panX = 0; panY = 0; }
        public void turn(double dyaw, double dpitch) { yaw = (yaw + dyaw) % 360; pitch = Math.clamp(pitch + dpitch, -90, 90); }
    }

    // direction: +Y, -Y, +Z, -Z, +X, -X
    private static final int[] DX = {0, 0, 0, 0, 1, -1}, DY = {1, -1, 0, 0, 0, 0}, DZ = {0, 0, 1, -1, 0, 0};
    /** The corner of each face the picture starts from, and the way it runs along its two sides, so the texture reads the right way up and not mirrored. */
    private static final int[][] ORIGIN = {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {1, 0, 0}, {1, 0, 1}, {0, 0, 0}};
    private static final int[][] AXIS_A = {{1, 0, 0}, {1, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] AXIS_B = {{0, 0, 1}, {0, 0, 1}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}};
    private static final double[] SHADE = {1.0, 0.5, 0.82, 0.62, 0.72, 0.55};
    private static final int GOLD = 0xffffd24a;

    /** The size of a block in pixels at zoom 1, so the whole box fits in the picture. */
    public static double baseScale(Scene s, int w, int h) {
        double diagonal = Math.sqrt((double) s.sx * s.sx + (double) s.sy * s.sy + (double) s.sz * s.sz);
        return Math.min(w, h) * 0.9 / Math.max(1, diagonal);
    }

    /**
     * Draws the scene into {@code pixels} ({@code w * h}, 0xAARRGGBB), which it first fills with {@code background}.
     *
     * @param highlight a cell {x, y, z} to light up (its faces are tinted and its edges drawn on top, even where it is hidden), or null
     */
    public static void render(Scene s, View v, int w, int h, int[] pixels, int background, int[] highlight) {
        java.util.Arrays.fill(pixels, 0, w * h, background);
        double yaw = Math.toRadians(v.yaw), pitch = Math.toRadians(v.pitch);
        double[] cam = {Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw)};
        double[] right = {Math.cos(yaw), 0, -Math.sin(yaw)};
        double[] up = {cam[1] * right[2] - cam[2] * right[1], cam[2] * right[0] - cam[0] * right[2], cam[0] * right[1] - cam[1] * right[0]};
        double scale = baseScale(s, w, h) * v.zoom, cx = s.sx / 2.0, cy = s.sy / 2.0, cz = s.sz / 2.0;
        double ox = w / 2.0 + v.panX, oy = h / 2.0 + v.panY;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        int hl = highlight == null ? -1 : s.cell(highlight[0], highlight[1], highlight[2]);
        boolean hlInside = highlight != null && highlight[0] >= 0 && highlight[1] >= 0 && highlight[2] >= 0 && highlight[0] < s.sx && highlight[1] < s.sy && highlight[2] < s.sz;
        for (int code : s.visible) {
            int cell = code / 6, d = code % 6;
            double facing = DX[d] * cam[0] + DY[d] * cam[1] + DZ[d] * cam[2];
            if (facing <= 0.0001) continue; // turned away
            int x = cell % s.sx, z = cell / s.sx % s.sz, y = cell / (s.sx * s.sz);
            int[][] tex = s.faces[s.cells[cell] & 255];
            int[] texture = tex[d == 0 ? TOP : d == 1 ? BOTTOM : SIDE];
            if (texture == null) continue;
            double[] p0 = {x + ORIGIN[d][0] - cx, y + ORIGIN[d][1] - cy, z + ORIGIN[d][2] - cz};
            double[] a = {AXIS_A[d][0], AXIS_A[d][1], AXIS_A[d][2]}, b = {AXIS_B[d][0], AXIS_B[d][1], AXIS_B[d][2]};
            double sx0 = ox + scale * dot(p0, right), sy0 = oy - scale * dot(p0, up), z0 = -dot(p0, cam);
            double sax = scale * dot(a, right), say = -scale * dot(a, up), za = -dot(a, cam);
            double sbx = scale * dot(b, right), sby = -scale * dot(b, up), zb = -dot(b, cam);
            double det = sax * sby - sbx * say;
            if (Math.abs(det) < 1e-6) continue;
            double minX = Math.min(Math.min(sx0, sx0 + sax), Math.min(sx0 + sbx, sx0 + sax + sbx)), maxX = Math.max(Math.max(sx0, sx0 + sax), Math.max(sx0 + sbx, sx0 + sax + sbx));
            double minY = Math.min(Math.min(sy0, sy0 + say), Math.min(sy0 + sby, sy0 + say + sby)), maxY = Math.max(Math.max(sy0, sy0 + say), Math.max(sy0 + sby, sy0 + say + sby));
            int x1 = Math.max(0, (int) Math.floor(minX)), x2 = Math.min(w - 1, (int) Math.ceil(maxX)), y1 = Math.max(0, (int) Math.floor(minY)), y2 = Math.min(h - 1, (int) Math.ceil(maxY));
            double shade = SHADE[d];
            boolean lit = hlInside && cell == hl;
            for (int py = y1; py <= y2; py++)
                for (int px = x1; px <= x2; px++) {
                    double rx = px + 0.5 - sx0, ry = py + 0.5 - sy0;
                    double fa = (rx * sby - sbx * ry) / det, fb = (sax * ry - rx * say) / det;
                    if (fa < 0 || fa >= 1 || fb < 0 || fb >= 1) continue;
                    float depthHere = (float) (z0 + fa * za + fb * zb);
                    int at = py * w + px;
                    if (depthHere >= depth[at]) continue;
                    int tx = Math.min(TEX - 1, (int) (fa * TEX)), ty = d < 2 ? Math.min(TEX - 1, (int) (fb * TEX)) : Math.min(TEX - 1, (int) ((1 - fb) * TEX));
                    int c = texture[ty * TEX + tx];
                    if ((c >>> 24) < 128) continue;
                    depth[at] = depthHere;
                    int r = (int) (((c >> 16) & 255) * shade), g = (int) (((c >> 8) & 255) * shade), bl = (int) ((c & 255) * shade);
                    if (lit) { r = (r + 255) / 2; g = (g + 210) / 2; bl = (bl + 74) / 2; }
                    pixels[at] = 0xff000000 | r << 16 | g << 8 | bl;
                }
        }
        if (hlInside) outline(highlight, cx, cy, cz, right, up, scale, ox, oy, w, h, pixels);
    }

    /** The edges of one cell, on top of everything. */
    private static void outline(int[] cell, double cx, double cy, double cz, double[] right, double[] up, double scale, double ox, double oy, int w, int h, int[] pixels) {
        double[][] corner = new double[8][2];
        for (int i = 0; i < 8; i++) {
            double[] p = {cell[0] + (i & 1) - cx, cell[1] + ((i >> 1) & 1) - cy, cell[2] + ((i >> 2) & 1) - cz};
            corner[i][0] = ox + scale * dot(p, right);
            corner[i][1] = oy - scale * dot(p, up);
        }
        for (int i = 0; i < 8; i++)
            for (int bit = 0; bit < 3; bit++) {
                if (((i >> bit) & 1) != 0) continue;
                line(corner[i], corner[i | 1 << bit], w, h, pixels);
            }
    }

    private static void line(double[] from, double[] to, int w, int h, int[] pixels) {
        int steps = (int) Math.ceil(Math.max(Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1]))) + 1;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(from[0] + (to[0] - from[0]) * t), y = (int) Math.round(from[1] + (to[1] - from[1]) * t);
            for (int dx = 0; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++) {
                    int px = x + dx, py = y + dy;
                    if (px >= 0 && py >= 0 && px < w && py < h) pixels[py * w + px] = GOLD;
                }
        }
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
}
