package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MagicWandTest {
    private static MagicWand.World of(Set<Point> solid){
        return new MagicWand.World(){
            public boolean loaded(Point p){return true;}
            public boolean air(Point p){return !solid.contains(p);}
            public boolean matches(Point p){return solid.contains(p);}
            public boolean surface(Point p){return true;}
        };
    }
    private static MagicWand.Params params(int limit){return new MagicWand.Params(limit,1,false,true,true,true,false,128,100_000);}

    private static Set<Point> row(int length){var set=new HashSet<Point>();for(int x=0;x<length;x++)set.add(new Point(x,0,0));return set;}

    @Test void selectsTheConnectedBlocks(){
        var result=MagicWand.select(new Point(0,0,0),of(row(10)),params(100));
        assertEquals(10,result.found().size());
        assertFalse(result.truncated());
    }

    @Test void doesNotCrossAGap(){
        var blocks=row(10);blocks.remove(new Point(5,0,0));
        assertEquals(5,MagicWand.select(new Point(0,0,0),of(blocks),params(100)).found().size());
    }

    @Test void stopsAtTheLimitAndSaysSo(){
        var result=MagicWand.select(new Point(0,0,0),of(row(50)),params(10));
        assertEquals(10,result.found().size());
        assertTrue(result.truncated());
    }

    @Test void reportsWhenTheVisitCapEndsTheSearchEarly(){
        var result=MagicWand.select(new Point(0,0,0),of(row(50)),new MagicWand.Params(1000,1,false,true,true,true,false,128,20));
        assertTrue(result.truncated());
    }
}
