package dev.craftium.core;

/**
 * Draws a box of textured blocks from any side, in software, for a preview that can be turned, panned and zoomed. Each block is a few textured
 * rectangles (a plain block is six, a stair or a fence more, a plant two crossed ones) seen through an orthographic camera and drawn with a depth buffer,
 * so it needs no game, no GPU and no player.
 */
public final class VoxelOrbit {
    private VoxelOrbit() {}

    /** The side length of the square texture of a block face. */
    public static final int TEX = 16;

    /**
     * One textured rectangle of a block, in the block's own space (0 to 1 on each axis). A point of it is {@code o + fa * a + fb * b} for fa and fb from 0 to 1,
     * and the texel (0 to {@code tw} by 0 to {@code th}, v down; 16 by 16 for a block texture, larger for an entity's) at that point is {@code (u0 + fa * ua + fb * ub, v0 + fa * va + fb * vb)}.
     *
     * @param normal  which way it faces, for hiding it when it faces away
     * @param cull    the side of the block it lies on (see {@link #UP}), or -1: it is hidden when a block that fills that side stands against it
     * @param shade   the side it counts as facing, for how light it is drawn
     */
    public record Quad(float[] o, float[] a, float[] b, float[] normal, float u0, float v0, float ua, float va, float ub, float vb, int[] texture, int tw, int th, int cull, int shade) {}

    /** What a block is made of: its rectangles, and whether it fills the whole cell and hides what is behind it. */
    public record Shape(Quad[] quads, boolean full) {
        /** A plain cube with a texture for its top, its four sides and its underside. */
        public static Shape cube(int[] top, int[] side, int[] bottom) {
            var quads = new Quad[6];
            for (int d = 0; d < 6; d++) {
                int[] t = d == UP ? top : d == DOWN ? bottom : side;
                float[] o = {ORIGIN[d][0], ORIGIN[d][1], ORIGIN[d][2]}, a = {AXIS_A[d][0], AXIS_A[d][1], AXIS_A[d][2]}, b = {AXIS_B[d][0], AXIS_B[d][1], AXIS_B[d][2]};
                float[] n = {DX[d], DY[d], DZ[d]};
                boolean flat = d < 2;
                quads[d] = new Quad(o, a, b, n, 0, flat ? 0 : TEX, TEX, 0, 0, flat ? TEX : -TEX, t, TEX, TEX, d, d);
            }
            return new Shape(quads, true);
        }
    }

    /** The blocks of a preview. */
    public static final class Scene {
        public final int sx, sy, sz;
        final byte[] cells;
        final Shape[] shapes;
        /** The rectangles that can be seen, as a cell and which of its block's rectangles. */
        final int[] visibleCell, visibleQuad;

        /**
         * @param cells  one byte per cell, {@code x + sx * (z + sz * y)}: 0 is empty, otherwise a slot of {@code shapes}
         * @param shapes by slot (index 0 unused); null for a slot that is not drawn
         */
        public Scene(int sx, int sy, int sz, byte[] cells, Shape[] shapes) {
            if (sx < 1 || sy < 1 || sz < 1 || cells.length != sx * sy * sz) throw new IllegalArgumentException("The blocks do not fill the box");
            this.sx = sx; this.sy = sy; this.sz = sz; this.cells = cells; this.shapes = shapes;
            int count = 0;
            int[] cell = new int[64], quad = new int[64];
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++)
                    for (int x = 0; x < sx; x++) {
                        int at = x + sx * (z + sz * y), slot = cells[at] & 255;
                        var shape = shape(slot);
                        if (shape == null) continue;
                        for (int q = 0; q < shape.quads().length; q++) {
                            int side = shape.quads()[q].cull();
                            if (side >= 0) {
                                int nx = x + DX[side], ny = y + DY[side], nz = z + DZ[side];
                                if (nx >= 0 && ny >= 0 && nz >= 0 && nx < sx && ny < sy && nz < sz) {
                                    int other = cells[nx + sx * (nz + sz * ny)] & 255;
                                    var next = shape(other);
                                    if (next != null && (next.full() || other == slot)) continue; // hidden against a block that fills the side, or an identical one
                                }
                            }
                            if (count == cell.length) { cell = java.util.Arrays.copyOf(cell, count * 2); quad = java.util.Arrays.copyOf(quad, count * 2); }
                            cell[count] = at;
                            quad[count++] = q;
                        }
                    }
            visibleCell = java.util.Arrays.copyOf(cell, count);
            visibleQuad = java.util.Arrays.copyOf(quad, count);
        }

