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
    @Test void aHierarchyStepIsCountedInTheSizeOfTheHistory(){
        var saved=new dev.craftium.core.SavedSelections();var sel=new dev.craftium.core.Selection();sel.combine(List.of(A),dev.craftium.core.Selection.Mode.REPLACE);
        saved.save(sel);saved.save(sel);var before=saved.state();saved.rename(saved.entries().getFirst().id(),"Roof");
        var edit=Session.Edit.selecting("Rename BuildObject",Session.SelectionChange.hierarchy(before,saved.state()));
        assertTrue(edit.hierarchyOnly());assertFalse(edit.selectionOnly(),"a Hierarchy step is its own kind, labelled by name alone");
        assertTrue(edit.estimatedBytes()>=4*96,"two snapshots of two rows each: "+edit.estimatedBytes());
    }
    private static dev.craftium.core.SavedSelections.Held held(Point at,String state){var d=new dev.craftium.core.PackedCells();d.put(at,state);return new dev.craftium.core.SavedSelections.Held(d,dev.craftium.core.PackedCells.EMPTY);}
    @Test void whatAnObjectKeepsOutsideTheWorldIsKeptOnlyWhereItDiffersAndAnEmptyBeforeIsNone(){
        var none=dev.craftium.core.SavedSelections.Held.NONE;
        var change=Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(Map.of(1,none,2,held(A,"minecraft:dirt")),Map.of(1,held(B,"minecraft:stone"),2,held(A,"minecraft:dirt")));
        assertTrue(change.hasHeld());assertEquals(java.util.Set.of(1),change.heldBefore().keySet(),"object 2 did not change");
        assertTrue(change.heldBefore().get(1).isEmpty());assertEquals("minecraft:stone",change.heldAfter().get(1).dormant().get(B));
        assertFalse(Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(Map.of(1,none),Map.of(1,none)).hasHeld());
    }
    @Test void twoChangesInARunBeginWhereTheFirstBeganAndEndWhereTheLastEnded(){
        var first=Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(Map.of(1,dev.craftium.core.SavedSelections.Held.NONE),Map.of(1,held(A,"minecraft:dirt")));
        var second=Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(Map.of(1,held(A,"minecraft:dirt")),Map.of(1,held(B,"minecraft:stone")));
        var both=first.then(second);
        assertTrue(both.heldBefore().get(1).isEmpty(),"Undo goes back to before the run");
        assertEquals("minecraft:stone",both.heldAfter().get(1).dormant().get(B),"Redo goes forward to the end of it");
    }
    @Test void theOtherWaysOfBuildingAChangeKeepWhatIsHeld(){
        var change=Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(Map.of(1,dev.craftium.core.SavedSelections.Held.NONE),Map.of(1,held(A,"minecraft:dirt")));
        assertTrue(change.withObjects(Map.of(1,List.of(A)),Map.of(1,List.of(B))).hasHeld());
        assertTrue(change.andObjects(Map.of(1,List.of(A)),Map.of(1,List.of(B))).hasHeld());
        assertTrue(change.with(Map.of("a","b"),Map.of("a","c")).hasHeld());
    }
}
