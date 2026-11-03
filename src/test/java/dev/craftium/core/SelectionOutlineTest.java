package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionOutlineTest {
    private static Set<Point> cuboid(int x0,int y0,int z0,int x1,int y1,int z1){
        var points=new HashSet<Point>();
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)points.add(new Point(x,y,z));
        return points;
    }
    private static double totalLength(SelectionOutline.Result r){
        double sum=0;for(var s:r.edges())sum+=Math.abs(s.x1()-s.x0())+Math.abs(s.y1()-s.y0())+Math.abs(s.z1()-s.z0());return sum;
    }
    private static long area(SelectionOutline.Result r){
        long sum=0;for(var f:r.faces())sum+=Math.abs((long)(f.x1()-f.x0())*(f.y1()-f.y0())+(long)(f.y1()-f.y0())*(f.z1()-f.z0())+(long)(f.x1()-f.x0())*(f.z1()-f.z0()));return sum;
    }

    @Test void emptySelectionHasNoOutline(){
        var r=SelectionOutline.of(Set.of());
        assertTrue(r.edges().isEmpty());assertTrue(r.faces().isEmpty());
    }

    @Test void singleBlockIsOneBox(){
        var r=SelectionOutline.of(Set.of(new Point(4,5,6)));
        assertEquals(12,r.edges().size());
        assertEquals(6,r.faces().size());
        assertEquals(12.0,totalLength(r));
    }

    @Test void cuboidIsExactlyOneBoxWhateverItsSize(){
        var r=SelectionOutline.of(cuboid(0,0,0,4,2,5)); // 5 x 3 x 6 blocks
        assertEquals(12,r.edges().size(),"a cuboid must be drawn as 12 long edges, not one box per block");
        assertEquals(6,r.faces().size());
        assertEquals(4*(5+3+6),totalLength(r));
        // Surface area: 2*(5*3 + 5*6 + 3*6)
        assertEquals(2*(5*3+5*6+3*6),area(r));
    }

    @Test void edgesAreSingleLongSegments(){
        var r=SelectionOutline.of(cuboid(0,0,0,9,0,0)); // a row of ten blocks
        assertEquals(12,r.edges().size());
        assertTrue(r.edges().stream().anyMatch(s->Math.abs(s.x1()-s.x0())==10),"the row's long edges must not be split per block");
    }

    @Test void lShapeShowsOnlyItsSilhouette(){
        // An L made of a 4x1x1 bar and a 1x3x1 post beside it, extruded 2 deep: 6 outline corners.
        var l=new HashSet<Point>();
        l.addAll(cuboid(0,0,0,3,0,1));
        l.addAll(cuboid(0,1,0,0,2,1));
        var r=SelectionOutline.of(l);
        // L outline: 6 edges on the front, 6 on the back and 6 joining them.
        assertEquals(18,r.edges().size());
        // Faces: each L-shaped cap merges into 2 rectangles (the bar and the post), plus one wall per outline side.
        assertEquals(2*2+6,r.faces().size());
    }

    @Test void flatSlabOutlinesItsRim(){
        var r=SelectionOutline.of(cuboid(0,0,0,5,0,5));
        assertEquals(12,r.edges().size());
    }

    @Test void sealedCavityIsIgnored(){
        var hollow=cuboid(0,0,0,4,4,4);
        hollow.removeAll(cuboid(1,1,1,3,3,3));
        var solid=SelectionOutline.of(cuboid(0,0,0,4,4,4));
        var r=SelectionOutline.of(hollow);
        assertEquals(solid.edges().size(),r.edges().size(),"a cavity with no way out must not get its own outline");
        assertEquals(12,r.edges().size());
        assertEquals(6,r.faces().size());
    }

    @Test void openingIntoACavityIsOutlined(){
        var open=cuboid(0,0,0,4,4,4);
        open.removeAll(cuboid(1,1,1,3,3,3));
        open.remove(new Point(2,2,0)); // a hole through the wall, so the cavity is no longer sealed
        var r=SelectionOutline.of(open);
        assertTrue(r.edges().size()>12,"a hole into the cavity is part of the outside and must be outlined");
    }

    @Test void separateBlocksKeepSeparateOutlines(){
        var r=SelectionOutline.of(Set.of(new Point(0,0,0),new Point(10,0,0)));
        assertEquals(24,r.edges().size());
        assertEquals(12,r.faces().size());
    }

    @Test void staircaseKeepsItsCreasesButNotItsTreads(){
        // Columns of height 1, 2 and 3, one block deep. The side profile has 8 corners, so the outline is
        // 8 edges on the front, 8 on the back and 8 joining them.
        var stairs=new HashSet<Point>();
        for(int x=0;x<3;x++)stairs.addAll(cuboid(x,0,0,x,x,0));
        var r=SelectionOutline.of(stairs);
        assertEquals(24,r.edges().size());
        assertTrue(r.edges().stream().noneMatch(s->s.x0()==s.x1()&&s.y0()==s.y1()&&s.z0()==s.z1()),"no degenerate segments");
    }
}
