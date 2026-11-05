package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScreenSilhouetteTest {
    private static double length(List<ScreenSilhouette.Line> lines){
        double sum=0;for(var l:lines)sum+=Math.sqrt(Math.pow(l.x1()-l.x0(),2)+Math.pow(l.y1()-l.y0(),2)+Math.pow(l.z1()-l.z0(),2));return sum;
    }
    @Test void cubeSeenFromACornerIsAHexagon(){
        var lines=ScreenSilhouette.of(SelectionOutline.of(Set.of(new Point(0,0,0))),5,6,7);
        assertEquals(6,lines.size());assertEquals(6,length(lines),1e-6); // the six edges that do not touch the near corner or the far one
    }
    @Test void aCubeFromAFaceShowsItsFourEdges(){
        var lines=ScreenSilhouette.of(SelectionOutline.of(Set.of(new Point(0,0,0))),.5,.5,9);
        assertEquals(4,length(lines),1e-6);
    }
    @Test void anInnerCreaseOfAConcaveShapeIsLeftOut(){
        var l=new HashSet<Point>();for(int i=0;i<3;i++){l.add(new Point(i,0,0));l.add(new Point(0,0,i));}
        var outline=SelectionOutline.of(l);
        var contour=ScreenSilhouette.of(outline,20,30,20);
        assertNotNull(contour);
        // Seen from above and the corner side, the L's inside corner is a crease between faces that both face the eye.
        assertTrue(length(contour)<outline.edges().stream().mapToDouble(s->Math.abs(s.x1()-s.x0())+Math.abs(s.y1()-s.y0())+Math.abs(s.z1()-s.z0())).sum());
    }
    @Test void insideTheShapeThereIsNoContour(){
        assertNull(ScreenSilhouette.of(SelectionOutline.of(Set.of(new Point(0,0,0))),.5,.5,.5));
    }
    @Test void aLongRowSeenFromNearItsEndStillHasAContour(){
        var row=new HashSet<Point>();for(int i=0;i<20;i++)row.add(new Point(i,0,0));
        // Part of the row lies behind the plane through this eye that faces the row's middle.
        var lines=ScreenSilhouette.of(SelectionOutline.of(row),.5,.5,2);
        assertNotNull(lines);assertFalse(lines.isEmpty());
    }
}
