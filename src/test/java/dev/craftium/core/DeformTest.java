package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeformTest {
    private static final Point[] FACES = {
        new Point(1, 0, 0),
        new Point(-1, 0, 0),
        new Point(0, 1, 0),
        new Point(0, -1, 0),
        new Point(0, 0, 1),
        new Point(0, 0, -1)
    };

    /** A world of named blocks: anything not in the map is air. */
    static final class World implements BrushRules.Cells {
        final Map<Point, String> blocks = new HashMap<>();

        public boolean solid(Point p) {
            return blocks.containsKey(p);
        }

        public boolean air(Point p) {
            return !solid(p);
        }

        /** The world after a dab: every planned block takes the block of its source, as the server copies it. */
        World after(Map<Point, Point> plan) {
            var next = new World();
            next.blocks.putAll(blocks);
            for (var e : plan.entrySet()) {
                var block = blocks.get(e.getValue());
                if (block == null) next.blocks.remove(e.getKey());
                else next.blocks.put(e.getKey(), block);
            }
            return next;
        }
    }

    private static List<Point> ball(Point c, int r) {
        var cells = new ArrayList<Point>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) if (x * x + y * y + z * z <= r * r) cells.add(c.add(x, y, z));
        return cells;
    }

    private static Options options(int radius, double strength, boolean pull) {
        var o = new Options();
        o.radius = radius;
        o.strength = strength;
        o.invert = pull;
        return o;
    }

    private static final int OUTER = 12, BOX = OUTER + 3;

    /** A hollow ball of stone OUTER blocks across from its centre, with walls {@code thickness} blocks thick, closed through its faces. */
    static World hollow(int thickness) {
        Predicate<Point> in = p -> p.x() * p.x() + p.y() * p.y() + p.z() * p.z() <= OUTER * OUTER;
        Set<Point> kept = new HashSet<>();
        for (int x = -BOX; x <= BOX; x++)
            for (int y = -BOX; y <= BOX; y++)
                for (int z = -BOX; z <= BOX; z++) {
                    var p = new Point(x, y, z);
                    if (in.test(p)) kept.add(p);
                }
        // Wear the ball away from inside, one face layer at a time, until only the wall is left.
        Set<Point> core = new HashSet<>(kept);
        for (int i = 0; i < thickness; i++) {
            var next = new HashSet<Point>();
            for (Point p : core) {
                boolean all = true;
                for (Point f : FACES) all &= core.contains(p.add(f.x(), f.y(), f.z()));
                if (all) next.add(p);
            }
            core = next;
        }
        var w = new World();
        for (Point p : kept) if (!core.contains(p)) w.blocks.put(p, "stone");
        return w;
    }

    /** Whether air can get from inside the ball to outside its box through faces alone. */
    private static boolean leaks(World w) {
        var seen = new HashSet<Point>();
        var queue = new ArrayDeque<Point>();
        // From the far half of the hollow, which the pushes never reach.
        var start = new Point(-OUTER / 2, 0, 0);
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            if (Math.abs(p.x()) >= BOX || Math.abs(p.y()) >= BOX || Math.abs(p.z()) >= BOX) return true;
            for (Point f : FACES) {
                Point q = p.add(f.x(), f.y(), f.z());
                if (!w.solid(q) && seen.add(q)) queue.add(q);
            }
        }
        return false;
    }

    /** The outermost wall block on the +x axis. */
    private static int outerX(World w) {
        for (int x = BOX; x > -BOX; x--) if (w.solid(new Point(x, 0, 0))) return x;
        return Integer.MIN_VALUE;
    }

    private static int wallOnAxis(World w) {
        int count = 0;
        for (int x = 0; x <= BOX; x++) if (w.solid(new Point(x, 0, 0))) count++;
        return count;
    }

    static World push(World w, int radius, double strength, boolean pull) {
        var c = new Point(outerX(w), 0, 0);
        var plan = Deform.plan(ball(c, radius), c, new Point(1, 0, 0), options(radius, strength, pull), w, 2);
        return w.after(plan);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void pushingTheSideOfAHollowBallKeepsItClosedAndAsThick(int thickness) {
        var w = hollow(thickness);
        assertFalse(leaks(w), "the test ball leaks before it is touched");
        int before = outerX(w);
        var pushed = push(w, 6, 4, false);
        assertFalse(leaks(pushed), "a hole after one dab");
        assertEquals(before - 4, outerX(pushed), "the centre of the wall moves in by the strength");
        assertEquals(thickness, wallOnAxis(pushed), "the wall keeps its thickness at the centre");
        for (var e : w.blocks.entrySet())
            if (e.getKey().x() <= 0) assertTrue(pushed.solid(e.getKey()), "the far side moved at " + e.getKey());
        // A stroke of several dabs, each on the wall where the last one left it.
        var again = push(push(pushed, 6, 2, false), 6, 2, false);
        assertFalse(leaks(again), "a hole after three dabs");
        assertEquals(before - 8, outerX(again));
        assertEquals(thickness, wallOnAxis(again));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void pullingTheSideOfAHollowBallKeepsItClosed(int thickness) {
        var w = hollow(thickness);
        int before = outerX(w);
        var pulled = push(w, 6, 3, true);
        assertFalse(leaks(pulled));
        assertEquals(before + 3, outerX(pulled));
        assertEquals(thickness, wallOnAxis(pulled));
    }

    @Test
    void pushingOffCentreStillLeavesNoHole() {
        var w = hollow(1);
        // Clicked on a slanted part of the wall, so the push runs at an angle to the grid.
        Point c = null;
        for (int x = BOX; x > 0 && c == null; x--) if (w.solid(new Point(x, x, 0))) c = new Point(x, x, 0);
        assertNotNull(c);
        var normal = w.solid(c.add(1, 0, 0)) ? new Point(0, 1, 0) : new Point(1, 0, 0);
        var plan = Deform.plan(ball(c, 7), c, normal, options(7, 4, false), w, 2);
        assertFalse(plan.isEmpty());
        assertFalse(leaks(w.after(plan)));
    }

    /** A stroke wandering over a one-block shell, with mixed sizes and strengths, Push and Pull: never a hole. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void aWanderingStrokeNeverOpensTheShell(int thickness) {
        var w = hollow(thickness);
        var random = new java.util.Random(20261123L);
        int idle = 0;
        for (int dab = 0; dab < 30; dab++) {
            // A wall block on the near half with open air beside it, clicked on that side.
            var surface = new ArrayList<Point[]>();
            for (Point p : w.blocks.keySet())
                if (p.x() > 2)
                    for (Point f : FACES) {
                        Point q = p.add(f.x(), f.y(), f.z());
                        if (!w.solid(q)
                                && q.x() * q.x() + q.y() * q.y() + q.z() * q.z()
                                        > p.x() * p.x() + p.y() * p.y() + p.z() * p.z())
                            surface.add(new Point[] {p, f});
                    }
            surface.sort(java.util.Comparator.comparing((Point[] e) -> e[0].x())
                    .thenComparing(e -> e[0].y())
                    .thenComparing(e -> e[0].z())
                    .thenComparing(e -> e[1].x())
                    .thenComparing(e -> e[1].y())
                    .thenComparing(e -> e[1].z()));
            var pick = surface.get(random.nextInt(surface.size()));
            int radius = 3 + random.nextInt(6);
            var o = options(radius, 1 + random.nextInt(Deform.maxStrength(radius)), random.nextInt(4) == 0);
            var plan = Deform.plan(ball(pick[0], radius), pick[0], pick[1], o, w, 2);
            if (plan.isEmpty()) idle++;
            w = w.after(plan);
            assertFalse(leaks(w), "a hole after dab " + dab + " at " + pick[0] + " radius " + radius);
        }
        assertTrue(idle < 3, idle + " of the dabs moved nothing");
    }

    @Test
    void groundKeepsItsTopBlockOnTop() {
        var w = new World();
        for (int x = -12; x <= 12; x++)
            for (int z = -12; z <= 12; z++) {
                w.blocks.put(new Point(x, 0, z), "grass");
                for (int y = -10; y < 0; y++) w.blocks.put(new Point(x, y, z), "dirt");
            }
        var c = new Point(0, 0, 0);
        var up = new Point(0, 1, 0);
        var pushed = w.after(Deform.plan(ball(c, 6), c, up, options(6, 3, false), w, 2));
        assertEquals("grass", pushed.blocks.get(new Point(0, -3, 0)), "the grass went down with the surface");
        assertFalse(pushed.solid(new Point(0, -2, 0)));
        assertEquals("dirt", pushed.blocks.get(new Point(0, -4, 0)));
        var pulled = w.after(Deform.plan(ball(c, 6), c, up, options(6, 3, true), w, 2));
        assertEquals("grass", pulled.blocks.get(new Point(0, 3, 0)), "the grass went up with the surface");
        assertEquals("dirt", pulled.blocks.get(new Point(0, 2, 0)));
        assertFalse(pulled.solid(new Point(0, 4, 0)));
    }

    @Test
    void aPushedWallOverwritesWhatStandsInItsWay() {
        var w = hollow(1);
        int wall = outerX(w);
        // A pillar inside the hollow, just behind the wall.
        w.blocks.put(new Point(wall - 3, 0, 0), "pillar");
        w.blocks.put(new Point(wall - 6, 0, 0), "far pillar");
        var pushed = push(w, 6, 3, false);
        assertEquals("stone", pushed.blocks.get(new Point(wall - 3, 0, 0)), "the wall wins");
        assertEquals("far pillar", pushed.blocks.get(new Point(wall - 6, 0, 0)), "what the wall does not reach stays");
        assertFalse(leaks(pushed));
    }

    @Test
    void strengthIsCappedBelowTwoThirdsOfTheRadius() {
        assertEquals(4, Deform.maxStrength(6));
        assertEquals(1, Deform.maxStrength(1));
        assertEquals(4, Deform.strength(options(6, 10, false)));
        assertEquals(1, Deform.strength(options(6, 0, false)));
    }

    @Test
    void clickingIntoOpenAirDoesNothing() {
        var w = hollow(1);
        var c = new Point(BOX, 0, 0);
        assertTrue(Deform.plan(ball(c, 4), c, new Point(1, 0, 0), options(4, 2, false), w, 2)
                .isEmpty());
    }

    @Test
    void aDabIsTheSameEachTime() {
        var w = hollow(2);
        var c = new Point(outerX(w), 0, 0);
        var o = options(6, 4, false);
        assertEquals(
                new ArrayList<>(
                        Deform.plan(ball(c, 6), c, new Point(1, 0, 0), o, w, 2).entrySet()),
                new ArrayList<>(
                        Deform.plan(ball(c, 6), c, new Point(1, 0, 0), o, w, 2).entrySet()));
    }

    /** Ground with its top at {@code height(x, z)}, ten blocks deep, grass on top. */
    private static World ground(java.util.function.IntBinaryOperator height) {
        var w = new World();
        for (int x = -20; x <= 20; x++)
            for (int z = -20; z <= 20; z++) {
                int h = height.applyAsInt(x, z);
                w.blocks.put(new Point(x, h, z), "grass");
                for (int y = -12; y < h; y++) w.blocks.put(new Point(x, y, z), "dirt");
            }
        return w;
    }

    private static int top(World w, int x, int z) {
        for (int y = 12; y > -14; y--) if (w.solid(new Point(x, y, z))) return y;
        return Integer.MIN_VALUE;
    }

    /** A stroke along x, one dab per block, each clicked on the ground's top as it was before the stroke. */
    private static Deform.Stroke strokeAlongX(World w, int from, int to, int z, Options o) {
        var stroke = new Deform.Stroke();
        for (int x = from; x <= to; x++) {
            var c = new Point(x, top(w, x, z), z);
            stroke.add(ball(c, o.radius), c, new Point(0, 1, 0), o, w, 2);
        }
        return stroke;
    }

    @Test
    void overlappingDabsPushAnEvenLayerWithNoBump() {
        var w = ground((x, z) -> 0);
        var o = options(5, 3, false);
        // Two strokes crossing each other, so every block in the middle is under many dabs.
        var stroke = strokeAlongX(w, -6, 6, 0, o);
        for (int z = -6; z <= 6; z++) {
            var c = new Point(0, 0, z);
            stroke.add(ball(c, 5), c, new Point(0, 1, 0), o, w, 2);
        }
        var after = w.after(stroke.plan());
        for (int x = -20; x <= 20; x++)
            for (int z = -20; z <= 20; z++) {
                int t = top(after, x, z);
                assertTrue(t <= 0 && t >= -3, "the ground at " + x + ", " + z + " is at " + t);
                assertEquals("grass", after.blocks.get(new Point(x, t, z)), "grass is still on top at " + x + ", " + z);
            }
        for (int x = -6; x <= 6; x++)
            assertEquals(-3, top(after, x, 0), "the middle of the stroke is pushed in by the strength");
    }

    @Test
    void aStrokeKeepsTheRoughnessOfTheGround() {
        java.util.function.IntBinaryOperator height = (x, z) -> Math.floorMod(x * 7 + z * 13, 3) == 0 ? 1 : 0;
        var w = ground(height);
        var after = w.after(strokeAlongX(w, -8, 8, 0, options(6, 3, false)).plan());
        // Along the middle of the stroke, where every block moves by the full strength.
        for (int x = -6; x <= 6; x++)
            assertEquals(height.applyAsInt(x, 0) - 3, top(after, x, 0), "the relief moved down as it is at x " + x);
    }

    @Test
    void aStrokeOverAHollowBallNeverOpensIt() {
        for (int thickness = 1; thickness <= 2; thickness++) {
            var w = hollow(thickness);
            var stroke = new Deform.Stroke();
            var o = options(6, 3, false);
            // Along the outside of the +x side, each dab on the wall as it was before the stroke.
            for (int y = -6; y <= 6; y++) {
                int x = BOX;
                while (!w.solid(new Point(x, y, 0))) x--;
                var c = new Point(x, y, 0);
                stroke.add(ball(c, 6), c, new Point(1, 0, 0), o, w, 2);
                assertFalse(leaks(w.after(stroke.plan())), "a hole after the dab at " + c + ", thickness " + thickness);
            }
            assertTrue(outerX(w.after(stroke.plan())) <= outerX(w) - 3);
        }
    }

    @Test
    void whatEachDabReportsAddsUpToTheStroke() {
        var w = hollow(2);
        var stroke = new Deform.Stroke();
        var sum = new HashMap<Point, Point>();
        var o = options(5, 3, false);
        for (int y = -4; y <= 4; y += 2) {
            int x = BOX;
            while (!w.solid(new Point(x, y, 0))) x--;
            var c = new Point(x, y, 0);
            for (var e : stroke.add(ball(c, 5), c, new Point(1, 0, 0), o, w, 2).entrySet())
                if (e.getKey().equals(e.getValue())) sum.remove(e.getKey());
                else sum.put(e.getKey(), e.getValue());
        }
        assertEquals(stroke.plan(), sum);
    }
}
