package dev.craftium.server;

import dev.craftium.core.Point;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionChangeObjectsTest {
    private static final Point A=new Point(0,0,0),B=new Point(1,0,0),C=new Point(2,0,0);

    @Test void onlyTheObjectsThatMovedAreKept(){
        var change=new Session.SelectionChange(List.of(A),List.of(B)).withObjects(Map.of(1,List.of(A),2,List.of(C)),Map.of(1,List.of(B),2,List.of(C)));
        assertTrue(change.hasObjects());
        assertEquals(Map.of(1,List.of(A)),change.objectsBefore());assertEquals(Map.of(1,List.of(B)),change.objectsAfter());
    }
    @Test void nothingMovedIsNoChange(){
        assertFalse(new Session.SelectionChange(List.of(A),List.of(B)).withObjects(Map.of(1,List.of(A)),Map.of(1,List.of(A))).hasObjects());
    }
    @Test void twoChangesInARowKeepWhereTheFirstBeganAndWhereTheLastEnded(){
        var first=new Session.SelectionChange(List.of(A),List.of(B)).withObjects(Map.of(1,List.of(A)),Map.of(1,List.of(B)));
        var second=new Session.SelectionChange(List.of(B),List.of(C)).withObjects(Map.of(1,List.of(B),2,List.of(A)),Map.of(1,List.of(C),2,List.of(B)));
        var both=first.then(second);
        assertEquals(List.of(A),both.objectsBefore().get(1));assertEquals(List.of(C),both.objectsAfter().get(1));
        assertEquals(List.of(A),both.objectsBefore().get(2));assertEquals(List.of(B),both.objectsAfter().get(2));
    }
}
