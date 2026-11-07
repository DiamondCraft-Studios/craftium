package dev.craftium.client;

/** The two drawing operations the editor's icons are made of, so an icon can be drawn to the screen or into a baked texture. */
public interface Canvas {
    void fill(int x1,int y1,int x2,int y2,int color);
    /** A one-pixel border drawn just inside the rectangle. */
    void outline(int x,int y,int width,int height,int color);
}
