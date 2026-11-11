package dev.craftium.client;

import dev.craftium.client.compat.Tex;
import dev.craftium.core.BlueprintInfo;
import dev.craftium.core.BlueprintVoxels;
import java.util.*;

/**
 * The picture of each blueprint in the browser: the same isometric view the AI build sessions show for a preview, each face in the colour of the texture the block's model puts on it, drawn once from the
 * blocks the listing carries and kept until the browser closes. A blueprint listed without blocks (an imported file not yet loaded, or
 * one too big to carry them) has no picture here and keeps its flat thumbnail.
 */
final class BlueprintThumbnails {
    /** Pictures made in one frame, so opening a page of them does not stall it. */
    private static final int PER_FRAME = 2;
    /** A made picture with a hash of the data it was made from, so a blueprint saved again under its name (the same file, other blocks) is drawn again. */
    private record Held(int content, AiThumbnails.Entry entry) {}
    private static final Map<String, Held> CACHE = new HashMap<>();
    /** Files whose data could not be drawn, with the hash of that data. */
    private static final Map<String, Integer> NONE = new HashMap<>();
    /** The hash of each listing entry's data, worked out once per entry (the browser asks every frame). */
    private static final Map<BlueprintInfo, Integer> HASHES = new IdentityHashMap<>();
    private static int builtThisFrame;
    private BlueprintThumbnails() {}

    static void newFrame() { builtThisFrame = 0; }

    /** The picture for this listing, or null while it is waiting to be drawn or when it has none. */
    static Tex of(BlueprintInfo info) {
        if (info.cells() == null || info.palette() == null) return null;
        if (HASHES.size() > 1024) HASHES.clear();
        int content = HASHES.computeIfAbsent(info, i -> Objects.hash(i.cells(), i.palette(), i.size(), i.scale()));
        var held = CACHE.get(info.file());
        if (held != null && held.content() == content) return held.entry().tex();
        if (held != null) { AiThumbnails.release(held.entry()); CACHE.remove(info.file()); } // the blueprint has changed since: its old picture goes
        Integer failed = NONE.get(info.file());
        if ((failed != null && failed == content) || builtThisFrame >= PER_FRAME) return null;
        builtThisFrame++;
        var entry = build(info);
        if (entry == null) { NONE.put(info.file(), content); return null; }
        NONE.remove(info.file());
        CACHE.put(info.file(), new Held(content, entry));
        return entry.tex();
    }

    static void releaseAll() {
        for (var held : CACHE.values()) AiThumbnails.release(held.entry());
        CACHE.clear();
        NONE.clear();
        HASHES.clear();
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
        int scale = Math.max(1, info.scale());
        try { return AiThumbnails.draw(BlueprintVoxels.cells(info.size().x(), scale), BlueprintVoxels.cells(info.size().y(), scale), BlueprintVoxels.cells(info.size().z(), scale), top, side, bottom, 0); }
        catch (IllegalArgumentException tooLarge) { return null; } // a box the small picture cannot hold: it keeps its flat thumbnail
    }
}
