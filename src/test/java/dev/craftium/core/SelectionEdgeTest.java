package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionEdgeTest {
    private static List<Point> disc(int radius){var out=new ArrayList<Point>();for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)if(x*x+z*z<=radius*radius)out.add(new Point(x,5,z));return out;}

    @Test void aFlatDiscGivesItsRimNotTheWholeSheet(){
        var rim=SelectionEdge.outline(disc(6),1);
        assertTrue(rim.size()<disc(6).size()/2);
        assertFalse(rim.contains(new Point(0,5,0)));
        assertTrue(rim.contains(new Point(6,5,0)));
        assertTrue(rim.stream().allMatch(p->p.y()==5));
    }
    @Test void thicknessTakesInMoreRingsAndNeverMoreThanTheShape(){
        var one=SelectionEdge.outline(disc(6),1);var two=SelectionEdge.outline(disc(6),2);
        assertTrue(two.containsAll(one));assertTrue(two.size()>one.size());
        assertEquals(new HashSet<>(disc(6)),new HashSet<>(SelectionEdge.outline(disc(6),50)));
    }
    @Test void aSolidBoxGivesItsShell(){
        var box=Selection.box(new Point(0,0,0),new Point(4,4,4));
        var shell=SelectionEdge.outline(box,1);
        assertEquals(125-27,shell.size());
        assertFalse(shell.contains(new Point(2,2,2)));
    }
    @Test void aWallIsJudgedInItsOwnPlane(){
        var wall=Selection.box(new Point(0,0,3),new Point(4,4,3));
        assertEquals(16,SelectionEdge.outline(wall,1).size());
    }
    @Test void aSingleBlockIsAllEdge(){assertEquals(Set.of(new Point(1,2,3)),SelectionEdge.outline(List.of(new Point(1,2,3)),1));}

    /** A disc lying in the plane x + y = 0 (one block thick on the diagonal), radius r in blocks along z and along the diagonal. */
    private static List<Point> diagonalDisc(int radius){
        var out=new ArrayList<Point>();
        for(int u=-radius;u<=radius;u++)for(int z=-radius;z<=radius;z++)if(u*u*2+z*z<=radius*radius*2)out.add(new Point(u,-u,z));
        return out;
    }
    @Test void aDiagonalSheetGivesItsRimToo(){
        var sheet=diagonalDisc(6);
        var rim=SelectionEdge.outline(sheet,1);
        assertTrue(rim.size()<sheet.size()/2,"rim "+rim.size()+" of "+sheet.size());
        assertFalse(rim.contains(new Point(0,0,0)));
        assertTrue(rim.contains(new Point(0,0,6)));
        assertTrue(SelectionEdge.outline(sheet,2).containsAll(rim));
    }
    @Test void aSlantedSquareKeepsItsInteriorOut(){
        var out=new ArrayList<Point>();
        for(int u=0;u<10;u++)for(int z=0;z<10;z++)out.add(new Point(u,u/2,z)); // slope 1:2, rounded into steps
        var rim=SelectionEdge.outline(out,1);
        assertFalse(rim.contains(new Point(5,2,5)));
        assertTrue(rim.contains(new Point(5,2,0)));
        assertTrue(rim.size()<out.size()/2);
    }
    @Test void pixelPerfectChangesNothingAtThicknessOne(){
        assertEquals(SelectionEdge.outline(disc(10),1),SelectionEdge.outline(disc(10),1,true));
        assertEquals(SelectionEdge.outline(diagonalDisc(8),1),SelectionEdge.outline(diagonalDisc(8),1,true));
    }
    @Test void pixelPerfectMakesAThickRimEvenAndStillFollowsThickness(){
        for(int t=2;t<=4;t++){
            var plain=SelectionEdge.outline(disc(10),t);var perfect=SelectionEdge.outline(disc(10),t,true);
            assertNotEquals(plain,perfect,"thickness "+t);
            assertTrue(perfect.size()>SelectionEdge.outline(disc(10),t-1,true).size(),"grows with thickness "+t);
            assertTrue(perfect.contains(new Point(10,5,0)));assertFalse(perfect.contains(new Point(0,5,0)));
        }
        // On the diagonal a face-only peel is thinner than on the axis; pixel perfect evens that out by making the diagonal part thicker, never thinner.
        assertTrue(SelectionEdge.outline(disc(10),3,true).containsAll(SelectionEdge.outline(disc(10),3)));
    }
    @Test void pixelPerfectOnATiltedSheetAlsoFollowsThickness(){
        var sheet=diagonalDisc(10);
        var one=SelectionEdge.outline(sheet,2,true);var two=SelectionEdge.outline(sheet,3,true);
        assertTrue(two.containsAll(one));assertTrue(two.size()>one.size());assertFalse(two.contains(new Point(0,0,0)));
    }
    @Test void pixelPerfectLeavesAStraightEdgeAlone(){
        var box=Selection.box(new Point(0,0,0),new Point(6,0,6));
        assertEquals(SelectionEdge.outline(box,1),SelectionEdge.outline(box,1,true));
    }
}
