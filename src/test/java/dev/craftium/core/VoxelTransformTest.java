package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxelTransformTest {
    private static Set<Point> row(int n){var s=new HashSet<Point>();for(int i=0;i<n;i++)s.add(new Point(i,0,0));return s;}

    @Test void aQuarterTurnKeepsEveryBlock(){
        var landed=VoxelTransform.landed(row(4),new Geometry.Vector(2,.5,.5),90,0,0,1,1,1,0,0,0,10_000);
        assertEquals(4,landed.size());
        assertTrue(landed.stream().allMatch(p->p.x()==landed.iterator().next().x()),"a row along X stands along Z or Y after a yaw");
    }
    @Test void aMoveShiftsTheCells(){
        var landed=VoxelTransform.landed(row(2),new Geometry.Vector(1,.5,.5),0,0,0,1,1,1,3,0,0,10_000);
        assertEquals(Set.of(new Point(3,0,0),new Point(4,0,0)),landed);
    }
    @Test void aDoubleScaleDoublesTheRow(){
        var landed=VoxelTransform.landed(row(2),new Geometry.Vector(0,0,0),0,0,0,2,1,1,0,0,0,10_000);
        assertEquals(4,landed.size());
    }
    @Test void tooLargeAVolumeGivesNull(){
        assertNull(VoxelTransform.landed(row(50),new Geometry.Vector(0,0,0),0,0,0,100,100,100,0,0,0,1_000));
    }
}
