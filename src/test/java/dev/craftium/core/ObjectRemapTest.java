package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectRemapTest {
    private static Point p(int x,int y,int z){return new Point(x,y,z);}

    @Test void aMoveCarriesTheBlocksOfAnObjectAndLeavesTheOthersWhereTheyAre(){
        var source=Set.of(p(0,0,0),p(1,0,0));var origin=new LinkedHashMap<Point,Point>();
        origin.put(p(5,0,0),p(0,0,0));origin.put(p(6,0,0),p(1,0,0)); // both moved five along
        assertEquals(Set.of(p(5,0,0)),new HashSet<>(ObjectRemap.apply(List.of(p(0,0,0)),source,origin)),"the one block it held went with it");
        assertEquals(Set.of(p(9,9,9),p(6,0,0)),new HashSet<>(ObjectRemap.apply(List.of(p(1,0,0),p(9,9,9)),source,origin)),"a block outside the selection stays where it was");
    }
    @Test void aCellTheShapeGrewIntoInheritsFromWhereItWasSampledSoScalingUpKeepsTheObjectWhole(){
        var source=Set.of(p(0,0,0));var origin=new LinkedHashMap<Point,Point>();
        origin.put(p(0,0,0),p(0,0,0));origin.put(p(1,0,0),p(0,0,0)); // one block became two
        assertEquals(Set.of(p(0,0,0),p(1,0,0)),new HashSet<>(ObjectRemap.apply(List.of(p(0,0,0)),source,origin)));
    }
    @Test void aBlockThatWasSampledAwayIsLostFromItsObject(){
        var source=Set.of(p(0,0,0),p(1,0,0));var origin=new LinkedHashMap<Point,Point>();
        origin.put(p(0,0,0),p(0,0,0)); // two blocks became one: the second was dropped
        assertEquals(List.of(),ObjectRemap.apply(List.of(p(1,0,0)),source,origin));
    }
    @Test void aQuarterTurnKeepsEveryBlockOfAnObjectExactlyOnce(){
        var own=new ArrayList<Point>();var source=new HashSet<Point>();var origin=new LinkedHashMap<Point,Point>();
        for(int x=0;x<3;x++)for(int z=0;z<2;z++){var from=p(x,0,z);own.add(from);source.add(from);origin.put(p(-z,0,x),from);} // turned a quarter about the corner
        var after=ObjectRemap.apply(own,source,origin);
        assertEquals(6,after.size());assertEquals(origin.keySet(),new HashSet<>(after));
    }
}
