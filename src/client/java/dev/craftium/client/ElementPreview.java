package dev.craftium.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.Gfx;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import dev.craftium.core.VoxelOrbit;
import net.minecraft.client.Minecraft;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.InflaterInputStream;

/**
 * The picture of the selected element in the pool editor: its blocks with their textures, drawn in software (see {@link VoxelOrbit}) and turned with the
 * left button, panned with the middle one and zoomed with the wheel. The picture is drawn again only when the view or the highlight changes.
 */
final class ElementPreview {
    /** How many pixels are drawn for each pixel of the panel, so the blocks stay crisp. */
    private static final int DETAIL = 2;
    private static final int BACKGROUND = 0xff1b2531;

    private String key = "";
    private VoxelOrbit.Scene scene;
    private final VoxelOrbit.View view = new VoxelOrbit.View();
    private int[] highlight, drawnHighlight;
    private boolean dirty = true;
    private int drawnW, drawnH;
    private Tex tex;
    private int[] pixels = new int[0];
    /** The mouse button that is moving the view (0 turns, 2 pans), or -1. */
    private int dragging = -1;

    /** Chooses what to show: the blocks of a template, or nothing (null) when it has none. The view starts again when the template changes. */
    void show(String template, JsonObject thumb) {
        String next = thumb == null ? "" : template + "#" + thumb.get("data").getAsString().hashCode();
        if (next.equals(key)) return;
        key = next;
        scene = thumb == null ? null : build(thumb);
        view.reset();
        highlight = null;
        dirty = true;
    }

    void highlight(int[] cell) {
        if (Arrays.equals(cell, highlight)) return;
        highlight = cell == null ? null : cell.clone();
        dirty = true;
    }

    boolean hasScene() { return scene != null; }
    void reset() { view.reset(); dirty = true; }
    void top() { view.top(); dirty = true; }
    void front() { view.front(); dirty = true; }

    boolean press(int button) { if (button != 0 && button != 2) return false; dragging = button; return true; }
    void letGo() { dragging = -1; }
    boolean dragging() { return dragging >= 0; }

    void drag(double dx, double dy) {
        if (dragging == 0) view.turn(-dx * 0.8, dy * 0.8);
        else if (dragging == 2) { view.panX += dx * DETAIL; view.panY += dy * DETAIL; }
        dirty = true;
    }

    /** Zooms towards the pointer, which is given in pixels from the middle of the panel. */
    void zoom(double steps, double fromCentreX, double fromCentreY) {
        double before = view.zoom, after = Math.clamp(before * Math.pow(1.15, steps), 0.2, 12);
        double k = after / before;
        view.panX = (view.panX - fromCentreX * DETAIL) * k + fromCentreX * DETAIL;
        view.panY = (view.panY - fromCentreY * DETAIL) * k + fromCentreY * DETAIL;
        view.zoom = after;
        dirty = true;
    }

    /** Draws the picture into the panel (x, y, w, h); false when there is nothing to show. */
    boolean draw(Gfx g, int x, int y, int w, int h) {
        if (scene == null) return false;
        int pw = Math.max(8, w * DETAIL), ph = Math.max(8, h * DETAIL);
        if (tex == null || dirty || pw != drawnW || ph != drawnH || !Arrays.equals(highlight, drawnHighlight)) {
            if (pixels.length != pw * ph) pixels = new int[pw * ph];
            VoxelOrbit.render(scene, view, pw, ph, pixels, BACKGROUND, highlight);
            var image = new NativeImage(pw, ph, false);
            for (int row = 0; row < ph; row++) for (int col = 0; col < pw; col++) image.setPixel(col, row, pixels[row * pw + col]);
            var texture = new ReferenceTexture(image);
            texture.filtering(true);
            var next = Tex.of("craftium", "pool_preview/" + UUID.randomUUID());
            Minecraft.getInstance().getTextureManager().register(next.id(), texture);
            release(tex);
            tex = next;
            drawnW = pw;
            drawnH = ph;
            drawnHighlight = highlight == null ? null : highlight.clone();
            dirty = false;
        }
        g.blit(tex, x, y, 0, 0, w, h, pw, ph, pw, ph, 0xffffffff);
        return true;
    }

    void releaseAll() { release(tex); tex = null; key = ""; scene = null; }

    private static void release(Tex t) { if (t != null) Minecraft.getInstance().getTextureManager().release(t.id()); }

    /** The scene of the blocks the server sent as a byte per cell and the names the bytes stand for. */
    private static VoxelOrbit.Scene build(JsonObject thumb) {
        try {
            var size = thumb.getAsJsonArray("size");
            int sx = size.get(0).getAsInt(), sy = size.get(1).getAsInt(), sz = size.get(2).getAsInt();
            byte[] cells;
            try (var in = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(thumb.get("data").getAsString())))) { cells = in.readAllBytes(); }
            if (cells.length != sx * sy * sz) return null;
            var names = thumb.getAsJsonArray("palette");
            var shapes = new VoxelOrbit.Shape[names.size() + 1];
            var colours = new HashMap<String, int[]>();
            for (int i = 0; i < names.size(); i++) {
                String state = names.get(i).getAsString();
                int[] flat = AiView.faceColours(state, colours);
                if (flat[1] == 0) continue; // air and the like: nothing to draw
                var shape = BlockShapes.of(state);
                if (shape == null) { // no model to read: a cube from the textures of its faces, or from its colour
                    int bracket = state.indexOf('[');
                    var pictures = BlockColours.pictures(bracket < 0 ? state : state.substring(0, bracket));
                    if (pictures == null) {
                        int[] top = new int[256], side = new int[256], bottom = new int[256];
                        Arrays.fill(top, flat[0]);
                        Arrays.fill(side, flat[1]);
                        Arrays.fill(bottom, flat[2]);
                        pictures = new int[][]{top, side, bottom};
                    }
                    shape = VoxelOrbit.Shape.cube(pictures[0], pictures[1], pictures[2]);
                }
                shapes[i + 1] = shape;
            }
            return new VoxelOrbit.Scene(sx, sy, sz, cells, shapes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
