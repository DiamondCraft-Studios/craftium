package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeometryTest {
    @Test void brushesAreSymmetricAndHollowShellsExcludeTheInterior() {
        for (var shape : BrushMath.Shape.values()) {
            var solid = new HashSet<>(BrushMath.brush(new Point(0,0,0),5,shape,false));
            var shell = new HashSet<>(BrushMath.brush(new Point(0,0,0),5,shape,true));
            assertTrue(solid.containsAll(shell)); assertFalse(shell.contains(new Point(0,0,0)));
            solid.forEach(p -> assertTrue(solid.contains(new Point(-p.x(),p.y(),p.z())) && solid.contains(new Point(p.x(),p.y(),-p.z())), shape + " is not mirrored sideways"));
            // The pointed shapes and the hemisphere stand on a base, so they are not the same upside down.
            if (!EnumSet.of(BrushMath.Shape.PYRAMID, BrushMath.Shape.CONE, BrushMath.Shape.HEMISPHERE).contains(shape))
                solid.forEach(p -> assertTrue(solid.contains(new Point(p.x(),-p.y(),p.z())), shape + " is not mirrored vertically"));
        }
    }
    @Test void spheresHaveNoSingleBlockNubsAtTheAxes() {
        for (int r = 2; r <= 24; r++) {
            var sphere = new HashSet<>(BrushMath.brush(new Point(0,0,0),r,BrushMath.Shape.SPHERE,false));
            // The block at the end of an axis must have a neighbour beside it on the surface, so it is part of a cap.
            assertTrue(sphere.contains(new Point(r,0,0)) && sphere.contains(new Point(r,1,0)) && sphere.contains(new Point(r,0,1)), "nub at radius " + r);
            assertFalse(sphere.contains(new Point(r + 1,0,0)));
        }
    }
    @Test void turnedAndScaledShapeMatchesTheCellByCellDefinition() {
        var o = new Options(); o.shape = BrushMath.Shape.SPHERE; o.radius = 6;
        o.yaw = 37; o.pitch = -52; o.roll = 11; o.sizeX = 1.5; o.sizeY = .6; o.sizeZ = 1;
        var made = new HashSet<>(Geometry.shape(new Point(0,0,0), o));
        double reach = (o.radius + .5) / o.radius;
        var expected = new HashSet<Point>();
        int extent = (int) Math.ceil(o.radius * Math.sqrt(o.sizeX*o.sizeX + o.sizeY*o.sizeY + o.sizeZ*o.sizeZ));
        for (int x = -extent; x <= extent; x++) for (int y = -extent; y <= extent; y++) for (int z = -extent; z <= extent; z++) {
            var v = Geometry.inverseRotate(new Geometry.Vector(x, y, z), o.yaw, o.pitch, o.roll);
            double a = v.x() / (o.radius * o.sizeX), b = v.y() / (o.radius * o.sizeY), c = v.z() / (o.radius * o.sizeZ);
            if (a*a + b*b + c*c <= reach*reach) expected.add(new Point(x, y, z));
        }
        assertFalse(made.isEmpty());
        assertEquals(expected, made);
    }
    @Test void everyShapeIsInExactlyOnePickerGroup() {
        var listed = new ArrayList<BrushMath.Shape>();
        BrushMath.GROUPS.values().forEach(listed::addAll);
        assertEquals(BrushMath.Shape.values().length, listed.size());
        assertEquals(EnumSet.allOf(BrushMath.Shape.class), EnumSet.copyOf(listed));
    }
    @Test void pointedAndFlatShapesHaveTheirForm() {
        var pyramid = new HashSet<>(BrushMath.brush(new Point(0,0,0),4,BrushMath.Shape.PYRAMID,false));
        assertTrue(pyramid.contains(new Point(4,-4,4)) && pyramid.contains(new Point(0,4,0)) && !pyramid.contains(new Point(1,4,0)));
        var cone = new HashSet<>(BrushMath.brush(new Point(0,0,0),4,BrushMath.Shape.CONE,false));
        assertTrue(cone.contains(new Point(4,-4,0)) && !cone.contains(new Point(4,-4,4)) && cone.contains(new Point(0,4,0)));
        assertTrue(BrushMath.brush(new Point(0,0,0),6,BrushMath.Shape.PLANE,false).stream().allMatch(p -> p.y() == 0));
        assertEquals(13 * 13, BrushMath.brush(new Point(0,0,0),6,BrushMath.Shape.PLANE,false).size());
        assertTrue(BrushMath.brush(new Point(0,0,0),6,BrushMath.Shape.HEMISPHERE,false).stream().allMatch(p -> p.y() >= 0));
    }
    @Test void polygonIsFlatAndFollowsItsSides() {
        var o = new Point(0,0,0);
        var triangle = BrushMath.brush(o,10,BrushMath.Shape.POLYGON,false,1,false,0,3);
        var square = BrushMath.brush(o,10,BrushMath.Shape.POLYGON,false,1,false,0,4);
        var many = BrushMath.brush(o,10,BrushMath.Shape.POLYGON,false,1,false,0,256);
        assertTrue(triangle.stream().allMatch(p -> p.y() == 0));
        assertTrue(triangle.size() < square.size() && square.size() < many.size());
        assertTrue(many.size() <= BrushMath.brush(o,10,BrushMath.Shape.DISK,false).size());
    }
    @Test void selectionBooleanOperationsAreAtomicAtTheLimit() {
        var s = new Selection();
        var a = new Point(0,0,0); var b = new Point(1,0,0); var c = new Point(2,0,0);
        s.combine(List.of(a,b),Selection.Mode.REPLACE);
        s.combine(List.of(b,c),Selection.Mode.ADD); assertEquals(3,s.size());
        s.combine(List.of(b,c),Selection.Mode.INTERSECT); assertEquals(Set.of(b,c),s.points());
        s.combine(List.of(b),Selection.Mode.SUBTRACT); assertEquals(Set.of(c),s.points());
        var huge = new ArrayList<Point>(); for(int i=0;i<=Selection.LIMIT;i++) huge.add(new Point(i,0,0));
        assertThrows(IllegalArgumentException.class,()->s.combine(huge,Selection.Mode.REPLACE));
        assertEquals(Set.of(c),s.points());
    }
    @Test void boxRejectsOverflowAndIncludesBothCorners() {
        assertEquals(27, Selection.box(new Point(2,2,2),new Point(0,0,0)).size());
        assertThrows(IllegalArgumentException.class,()->Selection.box(new Point(Integer.MIN_VALUE,0,0),new Point(Integer.MAX_VALUE,0,0)));
    }
    @Test void lineHasNoGapsAndIncludesBothEndpoints() {
        Point a=new Point(-4,2,8),b=new Point(11,-7,0);
        var line=BrushMath.line(a,b); assertEquals(a,line.getFirst());assertEquals(b,line.getLast());
        for(int i=1;i<line.size();i++) assertTrue(line.get(i).distanceSquared(line.get(i-1))<=3);
        assertEquals(List.of(a),BrushMath.line(a,a));
    }
    @Test void transformsRoundTrip() {
        Point p=new Point(4,-8,17);
        assertEquals(p,p.rotateY().rotateY().rotateY().rotateY());assertEquals(p,p.mirrorX().mirrorX());
    }
    @Test void noiseIsStableContinuousAndBounded() {
        for(int i=-30;i<30;i++) {
            double a=BrushMath.noise(i*.13,3.25,-1.2,42);
            assertTrue(a>=0 && a<=1);assertEquals(a,BrushMath.noise(i*.13,3.25,-1.2,42));
            assertTrue(Math.abs(a-BrushMath.noise(i*.13+.001,3.25,-1.2,42))<.01);
        }
        assertNotEquals(BrushMath.noise(.3,.4,.5,42),BrushMath.noise(.3,.4,.5,43));
    }
}
