package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The groups of the Hierarchy: flat headings that the BuildObjects at the top level sit under, in an order of their own. */
class SavedGroupsTest {
    private static Selection live(Point... points){var s=new Selection();s.combine(List.of(points),Selection.Mode.REPLACE);return s;}
    private static final Point A=new Point(0,0,0),B=new Point(1,0,0),C=new Point(2,0,0);
    private static List<String> names(SavedSelections saved){return saved.entries().stream().map(SavedSelections.Entry::name).toList();}
    private static List<String> groupNames(SavedSelections saved){return saved.groups().stream().map(SavedSelections.Group::name).toList();}

    @Test void everyHierarchyStartsWithANewGroupAndEverythingIsInIt(){
        var saved=new SavedSelections();
        assertEquals(List.of("New Group"),groupNames(saved));
        var s=live(A);var one=saved.save(s);var two=saved.save(s);
        assertSame(saved.groups().getFirst(),one.group());assertSame(saved.groups().getFirst(),two.group());
    }
    @Test void newGroupsAreNumberedLikeBuildObjectsAndRenamed(){
        var saved=new SavedSelections();
        saved.createGroup("");saved.createGroup("");
        assertEquals(List.of("New Group","New Group (1)","New Group (2)"),groupNames(saved));
        saved.renameGroup(saved.groups().get(1).id(),"Walls");assertEquals("Walls",saved.groups().get(1).name());
        assertThrows(IllegalArgumentException.class,()->saved.renameGroup(saved.groups().get(1).id()," "));
        assertThrows(IllegalArgumentException.class,()->saved.renameGroup(saved.groups().get(1).id(),"x".repeat(41)));
    }
    @Test void aBuildObjectMovesToAnotherGroupAndTheListFollowsTheGroupOrder(){
        var saved=new SavedSelections();var s=live(A);
        var one=saved.save(s);var two=saved.save(s);var three=saved.save(s);
        var second=saved.createGroup("Second");
        saved.placeIn(List.of(one.id()),0,second.id(),0);
        assertEquals(List.of("BuildObject (1)","BuildObject (2)","BuildObject"),names(saved),"the first group's two, then the second's one");
        assertSame(second,one.group());
        saved.moveGroup(second.id(),0);
        assertEquals(List.of("BuildObject","BuildObject (1)","BuildObject (2)"),names(saved));
        assertEquals(List.of("Second","New Group"),groupNames(saved));
    }
    @Test void thePlaceAtTheTopLevelCountsInsideTheGroup(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Second");var c=saved.save(s);var d=saved.save(s);
        saved.placeIn(List.of(c.id(),d.id()),0,second.id(),0);
        saved.placeIn(List.of(a.id()),0,second.id(),1);
        assertEquals(List.of("BuildObject (1)","BuildObject (2)","BuildObject","BuildObject (3)"),names(saved),"b alone first, then the second group: c, a, d");
    }
    @Test void anOldPlaceAtTheTopLevelStillMeansAPlaceAmongAllOfThem(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Second");var c=saved.save(s);
        saved.placeIn(List.of(c.id()),0,second.id(),0);
        saved.place(a.id(),0,2);
        assertSame(second,a.group(),"index 2 of the others (b, c) is the end, which is the last group");
        saved.place(c.id(),0,0);
        assertSame(saved.groups().getFirst(),c.group(),"before b, so in b's group");
    }
    @Test void aBuildObjectInsideAnotherIsInTheGroupOfTheOneAtTheTop(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Second");
        saved.place(b.id(),a.id(),0);saved.placeIn(List.of(a.id()),0,second.id(),0);
        assertSame(second,b.group());assertEquals(b.parentId(),a.id());
        assertEquals(List.of("BuildObject","BuildObject (1)"),names(saved));
    }
    @Test void deletingAGroupMovesItsBuildObjectsToTheOneBeforeAndTheLastOneStays(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var second=saved.createGroup("Second");var third=saved.createGroup("Third");
        var b=saved.save(s);saved.placeIn(List.of(b.id()),0,second.id(),0);
        saved.deleteGroup(second.id());
        assertSame(saved.groups().getFirst(),b.group());assertEquals(List.of("New Group","Third"),groupNames(saved));
    }
    @Test void thereIsAlwaysAtLeastOneGroup(){
        var saved=new SavedSelections();
        assertThrows(IllegalArgumentException.class,()->saved.deleteGroup(saved.groups().getFirst().id()));
        assertEquals(1,saved.groups().size());
    }
    @Test void anEmptyBuildObjectGoesAtTheEndOfTheGroupAskedFor(){
        var saved=new SavedSelections();var s=live(A);
        var second=saved.createGroup("Second");saved.save(s);
        var empty=saved.createEmpty(second.id());
        assertEquals(0,empty.size());assertSame(second,empty.group());assertEquals(empty,saved.entries().getLast());
        assertSame(saved.groups().getFirst(),saved.createEmpty(0).group(),"none asked for is the first group");
    }
    @Test void aNewBuildObjectGoesInTheGroupOfTheOneBeingLookedAt(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var second=saved.createGroup("Second");saved.placeIn(List.of(a.id()),0,second.id(),0);
        saved.view(a.id(),s);
        assertSame(second,saved.save(s).group());
    }
    @Test void undoPutsBackGroupsTheirOrderAndWhatWasInThem(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var before=saved.state();
        var second=saved.createGroup("Second");saved.placeIn(List.of(a.id()),0,second.id(),0);saved.renameGroup(second.id(),"Roofs");
        var after=saved.state();
        assertFalse(saved.structureMatches(before));
        saved.restore(before,s);
        assertEquals(List.of("New Group"),groupNames(saved));assertSame(saved.groups().getFirst(),a.group());assertTrue(saved.structureMatches(before));
        saved.restore(after,s);
        assertEquals(List.of("New Group","Roofs"),groupNames(saved));assertSame(second,a.group());
    }
    @Test void groupsAndWhatIsInThemSurviveBeingSavedAndLoaded(){
        var saved=new SavedSelections();var s=live(A,B);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Walls");
        saved.placeIn(List.of(b.id()),0,second.id(),0);saved.place(saved.save(s).id(),b.id(),0);
        var tree=saved.tree();
        var back=new SavedSelections(SavedSelections.Tree.load(tree.nextId(),tree.saved(),tree.nextGroupId(),tree.savedGroups()));
        assertEquals(groupNames(saved),groupNames(back));
        assertEquals(names(saved),names(back));
        for(var e:saved.entries())assertEquals(e.groupId(),back.byId(e.id()).groupId(),e.name());
        assertEquals(saved.groups().get(1).guid(),back.groups().get(1).guid());
    }
    @Test void aFileFromBeforeGroupsGetsTheFirstGroupAndAFileWithAGroupThatIsGoneFallsBackToIt(){
        var rows=List.of(new SavedSelections.Saved(1,"g1","A",0,0,List.of(A)),new SavedSelections.Saved(2,"g2","B",0,0,List.of(B),77));
        var old=new SavedSelections(SavedSelections.Tree.load(1,rows));
        assertEquals(List.of("New Group"),groupNames(old));
        assertTrue(old.entries().stream().allMatch(e->e.group()==old.groups().getFirst()));
        var gone=new SavedSelections(SavedSelections.Tree.load(1,rows,3,List.of(new SavedSelections.SavedGroup(2,"x","Only"))));
        assertTrue(gone.entries().stream().allMatch(e->e.groupId()==2),"B's group 77 is not there");
    }
    @Test void aDuplicateAfterABuildObjectStaysInItsGroup(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var second=saved.createGroup("Second");var b=saved.save(s);saved.placeIn(List.of(b.id()),0,second.id(),0);
        var made=saved.createFrom("Pasted",List.of(B),b.id());
        assertSame(second,made.group());assertEquals(List.of("BuildObject","BuildObject (1)","Pasted"),names(saved));
    }

