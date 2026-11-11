package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VoxelViewTest {
    private static final int GREY = 0xffc8c8c8;

    private static Set<Integer> colours(java.awt.image.BufferedImage image) {
        var found = new HashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) found.add(image.getRGB(x, y) & 0xffffff);
        return found;
    }

    private static int centre(VoxelView.Result r) { return r.image().getRGB(r.image().getWidth() / 2, r.image().getHeight() / 2) & 0xffffff; }

    @Test void lookingDownShowsTheTopInItsOwnColour() {
        var r = VoxelView.render(1, 1, 1, new int[]{0xff00ff00}, VoxelView.Angle.TOP, 200, 200);
        assertEquals(0x00ff00, centre(r));
    }

    @Test void anIsometricCornerShowsTheTopAndTwoSidesInDifferentShades() {
        var r = VoxelView.render(1, 1, 1, new int[]{GREY}, VoxelView.Angle.SE, 300, 300);
        var found = colours(r.image());
        assertTrue(found.contains(0xc8c8c8), "top");          // 200 * 1.0
        assertTrue(found.contains(0xa0a0a0), "east side");    // 200 * 0.80
        assertTrue(found.contains(0x909090), "south side");   // 200 * 0.72
        assertFalse(found.contains(0x6e6e6e), "the north side cannot be seen from the south east"); // 200 * 0.55
    }

    @Test void theNearerBlockHidesTheFurtherOne() {
        int red = 0xffff0000, blue = 0xff0000ff;
        int[] blocks = {blue, red}; // z = 0 is blue, z = 1 is red; x fastest, then z
        var fromSouth = VoxelView.render(1, 1, 2, blocks, VoxelView.Angle.SOUTH, 300, 300);
        assertEquals(0xb70000, centre(fromSouth)); // red, 255 * 0.72, nearest the camera in the south
        var fromNorth = VoxelView.render(1, 1, 2, blocks, VoxelView.Angle.NORTH, 300, 300);
        assertEquals(0x00008c, centre(fromNorth)); // blue, 255 * 0.55
    }

    @Test void aFaceHiddenByANeighbourIsNotDrawn() {
        // Two blocks side by side seen from the east: only the nearer one's east face is visible, and the picture is two blocks wide in z.
        int[] blocks = {GREY, GREY};
        var r = VoxelView.render(2, 1, 1, blocks, VoxelView.Angle.TOP, 200, 200);
        var found = colours(r.image());
        assertTrue(found.contains(0xc8c8c8));
        assertFalse(found.contains(0xa0a0a0));
    }

    @Test void emptySpaceIsJustBackgroundAndEdges() {
        var r = VoxelView.render(2, 2, 2, new int[8], VoxelView.Angle.NE, 300, 300);
        assertFalse(colours(r.image()).contains(0xc8c8c8));
    }

    @Test void saysWhichWayEastAndNorthPoint() {
        var top = VoxelView.render(1, 1, 1, new int[]{GREY}, VoxelView.Angle.TOP, 100, 100);
        assertEquals("right", top.east());
        assertEquals("up", top.north());
        var south = VoxelView.render(1, 1, 1, new int[]{GREY}, VoxelView.Angle.SOUTH, 100, 100);
        assertEquals("right", south.east());
        assertEquals("straight towards or away from the viewer", south.north());
        var north = VoxelView.render(1, 1, 1, new int[]{GREY}, VoxelView.Angle.NORTH, 100, 100);
        assertEquals("left", north.east());
    }

    @Test void thePictureFitsTheSizeAsked() {
        int n = 40;
        int[] blocks = new int[n * n * n];
        java.util.Arrays.fill(blocks, GREY);
        for (var angle : VoxelView.Angle.values()) {
            var r = VoxelView.render(n, n, n, blocks, angle, 1024, 768);
            assertTrue(r.image().getWidth() <= 1024 && r.image().getHeight() <= 768, angle + " " + r.image().getWidth() + "x" + r.image().getHeight());
        }
    }

    @Test void aLongThinBoxIsRefusedInsteadOfDrawnTooSmall() {
        // Small in volume, but far wider than the picture: callers that draw thumbnails must catch this rather than let it reach a screen.
        int n = 5000;
        assertThrows(IllegalArgumentException.class, () -> VoxelView.render(n, 1, 1, new int[n], VoxelView.Angle.SE, 128, 88));
    }

    @Test void refusesBlocksThatDoNotFillTheBox() {
        assertThrows(IllegalArgumentException.class, () -> VoxelView.render(2, 2, 2, new int[3], VoxelView.Angle.TOP, 100, 100));
    }

    @Test void parsesAngleNames() {
        assertEquals(VoxelView.Angle.SE, VoxelView.Angle.parse("se"));
        assertEquals(VoxelView.Angle.TOP, VoxelView.Angle.parse("TOP"));
        assertThrows(IllegalArgumentException.class, () -> VoxelView.Angle.parse("above"));
    }
}
