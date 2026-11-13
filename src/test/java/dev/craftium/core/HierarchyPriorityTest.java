package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Which BuildObject wins a cell that several of them want, by where they stand in the Hierarchy: a child is above its parent, of the BuildObjects on one level the
 * one below is above, and of the groups the one below is above. The order of {@link SavedSelections#entries} (each group in turn, each BuildObject at the top followed
 * by everything under it) is the order of priority, lowest first, so the last one that wants a cell gets it.
 */
class HierarchyPriorityTest {
    private static final Point CELL=new Point(0,0,0);

    private static Selection live(Point... points){var s=new Selection();s.combine(List.of(points),Selection.Mode.REPLACE);return s;}
    private static SavedSelections.Entry make(SavedSelections saved){return saved.save(live(new Point(100+saved.entries().size(),0,0)));}

    /** The id of the BuildObject that ends up with {@code CELL} when every one of {@code wanting} asks for it, in the order the Hierarchy lists them. */
    private static int winner(SavedSelections saved,List<SavedSelections.Entry> wanting){
        var ordered=new ArrayList<ObjectMove.Obj>();
        for(var e:saved.entries())ordered.add(new ObjectMove.Obj(e.id(),new HashSet<>(),new PackedCells(),new PackedCells()));
        var placements=new ArrayList<ObjectMove.Placement>();
        for(var e:wanting)placements.add(new ObjectMove.Placement(e.id(),CELL,ObjectMove.Block.of("minecraft:stone")));
        var result=ObjectMove.place(ordered,placements,c->null,s->!ObjectMove.isAir(s));
        int won=-1;
        for(var o:result.objects().values())if(o.own().contains(CELL)){assertEquals(-1,won,"one owner at most");won=o.id();}
        return won;
    }
    private static List<Integer> ids(SavedSelections saved){return saved.entries().stream().map(SavedSelections.Entry::id).toList();}

    @Test void aChildIsAboveItsParent(){
        var saved=new SavedSelections();var parent=make(saved);var child=make(saved);
        saved.place(child.id(),parent.id(),0);
        assertEquals(List.of(parent.id(),child.id()),ids(saved));
        assertEquals(child.id(),winner(saved,List.of(parent,child)));
    }
    @Test void aChildIsAboveItsParentWhateverTheOrderTheyWereMadeIn(){
        var saved=new SavedSelections();var child=make(saved);var parent=make(saved);
        saved.place(child.id(),parent.id(),0); // made first, then put under the one made second
        assertEquals(List.of(parent.id(),child.id()),ids(saved));
        assertEquals(child.id(),winner(saved,List.of(parent,child)));
    }
    @Test void ofBuildObjectsOnOneLevelTheOneBelowIsAbove(){
        var saved=new SavedSelections();var upper=make(saved);var lower=make(saved);
        assertEquals(lower.id(),winner(saved,List.of(upper,lower)));
        saved.place(lower.id(),0,0); // dragged above the other one
        assertEquals(List.of(lower.id(),upper.id()),ids(saved));
        assertEquals(upper.id(),winner(saved,List.of(lower,upper)),"it is the other one that is below now");
    }
    @Test void ofSeveralChildrenTheOneBelowIsAboveAndAllAreAboveTheParent(){
        var saved=new SavedSelections();var parent=make(saved);var first=make(saved);var second=make(saved);
        saved.place(first.id(),parent.id(),0);saved.place(second.id(),parent.id(),1);
        assertEquals(List.of(parent.id(),first.id(),second.id()),ids(saved));
        assertEquals(second.id(),winner(saved,List.of(parent,first,second)));
        assertEquals(first.id(),winner(saved,List.of(parent,first)));
    }
    @Test void ofTheGroupsTheOneBelowIsAbove(){
        var saved=new SavedSelections();var inFirst=make(saved);var second=saved.createGroup("Second");var inSecond=make(saved);
        saved.placeIn(List.of(inSecond.id()),0,second.id(),0);
        assertEquals(List.of(inFirst.id(),inSecond.id()),ids(saved));
        assertEquals(inSecond.id(),winner(saved,List.of(inFirst,inSecond)));
        saved.moveGroup(second.id(),0); // the second group dragged above the first
        assertEquals(List.of(inSecond.id(),inFirst.id()),ids(saved));
        assertEquals(inFirst.id(),winner(saved,List.of(inSecond,inFirst)),"the first group is below now");
    }
    @Test void aGroupDecidesBeforeTheBuildObjectsInIt(){
        var saved=new SavedSelections();
        var upperTop=make(saved);var upperChild=make(saved);saved.place(upperChild.id(),upperTop.id(),0); // a BuildObject with a child, in the first group
        var second=saved.createGroup("Second");var lowerGroup=make(saved);saved.placeIn(List.of(lowerGroup.id()),0,second.id(),0);
        assertEquals(lowerGroup.id(),winner(saved,List.of(upperTop,upperChild,lowerGroup)),"even against a child, the lower group wins");
    }
    @Test void aLowerSiblingIsAboveEverythingInTheOneAboveIt(){
        // Everything is ordered as one list, as the window shows it: a child is above its own parent only, so the BuildObject below the parent is above the child too.
        var saved=new SavedSelections();var parent=make(saved);var child=make(saved);var sibling=make(saved);
        saved.place(child.id(),parent.id(),0);
        assertEquals(List.of(parent.id(),child.id(),sibling.id()),ids(saved));
        assertEquals(sibling.id(),winner(saved,List.of(parent,child,sibling)));
        assertEquals(child.id(),winner(saved,List.of(parent,child)));
    }
    @Test void draggingABuildObjectBelowAnotherGivesItTheCellTheOtherWasHidingUnder(){
        var saved=new SavedSelections();var upper=make(saved);var lower=make(saved); // lower is on top of upper
        // the upper one stands in the cell, and the lower one, which outranks it, keeps its block hidden there: that is how it got there before the order was this
        var world=new HashMap<Point,ObjectMove.Block>(Map.of(CELL,ObjectMove.Block.of("minecraft:stone")));
        var hidden=new PackedCells();hidden.put(CELL,"minecraft:dirt");
        saved.place(lower.id(),0,0); // the lower one dragged to the top, so now the other one outranks it
        var holder=new ObjectMove.Obj(lower.id(),new HashSet<>(),hidden,new PackedCells());var stands=new ObjectMove.Obj(upper.id(),new HashSet<>(List.of(CELL)),new PackedCells(),new PackedCells());
        var ordered=new ArrayList<ObjectMove.Obj>();for(var e:saved.entries())ordered.add(e.id()==lower.id()?holder:stands);
        assertEquals(upper.id(),saved.entries().get(1).id(),"the upper one is below now");
        var r=ObjectMove.reorder(ordered,world::get,s->!ObjectMove.isAir(s));
        assertTrue(r.writes().isEmpty(),"the one that stands there outranks the one hiding: nothing changes");
        saved.place(lower.id(),0,1); // and back to the bottom: the hiding one outranks the one standing there
        ordered.clear();for(var e:saved.entries())ordered.add(e.id()==lower.id()?holder:stands);
        r=ObjectMove.reorder(ordered,world::get,s->!ObjectMove.isAir(s));
        assertEquals("minecraft:dirt",r.writes().get(CELL).state());assertEquals(Set.of(CELL),r.objects().get(lower.id()).own());
        assertEquals("minecraft:stone",r.objects().get(upper.id()).dormant().get(CELL));
    }
}
