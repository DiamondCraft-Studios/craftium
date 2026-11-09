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
        assertEquals("Selection 1",saved.save(s).name());assertEquals("Selection 2",saved.save(s).name());
        assertEquals(Set.of(A,B),set(s));assertNull(saved.active());
    }
    @Test void aNameIsNotGivenAgainAfterADelete(){
        var saved=new SavedSelections();var s=live(A);saved.save(s);var two=saved.save(s);saved.delete(two.id(),s);
        assertEquals("Selection 3",saved.save(s).name());
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
        assertEquals(List.of("Selection 1","Selection 2","Selection 3"),saved.entries().stream().map(SavedSelections.Entry::name).toList());
        assertSame(two,saved.byName("selection 2"));
    }
    @Test void renamingKeepsNamesUniqueAndNonEmpty(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);var two=saved.save(s);
        saved.rename(one.id(),"Roof");assertSame(one,saved.byName("roof"));
        assertThrows(IllegalArgumentException.class,()->saved.rename(two.id(),"ROOF"));
        assertThrows(IllegalArgumentException.class,()->saved.rename(two.id(),"  "));
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
