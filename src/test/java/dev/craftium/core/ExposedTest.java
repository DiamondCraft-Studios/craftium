package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExposedTest {
    private static List<Point> cube(int side){
        var cells=new ArrayList<Point>();
        for(int x=0;x<side;x++)for(int y=0;y<side;y++)for(int z=0;z<side;z++)cells.add(new Point(x,y,z));
        return cells;
    }
    private static boolean onSurface(Point p,int side){
        return p.x()==0||p.y()==0||p.z()==0||p.x()==side-1||p.y()==side-1||p.z()==side-1;
    }

    @Test void aSetThatFitsIsReturnedWhole(){
        var cells=cube(3);
        assertEquals(cells,Exposed.first(cells,27));
        assertEquals(27,Exposed.first(new HashSet<>(cells),100).size());
    }

    @Test void theSurfaceComesBeforeTheInside(){
        var cells=cube(10); // 1000 blocks, 488 of them on the surface
        var shown=Exposed.first(cells,488);
        assertEquals(488,shown.size());
        for(Point p:shown)assertTrue(onSurface(p,10),"buried block drawn before the surface was complete: "+p);
    }

    @Test void leftoverRoomIsFilledWithBuriedBlocksWithoutRepeats(){
        var shown=Exposed.first(cube(10),600);
        assertEquals(600,shown.size());
        assertEquals(600,new HashSet<>(shown).size());
        assertEquals(488,shown.stream().filter(p->onSurface(p,10)).count());
    }

    @Test void aCutSurfaceKeepsTheOrderOfTheInput(){
        var cells=cube(10);
        var shown=Exposed.first(cells,50);
        assertEquals(cells.stream().filter(p->onSurface(p,10)).limit(50).toList(),shown);
    }

    @Test void nothingFitsInNothing(){
        assertTrue(Exposed.first(cube(4),0).isEmpty());
    }

    @Test void negativeCoordinatesAreTreatedAsNeighboursToo(){
        var cells=new ArrayList<Point>();
        for(int x=-3;x<=3;x++)for(int y=-3;y<=3;y++)for(int z=-3;z<=3;z++)cells.add(new Point(x,y,z));
        var shown=Exposed.first(cells,7*7*7-5*5*5); // exactly the surface
        for(Point p:shown)assertTrue(Math.max(Math.abs(p.x()),Math.max(Math.abs(p.y()),Math.abs(p.z())))==3,p.toString());
    }
}
