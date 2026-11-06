package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScreenSilhouetteIndexTest {
    private static Set<Point> blob(Random rnd,int size,int blocks){
        var s=new HashSet<Point>();
        int x=0,y=0,z=0;
        for(int i=0;i<blocks;i++){
            s.add(new Point(x,y,z));
            switch(rnd.nextInt(6)){case 0->x++;case 1->x--;case 2->y++;case 3->y--;case 4->z++;default->z--;}
            x=Math.max(-size,Math.min(size,x));y=Math.max(-size,Math.min(size,y));z=Math.max(-size,Math.min(size,z));
        }
        return s;
    }

    /** The two classes have their own Line types, so compare the numbers in them. */
    private static List<List<Double>> numbers(List<?> lines){
        if(lines==null)return null;
        var out=new ArrayList<List<Double>>();
        for(Object l:lines){
            if(l instanceof ScreenSilhouette.Line a)out.add(List.of(a.x0(),a.y0(),a.z0(),a.x1(),a.y1(),a.z1()));
            else if(l instanceof ReferenceSilhouette.Line b)out.add(List.of(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1()));
        }
        return out;
    }

    /** The grid and the sweep only decide which faces are looked at, so the contour must be exactly the one the plain scan of every face finds. */
    @Test void findsExactlyTheContourTheScanOfEveryFaceFinds(){
        var rnd=new Random(11);
        int compared=0;
        for(int n=0;n<60;n++){
            var outline=SelectionOutline.of(blob(rnd,2+rnd.nextInt(8),20+rnd.nextInt(400)));
            for(int e=0;e<4;e++){
                double ex=(rnd.nextDouble()-.5)*80,ey=(rnd.nextDouble()-.5)*80,ez=(rnd.nextDouble()-.5)*80;
                var fast=ScreenSilhouette.of(outline,ex,ey,ez);
                var plain=ReferenceSilhouette.of(outline,ex,ey,ez);
                assertEquals(numbers(plain),numbers(fast),"shape "+n+", eye "+ex+","+ey+","+ez);
                var indexed=fast;
                if(indexed!=null)compared++;
            }
        }
        assertTrue(compared>100,"too few of the cases had a contour to compare: "+compared);
    }

    /** A hundred thousand blocks of rolling ground: far too many segment-and-face pairs for the plain scan, which gave up on it. */
    @Test void aLargeSelectionNowHasAContour(){
        var rnd=new Random(2);var blocks=new HashSet<Point>();
        for(int x=0;x<100;x++)for(int z=0;z<100;z++){int h=8+(int)(6*Math.sin(x/9.0)+6*Math.cos(z/7.0)+rnd.nextInt(3));for(int y=0;y<h;y++)blocks.add(new Point(x,y,z));}
        var outline=SelectionOutline.of(blocks);
        double ex=130,ey=60,ez=140;
        assertNull(ReferenceSilhouette.of(outline,ex,ey,ez),"the premise: the original algorithm gave up on this one");
        var lines=ScreenSilhouette.of(outline,ex,ey,ez);
        assertNotNull(lines);assertFalse(lines.isEmpty());
        for(var l:lines){
            assertTrue(l.x0()>=0&&l.x0()<=100&&l.x1()>=0&&l.x1()<=100&&l.z0()>=0&&l.z0()<=100&&l.z1()>=0&&l.z1()<=100,"a contour line outside the selection: "+l);
        }
    }
}
