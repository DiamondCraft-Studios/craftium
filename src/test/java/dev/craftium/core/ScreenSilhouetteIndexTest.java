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

    private static List<List<Long>> sorted(List<ScreenSilhouette.Line> lines,double dx,double dy,double dz){
        var out=new ArrayList<List<Long>>();
        for(var l:lines){
            long[] a={Math.round((l.x0()+dx)*1e5),Math.round((l.y0()+dy)*1e5),Math.round((l.z0()+dz)*1e5)},b={Math.round((l.x1()+dx)*1e5),Math.round((l.y1()+dy)*1e5),Math.round((l.z1()+dz)*1e5)};
            boolean swap=a[0]!=b[0]?a[0]>b[0]:a[1]!=b[1]?a[1]>b[1]:a[2]>b[2]; // a line has no direction
            out.add(swap?List.of(b[0],b[1],b[2],a[0],a[1],a[2]):List.of(a[0],a[1],a[2],b[0],b[1],b[2]));
        }
        out.sort(Comparator.comparing(Object::toString));
        return out;
    }

    /**
     * Dragging a selection draws its outline unmoved and then moves the lines. The contour the camera sees of the moved shape is the contour of the
     * unmoved shape seen from the camera taken back by the move, with the lines moved. The contour seen from the camera as it is, which is what was
     * drawn before, is a different set of lines, and moved it leaves ends that do not meet.
     */
    @Test void aMovedShapeHasTheContourOfTheUnmovedOneSeenFromTheCameraTakenBack(){
        var rnd=new Random(3);
        int differing=0,checked=0;
        for(int n=0;n<40;n++){
            var blocks=blob(rnd,3+rnd.nextInt(6),60+rnd.nextInt(300));
            int mx=rnd.nextInt(60)-30,my=rnd.nextInt(60)-30,mz=rnd.nextInt(60)-30;
            var moved=new HashSet<Point>();for(var p:blocks)moved.add(new Point(p.x()+mx,p.y()+my,p.z()+mz));
            var here=SelectionOutline.of(blocks);var there=SelectionOutline.of(moved);
            double ex=(rnd.nextDouble()-.5)*120,ey=(rnd.nextDouble()-.5)*120,ez=(rnd.nextDouble()-.5)*120;
            var truth=ScreenSilhouette.of(there,ex,ey,ez);
            var fromTakenBack=ScreenSilhouette.of(here,ex-mx,ey-my,ez-mz);
            var fromCamera=ScreenSilhouette.of(here,ex,ey,ez);
            if(truth==null||fromTakenBack==null||fromCamera==null)continue;
            checked++;
            assertEquals(sorted(truth,0,0,0),sorted(fromTakenBack,mx,my,mz),"shape "+n);
            if(!sorted(truth,0,0,0).equals(sorted(fromCamera,mx,my,mz)))differing++;
        }
        assertTrue(checked>15,"too few cases had a contour: "+checked);
        assertTrue(differing>checked/2,"the premise: the contour seen from the unmoved position should usually be wrong for the moved shape ("+differing+" of "+checked+")");
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
