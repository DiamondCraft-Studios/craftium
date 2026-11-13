package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The packed block-state store behind dormant and replaced blocks. */
class PackedCellsTest{
    private static final String STONE="minecraft:stone",STAIRS="minecraft:oak_stairs[facing=north]";

    @Test void cellsPackAndUnpackAtTheEdgesOfTheWorld(){
        for(var p:List.of(new Point(0,0,0),new Point(-30_000_000,-2048,29_999_999),new Point(33_554_431,2047,-33_554_432),new Point(-1,-1,-1)))
            assertEquals(p,PackedCells.unpack(PackedCells.pack(p)));
    }
    @Test void putGetAndRemove(){
        var c=new PackedCells();var a=new Point(1,2,3);var b=new Point(-4,5,-6);
        assertTrue(c.put(a,STONE));assertFalse(c.put(a,STONE),"the same state again is no change");
        assertTrue(c.put(a,STAIRS));assertEquals(STAIRS,c.get(a));
        c.put(b,STONE);assertEquals(2,c.size());
        assertTrue(c.remove(a));assertFalse(c.remove(a));assertNull(c.get(a));assertEquals(STONE,c.get(b));
        assertTrue(c.put(b,null));assertFalse(c.has(b),"a blank state removes");assertTrue(c.isEmpty());
    }
    @Test void manyCellsSurviveGrowthAndRemovalsInAnyOrder(){
        var c=new PackedCells();var rnd=new Random(7);var expected=new HashMap<Point,String>();
        for(int i=0;i<20_000;i++){var p=new Point(rnd.nextInt(60)-30,rnd.nextInt(40),rnd.nextInt(60)-30);var s=i%3==0?STONE:STAIRS;c.put(p,s);expected.put(p,s);}
        assertEquals(expected.size(),c.size());
        var keys=new ArrayList<>(expected.keySet());Collections.shuffle(keys,rnd);
        for(int i=0;i<keys.size();i+=2){c.remove(keys.get(i));expected.remove(keys.get(i));}
        assertEquals(expected.size(),c.size());
        for(var e:expected.entrySet())assertEquals(e.getValue(),c.get(e.getKey()));
        for(int i=0;i<keys.size();i+=2)assertFalse(c.has(keys.get(i)),"removed cells stay gone, and later cells on their probe path are still found");
        var seen=new HashMap<Point,String>();c.forEach(seen::put);assertEquals(expected,seen);
    }
    @Test void theSavedFormRoundTripsAndIsTheSameEveryTime(){
        var c=new PackedCells();c.put(new Point(3,0,0),STAIRS);c.put(new Point(1,0,0),STONE);c.put(new Point(2,0,0),STAIRS);
        var back=PackedCells.of(c.keysArray(),c.paletteList(),c.indexesArray());
        assertEquals(3,back.size());assertEquals(STAIRS,back.get(new Point(3,0,0)));assertEquals(STONE,back.get(new Point(1,0,0)));
        assertArrayEquals(c.keysArray(),back.keysArray());assertEquals(c.paletteList(),back.paletteList());assertArrayEquals(c.indexesArray(),back.indexesArray());
        c.put(new Point(1,0,0),STAIRS);
        assertEquals(List.of(STAIRS),c.paletteList(),"a state no cell uses is not saved");
    }
    @Test void aDamagedSavedFormIsRefused(){
        assertThrows(IllegalArgumentException.class,()->PackedCells.of(new long[]{1},List.of(STONE),new int[]{0,0}));
        assertThrows(IllegalArgumentException.class,()->PackedCells.of(new long[]{1},List.of(STONE),new int[]{1}));
        assertThrows(IllegalArgumentException.class,()->PackedCells.of(new long[]{1},List.of(STONE),new int[]{-1}));
    }
    @Test void aCopyIsIndependentAndTheSharedEmptyStoreCannotBeWrittenTo(){
        var c=new PackedCells();c.put(new Point(0,0,0),STONE);var copy=c.copy();copy.put(new Point(1,0,0),STONE);
        assertEquals(1,c.size());assertEquals(2,copy.size());
        assertThrows(UnsupportedOperationException.class,()->PackedCells.EMPTY.put(new Point(0,0,0),STONE));
        assertNull(PackedCells.EMPTY.get(new Point(0,0,0)));assertTrue(PackedCells.EMPTY.points().isEmpty());
    }
    @Test void blockEntityDataStaysWithItsCellThroughChangesCopiesAndTheSavedForm(){
        var c=new PackedCells();var a=new Point(1,0,0);var b=new Point(2,0,0);
        c.put(a,"minecraft:chest[facing=north]","{Items:[]}");c.put(b,STONE);
        assertEquals("{Items:[]}",c.data(a));assertNull(c.data(b));
        assertFalse(c.put(a,"minecraft:chest[facing=north]","{Items:[]}"));assertTrue(c.put(a,"minecraft:chest[facing=north]","{Items:[1]}"),"the data alone is a change");
        var back=PackedCells.of(c.keysArray(),c.paletteList(),c.indexesArray(),c.dataKeysArray(),c.dataList());
        assertEquals("{Items:[1]}",back.data(a));assertNull(back.data(b));
        assertEquals("{Items:[1]}",c.copy().data(a));
        c.put(a,STONE);assertNull(c.data(a),"a new block there has none of the old block's data");
        c.put(b,STONE,"{x:1}");c.remove(b);assertNull(c.data(b));
        assertThrows(IllegalArgumentException.class,()->PackedCells.of(new long[]{PackedCells.pack(a)},List.of(STONE),new int[]{0},new long[]{PackedCells.pack(b)},List.of("{}")));
    }
}
