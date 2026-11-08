package dev.craftium.client.showcase;

/**
 * One showcase picture: where the camera is (its eye, in world coordinates), which way it looks, how wide its lens is and how big
 * the picture is. The camera has no roll: the game's own camera cannot tilt sideways.
 */
public record ShotSpec(String name, double x, double y, double z, float yaw, float pitch, int fov, int width, int height, boolean hideHud) {
    public static final int DEFAULT_WIDTH = 1920, DEFAULT_HEIGHT = 1080, MAX_WIDTH = 3840, MAX_HEIGHT = 2160, MIN_SIDE = 64;
    /** The game's own field of view slider runs from 30 to 110, and the option refuses anything else. */
    public static final int MIN_FOV = 30, MAX_FOV = 110, DEFAULT_FOV = 70;

    public ShotSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A shot needs a name");
        if (width < MIN_SIDE || height < MIN_SIDE || width > MAX_WIDTH || height > MAX_HEIGHT)
            throw new IllegalArgumentException("The picture is " + width + " by " + height + "; each side must be " + MIN_SIDE + " to at most " + MAX_WIDTH + " by " + MAX_HEIGHT + " (4K)");
        if (fov < MIN_FOV || fov > MAX_FOV) throw new IllegalArgumentException("The field of view is " + fov + "; it must be " + MIN_FOV + " to " + MAX_FOV);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("The camera position and angles must be finite numbers");
        if (pitch < -90 || pitch > 90) throw new IllegalArgumentException("The pitch is " + pitch + "; it must be -90 (up) to 90 (down)");
    }

    /** A 1080p shot with the default lens and the interface hidden. */
    public static ShotSpec of(String name, double x, double y, double z, float yaw, float pitch) {
        return new ShotSpec(name, x, y, z, yaw, pitch, DEFAULT_FOV, DEFAULT_WIDTH, DEFAULT_HEIGHT, true);
    }
}
