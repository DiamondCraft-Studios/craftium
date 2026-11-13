package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DragMathTest {
    // A camera at the origin looking down +Z, x to the right and y up.
    private static final double[] RIGHT={1,0,0},UP={0,1,0},FORWARD={0,0,1};
    private static final double FOCAL=400;

    @Test void acrossAnAxisAPixelIsTheBlocksItSpans(){
        // 10 blocks away a block is FOCAL/10 = 40 pixels wide, so 40 pixels to the right is one block along +X.
        double[] s=DragMath.step(new double[]{0,0,10},new double[][]{{1,0,0}},RIGHT,UP,FORWARD,FOCAL,40,0);
        assertEquals(1,s[0],1e-9);
    }
    @Test void thePointerMovingAcrossTheAxisMovesNothing(){
        double[] s=DragMath.step(new double[]{0,0,10},new double[][]{{1,0,0}},RIGHT,UP,FORWARD,FOCAL,0,40);
        assertEquals(0,s[0],1e-9);
    }
    @Test void aStepIsWorthMoreTheFartherTheHandleIs(){
        double near=DragMath.step(new double[]{0,0,5},new double[][]{{1,0,0}},RIGHT,UP,FORWARD,FOCAL,20,0)[0];
        double far=DragMath.step(new double[]{0,0,50},new double[][]{{1,0,0}},RIGHT,UP,FORWARD,FOCAL,20,0)[0];
        assertEquals(10,far/near,1e-9);
    }
    @Test void anAxisTheCameraLooksAlongIsCappedNotWithoutEnd(){
        // The handle is dead ahead and the axis runs straight away from the camera: it is a point on the screen.
        double[] s=DragMath.step(new double[]{0,0,10},new double[][]{{0,0,1}},RIGHT,UP,FORWARD,FOCAL,5,5);
        assertTrue(Double.isFinite(s[0]));
        assertTrue(Math.abs(s[0])<=Math.hypot(5,5)*10/FOCAL+1e-9,"a pixel is worth at most distance/focal blocks, was "+s[0]);
    }
    @Test void aNearlyAlignedAxisDoesNotLeap(){
        // Just off the view's middle the axis is a few pixels long; the answer must stay inside the cap, not blow up.
        double off=Math.abs(DragMath.step(new double[]{.01,0,10},new double[][]{{0,0,1}},RIGHT,UP,FORWARD,FOCAL,5,5)[0]);
        assertTrue(off<=Math.hypot(5,5)*10/FOCAL+1e-9,"was "+off);
    }
    @Test void aHandleBehindTheCameraIsLeftAlone(){
        assertArrayEquals(new double[]{0},DragMath.step(new double[]{0,0,-3},new double[][]{{1,0,0}},RIGHT,UP,FORWARD,FOCAL,40,0),0);
    }
    @Test void aPlaneSeenSquareOnIsExactInBothAxes(){
        double[] s=DragMath.step(new double[]{0,0,10},new double[][]{{1,0,0},{0,1,0}},RIGHT,UP,FORWARD,FOCAL,40,-80);
        assertEquals(1,s[0],1e-9);assertEquals(2,s[1],1e-9); // y is up in the world and down on the screen
    }
    @Test void aPlaneSeenEdgeOnIsCappedAlongTheDirectionItCannotSee(){
        // The plane of X and Z seen from straight ahead and level: Z is along the view, so a pointer step cannot tell how far along it.
        double[] s=DragMath.step(new double[]{0,0,10},new double[][]{{1,0,0},{0,0,1}},RIGHT,UP,FORWARD,FOCAL,40,0);
        assertEquals(1,s[0],1e-6);
        assertTrue(Math.abs(s[1])<1e-6,"nothing along the view for a sideways step, was "+s[1]);
        double[] up=DragMath.step(new double[]{0,0,10},new double[][]{{1,0,0},{0,0,1}},RIGHT,UP,FORWARD,FOCAL,0,40);
        assertTrue(Double.isFinite(up[0])&&Double.isFinite(up[1]));
    }
    @Test void flyingForwardDoesNotChangeWhatThePointerDoesNotDo(){
        // Only steps are converted, so a camera that moves and a pointer that does not give no amount at all.
        assertEquals(0,DragMath.step(new double[]{0,0,3},new double[][]{{0,0,1}},RIGHT,UP,FORWARD,FOCAL,0,0)[0],0);
    }
}