        /** Plain cubes, from a top, side and underside texture for each slot (index 0 unused). */
        public Scene(int sx, int sy, int sz, byte[] cells, int[][][] faces) {
            this(sx, sy, sz, cells, cubes(faces));
        }

        private static Shape[] cubes(int[][][] faces) {
            var out = new Shape[faces.length];
            for (int i = 0; i < faces.length; i++) if (faces[i] != null) out[i] = Shape.cube(faces[i][0], faces[i][1], faces[i][2]);
            return out;
        }

        private Shape shape(int slot) { return slot > 0 && slot < shapes.length ? shapes[slot] : null; }
        public int cell(int x, int y, int z) { return x + sx * (z + sz * y); }
        public boolean has(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz && shape(cells[cell(x, y, z)] & 255) != null; }
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

    /** The sides of a block, as the numbers the rectangles name them by. */
    public static final int UP = 0, DOWN = 1, SOUTH = 2, NORTH = 3, EAST = 4, WEST = 5;
    private static final int[] DX = {0, 0, 0, 0, 1, -1}, DY = {1, -1, 0, 0, 0, 0}, DZ = {0, 0, 1, -1, 0, 0};
    /** The corner of each side a picture starts from, and the way it runs along its two edges, so a texture reads the right way up and not mirrored. */
    private static final int[][] ORIGIN = {{0, 1, 0}, {0, 0, 1}, {0, 0, 1}, {1, 0, 0}, {1, 0, 1}, {0, 0, 0}};
    private static final int[][] AXIS_A = {{1, 0, 0}, {1, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] AXIS_B = {{0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}};
    private static final double[] SHADE = {1.0, 0.5, 0.82, 0.62, 0.72, 0.55};
    /** The light blue the editor uses for what is under the pointer. */
    public static final int HOVER = 0xff7fd8ff;
    /** How far the lines of the outline are drawn towards the camera, in blocks: they lie in the surface of the blocks, so exactly there they would be cut away by them. */
    private static final float LINE_BIAS = 0.04f;

    /** The size of a block in pixels at zoom 1, so the whole box fits in the picture. */
    public static double baseScale(Scene s, int w, int h) {
        double diagonal = Math.sqrt((double) s.sx * s.sx + (double) s.sy * s.sy + (double) s.sz * s.sz);
        return Math.min(w, h) * 0.9 / Math.max(1, diagonal);
    }

    public static void render(Scene s, View v, int w, int h, int[] pixels, int background, int[] highlight) {
        render(s, v, w, h, pixels, background, highlight, HOVER);
    }

    /**
     * Draws the scene into {@code pixels} ({@code w * h}, 0xAARRGGBB), which it first fills with {@code background}.
     *
     * <p>A highlighted cell is shown the way the editor shows the block under the pointer: its edges are an outline that blocks in front of it hide, and where it
     * is embedded among other blocks the faces of it that can be seen are coloured too, so it reads as part of the surface. A cell that is hidden altogether
     * from this side is not lost: its outline is then drawn faintly through the blocks, so it can still be found.
     *
     * @param highlight a cell {x, y, z} to light up, or null
     * @param hover     the colour of the highlight
     */
    public static void render(Scene s, View v, int w, int h, int[] pixels, int background, int[] highlight, int hover) {
        java.util.Arrays.fill(pixels, 0, w * h, background);
        double yaw = Math.toRadians(v.yaw), pitch = Math.toRadians(v.pitch);
        double[] cam = {Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw)};
        double[] right = {Math.cos(yaw), 0, -Math.sin(yaw)};
        double[] up = {cam[1] * right[2] - cam[2] * right[1], cam[2] * right[0] - cam[0] * right[2], cam[0] * right[1] - cam[1] * right[0]};
        double scale = baseScale(s, w, h) * v.zoom, cx = s.sx / 2.0, cy = s.sy / 2.0, cz = s.sz / 2.0;
        double ox = w / 2.0 + v.panX, oy = h / 2.0 + v.panY;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        boolean hlInside = highlight != null && highlight[0] >= 0 && highlight[1] >= 0 && highlight[2] >= 0 && highlight[0] < s.sx && highlight[1] < s.sy && highlight[2] < s.sz;
        int hl = hlInside ? s.cell(highlight[0], highlight[1], highlight[2]) : -1;
        boolean embedded = hlInside && (s.has(highlight[0] + 1, highlight[1], highlight[2]) || s.has(highlight[0] - 1, highlight[1], highlight[2]) || s.has(highlight[0], highlight[1] + 1, highlight[2])
                || s.has(highlight[0], highlight[1] - 1, highlight[2]) || s.has(highlight[0], highlight[1], highlight[2] + 1) || s.has(highlight[0], highlight[1], highlight[2] - 1));
        double[] p0 = new double[3], a = new double[3], b = new double[3];
        for (int i = 0; i < s.visibleCell.length; i++) {
            int cell = s.visibleCell[i];
            Quad q = s.shapes[s.cells[cell] & 255].quads()[s.visibleQuad[i]];
            if (q.normal()[0] * cam[0] + q.normal()[1] * cam[1] + q.normal()[2] * cam[2] <= 0.0001) continue; // turned away
            int x = cell % s.sx, z = cell / s.sx % s.sz, y = cell / (s.sx * s.sz);
            p0[0] = x + q.o()[0] - cx; p0[1] = y + q.o()[1] - cy; p0[2] = z + q.o()[2] - cz;
            for (int k = 0; k < 3; k++) { a[k] = q.a()[k]; b[k] = q.b()[k]; }
            double sx0 = ox + scale * dot(p0, right), sy0 = oy - scale * dot(p0, up), z0 = -dot(p0, cam);
            double sax = scale * dot(a, right), say = -scale * dot(a, up), za = -dot(a, cam);
            double sbx = scale * dot(b, right), sby = -scale * dot(b, up), zb = -dot(b, cam);
            double det = sax * sby - sbx * say;
            if (Math.abs(det) < 1e-6) continue;
            double minX = Math.min(Math.min(sx0, sx0 + sax), Math.min(sx0 + sbx, sx0 + sax + sbx)), maxX = Math.max(Math.max(sx0, sx0 + sax), Math.max(sx0 + sbx, sx0 + sax + sbx));
            double minY = Math.min(Math.min(sy0, sy0 + say), Math.min(sy0 + sby, sy0 + say + sby)), maxY = Math.max(Math.max(sy0, sy0 + say), Math.max(sy0 + sby, sy0 + say + sby));
            int x1 = Math.max(0, (int) Math.floor(minX)), x2 = Math.min(w - 1, (int) Math.ceil(maxX)), y1 = Math.max(0, (int) Math.floor(minY)), y2 = Math.min(h - 1, (int) Math.ceil(maxY));
            double shade = SHADE[q.shade()];
            boolean lit = embedded && cell == hl;
            int[] texture = q.texture();
            for (int py = y1; py <= y2; py++)
                for (int px = x1; px <= x2; px++) {
                    double rx = px + 0.5 - sx0, ry = py + 0.5 - sy0;
                    double fa = (rx * sby - sbx * ry) / det, fb = (sax * ry - rx * say) / det;
                    if (fa < 0 || fa >= 1 || fb < 0 || fb >= 1) continue;
                    float depthHere = (float) (z0 + fa * za + fb * zb);
                    int at = py * w + px;
                    if (depthHere >= depth[at]) continue;
                    int tx = Math.clamp((int) Math.floor(q.u0() + q.ua() * fa + q.ub() * fb), 0, q.tw() - 1), ty = Math.clamp((int) Math.floor(q.v0() + q.va() * fa + q.vb() * fb), 0, q.th() - 1);
                    int c = texture[ty * q.tw() + tx];
                    if ((c >>> 24) < 128) continue;
                    depth[at] = depthHere;
                    int r = (int) (((c >> 16) & 255) * shade), g = (int) (((c >> 8) & 255) * shade), bl = (int) ((c & 255) * shade);
                    if (lit) { r = (r * 3 + ((hover >> 16) & 255) * 2) / 5; g = (g * 3 + ((hover >> 8) & 255) * 2) / 5; bl = (bl * 3 + (hover & 255) * 2) / 5; }
                    pixels[at] = 0xff000000 | r << 16 | g << 8 | bl;
                }
        }
        if (hlInside) outline(highlight, cx, cy, cz, cam, right, up, scale, ox, oy, w, h, pixels, depth, hover);
    }

