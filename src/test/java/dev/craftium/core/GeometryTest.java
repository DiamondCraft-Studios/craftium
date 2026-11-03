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