    @Test void groupingBuildObjectsTogetherKeepsTheGroupEachWasInAndUngroupingPutsItBack(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Second");var first=saved.groups().getFirst();
        saved.placeIn(List.of(b.id()),0,second.id(),0);
        var parent=saved.group(List.of(a.id(),b.id()));
        assertEquals(first.id(),parent.groupId(),"the new parent takes the place of the first one");
        assertEquals(first.id(),a.ownGroupId());assertEquals(second.id(),b.ownGroupId(),"B still remembers its own group");
        assertSame(first,b.group(),"it is shown in the group of what it is under");
        saved.ungroup(List.of(b.id()));
        assertNull(b.parent());assertSame(second,b.group(),"back in its own group, and not left behind in the first");
        assertSame(first,parent.group());assertEquals(a.parent(),parent);
        saved.ungroup(List.of(a.id()));
        assertNull(a.parent());assertSame(first,a.group());assertEquals(List.of("BuildObject (2)","BuildObject","BuildObject (1)"),names(saved),"the parent, then A right after it, then B in its group");
    }
    @Test void ungroupingOneThatIsNotInsideAnotherIsRefusedAndOneLevelOutStaysUnderTheGrandparent(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var c=saved.save(s);
        assertThrows(IllegalArgumentException.class,()->saved.ungroup(List.of(a.id())));
        saved.place(b.id(),a.id(),0);saved.place(c.id(),b.id(),0);
        saved.ungroup(List.of(c.id()));
        assertSame(a,b.parent());assertSame(a,c.parent(),"one level out, to the grandparent");
    }
    @Test void movingAParentToAnotherGroupTakesEverythingUnderItWithIt(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Second");var third=saved.createGroup("Third");
        saved.placeIn(List.of(b.id()),0,second.id(),0);
        var parent=saved.group(List.of(a.id(),b.id()));
        saved.placeIn(List.of(parent.id()),0,third.id(),0);
        assertSame(third,parent.group());assertSame(third,a.ownGroup());assertSame(third,b.ownGroup(),"what it remembered is replaced: it moved with its parent");
        saved.ungroup(List.of(b.id()));assertSame(third,b.group());
    }
    @Test void undoAndASaveKeepWhatABuildObjectRemembersWhileItIsInsideAnother(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Second");saved.placeIn(List.of(b.id()),0,second.id(),0);
        var before=saved.state();var parent=saved.group(List.of(a.id(),b.id()));var after=saved.state();
        saved.restore(before,s);assertNull(b.parent());assertSame(second,b.group());
        saved.restore(after,s);assertSame(parent,b.parent());assertSame(second,b.ownGroup());
        var tree=saved.tree();
        var back=new SavedSelections(SavedSelections.Tree.load(tree.nextId(),tree.saved(),tree.nextGroupId(),tree.savedGroups()));
        assertEquals(second.id(),back.byId(b.id()).ownGroupId());assertEquals(parent.groupId(),back.byId(b.id()).groupId());
        back.ungroup(List.of(b.id()));assertEquals(second.id(),back.byId(b.id()).groupId());
    }
    @Test void deletingAGroupAlsoMovesWhatIsRememberedInIt(){
        var saved=new SavedSelections();var s=live(A);
        var a=saved.save(s);var b=saved.save(s);var second=saved.createGroup("Second");saved.placeIn(List.of(b.id()),0,second.id(),0);
        saved.group(List.of(a.id(),b.id()));saved.deleteGroup(second.id());
        assertSame(saved.groups().getFirst(),b.ownGroup());
    }
}
