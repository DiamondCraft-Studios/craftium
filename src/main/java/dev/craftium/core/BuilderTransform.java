package dev.craftium.core;

/** Exact cell transforms shared by the builder's preview and server transaction. */
public final class BuilderTransform {
    private BuilderTransform() {}

    public static Point apply(Point p, Point size, int turns, int flip) {
        int x = flip == 0 ? size.x() - 1 - p.x() : p.x(),
                y = flip == 1 ? size.y() - 1 - p.y() : p.y(),
                z = flip == 2 ? size.z() - 1 - p.z() : p.z(),
                sx = size.x(),
                sz = size.z();
        for (int i = 0; i < Math.floorMod(turns, 4); i++) {
            int nextX = sz - 1 - z;
            z = x;
            x = nextX;
            int temp = sx;
            sx = sz;
            sz = temp;
        }
        return new Point(x, y, z);
    }
}
