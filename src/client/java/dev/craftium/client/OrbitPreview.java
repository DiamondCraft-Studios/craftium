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
 * A picture of blocks with their textures and models, drawn in software (see {@link VoxelOrbit}) and turned with the left button, panned with the middle one and zoomed
 * with the wheel. The pool editor shows the selected element with it and the export window the structure to be exported, with lines drawn over it ({@link #overlay}).
 * The picture is drawn again only when the view, the highlight or the overlay changes.
 */
final class OrbitPreview {
    /** How many pixels are drawn for each pixel of the panel, so the blocks stay crisp. */
    private static final int DETAIL = 2;
    /** How long after the view last moved the picture is drawn at half detail, which is four times fewer pixels: it is sharp again once the view rests. */
    private static final long SETTLE_MS = 160;
    private long movingUntil;
    private int drawnDetail;
    private ReferenceTexture texture;
    private static final int BACKGROUND = 0xff1b2531;

    private String key = "";
    private VoxelOrbit.Scene scene;
    private final VoxelOrbit.View view = new VoxelOrbit.View();
    private int[] highlight, drawnHighlight;
    private boolean dirty = true;
    private VoxelOrbit.Overlay overlay;
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
        scene = thumb == null ? null : PreviewScenes.of(template, thumb);
        view.reset();
        highlight = null;
        dirty = true;
    }

    /** Chooses a scene made by the caller; {@code key} says when it is a new one (the view starts again then), and a null scene shows nothing. */
    void showScene(String sceneKey, VoxelOrbit.Scene next) {
        if (next == scene && sceneKey.equals(key)) return;
        boolean same = sceneKey.equals(key);
        key = sceneKey;
        scene = next;
        if (!same) view.reset();
        highlight = null;
        dirty = true;
    }

    /** Lines and dots drawn over the blocks (the export window's bounding box and axes), or null. */
    void overlay(VoxelOrbit.Overlay next) {
        if (next == overlay) return;
        overlay = next;
        dirty = true;
    }

    void highlight(int[] cell) {
        if (Arrays.equals(cell, highlight)) return;
        highlight = cell == null ? null : cell.clone();
        dirty = true;
    }

    boolean hasScene() { return scene != null; }

    private int[] pickedAt; private int pickedX = Integer.MIN_VALUE, pickedY, pickedW, pickedH; private double pickedYaw, pickedPitch, pickedZoom, pickedPanX, pickedPanY; private VoxelOrbit.Scene pickedScene;

    /** The cell of the block under a point of the picture drawn at (x, y, w, h), as {x, y, z}, or null; remembered while the pointer and the view stay where they are. */
    int[] cellAt(double pointX, double pointY, int x, int y, int w, int h) {
        if (scene == null) return null;
        int px = (int) ((pointX - x) * DETAIL), py = (int) ((pointY - y) * DETAIL), pw = Math.max(8, w * DETAIL), ph = Math.max(8, h * DETAIL);
        boolean same = pickedScene == scene && px == pickedX && py == pickedY && pw == pickedW && ph == pickedH && view.yaw == pickedYaw && view.pitch == pickedPitch && view.zoom == pickedZoom && view.panX == pickedPanX && view.panY == pickedPanY;
        if (!same) {
            pickedAt = VoxelOrbit.pick(scene, view, pw, ph, px, py);
            pickedScene = scene; pickedX = px; pickedY = py; pickedW = pw; pickedH = ph;
            pickedYaw = view.yaw; pickedPitch = view.pitch; pickedZoom = view.zoom; pickedPanX = view.panX; pickedPanY = view.panY;
        }
        return pickedAt == null ? null : pickedAt.clone();
    }
    private void moved() { dirty = true; movingUntil = System.currentTimeMillis() + SETTLE_MS; }
    void reset() { view.reset(); moved(); }
    void top() { view.top(); moved(); }
    void front() { view.front(); moved(); }

    boolean press(int button) { if (button != 0 && button != 2) return false; dragging = button; return true; }
    void letGo() { dragging = -1; }
    boolean dragging() { return dragging >= 0; }

    void drag(double dx, double dy) {
        if (dragging == 0) view.turn(-dx * 0.8, dy * 0.8);
        else if (dragging == 2) { view.panX += dx * DETAIL; view.panY += dy * DETAIL; }
        moved();
    }

    /** Zooms towards the pointer, which is given in pixels from the middle of the panel. */
    void zoom(double steps, double fromCentreX, double fromCentreY) {
        double before = view.zoom, after = Math.clamp(before * Math.pow(1.15, steps), 0.2, 12);
        double k = after / before;
        view.panX = (view.panX - fromCentreX * DETAIL) * k + fromCentreX * DETAIL;
        view.panY = (view.panY - fromCentreY * DETAIL) * k + fromCentreY * DETAIL;
        view.zoom = after;
        moved();
    }

    /** Draws the picture into the panel (x, y, w, h); false when there is nothing to show. */
    boolean draw(Gfx g, int x, int y, int w, int h) {
        if (scene == null) return false;
        boolean moving = System.currentTimeMillis() < movingUntil;
        int detail = moving ? 1 : DETAIL;
        int pw = Math.max(8, w * detail), ph = Math.max(8, h * detail);
        if (tex == null || dirty || detail != drawnDetail || pw != drawnW || ph != drawnH || !Arrays.equals(highlight, drawnHighlight)) {
            if (pixels.length != pw * ph) pixels = new int[pw * ph];
            // the view keeps its pan in pixels of the sharp picture; a picture of fewer pixels moves by fewer
            var shown = new VoxelOrbit.View();
            shown.yaw = view.yaw; shown.pitch = view.pitch; shown.zoom = view.zoom;
            shown.panX = view.panX * detail / DETAIL; shown.panY = view.panY * detail / DETAIL;
            VoxelOrbit.render(scene, shown, pw, ph, pixels, BACKGROUND, highlight, ClientState.options.hoverColor, overlay);
            if (texture == null || drawnW != pw || drawnH != ph) { // a new size needs a new texture; the same size is written into the one there is
                var image = new NativeImage(pw, ph, false);
                var next = new ReferenceTexture(image);
                next.filtering(true);
                var id = Tex.of("craftium", "pool_preview/" + UUID.randomUUID());
                Minecraft.getInstance().getTextureManager().register(id.id(), next);
                release(tex);
                tex = id;
                texture = next;
            }
            var image = texture.getPixels();
            for (int row = 0; row < ph; row++) { int base = row * pw; for (int col = 0; col < pw; col++) image.setPixel(col, row, pixels[base + col]); }
            texture.upload();
            drawnW = pw;
            drawnH = ph;
            drawnDetail = detail;
            drawnHighlight = highlight == null ? null : highlight.clone();
            dirty = false;
        }
        g.blit(tex, x, y, 0, 0, w, h, pw, ph, pw, ph, 0xffffffff);
        return true;
    }

    void releaseAll() { release(tex); tex = null; texture = null; key = ""; scene = null; overlay = null; PreviewScenes.clear(); }

    private static void release(Tex t) { if (t != null) Minecraft.getInstance().getTextureManager().release(t.id()); }
}
