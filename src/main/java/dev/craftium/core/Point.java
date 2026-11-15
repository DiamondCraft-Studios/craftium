package dev.craftium.core;

public record Point(int x, int y, int z) {
    public Point add(int dx, int dy, int dz) {
        return new Point(x + dx, y + dy, z + dz);
    }

    public Point subtract(Point other) {
        return add(-other.x, -other.y, -other.z);
    }

    public double distanceSquared(Point other) {
        double dx = (double) x - other.x, dy = (double) y - other.y, dz = (double) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public Point rotateY() {
        return new Point(-z, y, x);
    }

    public Point mirrorX() {
        return new Point(-x, y, z);
    }

    public String coordinates() {
        return x + ", " + y + ", " + z;
    }
}
