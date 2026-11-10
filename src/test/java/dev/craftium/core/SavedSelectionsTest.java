package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SavedSelectionsTest {
    private static Selection live(Point... points){var s=new Selection();s.combine(List.of(points),Selection.Mode.REPLACE);return s;}
    private static Set<Point> set(Selection s){return new HashSet<>(s.points());}
    private static final Point A=new Point(0,0,0),B=new Point(1,0,0),C=new Point(2,0,0);

    @Test void savingNamesThemInOrderAndLeavesTheLiveSelectionAlone(){
        var saved=new SavedSelections();var s=live(A,B);
        assertEquals("BuildObject",saved.save(s).name());assertEquals("BuildObject (1)",saved.save(s).name());
        assertEquals(Set.of(A,B),set(s));assertNull(saved.active());
    }
    @Test void aNumberFreedAtTheEndIsUsedAgainButAGapIsNotFilled(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);var two=saved.save(s);saved.save(s);
        saved.delete(two.id(),s);assertEquals("BuildObject (3)",saved.save(s).name(),"the gap at 2 stays, the count goes on from the highest");
        var last=saved.entries().getLast();saved.delete(last.id(),s);assertEquals("BuildObject (3)",saved.save(s).name(),"the freed end comes round again");
    }
    @Test void deletingEverythingStartsTheCountAgainAtOne(){
        var saved=new SavedSelections();var s=live(A);saved.save(s);saved.save(s);
        for(var e:List.copyOf(saved.entries()))saved.delete(e.id(),s);
        assertEquals("BuildObject",saved.save(s).name());
    }
    @Test void aRenamedEntryDoesNotCountTowardsTheNumbers(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);var two=saved.save(s);
        saved.rename(two.id(),"Roof");assertEquals("BuildObject (1)",saved.save(s).name());
    }
    @Test void anEntryBroughtBackWhoseNameWasTakenKeepsItAndTheGuidsTellThemApart(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);saved.delete(one.id(),s);
        var again=saved.save(s); // takes "BuildObject" again
        assertSame(one,saved.undoDelete(0));
        assertEquals(List.of("BuildObject","BuildObject"),saved.entries().stream().map(SavedSelections.Entry::name).toList());
        assertNotEquals(one.guid(),again.guid());
        assertSame(one,saved.find(one.guid()));
    }
    @Test void savingNothingIsRefused(){assertThrows(IllegalArgumentException.class,()->new SavedSelections().save(new Selection()));}
    @Test void viewingSwapsAndReleasingBringsTheOriginalBack(){
        var saved=new SavedSelections();var s=live(A,B);var one=saved.save(s);s.combine(List.of(C),Selection.Mode.REPLACE);
        saved.view(one.id(),s);assertEquals(Set.of(A,B),set(s));assertSame(one,saved.active());assertFalse(saved.editing());
        saved.release(s);assertEquals(Set.of(C),set(s));assertNull(saved.active());
    }
    @Test void viewingAnotherKeepsTheFirstOriginalNotTheOneJustSeen(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);s.combine(List.of(B),Selection.Mode.REPLACE);var two=saved.save(s);
        s.combine(List.of(C),Selection.Mode.REPLACE);
        saved.view(one.id(),s);saved.view(two.id(),s);assertEquals(Set.of(B),set(s));
        saved.release(s);assertEquals(Set.of(C),set(s));
    }
    @Test void changesWhileOnlyViewingNeverReachTheEntry(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);
        saved.view(one.id(),s);s.combine(List.of(B),Selection.Mode.ADD);assertFalse(saved.sync(s));
        assertEquals(List.of(A),one.points());saved.release(s);
        assertEquals(List.of(A),one.points());
    }
    @Test void editingWritesChangesBackAndReleaseRestoresTheOriginal(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);s.combine(List.of(C),Selection.Mode.REPLACE);
        saved.edit(one.id(),s);s.combine(List.of(B),Selection.Mode.ADD);assertTrue(saved.sync(s));
        assertEquals(Set.of(A,B),new HashSet<>(one.points()));
        s.combine(List.of(A),Selection.Mode.SUBTRACT);saved.sync(s);assertEquals(List.of(B),one.points());
        saved.release(s);assertEquals(Set.of(C),set(s));assertEquals(List.of(B),one.points());
    }
    @Test void clearingTheSelectionWhileEditingDoesNotEmptyTheEntry(){
        var saved=new SavedSelections();var s=live(A,B);var one=saved.save(s);saved.edit(one.id(),s);
        s.clear();assertFalse(saved.sync(s));assertEquals(2,one.size());
    }
    @Test void aSavedEntryIsNotChangedByLaterEditsToTheLiveSelectionItWasSavedFrom(){
        var saved=new SavedSelections();var s=live(A,B);var one=saved.save(s);s.combine(List.of(C),Selection.Mode.ADD);
        assertEquals(2,one.size());
    }
    @Test void deletingTheEntryBeingLookedAtGivesTheOriginalBackFirst(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);s.combine(List.of(C),Selection.Mode.REPLACE);
        saved.edit(one.id(),s);saved.delete(one.id(),s);
        assertEquals(Set.of(C),set(s));assertNull(saved.active());assertTrue(saved.entries().isEmpty());
    }
    @Test void aDeletedEntryComesBackWhereItWas(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);var two=saved.save(s);saved.save(s);
        int at=saved.indexOf(one);saved.delete(one.id(),s);saved.restore(one,at);
        assertEquals(List.of("BuildObject","BuildObject (1)","BuildObject (2)"),saved.entries().stream().map(SavedSelections.Entry::name).toList());
        assertSame(two,saved.find("buildobject (1)"));
    }
    @Test void undoBringsBackTheLastDeleteOnlyWhenItIsNewerThanTheLastEdit(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);saved.save(s);
        saved.delete(one.id(),s);
        assertNull(saved.undoDelete(System.currentTimeMillis()+1000),"an edit made after the delete is undone first");
        assertSame(one,saved.undoDelete(0));assertEquals("BuildObject",saved.entries().getFirst().name());
        assertNull(saved.undoDelete(0),"nothing left to bring back");
    }
    @Test void renamingMayRepeatANameButNeverEmptiesIt(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);var two=saved.save(s);
        saved.rename(one.id(),"Roof");assertSame(one,saved.find("roof"));
        saved.rename(two.id(),"ROOF");assertEquals("ROOF",two.name(),"two may share a name");
        assertThrows(IllegalArgumentException.class,()->saved.rename(two.id(),"  "));
    }
    @Test void everyEntryHasItsOwnGuidThatFindsItWhateverItIsCalled(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);var two=saved.save(s);
        saved.rename(one.id(),"Roof");saved.rename(two.id(),"Roof");
        assertNotEquals(one.guid(),two.guid());assertEquals(36,one.guid().length());
        assertSame(two,saved.find(two.guid().toUpperCase()),"a GUID finds it, in any case");
        var error=assertThrows(IllegalArgumentException.class,()->saved.find("roof"));
        assertTrue(error.getMessage().contains(one.guid())&&error.getMessage().contains(two.guid()),"a name two share says which GUIDs there are: "+error.getMessage());
        assertNull(saved.find("Walls"));
    }
    @Test void anEntryKeepsItsGuidAcrossDeletingAndUndoing(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);String guid=one.guid();saved.save(s);
        saved.delete(one.id(),s);assertSame(one,saved.undoDelete(0));assertEquals(guid,saved.find(guid).guid());
    }
    @Test void movingAnEntryReordersTheOthersAroundIt(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);var c=saved.save(s);
        saved.move(c.id(),0);assertEquals(List.of(c,a,b),saved.entries());
        saved.move(c.id(),2);assertEquals(List.of(a,b,c),saved.entries());
        saved.move(a.id(),99);assertEquals(List.of(b,c,a),saved.entries(),"past the end is the end");
        saved.move(a.id(),-5);assertEquals(List.of(a,b,c),saved.entries(),"before the start is the start");
        long revision=saved.revision();saved.move(b.id(),1);assertEquals(revision,saved.revision(),"staying put changes nothing");
    }
    @Test void theLimitIsEnforced(){
        var saved=new SavedSelections();var s=live(A);for(int i=0;i<SavedSelections.MAX;i++)saved.save(s);
        assertThrows(IllegalArgumentException.class,()->saved.save(s));
    }

    @Test void theFirstColourIsTheBaseAndLaterOnesTurnTheHueKeepingTheRest(){
        int base=0xff2389c6;assertEquals(base,SavedSelectionColors.forIndex(base,0));
        var seen=new HashSet<Integer>();
        for(int i=0;i<12;i++){int c=SavedSelectionColors.forIndex(base,i);assertEquals(0xff,c>>>24);assertTrue(seen.add(c),"colour repeated at "+i);
            assertEquals(Math.max(base>>16&255,Math.max(base>>8&255,base&255)),Math.max(c>>16&255,Math.max(c>>8&255,c&255)),2);}
    }
    @Test void aGreyBaseStaysGrey(){assertEquals(0xff808080,SavedSelectionColors.forIndex(0xff808080,5));}
}
