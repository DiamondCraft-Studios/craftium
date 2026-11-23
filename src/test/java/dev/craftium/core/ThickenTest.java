package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThickenTest {
    private static List<Point> ball(Point c, int r) {
        var cells = new ArrayList<Point>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) if (x * x + y * y + z * z <= r * r) cells.add(c.add(x, y, z));
        return cells;
    }

    private static Options options(int radius, double strength, boolean thin) {
        var o = new Options();
        o.radius = radius;
        o.strength = strength;
        o.invert = thin;
        return o;
    }

    /** A flat floor: everything at y 0 and below, from y -8, is solid, named by its height. */
    private static DeformTest.World floor() {
        var w = new DeformTest.World();
        for (int x = -16; x <= 16; x++)
            for (int z = -16; z <= 16; z++)
                for (int y = -8; y <= 0; y++) w.blocks.put(new Point(x, y, z), y == 0 ? "grass" : "dirt");
        return w;
    }

    private static Thicken.Plan dab(
            DeformTest.World w, Point c, Point normal, int radius, double strength, boolean thin) {
        return Thicken.plan(ball(c, radius), c, normal, options(radius, strength, thin), w);
    }

    private static DeformTest.World apply(DeformTest.World w, Thicken.Plan plan) {
        var next = new DeformTest.World();
        next.blocks.putAll(w.blocks);
        for (var e : plan.added().entrySet()) next.blocks.put(e.getKey(), w.blocks.get(e.getValue()));
        for (Point p : plan.removed()) next.blocks.remove(p);
        return next;
    }

    @Test
    void thickensAFloorByTheStrengthAtTheCentre() {
        var w = floor();
        var plan = dab(w, new Point(0, 0, 0), new Point(0, 1, 0), 8, 3, false);
        assertTrue(plan.removed().isEmpty());
        for (int y = 1; y <= 3; y++) assertTrue(plan.added().containsKey(new Point(0, y, 0)), "y " + y);
        assertFalse(plan.added().containsKey(new Point(0, 4, 0)));
        // Nothing is added below the surface, or at the rim of the brush.
        for (Point p : plan.added().keySet()) {
            assertTrue(p.y() >= 1, p.toString());
            assertTrue(Math.abs(p.x()) < 9 && Math.abs(p.z()) < 9, p.toString());
        }
    }

    @Test
    void tapersTowardsTheRim() {
        var w = floor();
        var plan = dab(w, new Point(0, 0, 0), new Point(0, 1, 0), 8, 4, false);
        int centre = height(plan, 0), half = height(plan, 4), rim = height(plan, 8);
        assertEquals(4, centre);
        assertTrue(half > 0 && half < centre, "half " + half);
        assertEquals(0, rim);
    }

    @Test
    void aLowFalloffKeepsTheLayerFullNearerTheRing() {
        var w = floor();
        var hard = options(8, 2, false);
        hard.deformFalloff = 0;
        var soft = options(8, 2, false);
        soft.deformFalloff = 100;
        var c = new Point(0, 0, 0);
        var up = new Point(0, 1, 0);
        int hardAt5 = height(Thicken.plan(ball(c, 8), c, up, hard, w), 5);
        int softAt5 = height(Thicken.plan(ball(c, 8), c, up, soft, w), 5);
        assertEquals(2, hardAt5);
        assertTrue(softAt5 < 2, "soft " + softAt5);
        // Even with no falloff the edge is never a cliff: it fades over one and a half times the thickness, as
        // Deform's.
        assertEquals(0, height(Thicken.plan(ball(c, 8), c, up, hard, w), 9));
    }

    private static int height(Thicken.Plan plan, int x) {
        int top = 0;
        for (Point p : plan.added().keySet()) if (p.x() == x && p.z() == 0) top = Math.max(top, p.y());
        return top;
    }

    @Test
    void newBlocksCopyTheNearestSurfaceBlock() {
        var w = floor();
        w.blocks.put(new Point(0, 0, 0), "ore");
        var plan = dab(w, new Point(0, 0, 0), new Point(0, 1, 0), 6, 2, false);
        assertEquals(new Point(0, 0, 0), plan.added().get(new Point(0, 1, 0)));
        assertEquals("ore", apply(w, plan).blocks.get(new Point(0, 1, 0)));
        assertEquals("grass", apply(w, plan).blocks.get(new Point(3, 1, 0)));
    }

    @Test
    void thinsAFloor() {
        var w = floor();
        var plan = dab(w, new Point(0, 0, 0), new Point(0, 1, 0), 8, 2, true);
        assertTrue(plan.added().isEmpty());
        assertTrue(plan.removed().contains(new Point(0, 0, 0)));
        assertTrue(plan.removed().contains(new Point(0, -1, 0)));
        assertFalse(plan.removed().contains(new Point(0, -2, 0)));
    }

    @Test
    void thickensTheInsideOfAOneBlockShellAndNeverItsOutside() {
        var w = DeformTest.hollow(1);
        int inner = 0;
        while (!w.solid(new Point(inner, 0, 0))) inner++;
        var c = new Point(inner, 0, 0);
        var plan = dab(w, c, new Point(-1, 0, 0), 6, 3, false);
        assertFalse(plan.isEmpty());
        // Grown into the hollow by three blocks at the centre.
        for (int x = inner - 3; x < inner; x++) assertTrue(plan.added().containsKey(new Point(x, 0, 0)), "x " + x);
        assertFalse(plan.added().containsKey(new Point(inner - 4, 0, 0)));
        // Nothing outside the ball, which is 12 blocks across from its centre.
        for (Point p : plan.added().keySet())
            assertTrue(p.x() * p.x() + p.y() * p.y() + p.z() * p.z() < 12 * 12, p.toString());
    }

    @Test
    void thinningAOneBlockShellNeverReachesPastIt() {
        var w = DeformTest.hollow(3);
        int inner = 0;
        while (!w.solid(new Point(inner, 0, 0))) inner++;
        var plan = dab(w, new Point(inner, 0, 0), new Point(-1, 0, 0), 6, 1, true);
        assertTrue(plan.removed().contains(new Point(inner, 0, 0)));
        assertFalse(plan.removed().contains(new Point(inner + 1, 0, 0)));
    }

    @Test
    void doesNothingWhenNotAimedAtASurface() {
        var w = floor();
        assertTrue(dab(w, new Point(0, 5, 0), new Point(0, 1, 0), 4, 2, false).isEmpty());
        assertTrue(dab(w, new Point(0, -3, 0), new Point(0, 1, 0), 4, 2, false).isEmpty());
    }

    /** The top blocks of the floor within {@code r} of the middle, as the ring on the screen names them looking down. */
    private static List<Point> sheet(int r) {
        var cells = new ArrayList<Point>();
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) if (x * x + z * z <= r * r) cells.add(new Point(x, 0, z));
        return cells;
    }

    @Test
    void withTheRingItGrowsFromTheBlocksTheRingCovers() {
        var w = floor();
        var o = options(4, 3, false);
        var plan = Thicken.plan(sheet(8), new Point(0, 0, 0), new Point(0, 1, 0), o, w, true);
        for (int y = 1; y <= 3; y++) assertTrue(plan.added().containsKey(new Point(0, y, 0)), "y " + y);
        // The taper follows the ring, not the radius: halfway out to the ring's edge there is still a layer.
        assertTrue(height(plan, 5) > 0);
        for (Point p : plan.added().keySet()) assertTrue(p.y() >= 1, p.toString());
    }

    @Test
    void withTheRingThinReachesIntoTheWallBehindTheSurface() {
        var w = floor();
        var plan = Thicken.plan(sheet(8), new Point(0, 0, 0), new Point(0, 1, 0), options(4, 3, true), w, true);
        assertTrue(plan.removed().contains(new Point(0, 0, 0)));
        assertTrue(plan.removed().contains(new Point(0, -2, 0)));
        assertFalse(plan.removed().contains(new Point(0, -3, 0)));
    }

    @Test
    void withTheRingThinNeverReachesAnotherWallPastAGap() {
        // A one-block floor at y 0 with open air under it and another floor at y -2.
        var w = new DeformTest.World();
        for (int x = -12; x <= 12; x++)
            for (int z = -12; z <= 12; z++) {
                w.blocks.put(new Point(x, 0, z), "stone");
                w.blocks.put(new Point(x, -2, z), "stone");
            }
        var plan = Thicken.plan(sheet(8), new Point(0, 0, 0), new Point(0, 1, 0), options(4, 5, true), w, true);
        assertTrue(plan.removed().contains(new Point(0, 0, 0)));
        for (Point p : plan.removed()) assertEquals(0, p.y(), p.toString());
    }

    @Test
    void thicknessIsWholeBlocksWithinItsRange() {
        assertEquals(1, Thicken.thickness(options(4, .2, false)));
        assertEquals(3, Thicken.thickness(options(4, 2.6, false)));
        assertEquals(Thicken.MAX_THICKNESS, Thicken.thickness(options(4, 50, false)));
    }
}
