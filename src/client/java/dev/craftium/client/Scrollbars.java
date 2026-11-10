package dev.craftium.client;

import dev.craftium.client.compat.Gfx;

/**
 * The scrollbar of the jigsaw planner's panels: a track and a thumb that can be dragged. It shows only when the content is taller than the room for it,
 * and the arithmetic is the same for drawing it, for catching a click on it and for dragging it.
 */
final class Scrollbars {
    static final int WIDTH = 6;
    private Scrollbars() {}

    static int thumb(int room, int content) { return Math.max(16, room * room / Math.max(room, content)); }

    /** Draws the bar at x for a view {@code room} tall that shows part of {@code content}; nothing when it all fits. */
    static void draw(Gfx g, int x, int y, int room, double scroll, int content, boolean grabbed) {
        if (content <= room) return;
        int thumb = thumb(room, content), travel = room - thumb;
        int thumbY = y + (int) Math.round(travel * (scroll / (content - room)));
        g.fill(x, y, x + WIDTH, y + room, 0xff1b2838);
        g.fill(x, thumbY, x + WIDTH, thumbY + thumb, grabbed ? 0xff7cc4ff : 0xff4a6585);
    }

    /** Whether the pointer is on the bar, a little generously so it is easy to take hold of. */
    static boolean hit(double mx, double my, int x, int y, int room) { return mx >= x - 3 && mx < x + WIDTH + 3 && my >= y && my < y + room; }

    /** The scroll position that puts the middle of the thumb where the pointer is. */
    static double scrollAt(double my, int y, int room, int content) {
        int travel = room - thumb(room, content);
        if (travel <= 0 || content <= room) return 0;
        return Math.clamp((my - y - thumb(room, content) / 2.0) / travel * (content - room), 0, content - room);
    }
}
