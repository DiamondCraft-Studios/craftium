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
    @Test void aStateWalksBackAndForthWithTheSameEntriesIdsAndGuids(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);String guid=one.guid();
        var s0=saved.state();saved.delete(one.id(),s);var s1=saved.state();var again=saved.save(s);var s2=saved.state(); // "BuildObject" is taken again, by another
        saved.restore(s1,s);assertEquals(List.of(),saved.entries());
        saved.restore(s0,s);assertEquals(List.of(one),saved.entries());assertSame(one,saved.find(guid));
        saved.restore(s2,s);assertEquals(List.of(again),saved.entries());assertNotEquals(again.guid(),guid);
    }
    @Test void restoringPutsBackNamesParentsOrderAndBlocksNotJustWhatExists(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);
        var before=saved.state();
        saved.rename(a.id(),"Roof");saved.place(b.id(),a.id(),0);saved.place(a.id(),0,5);saved.setPoints(Map.of(a.id(),List.of(C)));
        saved.restore(before,s);
        assertEquals(List.of(a,b),saved.entries());assertEquals("BuildObject",a.name());assertEquals(0,b.parentId());assertEquals(List.of(A),a.points());
        assertEquals(saved.state(),before);
    }
    @Test void restoringLetsGoOfABuildObjectThatIsNoLongerThere(){
        var saved=new SavedSelections();var s=live(A);var before=saved.state();var one=saved.save(s);
        s.restore(List.of(C));saved.view(one.id(),s);
        saved.restore(before,s);
        assertNull(saved.active());assertEquals(Set.of(C),set(s),"the player's own selection is back");
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
        var before=saved.state();saved.delete(one.id(),s);
        saved.restore(before,s);
        assertEquals(List.of("BuildObject","BuildObject (1)","BuildObject (2)"),saved.entries().stream().map(SavedSelections.Entry::name).toList());
        assertSame(two,saved.find("buildobject (1)"));
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
        var before=saved.state();saved.delete(one.id(),s);saved.restore(before,s);assertEquals(guid,saved.find(guid).guid());
    }
    @Test void placingReordersTheTopLevelAndKeepsTheRestInOrder(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);var c=saved.save(s);
        saved.place(c.id(),0,0);assertEquals(List.of(c,a,b),saved.entries());
        saved.place(c.id(),0,2);assertEquals(List.of(a,b,c),saved.entries());
        saved.place(a.id(),0,99);assertEquals(List.of(b,c,a),saved.entries(),"past the end is the end");
        saved.place(a.id(),0,-5);assertEquals(List.of(a,b,c),saved.entries(),"before the start is the start");
    }
    @Test void anEntryGoesUnderAnotherAtAnyDepthAndTheListStaysInTreeOrder(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);var c=saved.save(s);var d=saved.save(s);
        saved.place(b.id(),a.id(),0);saved.place(c.id(),b.id(),0);saved.place(d.id(),c.id(),0);
        assertEquals(List.of(a,b,c,d),saved.entries());
        assertEquals(List.of(0,1,2,3),List.of(saved.depth(a),saved.depth(b),saved.depth(c),saved.depth(d)));
        assertEquals(a.id(),b.parentId());assertEquals(c.id(),d.parentId());assertEquals(0,a.parentId());
        assertTrue(saved.isUnder(d,a)&&!saved.isUnder(a,d));
        assertEquals(List.of(b),saved.children(a));
    }
    @Test void movingAParentTakesEverythingUnderItAlong(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);var c=saved.save(s);var e=saved.save(s);
        saved.place(c.id(),b.id(),0);saved.place(e.id(),c.id(),0); // a, b > c > e
        saved.place(b.id(),0,0);assertEquals(List.of(b,c,e,a),saved.entries(),"b, with c and e under it, goes before a");
        saved.place(b.id(),a.id(),0);assertEquals(List.of(a,b,c,e),saved.entries(),"and under a, the three still one run");
        assertEquals(List.of(0,1,2,3),List.of(saved.depth(a),saved.depth(b),saved.depth(c),saved.depth(e)));
    }
    @Test void childrenKeepTheirOrderAmongThemselves(){
        var saved=new SavedSelections();var s=live(A);var p=saved.save(s);var x=saved.save(s);var y=saved.save(s);var z=saved.save(s);
        saved.place(x.id(),p.id(),0);saved.place(y.id(),p.id(),1);saved.place(z.id(),p.id(),1);
        assertEquals(List.of(x,z,y),saved.children(p),"z went in as the second child");
        assertEquals(List.of(p,x,z,y),saved.entries());
        saved.place(y.id(),p.id(),0);assertEquals(List.of(y,x,z),saved.children(p));
    }
    @Test void anEntryCannotGoUnderItselfOrUnderWhatIsUnderIt(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);
        saved.place(b.id(),a.id(),0);
        assertThrows(IllegalArgumentException.class,()->saved.place(a.id(),a.id(),0));
        assertThrows(IllegalArgumentException.class,()->saved.place(a.id(),b.id(),0));
        assertEquals(List.of(a,b),saved.entries(),"a refused place changes nothing");
    }
    @Test void lookingAtAParentShowsEverythingUnderItAndEditingOnlyItsOwn(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);s.restore(List.of(B));var b=saved.save(s);s.restore(List.of(C));var c=saved.save(s);
        saved.place(b.id(),a.id(),0);saved.place(c.id(),b.id(),0);
        s.restore(List.of(new Point(9,9,9)));
        saved.view(a.id(),s);assertEquals(Set.of(A,B,C),set(s),"the whole subtree");
        saved.release(s);saved.edit(a.id(),s);assertEquals(Set.of(A),set(s),"only its own blocks");
        assertEquals(Set.of(A,B,C),saved.effective(a));assertEquals(Set.of(C),saved.effective(c));
    }
    @Test void deletingAParentDeletesWhatIsUnderItAndRestoringBringsBackTheGroupWhereItWas(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);var c=saved.save(s);var d=saved.save(s);
        saved.place(c.id(),b.id(),0); // a, b > c, d
        var before=saved.state();
        saved.delete(b.id(),s);assertEquals(List.of(a,d),saved.entries(),"c went with b");
        saved.restore(before,s);assertEquals(List.of(a,b,c,d),saved.entries());
        assertEquals(b.id(),c.parentId(),"c is under b again");
    }
    @Test void deletingWhatIsBeingLookedAtInsideAGroupGivesTheSelectionBack(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);s.restore(List.of(B));var b=saved.save(s);saved.place(b.id(),a.id(),0);
        s.restore(List.of(C));saved.view(b.id(),s);saved.delete(a.id(),s);
        assertNull(saved.active());assertEquals(Set.of(C),set(s));
    }
    @Test void aSnapshotOfTheBlocksCanBePutBackAndOnlyWhatChangedCountsAsChanged(){
        var saved=new SavedSelections();var s=live(A);var one=saved.save(s);s.restore(List.of(B));var two=saved.save(s);
        var before=saved.snapshot();long revision=saved.revision();int version=two.version();
        assertFalse(saved.setPoints(before),"the same blocks change nothing");assertEquals(revision,saved.revision());
        assertTrue(saved.setPoints(Map.of(two.id(),List.of(C))));
        assertEquals(List.of(C),two.points());assertEquals(List.of(A),one.points());assertEquals(version+1,two.version());
        assertTrue(saved.setPoints(before));assertEquals(List.of(B),two.points());
        assertFalse(saved.setPoints(Map.of(999,List.of(C))),"an id that is gone is skipped");
    }
    private static final Point D=new Point(3,0,0),E=new Point(4,0,0);
    @Test void aSelectionThatTouchesNothingMakesAnObjectAtTheTopLevel(){
        var saved=new SavedSelections();var one=saved.create(live(A));var two=saved.create(live(B));
        assertEquals(List.of(one,two),saved.entries());assertEquals(0,two.parentId());assertEquals(List.of(B),two.points());
    }
    @Test void aSelectionThatTouchesAnObjectTakesItInWholeAsAChild(){
        var saved=new SavedSelections();var one=saved.create(live(A,B));
        var around=saved.create(live(B,C)); // reaches only half of one
        assertEquals(List.of(around,one),saved.entries(),"the new one takes the place of the one it took");
        assertEquals(around.id(),one.parentId());assertEquals(List.of(A,B),one.points(),"the one it took is left whole");
        assertEquals(List.of(C),around.points(),"and the new one holds only what no taken object holds");
    }
    @Test void aSelectionInsideAnObjectMakesAnEmptyParentAroundIt(){
        var saved=new SavedSelections();var one=saved.create(live(A,B));var around=saved.create(live(A,B));
        assertEquals(List.of(),around.points());assertEquals(around.id(),one.parentId());
        assertEquals(Set.of(A,B),saved.effective(around));
    }
    @Test void severalObjectsTouchedAreAllTakenInTheOrderTheyWereIn(){
        var saved=new SavedSelections();var a=saved.create(live(A));var b=saved.create(live(B));var c=saved.create(live(C));
        var around=saved.create(live(A,C,D));
        assertEquals(List.of(around,a,c,b),saved.entries(),"the new one first, with a and c under it, then b");
        assertEquals(List.of(a,c),saved.children(around),"a and c, and b was not touched");
        assertEquals(0,b.parentId());assertEquals(List.of(D),around.points());
    }
    @Test void anObjectUnderAnotherThatIsAlsoTouchedComesInWithItAndNotByItself(){
        var saved=new SavedSelections();var parent=saved.create(live(A));var child=saved.create(live(B));saved.place(child.id(),parent.id(),0);
        var around=saved.create(live(A,B));
        assertEquals(List.of(parent),saved.children(around),"only the outer one is a child of the new one");
        assertEquals(parent.id(),child.parentId(),"and the inner one is still under it");
    }
    @Test void touchingAnObjectInsideAParentPutsTheNewOneThereAndNotAtTheTop(){
        var saved=new SavedSelections();var parent=saved.create(live(A));var one=saved.create(live(B));var two=saved.create(live(C));
        saved.place(one.id(),parent.id(),0);saved.place(two.id(),parent.id(),1);
        var around=saved.create(live(B,D));
        assertEquals(List.of(around,two),saved.children(parent),"it sits where the one it took was");
        assertEquals(List.of(one),saved.children(around));
    }
    @Test void creatingStillHonoursTheLimit(){
        var saved=new SavedSelections();for(int i=0;i<SavedSelections.MAX;i++)saved.save(live(new Point(i+10,0,0)));
        assertThrows(IllegalArgumentException.class,()->saved.create(live(E)));
    }
    @Test void severalEntriesCanBeShownTogetherAsOneSelection(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);s.restore(List.of(B));var b=saved.save(s);s.restore(List.of(C));var c=saved.save(s);
        s.restore(List.of(new Point(9,9,9)));
        saved.viewSet(List.of(a.id(),c.id()),s);
        assertEquals(Set.of(A,C),set(s));assertEquals(List.of(a,c),saved.viewed());assertTrue(saved.isViewed(a)&&!saved.isViewed(b));
        assertFalse(saved.editing(),"only one can be edited");
        saved.viewSet(List.of(a.id(),b.id(),c.id()),s);assertEquals(Set.of(A,B,C),set(s));
        saved.viewSet(List.of(),s);assertNull(saved.active());assertTrue(saved.viewed().isEmpty());assertEquals(Set.of(new Point(9,9,9)),set(s),"the player's own selection is back");
    }
    @Test void showingSeveralTakesEachOnesWholeSubtree(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);s.restore(List.of(B));var b=saved.save(s);s.restore(List.of(C));var c=saved.save(s);
        saved.place(b.id(),a.id(),0);
        saved.viewSet(List.of(a.id(),c.id()),s);assertEquals(Set.of(A,B,C),set(s));
    }
    @Test void deletingOneOfTheShownLetsGoOfAll(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);s.restore(List.of(B));var b=saved.save(s);s.restore(List.of(C));
        saved.viewSet(List.of(a.id(),b.id()),s);saved.delete(b.id(),s);
        assertTrue(saved.viewed().isEmpty());assertEquals(Set.of(C),set(s));
    }
    @Test void aSingleViewIsStillOneViewedAndEditable(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);
        saved.view(a.id(),s);assertEquals(List.of(a),saved.viewed());saved.edit(a.id(),s);assertTrue(saved.editing());
        saved.viewSet(List.of(a.id()),s);assertFalse(saved.editing(),"showing it as a set is looking, not editing");
    }
    @Test void groupingPutsTheListedOnesInAnEmptyParentInTheFirstOnesPlace(){
        var saved=new SavedSelections();var a=saved.save(live(A));var b=saved.save(live(B));var c=saved.save(live(C));
        var group=saved.group(List.of(c.id(),a.id()));
        assertEquals(List.of(group,a,c,b),saved.entries(),"the group sits where the first of them was, with them in tree order");
        assertEquals(List.of(),group.points());assertEquals(List.of(a,c),saved.children(group));assertEquals(0,b.parentId());
    }
    @Test void groupingAParentAndItsChildKeepsTheChildUnderIt(){
        var saved=new SavedSelections();var a=saved.save(live(A));var b=saved.save(live(B));saved.place(b.id(),a.id(),0);
        var group=saved.group(List.of(a.id(),b.id()));
        assertEquals(List.of(a),saved.children(group));assertEquals(a.id(),b.parentId());
    }
    @Test void groupingOneInsideAParentGroupsItThere(){
        var saved=new SavedSelections();var p=saved.save(live(A));var x=saved.save(live(B));var y=saved.save(live(C));saved.place(x.id(),p.id(),0);saved.place(y.id(),p.id(),1);
        var group=saved.group(List.of(x.id()));
        assertEquals(List.of(group,y),saved.children(p));assertEquals(List.of(x),saved.children(group));
    }
    @Test void groupingNothingIsRefused(){assertThrows(IllegalArgumentException.class,()->new SavedSelections().group(List.of()));}
    @Test void aChildMadeFromTheSelectionTakesNothingInAndIsTheLast(){
        var saved=new SavedSelections();var p=saved.save(live(A));var x=saved.save(live(B));saved.place(x.id(),p.id(),0);
        var child=saved.createChild(p.id(),live(B,C));
        assertEquals(List.of(x,child),saved.children(p));assertEquals(List.of(B,C),child.points(),"it holds the whole selection, taking nothing in");assertEquals(0,saved.children(x).size());
    }
    @Test void deletingSeveralSkipsOnesThatWentWithAnother(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);var c=saved.save(s);saved.place(b.id(),a.id(),0);
        saved.deleteMany(List.of(a.id(),b.id(),c.id()),s);
        assertTrue(saved.entries().isEmpty());
    }
    @Test void anObjectMadeFromPastedBlocksGoesRightAfterTheOneItCameFrom(){
        var saved=new SavedSelections();var a=saved.save(live(A));var b=saved.save(live(B));
        var copy=saved.createFrom("Tower (copy)",List.of(C,D),a.id());
        assertEquals(List.of(a,copy,b),saved.entries());assertEquals(List.of(C,D),copy.points());assertEquals("Tower (copy)",copy.name());
    }
    @Test void aCopyOfAChildGoesAfterItUnderTheSameParent(){
        var saved=new SavedSelections();var p=saved.save(live(A));var x=saved.save(live(B));saved.place(x.id(),p.id(),0);
        var copy=saved.createFrom("x (copy)",List.of(C),x.id());
        assertEquals(List.of(x,copy),saved.children(p));
    }
    @Test void aPastedObjectWithNoPlaceOrAGonePlaceIsAtTheEndOfTheTopLevel(){
        var saved=new SavedSelections();var a=saved.save(live(A));var b=saved.save(live(B));
        assertEquals(List.of(a,b),saved.entries().subList(0,2));
        assertSame(saved.createFrom("",List.of(C),0),saved.entries().getLast(),"an empty name is the next default");
        assertEquals("BuildObject (2)",saved.entries().getLast().name());
        assertSame(saved.createFrom("x",List.of(D),999),saved.entries().getLast());
    }
    @Test void aLongNameIsCutAndNothingPastedIsRefused(){
        var saved=new SavedSelections();
        assertEquals(40,saved.createFrom("x".repeat(60),List.of(A),0).name().length());
        assertThrows(IllegalArgumentException.class,()->saved.createFrom("x",List.of(),0));
    }
    @Test void twoPlayersShareOneTreeAndEachLooksAtTheirOwn(){
        var tree=new SavedSelections.Tree();var alex=new SavedSelections(tree);var sam=new SavedSelections(tree);
        var a=alex.save(live(A));var b=alex.save(live(B));
        assertEquals(List.of(a,b),sam.entries(),"what one makes the other sees");assertSame(a,sam.byId(a.id()));
        var samLive=live(C);sam.view(a.id(),samLive);
        assertTrue(sam.isViewed(a));assertFalse(alex.isViewed(a),"what is looked at is each player's own");assertEquals(Set.of(A),set(samLive));
        sam.rename(b.id(),"Roof");assertEquals("Roof",b.name());
        assertEquals(3,new SavedSelections(tree).create(live(D)).id(),"ids go on from one counter");
    }
    @Test void aChangeToTheTreeBumpsTheRevisionOfEveryViewAndOnlyThatCountsAsSomethingToWrite(){
        var tree=new SavedSelections.Tree();var alex=new SavedSelections(tree);var sam=new SavedSelections(tree);
        var a=alex.save(live(A));long revision=sam.revision(),written=tree.mutations();
        var l=live(C);sam.view(a.id(),l);
        assertTrue(sam.revision()>revision,"looking changes what is sent");assertEquals(written,tree.mutations(),"but it is nothing to write to disk");
        alex.rename(a.id(),"Roof");assertTrue(tree.mutations()>written);
    }
    @Test void whenSomeoneDeletesWhatYouLookAtYouLetGoOfIt(){
        var tree=new SavedSelections.Tree();var alex=new SavedSelections(tree);var sam=new SavedSelections(tree);
        var a=alex.save(live(A));var samLive=live(C);sam.view(a.id(),samLive);
        alex.delete(a.id(),live(D));
        sam.reconcileView(samLive);
        assertTrue(sam.viewed().isEmpty());assertEquals(Set.of(C),set(samLive),"their own selection is back");
    }
    @Test void aTreeComesBackFromWhatWasSavedWithIdsGuidsParentsColoursAndBlocks(){
        var saved=new SavedSelections();var a=saved.save(live(A,B));var b=saved.save(live(C));saved.place(b.id(),a.id(),0);saved.setColor(b.id(),0xff112233);saved.rename(a.id(),"Roof");
        var back=new SavedSelections(SavedSelections.Tree.load(saved.tree().nextId(),saved.tree().saved()));
        assertEquals(2,back.entries().size());
        var a2=back.byId(a.id());var b2=back.byId(b.id());
        assertEquals(a.guid(),a2.guid());assertEquals("Roof",a2.name());assertEquals(List.of(A,B),a2.points());
        assertSame(a2,b2.parent());assertEquals(0xff112233,b2.color());
        assertEquals(saved.tree().nextId(),back.tree().nextId(),"new ids do not reuse old ones");
    }
    @Test void aDamagedTreeStillLoadsAsATreeInOrder(){
        var rows=List.of(
            new SavedSelections.Saved(1,"g1","A",9,0,List.of(A)),         // a parent that is not there: the top level
            new SavedSelections.Saved(2,"g2","B",1,0,List.of(B)),
            new SavedSelections.Saved(2,"g3","dup id",0,0,List.of(C)),    // an id that repeats
            new SavedSelections.Saved(3,"g1","dup guid",0,0,List.of(C)),  // a GUID that repeats
            new SavedSelections.Saved(4,"g4","",3,0,List.of(D)));        // a parent that comes later or is gone, and no name
        var tree=SavedSelections.Tree.load(1,rows);var saved=new SavedSelections(tree);
        assertEquals(List.of(1,2,4),saved.entries().stream().map(SavedSelections.Entry::id).toList());
        assertEquals(0,saved.byId(1).parentId());assertEquals(1,saved.byId(2).parentId());assertEquals(0,saved.byId(4).parentId());assertEquals("BuildObject",saved.byId(4).name());
        assertTrue(tree.nextId()>4);
    }
    @Test void structureIgnoresBlocksButNotNamesParentsOrColours(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var b=saved.save(s);var state=saved.state();
        saved.setPoints(Map.of(a.id(),List.of(C)));assertTrue(saved.structureMatches(state),"the blocks a BuildObject holds are not its structure");
        saved.rename(a.id(),"Roof");assertFalse(saved.structureMatches(state));
        saved.restore(state,s);assertTrue(saved.structureMatches(state));
        saved.setColor(b.id(),0xff000001);assertFalse(saved.structureMatches(state));
        saved.restore(state,s);saved.place(b.id(),a.id(),0);assertFalse(saved.structureMatches(state));
    }
    @Test void aColourIsPartOfWhatHistoryGivesBack(){
        var saved=new SavedSelections();var s=live(A);var a=saved.save(s);var before=saved.state();
        saved.setColor(a.id(),0xff123456);assertEquals(0xff123456,a.color());
        saved.restore(before,s);assertEquals(0,a.color());
    }
    @Test void severalEntriesAreMovedTogetherInTheirOrderIntoOnePlace(){
        var saved=new SavedSelections();var a=saved.save(live(A));var b=saved.save(live(B));var c=saved.save(live(C));var d=saved.save(live(D));
        saved.placeMany(List.of(d.id(),b.id()),0,1); // b and d, which sit apart, go between a and c; the place is counted without them
        assertEquals(List.of(a,b,d,c),saved.entries(),"in the order they were in, not the order they were named");
    }
    @Test void severalGoUnderAParentTakingTheirSubtreesAndSkippingOnesInsideAnother(){
        var saved=new SavedSelections();var p=saved.save(live(A));var x=saved.save(live(B));var y=saved.save(live(C));var z=saved.save(live(D));
        saved.place(z.id(),y.id(),0); // p, x, y > z
        saved.placeMany(List.of(x.id(),y.id(),z.id()),p.id(),0);
        assertEquals(List.of(x,y),saved.children(p),"z is under y, so it is not placed on its own");
        assertEquals(y.id(),z.parentId());assertEquals(List.of(p,x,y,z),saved.entries());
    }
    @Test void severalCannotGoInsideThemselves(){
        var saved=new SavedSelections();var a=saved.save(live(A));var b=saved.save(live(B));saved.place(b.id(),a.id(),0);
        assertThrows(IllegalArgumentException.class,()->saved.placeMany(List.of(a.id()),b.id(),0));
        assertThrows(IllegalArgumentException.class,()->saved.placeMany(List.of(),0,0));
        assertEquals(List.of(a,b),saved.entries(),"a refused move changes nothing");
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
