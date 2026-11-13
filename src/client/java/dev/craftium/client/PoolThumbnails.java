package dev.craftium.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import dev.craftium.core.VoxelView;
import net.minecraft.client.Minecraft;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.InflaterInputStream;

/**
 * Small isometric pictures of the templates in a pool, for the pool editor: the server sends each as a byte per cell and the names the bytes stand for
 * (see the server's JigsawEditor), and each is drawn in software once and kept until the editor closes.
 */
final class PoolThumbnails {
    static final int WIDTH = 104, HEIGHT = 72;
    private record Entry(Tex tex) {}
    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static int builtThisFrame;
    private PoolThumbnails() {}

    /** Called once per frame; a few pictures are made in a frame, so a page of cards fills in without a stall. */
    static void newFrame() { builtThisFrame = 0; }

    /** The picture for a template, or null when it has none (too big, empty) or its turn has not come yet. */
    static Tex of(String template, JsonObject thumb) {
        if (thumb == null) return null;
        String key = template + "#" + thumb.get("data").getAsString().hashCode();
        var entry = CACHE.get(key);
        if (entry == null && builtThisFrame < 3) {
            builtThisFrame++;
            entry = build(key, thumb);
            if (entry != null) CACHE.put(key, entry);
        }
        return entry == null ? null : entry.tex();
    }

    /**
     * The picture of the blocks, at most WIDTH by HEIGHT. The renderer wants a pixel for every block, so a template wider than about 60 blocks cannot be
     * drawn that small: it is drawn on a larger canvas, up to four times the size, and shrunk to fit.
     */
    private static java.awt.image.BufferedImage render(int sx, int sy, int sz, int[] top, int[] side, int[] bottom) {
        for (int k = 1; ; k++) {
            try {
                var picture = VoxelView.render(sx, sy, sz, top, side, bottom, VoxelView.Angle.SE, WIDTH * k, HEIGHT * k).image();
                return k == 1 ? picture : shrink(picture);
            } catch (IllegalArgumentException tooLarge) {
                if (k >= 4) throw tooLarge;
            }
        }
    }

    private static java.awt.image.BufferedImage shrink(java.awt.image.BufferedImage picture) {
        double scale = Math.min((double) WIDTH / picture.getWidth(), (double) HEIGHT / picture.getHeight());
        int w = Math.max(1, (int) Math.round(picture.getWidth() * scale)), h = Math.max(1, (int) Math.round(picture.getHeight() * scale));
        var out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(picture.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return out;
    }

    static void releaseAll() {
        for (var entry : CACHE.values()) Minecraft.getInstance().getTextureManager().release(entry.tex().id());
        CACHE.clear();
    }

    private static Entry build(String key, JsonObject thumb) {
        try {
            var size = thumb.getAsJsonArray("size");
            int sx = size.get(0).getAsInt(), sy = size.get(1).getAsInt(), sz = size.get(2).getAsInt();
            byte[] cells;
            try (var in = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(thumb.get("data").getAsString())))) { cells = in.readAllBytes(); }
            if (cells.length != sx * sy * sz) return null;
            var names = thumb.getAsJsonArray("palette");
            var list = new ArrayList<String>();
            for (var name : names) list.add(name.getAsString());
            var faces = PreviewFaces.of(list);
            int[][] palette = new int[faces.length + 1][];
            palette[0] = new int[3];
            System.arraycopy(faces, 0, palette, 1, faces.length);
            int[] top = new int[cells.length], side = new int[cells.length], bottom = new int[cells.length];
            boolean any = false;
            for (int i = 0; i < cells.length; i++) {
                int slot = cells[i] & 255;
                if (slot > 0 && slot < palette.length) { top[i] = palette[slot][0]; side[i] = palette[slot][1]; bottom[i] = palette[slot][2]; any |= side[i] != 0; }
            }
            if (!any) return null;
            var picture = render(sx, sy, sz, top, side, bottom);
            var image = new NativeImage(WIDTH, HEIGHT, false);
            int ox = (WIDTH - picture.getWidth()) / 2, oy = (HEIGHT - picture.getHeight()) / 2;
            for (int y = 0; y < HEIGHT; y++)
                for (int x = 0; x < WIDTH; x++) {
                    int px = x - ox, py = y - oy;
                    image.setPixel(x, y, px < 0 || py < 0 || px >= picture.getWidth() || py >= picture.getHeight() ? 0xff2a3441 : 0xff000000 | picture.getRGB(px, py));
                }
            var texture = new ReferenceTexture(image);
            texture.filtering(true);
            var tex = Tex.of("craftium", "pool_template/" + UUID.randomUUID());
            Minecraft.getInstance().getTextureManager().register(tex.id(), texture);
            return new Entry(tex);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
