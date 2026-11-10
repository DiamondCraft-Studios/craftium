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
            var cache = new HashMap<String, Integer>();
            int[] palette = new int[names.size() + 1];
            for (int i = 0; i < names.size(); i++) palette[i + 1] = AiView.colour(names.get(i).getAsString(), cache);
            int[] colours = new int[cells.length];
            boolean any = false;
            for (int i = 0; i < cells.length; i++) { int slot = cells[i] & 255; if (slot > 0 && slot < palette.length) { colours[i] = palette[slot]; any |= colours[i] != 0; } }
            if (!any) return null;
            var picture = VoxelView.render(sx, sy, sz, colours, VoxelView.Angle.SE, WIDTH, HEIGHT).image();
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
