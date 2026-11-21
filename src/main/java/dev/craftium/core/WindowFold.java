package dev.craftium.core;

/** Where an editor window goes when it is folded up to its title bar or unfolded again, so it never floats above the bottom edge or runs off it. */
public final class WindowFold {
    /** The height of the bar along the bottom of the editor that windows stay above. */
    public static final int BOTTOM_BAR = 25;
    /** The least a window's top can be: under the menu bar. */
    public static final int TOP = 27;
    /** How near the bottom edge a window's lower edge has to be for folding to snap it to the edge. */
    public static final int NEAR = 12;
    /** The height of a folded window. */
    public static final int TITLE = 19;

    private WindowFold() {}

    /** The lowest a window's lower edge may be on a canvas {@code canvasH} high. */
    public static int bottomEdge(int canvasH) {
        return canvasH - BOTTOM_BAR;
    }

    /** The top of a window {@code h} high at {@code y} once folded: on the bottom edge if it was near it or past it, else where it was. */
    public static int foldedY(int y, int h, int canvasH) {
        int edge = bottomEdge(canvasH);
        return y + h >= edge - NEAR ? Math.max(TOP, edge - TITLE) : y;
    }

    /** The top of a window {@code h} high at {@code y} once unfolded: moved up so that it is not cut off by the bottom edge, never above {@code TOP}. */
    public static int unfoldedY(int y, int h, int canvasH) {
        int edge = bottomEdge(canvasH);
        return y + h > edge ? Math.max(TOP, edge - h) : y;
    }

    /** Whether two windows share most of a column: they overlap sideways by more than half of the narrower one. */
    public static boolean sameColumn(int x1, int w1, int x2, int w2) {
        int overlap = Math.min(x1 + w1, x2 + w2) - Math.max(x1, x2);
        return overlap * 2 > Math.min(w1, w2);
    }

    /**
     * Where a folded window at {@code x} and {@code w} wide goes along a row so that it does not cover the folded windows already in it, given as pairs of
     * x and width. It moves to the right of the one it overlaps, or to the left of it when it would not fit on the canvas.
     */
    public static int rowX(int x, int w, int[][] others, int canvasW, int gap) {
        int result = x;
        for (int pass = 0; pass <= others.length; pass++) {
            int[] hit = null;
            for (int[] o : others)
                if (result < o[0] + o[1] && o[0] < result + w) {
                    hit = o;
                    break;
                }
            if (hit == null) return result;
            int right = hit[0] + hit[1] + gap;
            result = right + w <= canvasW ? right : Math.max(0, hit[0] - gap - w);
        }
        return result;
    }
}
