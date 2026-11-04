package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModifyMathTest {
    private static Options options(Options.ModifyMode mode){var o=new Options();o.modifyMode=mode;return o;}

    @Test void relativeOffsetsAreMultipliedBySelectionSize(){
        var o=options(Options.ModifyMode.TRANSLATE_COPIES);o.copies=2;o.offsetX=1;o.offsetY=0;o.offsetZ=0;
        var cells=List.of(new Point(0,0,0),new Point(2,0,0)); // three blocks wide
        var out=ModifyMath.plan(o,cells,new Point(0,0,0),new Point(2,0,0),null);
        assertTrue(out.containsKey(new Point(3,0,0)));assertTrue(out.containsKey(new Point(6,0,0)));
        o.modifyRelative=false;
        out=ModifyMath.plan(o,cells,new Point(0,0,0),new Point(2,0,0),null);
        assertTrue(out.containsKey(new Point(1,0,0)));assertTrue(out.containsKey(new Point(4,0,0)));
    }

    @Test void fullRevolveReturnsToTheStart(){
        var o=options(Options.ModifyMode.REVOLVE);o.modifyAxis=Options.ModifyAxis.Y;o.modifyAngle=360;
        var out=ModifyMath.plan(o,List.of(new Point(4,0,0)),new Point(4,0,0),new Point(4,0,0),new Point(0,0,0));
        assertTrue(out.containsKey(new Point(4,0,0)));assertTrue(out.containsKey(new Point(-4,0,0)));assertTrue(out.containsKey(new Point(0,0,4)));
        assertTrue(out.keySet().stream().allMatch(p->p.y()==0));
    }

    @Test void revolveNeedsACursor(){
        var o=options(Options.ModifyMode.REVOLVE);
        assertTrue(ModifyMath.plan(o,List.of(new Point(1,0,0)),new Point(1,0,0),new Point(1,0,0),null).isEmpty());
    }

    @Test void equidistantCopiesShareTheCircleWithTheOriginal(){
        var o=options(Options.ModifyMode.ROTATE_COPIES);o.copies=3;o.modifyAxis=Options.ModifyAxis.Y;
        var out=ModifyMath.plan(o,List.of(new Point(5,0,0)),new Point(5,0,0),new Point(5,0,0),new Point(0,0,0));
        assertTrue(out.containsKey(new Point(0,0,5)));assertTrue(out.containsKey(new Point(-5,0,0)));assertTrue(out.containsKey(new Point(0,0,-5)));
        assertEquals(3,out.size());
    }

    @Test void twistKeepsTheBottomAndTurnsTheTop(){
        var o=options(Options.ModifyMode.TWIST);o.modifyAxis=Options.ModifyAxis.Y;o.modifyAngle=90;
        var cells=new java.util.ArrayList<Point>();for(int y=0;y<=4;y++)for(int x=-2;x<=2;x++)cells.add(new Point(x,y,0));
        var out=ModifyMath.plan(o,cells,new Point(-2,0,0),new Point(2,4,0),null);
        assertTrue(out.containsKey(new Point(2,0,0)));
        assertTrue(out.containsKey(new Point(0,4,2))||out.containsKey(new Point(0,4,-2)));
    }
}
