package dev.craftium.client;

import java.util.HashMap;
import java.util.List;

/** The colours a block's faces get in a software-drawn preview, shared by the pool editor's pictures and the export window: the texture colour of its top, its sides and its bottom. */
final class PreviewFaces {
    private PreviewFaces() {}

    /** {@code {top, side, bottom}} for each block state in {@code names}, in order. */
    static int[][] of(List<String> names) {
        BlockColours.build();
        var cache = new HashMap<String, int[]>();
        int[][] out = new int[names.size()][];
        for (int i = 0; i < out.length; i++) out[i] = AiView.faceColours(names.get(i), cache);
        return out;
    }
}
