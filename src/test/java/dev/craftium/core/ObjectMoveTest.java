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
    private static Function<Point,ObjectMove.Block> view(Map<Point,String> world){return c->ObjectMove.Block.of(world.get(c));}
    private static ObjectMove.Result move(List<ObjectMove.Obj> objects,Set<Point> source,int dx,Map<Point,String> world){
        return ObjectMove.shift(objects,source,shift(dx),view(world),ObjectMoveTest::solid,false);
    }
    /** Applies a result to a world map the way the server will. */
    private static void apply(Map<Point,String> world,ObjectMove.Result r){for(var e:r.writes().entrySet()){if(e.getValue().isAir())world.remove(e.getKey());else world.put(e.getKey(),e.getValue().state());}}

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
        var r=ObjectMove.shift(List.of(held),Set.of(p(1)),Point::rotateY,view(world),ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(new Point(0,0,1),STONE,new Point(0,0,2),STAIRS),world);
    }
    @Test void airInTheSelectionClearsItsPlaceButIsNotPastedUnlessAsked(){
        var world=new HashMap<>(Map.of(p(0),STONE));
        var r=ObjectMove.shift(List.of(),Set.of(p(0),p(1)),shift(5),view(world),ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(p(5),STONE),world);
    }
    @Test void aChestKeepsItsContentsWhileItIsDormant(){
        var world=new HashMap<>(Map.of(p(0),"minecraft:chest[facing=north]",p(5),DIRT));
        var data=new HashMap<Point,String>(Map.of(p(0),"{Items:[{id:\"minecraft:diamond\",count:3}]}"));
        Function<Point,ObjectMove.Block> view=c->world.get(c)==null?null:new ObjectMove.Block(world.get(c),data.get(c));
        var r=ObjectMove.shift(List.of(obj(1,p(0))),Set.of(p(0)),c->c.add(5,0,0),view,ObjectMoveTest::solid,false);
        var o=r.objects().get(1);
        assertEquals("{Items:[{id:\"minecraft:diamond\",count:3}]}",o.dormant().data(p(5)),"hidden under the dirt, it still has its items");
    }
    @Test void aResamplingMoveSendsOneBlockToManyCellsAndEachIsResolvedOnItsOwn(){
        // A block scaled up to three cells, one of which has a world block: two are written, one is dormant.
        var world=new HashMap<>(Map.of(p(0),STONE,p(11),DIRT));
        var origin=new LinkedHashMap<Point,Point>();origin.put(p(10),p(0));origin.put(p(11),p(0));origin.put(p(12),p(0));
        var r=ObjectMove.move(List.of(obj(1,p(0))),Set.of(p(0)),origin,Map.of(),view(world),ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(p(10),STONE,p(11),DIRT,p(12),STONE),world);
        var o=r.objects().get(1);assertEquals(Set.of(p(10),p(12)),o.own());assertEquals(STONE,o.dormant().get(p(11)));
    }
    @Test void dormantBlocksAreSampledLikeSelectedOnesAndWhatIsSampledAwayIsGone(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(1),DIRT));
        var held=obj(1,cells(p(1),STAIRS,p(2),STAIRS),new PackedCells(),p(0));
        var dormantOrigin=Map.of(1,Map.<Point,Point>of(p(21),p(1))); // the block at 2 lands nowhere, as in scaling down
        var r=ObjectMove.move(List.of(held),Set.of(p(0)),Map.of(p(20),p(0)),dormantOrigin,view(world),ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(p(1),DIRT,p(20),STONE,p(21),STAIRS),world);
        assertTrue(r.objects().get(1).dormant().isEmpty());
    }
    @Test void theSelectionAfterAMoveHoldsOnlyTheCellsWhereABlockLanded(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(1),STONE,p(6),DIRT));
        var r=move(List.of(obj(1,p(0),p(1))),Set.of(p(0),p(1)),5,world);
        assertEquals(Set.of(p(5)),r.landed(),"the block that went dormant at 6 is not part of the selection, or the next move would carry the dirt");
    }
    @Test void aTransformCanLandABlockTurnedAndItIsWrittenAsItLands(){
        var world=new HashMap<>(Map.of(p(0),"minecraft:oak_stairs[facing=north]"));
        var landed=Map.of(p(5),new ObjectMove.Landing(p(0),new ObjectMove.Block("minecraft:oak_stairs[facing=east]",null)));
        var r=ObjectMove.moveLanded(List.of(obj(1,p(0))),Set.of(p(0)),landed,Map.of(),view(world),ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(p(5),"minecraft:oak_stairs[facing=east]"),world);
    }
    @Test void anAirBlockOfAnObjectThatLosesACellIsDroppedNotKept(){
        var world=new HashMap<>(Map.of(p(0),STONE,p(5),DIRT));
        var landed=Map.of(p(5),new ObjectMove.Landing(p(0),new ObjectMove.Block(ObjectMove.AIR,null)));
        var r=ObjectMove.moveLanded(List.of(obj(1,p(0))),Set.of(p(0)),landed,Map.of(),view(world),ObjectMoveTest::solid,true);apply(world,r);
        assertEquals(DIRT,world.get(p(5)));assertTrue(r.objects().get(1).dormant().isEmpty(),"nothing worth keeping");
    }
    private static ObjectMove.Placement put(int id,int x,String state){return new ObjectMove.Placement(id,p(x),ObjectMove.Block.of(state));}
    @Test void aPlacedObjectWritesWhereTheWorldIsFreeKeepsWhatLosesAndGivesNothingBackYet(){
        var world=new HashMap<>(Map.of(p(2),DIRT,p(3),WATER));
        var r=ObjectMove.place(List.of(obj(1)),List.of(put(1,1,STONE),put(1,2,STAIRS),put(1,3,STONE)),view(world),ObjectMoveTest::solid);apply(world,r);
        assertEquals(Map.of(p(1),STONE,p(2),DIRT,p(3),STONE),world);
        var o=r.objects().get(1);assertEquals(Set.of(p(1),p(3)),o.own());assertEquals(STAIRS,o.dormant().get(p(2)));assertEquals(WATER,o.replaced().get(p(3)));
        assertEquals(Set.of(p(1),p(3)),r.landed());
    }
    @Test void aPlacedObjectEarlierInTheListLosesToOneThatIsAlreadyThereAndALaterOneTakesTheCell(){
        var world=new HashMap<>(Map.of(p(0),DIRT));
        var there=obj(2,p(0));
        var earlier=ObjectMove.place(List.of(obj(1),there),List.of(put(1,0,STONE)),view(world),ObjectMoveTest::solid);
        assertEquals(STONE,earlier.objects().get(1).dormant().get(p(0)),"the object later in the list keeps the cell");assertNull(earlier.objects().get(2));
        var later=ObjectMove.place(List.of(there,obj(3)),List.of(put(3,0,STONE)),view(world),ObjectMoveTest::solid);apply(world,later);
        assertEquals(STONE,world.get(p(0)));assertEquals(DIRT,later.objects().get(2).dormant().get(p(0)));assertTrue(later.objects().get(2).own().isEmpty());
    }
    @Test void aBlockThatWasWaterloggedByCoveringWaterIsDryOnceItLeavesAndOneBuiltWaterloggedKeepsIt(){
        var logged="minecraft:oak_slab[type=bottom,waterlogged=true]";
        var world=new HashMap<>(Map.of(p(0),logged));
        var held=obj(1,new PackedCells(),cells(p(0),WATER),p(0)); // it stands in water at 0
        var out=move(List.of(held),Set.of(p(0)),5,world);apply(world,out);
        assertEquals("minecraft:oak_slab[type=bottom,waterlogged=false]",world.get(p(5)),"the water it was waterlogged with stays behind");
        assertEquals(WATER,world.get(p(0)));
        var own=new HashMap<>(Map.of(p(0),logged));
        var built=move(List.of(obj(1,p(0))),Set.of(p(0)),5,own);apply(own,built);
        assertEquals(logged,own.get(p(5)),"nothing was covered at 0, so the block was waterlogged by the builder and stays so");
    }
    @Test void isWaterMeansWaterAndNotBlocksThatStartWithTheWord(){
        assertTrue(ObjectMove.isWater("minecraft:water[level=0]"));assertTrue(ObjectMove.isWater("minecraft:water"));
        assertFalse(ObjectMove.isWater("minecraft:water_cauldron[level=1]"));assertFalse(ObjectMove.isWater(null));
    }
    @Test void aCarriedObjectThatHoldsNothingInTheWorldMovesItsHiddenBlocksWithTheSelection(){
        var world=new HashMap<>(Map.of(p(0),DIRT,p(1),DIRT,p(20),WATER));
        var buried=new ObjectMove.Obj(1,new HashSet<>(),cells(p(0),STONE,p(1),STAIRS),new PackedCells(),true);
        var r=ObjectMove.shift(List.of(buried),Set.of(),shift(20),view(world),ObjectMoveTest::solid,false);apply(world,r);
        assertEquals(Map.of(p(0),DIRT,p(1),DIRT,p(20),STONE,p(21),STAIRS),world,"they land where the world is free, and the dirt is untouched");
        var o=r.objects().get(1);assertEquals(Set.of(p(20),p(21)),o.own());assertTrue(o.dormant().isEmpty());assertEquals(WATER,o.replaced().get(p(20)));
        assertEquals(Set.of(p(20),p(21)),r.landed());
    }
    @Test void aCarriedObjectWhoseBlocksAreAllBlockedAgainKeepsThemAtTheNewPlace(){
        var world=new HashMap<>(Map.of(p(0),DIRT,p(5),DIRT));
        var buried=new ObjectMove.Obj(1,new HashSet<>(),cells(p(0),STONE),new PackedCells(),true);
        var r=ObjectMove.shift(List.of(buried),Set.of(),shift(5),view(world),ObjectMoveTest::solid,false);apply(world,r);
        assertTrue(r.writes().isEmpty());assertEquals(STONE,r.objects().get(1).dormant().get(p(5)));assertTrue(r.landed().isEmpty());
    }
    @Test void anObjectThatIsNotCarriedAndHoldsNothingStaysWhereItIs(){
        var world=new HashMap<Point,String>();
        var buried=new ObjectMove.Obj(1,new HashSet<>(),cells(p(0),STONE),new PackedCells());
        var r=ObjectMove.shift(List.of(buried),Set.of(),shift(5),view(world),ObjectMoveTest::solid,false);
        assertTrue(r.objects().isEmpty());
    }
    @Test void afterAReorderTheOneThatNowOutranksTheOtherTakesTheCellAndTheOtherKeepsItsBlock(){
        var world=new HashMap<>(Map.of(p(0),STONE)); // object 2 stands in the cell, object 1 keeps its dirt hidden under it
        var low=obj(1,cells(p(0),DIRT),new PackedCells());var high=obj(2,p(0));
        // 2 is now earlier in the list, so 1 outranks it
        var r=ObjectMove.reorder(List.of(high,low),view(world),ObjectMoveTest::solid);apply(world,r);
        assertEquals(DIRT,world.get(p(0)));
        assertEquals(Set.of(p(0)),r.objects().get(1).own());assertTrue(r.objects().get(1).dormant().isEmpty());
        assertTrue(r.objects().get(2).own().isEmpty());assertEquals(STONE,r.objects().get(2).dormant().get(p(0)),"nothing is lost");
        assertEquals(Set.of(p(0)),r.landed());
    }
    @Test void aReorderThatChangesNothingOrOnlyHidesOverFreeCellsWritesNothing(){
        var world=new HashMap<>(Map.of(p(0),STONE));
        var low=obj(1,cells(p(0),DIRT),new PackedCells());var high=obj(2,p(0));
        assertTrue(ObjectMove.reorder(List.of(low,high),view(world),ObjectMoveTest::solid).writes().isEmpty(),"2 still outranks 1");
        var free=obj(3,cells(p(7),DIRT),new PackedCells(),p(1)); // hidden at a cell nothing holds: that is Place here, not a reorder
        assertTrue(ObjectMove.reorder(List.of(free),view(world),ObjectMoveTest::solid).writes().isEmpty());
        assertTrue(ObjectMove.reorder(List.of(obj(4,p(0))),view(world),ObjectMoveTest::solid).objects().isEmpty());
    }
    @Test void aBuildObjectThatCoversWhatAnotherOneHoldsInAReplaceableBlockTakesTheCellFromIt(){
        var world=new HashMap<>(Map.of(p(0),WATER));
        var stale=obj(1,p(0)); // it holds the cell, but only in water, which is replaced when built on
        var r=ObjectMove.place(List.of(stale,obj(2)),List.of(put(2,0,STONE)),view(world),ObjectMoveTest::solid);apply(world,r);
        assertEquals(STONE,world.get(p(0)));assertEquals(Set.of(p(0)),r.objects().get(2).own());assertTrue(r.objects().get(1).own().isEmpty(),"no two hold one cell");
    }
}
