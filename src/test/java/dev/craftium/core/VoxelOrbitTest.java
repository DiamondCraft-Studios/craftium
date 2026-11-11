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

    @Test void aHiddenCellCanStillBeHighlighted() {
        var scene = cube(3);
        var view = new VoxelOrbit.View();
        int[] plain = new int[200 * 150], lit = new int[200 * 150];
        VoxelOrbit.render(scene, view, 200, 150, plain, BACKGROUND, null);
        VoxelOrbit.render(scene, view, 200, 150, lit, BACKGROUND, new int[]{1, 1, 1}); // the middle cell, inside the cube
        assertFalse(java.util.Arrays.equals(plain, lit));
        assertTrue(java.util.Arrays.stream(lit).anyMatch(p -> p == 0xffffd24a), "its edges are drawn on top");
    }

    @Test void alphaHolesAreSkippedAndEmptyScenesDrawNothing() {
        byte[] cells = new byte[8];
        var scene = new VoxelOrbit.Scene(2, 2, 2, cells, new int[][][]{null});
        int[] pixels = new int[100 * 100];
        VoxelOrbit.render(scene, new VoxelOrbit.View(), 100, 100, pixels, BACKGROUND, null);
        assertEquals(0, drawn(pixels));
    }
}
