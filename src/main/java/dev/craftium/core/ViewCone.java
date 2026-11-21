package dev.craftium.core;

/**
 * Whether a box can be on the screen: the cone around the view direction that holds the whole screen (out to its corners), against the sphere around the box.
 * It only ever errs towards drawing: a box it keeps may still be off the screen, one it drops never is. Pure, so it needs no game.
 */
public final class ViewCone {
    private final double ex, ey, ez, fx, fy, fz, halfAngle;

    /**
     * @param eye       where the camera is
     * @param forward   the direction it looks in (any length but zero)
     * @param fovDegrees the vertical field of view the frame is drawn with
     * @param aspect    the width of the screen over its height
     * @param marginDegrees added to the half angle, for what changes between the cone being made and the frame being drawn
     */
    public ViewCone(
            double ex,
            double ey,
            double ez,
            double fx,
            double fy,
            double fz,
            double fovDegrees,
            double aspect,
            double marginDegrees) {
        double length = Math.sqrt(fx * fx + fy * fy + fz * fz);
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
        this.fx = fx / length;
        this.fy = fy / length;
        this.fz = fz / length;
        double halfV = Math.toRadians(Math.clamp(fovDegrees, 1, 179)) / 2;
        // to the corner of the screen: the tangents of the two half angles make the sides of a right triangle
        double halfDiagonal = Math.atan(Math.tan(halfV) * Math.sqrt(1 + Math.max(aspect, 0) * Math.max(aspect, 0)));
        this.halfAngle = halfDiagonal + Math.toRadians(marginDegrees);
    }

    /** Whether any of the box from {@code (x0,y0,z0)} to {@code (x1,y1,z1)} can be seen. */
    public boolean sees(double x0, double y0, double z0, double x1, double y1, double z1) {
        if (halfAngle >= Math.PI) return true;
        double cx = (x0 + x1) / 2 - ex, cy = (y0 + y1) / 2 - ey, cz = (z0 + z1) / 2 - ez;
        double rx = (x1 - x0) / 2, ry = (y1 - y0) / 2, rz = (z1 - z0) / 2;
        double radius = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double distance = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (distance <= radius) return true; // the camera is in the sphere: it can face any of it
        double cos = (cx * fx + cy * fy + cz * fz) / distance;
        double angle = Math.acos(Math.clamp(cos, -1, 1));
        return angle <= halfAngle + Math.asin(radius / distance);
    }
}
