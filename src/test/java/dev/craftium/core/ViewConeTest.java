package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ViewConeTest {
    /** At the origin looking along -Z, as Minecraft's camera does with no turn, 70 degrees high on a 16:9 screen. */
    private static final ViewCone CONE = new ViewCone(0, 0, 0, 0, 0, -1, 70, 16 / 9d, 10);

    private static boolean sees(double x, double y, double z, double size) {
        return CONE.sees(x, y, z, x + size, y + size, z + size);
    }

    @Test
    void whatIsInFrontIsSeenAndWhatIsBehindIsNot() {
        assertTrue(sees(0, 0, -50, 1));
        assertFalse(sees(0, 0, 50, 1));
        assertFalse(sees(200, 0, 0, 1), "far off to the side");
    }

    @Test
    void theCornersOfTheScreenAreInside() {
        // 16:9 at 70 degrees high reaches about 51 degrees across at its corners
        double half = Math.toRadians(35), across = Math.atan(Math.tan(half) * 16 / 9d);
        assertTrue(sees(Math.tan(across) * 100 * 0.95, 0, -100, 0.01), "just inside the left and right edges");
        assertTrue(sees(Math.tan(half) * 100 * 0.95, Math.tan(half) * 100 * 0.95, -100, 0.01), "toward a corner");
    }

    @Test
    void aBigBoxThatReachesIntoViewIsSeen() {
        assertTrue(CONE.sees(-1000, -10, 5, 1000, 10, 2000), "it reaches in front from behind");
        assertTrue(CONE.sees(-5, -5, -5, 5, 5, 5), "the camera is inside it");
        assertTrue(CONE.sees(60, 0, -50, 160, 10, -40), "its centre is outside the cone, its edge is not");
    }
}