    /** The edges of one cell, depth tested; when none of it can be seen, faintly and through everything. */
    private static void outline(int[] cell, double cx, double cy, double cz, double[] cam, double[] right, double[] up, double scale, double ox, double oy, int w, int h, int[] pixels, float[] depth, int hover) {
        double[][] corner = new double[8][3];
        for (int i = 0; i < 8; i++) {
            double[] p = {cell[0] + (i & 1) - cx, cell[1] + ((i >> 1) & 1) - cy, cell[2] + ((i >> 2) & 1) - cz};
            corner[i][0] = ox + scale * dot(p, right);
            corner[i][1] = oy - scale * dot(p, up);
            corner[i][2] = -dot(p, cam);
        }
        int drawn = 0;
        for (int i = 0; i < 8; i++)
            for (int bit = 0; bit < 3; bit++) {
                if (((i >> bit) & 1) != 0) continue;
                drawn += line(corner[i], corner[i | 1 << bit], w, h, pixels, depth, 0xff000000 | hover & 0xffffff, false);
            }
        if (drawn > 0) return;
        for (int i = 0; i < 8; i++)
            for (int bit = 0; bit < 3; bit++) {
                if (((i >> bit) & 1) != 0) continue;
                line(corner[i], corner[i | 1 << bit], w, h, pixels, depth, 0xa0000000 | hover & 0xffffff, true);
            }
    }

