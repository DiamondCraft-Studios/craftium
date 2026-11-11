package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelOrbitTest {
    private static final int BACKGROUND = 0xff101010;

    private static VoxelOrbit.Scene cube(int n) {
        int[] red = new int[VoxelOrbit.TEX * VoxelOrbit.TEX];
        java.util.Arrays.fill(red, 0xffcc2020);
        byte[] cells = new byte[n * n * n];
        java.util.Arrays.fill(cells, (byte) 1);
        return new VoxelOrbit.Scene(n, n, n, cells, new int[][][]{null, {red, red, red}});
    }

    private static long drawn(int[] pixels) { return java.util.Arrays.stream(pixels).filter(p -> p != BACKGROUND).count(); }

    @Test void drawsTheBlocksAndMovesWithTheView() {
        var scene = cube(3);
        var view = new VoxelOrbit.View();
        int[] a = new int[200 * 150], b = new int[200 * 150];
        VoxelOrbit.render(scene, view, 200, 150, a, BACKGROUND, null);
        assertTrue(drawn(a) > 1000, "the cube is drawn");
        view.turn(40, 10);
        VoxelOrbit.render(scene, view, 200, 150, b, BACKGROUND, null);
        assertFalse(java.util.Arrays.equals(a, b), "turning changes the picture");
        view.panX = 30;
        int[] c = new int[200 * 150];
        VoxelOrbit.render(scene, view, 200, 150, c, BACKGROUND, null);
        assertFalse(java.util.Arrays.equals(b, c), "panning changes the picture");
    }

    @Test void zoomingInFillsMoreOfThePicture() {
        var scene = cube(3);
        var view = new VoxelOrbit.View();
        int[] near = new int[200 * 150], far = new int[200 * 150];
        VoxelOrbit.render(scene, view, 200, 150, far, BACKGROUND, null);
        view.zoom = 1.5;
        VoxelOrbit.render(scene, view, 200, 150, near, BACKGROUND, null);
        assertTrue(drawn(near) > drawn(far));
    }

    @Test void topAndFrontViewsShowASquare() {
        var scene = cube(4);
        var view = new VoxelOrbit.View();
        view.top();
        int[] pixels = new int[160 * 160];
        VoxelOrbit.render(scene, view, 160, 160, pixels, BACKGROUND, null);
        assertTrue(drawn(pixels) > 5000);
        view.front();
        java.util.Arrays.fill(pixels, 0);
        VoxelOrbit.render(scene, view, 160, 160, pixels, BACKGROUND, null);
        assertTrue(drawn(pixels) > 5000);
    }

    @Test void aHiddenCellIsOutlinedFaintlyAndASeenOneSharply() {
        var scene = cube(3);
        var view = new VoxelOrbit.View();
        int[] plain = new int[200 * 150], hidden = new int[200 * 150], seen = new int[200 * 150];
        VoxelOrbit.render(scene, view, 200, 150, plain, BACKGROUND, null);
        VoxelOrbit.render(scene, view, 200, 150, hidden, BACKGROUND, new int[]{1, 1, 1}); // the middle cell, inside the cube
        assertFalse(java.util.Arrays.equals(plain, hidden), "it can still be found");
        assertTrue(java.util.Arrays.stream(hidden).noneMatch(p -> p == VoxelOrbit.HOVER), "but only faintly, not as a solid line");
        VoxelOrbit.render(scene, view, 200, 150, seen, BACKGROUND, new int[]{2, 2, 2}); // the corner nearest the default camera
        assertTrue(java.util.Arrays.stream(seen).anyMatch(p -> p == VoxelOrbit.HOVER), "an outline of the hover colour");
    }

    @Test void theOutlineOfACellIsCutAwayByBlocksInFront() {
        var scene = cube(3);
        var view = new VoxelOrbit.View();
        int[] front = new int[200 * 150], back = new int[200 * 150];
        VoxelOrbit.render(scene, view, 200, 150, front, BACKGROUND, new int[]{2, 2, 2});
        VoxelOrbit.render(scene, view, 200, 150, back, BACKGROUND, new int[]{0, 0, 0}); // the far bottom corner
        long a = java.util.Arrays.stream(front).filter(p -> p == VoxelOrbit.HOVER).count(), b = java.util.Arrays.stream(back).filter(p -> p == VoxelOrbit.HOVER).count();
        assertTrue(a > 0 && b == 0, "the far cell has no sharp line: " + a + " / " + b);
    }

    @Test void alphaHolesAreSkippedAndEmptyScenesDrawNothing() {
        byte[] cells = new byte[8];
        var scene = new VoxelOrbit.Scene(2, 2, 2, cells, new int[][][]{null});
        int[] pixels = new int[100 * 100];
        VoxelOrbit.render(scene, new VoxelOrbit.View(), 100, 100, pixels, BACKGROUND, null);
        assertEquals(0, drawn(pixels));
    }

    private static int[] solid(int colour) { int[] t = new int[VoxelOrbit.TEX * VoxelOrbit.TEX]; java.util.Arrays.fill(t, colour); return t; }

    @Test void touchingBlocksHideTheFacesBetweenThem() {
        var shape = VoxelOrbit.Shape.cube(solid(0xff00aa00), solid(0xff00aa00), solid(0xff00aa00));
        var scene = new VoxelOrbit.Scene(2, 1, 1, new byte[]{1, 1}, new VoxelOrbit.Shape[]{null, shape});
        assertEquals(10, scene.visibleQuad.length, "two cubes side by side show 6 + 6 - 2 faces");
    }

    @Test void aSlabIsShorterThanACubeAndAPlaneIsSeenFromOneSideOnly() {
        // a bottom slab: its top at half height, drawn by hand
        float[] n = {0, 1, 0};
        var top = new VoxelOrbit.Quad(new float[]{0, 0.5f, 0}, new float[]{1, 0, 0}, new float[]{0, 0, 1}, n, 0, 0, 16, 0, 0, 16, solid(0xffcccccc), -1, VoxelOrbit.UP);
        var slab = new VoxelOrbit.Shape(new VoxelOrbit.Quad[]{top}, false);
        var scene = new VoxelOrbit.Scene(1, 1, 1, new byte[]{1}, new VoxelOrbit.Shape[]{null, slab});
        var view = new VoxelOrbit.View();
        int[] a = new int[100 * 100];
        VoxelOrbit.render(scene, view, 100, 100, a, BACKGROUND, null);
        assertTrue(drawn(a) > 100, "seen from above");
        view.pitch = -30; // from below
        int[] b = new int[100 * 100];
        VoxelOrbit.render(scene, view, 100, 100, b, BACKGROUND, null);
        assertEquals(0, drawn(b), "its top face is not drawn from below");
    }

    @Test void transparentTexelsLetWhatIsBehindShow() {
        int[] holes = solid(0x00000000);
        holes[0] = 0xffffffff;
        var shape = VoxelOrbit.Shape.cube(holes, holes, holes);
        var full = VoxelOrbit.Shape.cube(solid(0xffff0000), solid(0xffff0000), solid(0xffff0000));
        var view = new VoxelOrbit.View();
        int[] a = new int[100 * 100], b = new int[100 * 100];
        VoxelOrbit.render(new VoxelOrbit.Scene(1, 1, 1, new byte[]{1}, new VoxelOrbit.Shape[]{null, shape}), view, 100, 100, a, BACKGROUND, null);
        VoxelOrbit.render(new VoxelOrbit.Scene(1, 1, 1, new byte[]{1}, new VoxelOrbit.Shape[]{null, full}), view, 100, 100, b, BACKGROUND, null);
        assertTrue(drawn(a) < drawn(b) / 10);
    }
}
