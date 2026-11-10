package dev.craftium.client;

import dev.craftium.client.compat.Tex;
import dev.craftium.core.BlueprintInfo;
import java.util.*;

/**
 * The picture of each blueprint in the browser: the same isometric view the AI build sessions show for a preview, each face in the colour of the texture the block's model puts on it, drawn once from the
 * blocks the listing carries and kept until the browser closes. A blueprint listed without blocks (an imported file not yet loaded, or
 * one too big to carry them) has no picture here and keeps its flat thumbnail.
 */
final class BlueprintThumbnails {
    /** Pictures made in one frame, so opening a page of them does not stall it. */
    private static final int PER_FRAME = 2;
    private static final Map<String, AiThumbnails.Entry> CACHE = new HashMap<>();
    private static final Set<String> NONE = new HashSet<>();
    private static int builtThisFrame;
    private BlueprintThumbnails() {}

    static void newFrame() { builtThisFrame = 0; }

    /** The picture for this listing, or null while it is waiting to be drawn or when it has none. */
    static Tex of(BlueprintInfo info) {
        if (info.cells() == null || info.palette() == null) return null;
        String key = info.file() + "#" + info.cells().hashCode();
        var entry = CACHE.get(key);
        if (entry == null && !NONE.contains(key) && builtThisFrame < PER_FRAME) {
            builtThisFrame++;
            entry = build(info);
            if (entry == null) NONE.add(key); else CACHE.put(key, entry);
        }
        return entry == null ? null : entry.tex();
    }

    static void releaseAll() {
        for (var entry : CACHE.values()) AiThumbnails.release(entry);
        CACHE.clear();
        NONE.clear();
    }

    private static AiThumbnails.Entry build(BlueprintInfo info) {
        int[] cells = info.voxels();
        if (cells == null) return null;
        var cache = new HashMap<String, int[]>();
        int[][] palette = new int[info.palette().size()][];
        for (int i = 0; i < palette.length; i++) palette[i] = AiView.faceColours(info.palette().get(i), cache);
        int[] top = new int[cells.length], side = new int[cells.length], bottom = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] < 0) continue;
            top[i] = palette[cells[i]][0]; side[i] = palette[cells[i]][1]; bottom[i] = palette[cells[i]][2];
        }
        try { return AiThumbnails.draw(info.size().x(), info.size().y(), info.size().z(), top, side, bottom, 0); }
        catch (IllegalArgumentException tooLarge) { return null; } // a box the small picture cannot hold: it keeps its flat thumbnail
    }
}
