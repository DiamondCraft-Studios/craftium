package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AdvancedGeometryTest {
    @org.junit.jupiter.api.Test
    void rotatedCuboidIncludesRotatedCorners() {
        var o = new Options();
        o.shapeKind = Options.ShapeKind.CUBOID;
        o.radius = 8;
        o.yaw = 45;
        org.junit.jupiter.api.Assertions.assertTrue(
                Geometry.shape(new Point(0, 0, 0), o).contains(new Point(0, 8, 11)));
    }

    @Test
    void rotationAndInversePreserveCoordinates() {
        var p = new Geometry.Vector(5, -2, 8);
        var q = Geometry.inverseRotate(Geometry.rotate(p, 31, -57, 19), 31, -57, 19);
        assertEquals(p.x(), q.x(), 1e-9);
        assertEquals(p.y(), q.y(), 1e-9);
        assertEquals(p.z(), q.z(), 1e-9);
    }

    @Test
    void allShapeFamiliesProduceBoundedVolumes() {
        var o = new Options();
        o.radius = 4;
        for (var kind : Options.ShapeKind.values()) {
            o.shapeKind = kind;
            var points = Geometry.shape(new Point(0, 0, 0), o);
            assertFalse(points.isEmpty(), kind.toString());
            assertTrue(points.size() <= Selection.LIMIT);
            assertTrue(points.stream()
                    .allMatch(p -> Math.max(Math.abs(p.x()), Math.max(Math.abs(p.y()), Math.abs(p.z()))) <= 4));
        }
    }

    @Test
    void curvesIncludeEndpointsAndStayConnected() {
        var nodes = List.of(new Point(0, 0, 0), new Point(8, 6, 0), new Point(16, 0, 0));
        var o = new Options();
        for (var mode : Options.PathMode.values()) {
            o.pathMode = mode;
            var path = Geometry.path(nodes, o);
            assertEquals(nodes.getFirst(), path.getFirst());
            assertEquals(nodes.getLast(), path.getLast());
            for (int i = 1; i < path.size(); i++)
                assertTrue(path.get(i).distanceSquared(path.get(i - 1)) <= 3, mode.toString());
        }
    }

    @Test
    void convexHullIncludesTetrahedronVerticesAndExcludesOutside() {
        var nodes = List.of(new Point(0, 0, 0), new Point(4, 0, 0), new Point(0, 4, 0), new Point(0, 0, 4));
        var result = Geometry.model(nodes, new Options());
        assertTrue(result.containsAll(nodes));
        assertTrue(result.contains(new Point(1, 1, 1)));
        assertFalse(result.contains(new Point(4, 4, 4)));
    }

    @Test
    void allNoiseFamiliesAreDeterministicAndBounded() {
        var o = new Options();
        o.octaves = 3;
        for (var type : Options.Noise.values()) {
            o.noise = type;
            var a = new NoiseField(o);
            var b = new NoiseField(o);
            for (int i = -10; i < 10; i++) {
                Point p = new Point(i * 3, -i, 17);
                double value = a.sample(p);
                assertTrue(value >= 0 && value <= 1);
                assertEquals(value, b.sample(p));
            }
        }
    }

    @Test
    void masksComposeSpatialAndBooleanRules() {
        MaskExpression.Context c = new MaskExpression.Context() {
            public boolean block(Point p, String state) {
                return state.equals(p.y() == 0 ? "stone" : "air");
            }

            public boolean selection(Point p) {
                return p.x() >= 0;
            }

            public boolean sky(Point p) {
                return p.y() > 0;
            }

            public boolean surface(Point p) {
                return p.y() == 0;
            }

            public double angle(Point p) {
                return 90;
            }
        };
        var mask = MaskExpression.compile("all(block(stone), above(air), y(-2,2), not(selection))", c);
        assertTrue(mask.test(new Point(-1, 0, 0)));
        assertFalse(mask.test(new Point(1, 0, 0)));
        assertTrue(MaskExpression.compile("offset(0,-1,0,block(stone))", c).test(new Point(0, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> MaskExpression.compile("all(block(stone)", c));
    }
}
