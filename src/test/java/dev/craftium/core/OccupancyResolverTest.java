package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The one-occupant-per-cell rule of lifted BuildObjects, and the grid transform and volume it rests on. */
class OccupancyResolverTest{
    private static final Point O=new Point(0,0,0),X=new Point(1,0,0),Z=new Point(0,0,1);
    private static ObjectVolume volume(Point... points){var v=new ObjectVolume();for(Point p:points)v.set(p,"minecraft:stone");return v;}
    private static OccupancyResolver.Placed placed(String id,ObjectVolume v,GridTransform t){return new OccupancyResolver.Placed(id,v,t);}

    @Test void transformsRoundTripAndComposeLikeNestedObjects(){
        for(int turns=0;turns<4;turns++){
            var t=new GridTransform(turns,3,-2,5);var p=new Point(4,1,-7);
            assertEquals(p,t.invert(t.apply(p)));
        }
        var parent=new GridTransform(1,10,0,0);var child=new GridTransform(2,0,0,3);var p=new Point(1,2,3);
        assertEquals(parent.apply(child.apply(p)),parent.then(child).apply(p));
        assertEquals(3,new GridTransform(-1,0,0,0).turns());
        assertEquals(new Point(0,0,1),new GridTransform(1,0,0,0).apply(X));
    }
    @Test void volumeSerializesToTheSameTextAndBack(){
        var v=new ObjectVolume();v.set(new Point(2,0,1),"minecraft:oak_stairs[facing=north]");v.set(O,"minecraft:stone");
        var back=ObjectVolume.parse(v.serialize());
        assertEquals(v.cells(),back.cells());assertEquals(v.serialize(),back.serialize());
        v.set(O,"");assertFalse(v.has(O));assertArrayEquals(new int[]{2,0,1,2,0,1},v.bounds());
    }
    @Test void aSolidWorldBlockAlwaysWinsAndTheObjectBlockIsOnlyHidden(){
        var r=new OccupancyResolver(p->p.equals(X));
        var obj=placed("a",volume(O,X),GridTransform.IDENTITY);
        assertEquals(Set.of(X),r.suppressed(List.of(obj)).get("a"));
        assertNull(r.occupant(List.of(obj),X).objectId());
        assertEquals("a",r.occupant(List.of(obj),O).objectId());
        assertEquals(2,obj.volume().size());
    }
    @Test void theLaterObjectWinsAnOverlapAndTheEarlierOneOnlyLosesThatCell(){
        var r=new OccupancyResolver(p->false);
        var parent=placed("parent",volume(O,X),GridTransform.IDENTITY);
        var child=placed("child",volume(O),GridTransform.IDENTITY);
        var s=r.suppressed(List.of(parent,child));
        assertEquals(Set.of(O),s.get("parent"));assertTrue(s.get("child").isEmpty());
        assertEquals("child",r.occupant(List.of(parent,child),O).objectId());
        assertEquals("parent",r.occupant(List.of(parent,child),X).objectId());
        // Reversing the order flips the winner: nothing was deleted.
        s=r.suppressed(List.of(child,parent));
        assertEquals(Set.of(O),s.get("child"));assertTrue(s.get("parent").isEmpty());
    }
    @Test void suppressedCellsAreReportedInTheLoosersLocalSpace(){
        var r=new OccupancyResolver(p->false);
        var low=placed("low",volume(Z),new GridTransform(1,5,0,0)); // local (0,0,1) turns to (-1,0,0), then shifts to world (4,0,0)
        var high=placed("high",volume(O),GridTransform.shift(4,0,0));
        assertEquals(new Point(4,0,0),low.transform().apply(Z));
        var s=r.suppressed(List.of(low,high));
        assertEquals(Set.of(Z),s.get("low"));assertTrue(s.get("high").isEmpty());
    }
    @Test void movingAnObjectAwayFromTheWorldBringsItsBlocksBack(){
        var solid=new HashSet<>(Set.of(O));
        var r=new OccupancyResolver(solid::contains);
        var v=volume(O);
        assertEquals(Set.of(O),r.suppressed(List.of(placed("a",v,GridTransform.IDENTITY))).get("a"));
        assertTrue(r.suppressed(List.of(placed("a",v,GridTransform.shift(0,5,0)))).get("a").isEmpty());
    }
}
