package dev.craftium.client;

import com.google.gson.JsonObject;
import dev.craftium.core.VoxelOrbit;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** The blocks of a template as a scene to draw, made once from what the server sent and kept until the editor closes, for the thumbnails and the preview alike. */
final class PreviewScenes {
    private static final Map<String, Optional<VoxelOrbit.Scene>> CACHE = new HashMap<>();
    private PreviewScenes() {}

    static VoxelOrbit.Scene of(String template, JsonObject thumb) {
        String key = template + "#" + thumb.get("data").getAsString().hashCode();
        return CACHE.computeIfAbsent(key, k -> Optional.ofNullable(build(thumb))).orElse(null);
    }

    static void clear() { CACHE.clear(); }

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
