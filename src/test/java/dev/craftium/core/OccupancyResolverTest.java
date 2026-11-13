package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The one-occupant-per-cell rule of BuildObjects, and the volume text it rests on. */
class OccupancyResolverTest{
    private static final Point O=new Point(0,0,0),X=new Point(1,0,0);
    private static ObjectVolume volume(Point... points){var v=new ObjectVolume();for(Point p:points)v.set(p,"minecraft:stone");return v;}
    private static OccupancyResolver.Placed placed(String id,ObjectVolume v){return new OccupancyResolver.Placed(id,v);}

    @Test void volumeSerializesToTheSameTextAndBack(){
        var v=new ObjectVolume();v.set(new Point(2,0,1),"minecraft:oak_stairs[facing=north]");v.set(O,"minecraft:stone");
        var back=ObjectVolume.parse(v.serialize());
        assertEquals(v.cells(),back.cells());assertEquals(v.serialize(),back.serialize());
        v.set(O,"");assertFalse(v.has(O));assertArrayEquals(new int[]{2,0,1,2,0,1},v.bounds());
    }
    @Test void aSolidWorldBlockAlwaysWinsAndTheObjectBlockIsOnlyDormant(){
        var r=new OccupancyResolver(p->p.equals(X));
        var obj=placed("a",volume(O,X));
        assertEquals(Set.of(X),r.dormant(List.of(obj)).get("a"));
        assertEquals(2,obj.volume().size(),"the object keeps both blocks");
    }
    @Test void theLaterObjectWinsAnOverlapAndTheEarlierOneOnlyLosesThatCell(){
        var r=new OccupancyResolver(p->false);
        var parent=placed("parent",volume(O,X));
        var child=placed("child",volume(O));
        var s=r.dormant(List.of(parent,child));
        assertEquals(Set.of(O),s.get("parent"));assertTrue(s.get("child").isEmpty());
        // Reversing the order flips the winner: nothing was deleted.
        s=r.dormant(List.of(child,parent));
        assertEquals(Set.of(O),s.get("child"));assertTrue(s.get("parent").isEmpty());
    }
    @Test void aCellTheWorldWinsIsNotCountedAsOwnedSoTheOtherObjectsStayDormantToo(){
        var r=new OccupancyResolver(p->p.equals(O));
        var s=r.dormant(List.of(placed("a",volume(O)),placed("b",volume(O))));
        assertEquals(Set.of(O),s.get("a"));assertEquals(Set.of(O),s.get("b"));
    }
    @Test void movingAnObjectAwayFromTheWorldBringsItsBlocksBack(){
        var solid=new HashSet<>(Set.of(O));
        var r=new OccupancyResolver(solid::contains);
        assertEquals(Set.of(O),r.dormant(List.of(placed("a",volume(O)))).get("a"));
        assertTrue(r.dormant(List.of(placed("a",volume(new Point(0,5,0))))).get("a").isEmpty());
    }
}
