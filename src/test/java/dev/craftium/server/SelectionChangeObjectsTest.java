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
    @Test void whatAnEditDidAfterTheRemapIsStackedOnItKeepingWhereTheObjectBegan(){
        // a transform moved object 1 from A to B, then the blocks that became air took B out of it: one change from A to nothing
        var moved=new Session.SelectionChange(null,null,null,null,java.util.Map.of(),java.util.Map.of()).withObjects(Map.of(1,List.of(A)),Map.of(1,List.of(B)));
        var both=moved.andObjects(Map.of(1,List.of(B),2,List.of(C)),Map.of(1,List.of(),2,List.of()));
        assertEquals(List.of(A),both.objectsBefore().get(1),"where it began, before the move");
        assertEquals(List.of(),both.objectsAfter().get(1),"where it ended, after the prune");
        assertEquals(List.of(C),both.objectsBefore().get(2),"an object only the prune touched begins where the prune found it");
    }
    @Test void aPruneAloneRecordsBeforeAndAfter(){
        var change=Session.SelectionChange.restoring(Map.of(),Map.of()).andObjects(Map.of(1,List.of(A,B)),Map.of(1,List.of(B)));
        assertTrue(change.hasObjects());assertEquals(List.of(A,B),change.objectsBefore().get(1));assertEquals(List.of(B),change.objectsAfter().get(1));
        assertFalse(change.hasPoints(),"the player's own selection is left as it is");
    }
}
