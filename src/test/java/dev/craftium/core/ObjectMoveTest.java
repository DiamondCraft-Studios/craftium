package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** A move with the world winning: blocks that lose a cell become dormant, and come back when the cell is free. */
class ObjectMoveTest{
    private static final String STONE="minecraft:stone",DIRT="minecraft:dirt",WATER="minecraft:water[level=0]",STAIRS="minecraft:oak_stairs[facing=north]";
    private static Point p(int x){return new Point(x,0,0);}
    private static Function<Point,Point> shift(int dx){return c->c.add(dx,0,0);}
    private static boolean solid(String s){return !ObjectMove.isAir(s)&&!s.startsWith("minecraft:water");}
    private static ObjectMove.Obj obj(int id,Point... own){return new ObjectMove.Obj(id,new HashSet<>(List.of(own)),new PackedCells(),new PackedCells());}
    private static ObjectMove.Obj obj(int id,PackedCells dormant,PackedCells replaced,Point... own){return new ObjectMove.Obj(id,new HashSet<>(List.of(own)),dormant,replaced);}
    private static PackedCells cells(Object... pairs){var c=new PackedCells();for(int i=0;i<pairs.length;i+=2)c.put((Point)pairs[i],(String)pairs[i+1]);return c;}
    private static ObjectMove.Result move(List<ObjectMove.Obj> objects,Set<Point> source,int dx,Map<Point,String> world){
        return ObjectMove.move(objects,source,shift(dx),world::get,ObjectMoveTest::solid,false);
    }
    /** Applies a result to a world map the way the server will. */
    private static void apply(Map<Point,String> world,ObjectMove.Result r){for(var e:r.writes().entrySet()){if(ObjectMove.isAir(e.getValue()))world.remove(e.getKey());else world.put(e.getKey(),e.getValue());}}

    @Test void anObjectMovesIntoFreeSpaceAndTheOldPlaceIsCleared(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(1),STONE));
        var r=move(List.of(obj(1,p(0),p(1))),Set.of(p(0),p(1)),5,world);
        apply(world,r);
        assertEquals(Map.of(p(5),STONE,p(6),STONE),world);
        assertEquals(Set.of(p(5),p(6)),r.objects().get(1).own());assertTrue(r.objects().get(1).dormant().isEmpty());
    }
    @Test void aSolidWorldBlockWinsAndTheObjectsBlockGoesDormantWithoutBeingLost(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(1),STAIRS,p(6),DIRT));
        var r=move(List.of(obj(1,p(0),p(1))),Set.of(p(0),p(1)),5,world);
        apply(world,r);
        assertEquals(DIRT,world.get(p(6)),"the world block is untouched");assertEquals(STONE,world.get(p(5)));
        var o=r.objects().get(1);
        assertEquals(Set.of(p(5)),o.own());assertEquals(STAIRS,o.dormant().get(p(6)));assertEquals(1,o.dormant().size());
    }
    @Test void dormantBlocksTravelWithTheObjectAndComeBackWhereTheWorldIsFree(){
        var world=new HashMap<>(Map.of(p(5),STONE,p(6),DIRT));
        var held=obj(1,cells(p(6),STAIRS),new PackedCells(),p(5));
        var r=move(List.of(held),Set.of(p(5)),10,world);apply(world,r);
        assertEquals(Map.of(p(6),DIRT,p(15),STONE,p(16),STAIRS),world,"the hidden stairs arrive at 16, where nothing is");
        var o=r.objects().get(1);assertEquals(Set.of(p(15),p(16)),o.own());assertTrue(o.dormant().isEmpty());
    }
    @Test void aPartlySelectedObjectLeavesItsDormantBlocksWhereTheyAre(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(1),STONE,p(9),DIRT));
        var held=obj(1,cells(p(9),STAIRS),new PackedCells(),p(0),p(1));
        var r=move(List.of(held),Set.of(p(0)),20,world);apply(world,r);
        assertEquals(STAIRS,r.objects().get(1).dormant().get(p(9)));assertEquals(Set.of(p(1),p(20)),r.objects().get(1).own());
    }
    @Test void aBlockThatReplacedWaterGivesItBackWhenTheObjectLeaves(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(5),WATER));
        var r=move(List.of(obj(1,p(0))),Set.of(p(0)),5,world);apply(world,r);
        assertEquals(STONE,world.get(p(5)));assertEquals(WATER,r.objects().get(1).replaced().get(p(5)));
        var again=move(List.of(r.objects().get(1)),Set.of(p(5)),5,world);apply(world,again);
        assertEquals(WATER,world.get(p(5)),"the water is back where the object stood");assertEquals(STONE,world.get(p(10)));
        assertTrue(again.objects().get(1).replaced().isEmpty());
    }
    @Test void theLaterObjectTakesTheCellAndTheEarlierOneGetsItBackWhenItLeaves(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(5),DIRT));
        var parent=obj(1,p(5)); // earlier in the list: lower priority
        var child=obj(2,p(0));
        var r=move(List.of(parent,child),Set.of(p(0)),5,world);apply(world,r);
        assertEquals(STONE,world.get(p(5)),"the child won the cell");
        assertEquals(DIRT,r.objects().get(1).dormant().get(p(5)));assertFalse(r.objects().get(1).own().contains(p(5)));
        // The child leaves again: the parent's block returns.
        var back=move(List.of(r.objects().get(1),r.objects().get(2)),Set.of(p(5)),-5,world);
        // The selection at 5 belongs to the child (its own cell); the parent's hidden block is restored as the child vacates.
        apply(world,back);
        assertEquals(Map.of(p(0),STONE,p(5),DIRT),world);
        assertEquals(Set.of(p(5)),back.objects().get(1).own());assertTrue(back.objects().get(1).dormant().isEmpty());
    }
    @Test void theEarlierObjectLosesToTheLaterOneItMovesOnto(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(5),DIRT));
        var parent=obj(1,p(0));var child=obj(2,p(5));
        var r=move(List.of(parent,child),Set.of(p(0)),5,world);apply(world,r);
        assertEquals(DIRT,world.get(p(5)),"the later object keeps its block");
        assertEquals(STONE,r.objects().get(1).dormant().get(p(5)));assertTrue(r.objects().get(1).own().isEmpty());
        assertNull(r.objects().get(2),"the winner did not change");
    }
    @Test void anOrdinaryBlockOverwritesAndAnObjectThatHeldTheCellLosesIt(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(5),DIRT));
        var r=move(List.of(obj(1,p(5))),Set.of(p(0)),5,world);apply(world,r);
        assertEquals(STONE,world.get(p(5)));assertTrue(r.objects().get(1).own().isEmpty());assertTrue(r.objects().get(1).dormant().isEmpty(),"as before: nothing is kept");
    }
    @Test void aQuarterTurnMapsDormantBlocksToo(){
        var world=new HashMap<>(Map.of(p(1),STONE));
        var held=obj(1,cells(p(2),STAIRS),new PackedCells(),p(1));
        var r=ObjectMove.move(List.of(held),Set.of(p(1)),Point::rotateY,world::get,ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(new Point(0,0,1),STONE,new Point(0,0,2),STAIRS),world);
    }
    @Test void airInTheSelectionClearsItsPlaceButIsNotPastedUnlessAsked(){
        var world=new HashMap<>(Map.of(p(0),STONE));
        var r=ObjectMove.move(List.of(),Set.of(p(0),p(1)),shift(5),world::get,ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(p(5),STONE),world);
    }
}
