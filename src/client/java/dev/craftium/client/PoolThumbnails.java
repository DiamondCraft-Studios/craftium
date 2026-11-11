package dev.craftium.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import dev.craftium.core.VoxelOrbit;
import net.minecraft.client.Minecraft;
import java.util.*;

/**
 * Small pictures of the templates in a pool, for the cards of the pool editor: the textured, shaped blocks seen from a corner, drawn in software (the
 * same scene the big preview shows) once each and kept until the editor closes. Software is the right tool at this size: what costs is making the
 * scene of a template, which a graphics card would not make cheaper, and a few thousand pixels are nothing.
 */
final class PoolThumbnails {
    static final int WIDTH = 104, HEIGHT = 72;
    /** Drawn at twice the size and shrunk by the texture filter, so the blocks are not jagged. */
    private static final int DETAIL = 2, BACKGROUND = 0xff2a3441;
    private static final Map<String, Tex> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static int builtThisFrame;
    private PoolThumbnails() {}

    /** Called once per frame; a few pictures are made in a frame, so a page of cards fills in without a stall. */
    static void newFrame() { builtThisFrame = 0; }

    /** The picture for a template, or null when it has none (too big, empty) or its turn has not come yet. */
    static Tex of(String template, JsonObject thumb) {
        if (thumb == null) return null;
        String key = template + "#" + thumb.get("data").getAsString().hashCode();
        var known = CACHE.get(key);
        if (known != null || FAILED.contains(key) || builtThisFrame >= 3) return known;
        builtThisFrame++;
        var scene = PreviewScenes.of(template, thumb);
        if (scene == null) { FAILED.add(key); return null; }
        int w = WIDTH * DETAIL, h = HEIGHT * DETAIL;
        int[] pixels = new int[w * h];
        VoxelOrbit.render(scene, new VoxelOrbit.View(), w, h, pixels, BACKGROUND, null);
        var image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) image.setPixel(x, y, pixels[y * w + x]);
        var texture = new ReferenceTexture(image);
        texture.filtering(true);
        var tex = Tex.of("craftium", "pool_template/" + UUID.randomUUID());
        Minecraft.getInstance().getTextureManager().register(tex.id(), texture);
        CACHE.put(key, tex);
        return tex;
    }

    static void releaseAll() {
        for (var tex : CACHE.values()) Minecraft.getInstance().getTextureManager().release(tex.id());
        CACHE.clear();
        FAILED.clear();
    }
}