    /** A line of two-pixel width; with {@code ghost} it is blended in dashes over what is there and ignores the depth, otherwise only the parts not behind a block are drawn. Answers how many pixels it drew. */
    private static int line(double[] from, double[] to, int w, int h, int[] pixels, float[] depth, int colour, boolean ghost) {
        int steps = (int) Math.ceil(Math.max(Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1]))) + 1, drawn = 0;
        for (int i = 0; i <= steps; i++) {
            if (ghost && (i / 3) % 2 == 1) continue;
            double t = (double) i / steps;
            int x = (int) Math.round(from[0] + (to[0] - from[0]) * t), y = (int) Math.round(from[1] + (to[1] - from[1]) * t);
            float d = (float) (from[2] + (to[2] - from[2]) * t);
            for (int dx = 0; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++) {
                    int px = x + dx, py = y + dy;
                    if (px < 0 || py < 0 || px >= w || py >= h) continue;
                    int at = py * w + px;
                    if (!ghost && d - LINE_BIAS > depth[at]) continue; // a block is in front of this part
                    if (ghost) {
                        int under = pixels[at], k = (colour >>> 24);
                        int r = (((under >> 16) & 255) * (255 - k) + ((colour >> 16) & 255) * k) / 255, g = (((under >> 8) & 255) * (255 - k) + ((colour >> 8) & 255) * k) / 255, bl = ((under & 255) * (255 - k) + (colour & 255) * k) / 255;
                        pixels[at] = 0xff000000 | r << 16 | g << 8 | bl;
                    } else pixels[at] = colour;
                    drawn++;
                }
        }
        return drawn;
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
}
