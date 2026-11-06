package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class GeometryUntransformTest {
    /** The drag's transform as the editor applies it: scale about a point, turn, put back, then move. */
    private static Geometry.Vector forward(Geometry.Vector p,Geometry.Vector about,Geometry.Vector size,double yaw,double pitch,double roll,Geometry.Vector offset){
        var rel=p.subtract(about);
        var turned=Geometry.rotate(new Geometry.Vector(rel.x()*size.x(),rel.y()*size.y(),rel.z()*size.z()),yaw,pitch,roll);
        return new Geometry.Vector(about.x()+turned.x()+offset.x(),about.y()+turned.y()+offset.y(),about.z()+turned.z()+offset.z());
    }

    @Test void undoesAnyCombinationOfScaleTurnAndMove(){
        var rnd=new Random(5);
        for(int i=0;i<2_000;i++){
            var p=new Geometry.Vector(rnd.nextDouble()*100-50,rnd.nextDouble()*100-50,rnd.nextDouble()*100-50);
            var about=new Geometry.Vector(rnd.nextInt(40)-20,rnd.nextInt(40)-20,rnd.nextInt(40)-20);
            var size=new Geometry.Vector(.2+rnd.nextDouble()*4,.2+rnd.nextDouble()*4,(rnd.nextBoolean()?-1:1)*(.2+rnd.nextDouble()*4)); // a mirror too
            double yaw=rnd.nextDouble()*720-360,pitch=rnd.nextDouble()*720-360,roll=rnd.nextDouble()*720-360;
            var offset=new Geometry.Vector(rnd.nextInt(60)-30,rnd.nextInt(60)-30,rnd.nextInt(60)-30);
            var back=Geometry.untransform(forward(p,about,size,yaw,pitch,roll,offset),about,size,yaw,pitch,roll,offset);
            assertEquals(p.x(),back.x(),1e-6);assertEquals(p.y(),back.y(),1e-6);assertEquals(p.z(),back.z(),1e-6);
        }
    }

    @Test void aPlainMoveIsJustTheOffsetTakenAway(){
        var back=Geometry.untransform(new Geometry.Vector(13,5,-4),new Geometry.Vector(0,0,0),new Geometry.Vector(1,1,1),0,0,0,new Geometry.Vector(10,2,-4));
        assertEquals(3,back.x(),1e-12);assertEquals(3,back.y(),1e-12);assertEquals(0,back.z(),1e-12);
    }

    @Test void aShapeSquashedFlatCannotBeUndone(){
        assertNull(Geometry.untransform(new Geometry.Vector(1,1,1),new Geometry.Vector(0,0,0),new Geometry.Vector(1,0,1),0,0,0,new Geometry.Vector(0,0,0)));
    }
}
