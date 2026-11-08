package dev.craftium.client;

import java.util.List;

/**
 * A record of what the editor's panels lay out in a frame: every label written and every clickable area made, with where. It is off
 * ({@link #sink} is null) except while a test collects, so the calls cost a null check. The screen clears it at the start of each frame, so what
 * is left is the last whole frame.
 */
public final class PanelLog {
    private PanelLog() {}

    /** Where the lines go; null when nothing is recording. Only the client thread reads and writes it. */
    public static List<String> sink;

    /** A new frame begins: what an earlier frame recorded is dropped. */
    static void frame() { if (sink != null) sink.clear(); }

    static void text(String text, int x, int y, String panel) {
        if (sink != null) sink.add("text " + panel + " " + x + "," + y + " '" + text + "'");
    }

    static void control(int x, int y, int width, int height, String tip, String panel) {
        if (sink != null) sink.add("control " + panel + " " + x + "," + y + " " + width + "x" + height + " '" + tip + "'");
    }
}
