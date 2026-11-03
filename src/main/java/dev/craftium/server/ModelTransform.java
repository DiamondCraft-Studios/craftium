package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/**
 * Moves, rotates and scales the blocks of the selection around a pivot, as the Move, Rotate and Scale tools do.
 * Every cell the transformed shape covers looks back to the cell it came from, so rotated and scaled shapes have
 * no holes; the cells left behind are cleared. The blocks themselves are not turned, only where they are.
 */
final class ModelTransform {
    /** The most cells a transform may cover, so one request cannot be asked to visit a huge volume. */
    private static final long CELL_LIMIT=2_000_000L;

    private ModelTransform(){}

    /** Queues the changes in {@code plan} and returns the cells the blocks end up in. */
    static LinkedHashSet<Point> apply(EditPlanner plan,Session session,Options o){
        var selection=session.selection;
        plan.exact=true;
        if(selection.size()==0)throw new IllegalArgumentException("Create a selection first");
        boolean identity=o.offsetX==0&&o.offsetY==0&&o.offsetZ==0&&o.yaw==0&&o.pitch==0&&o.roll==0&&o.sizeX==1&&o.sizeY==1&&o.sizeZ==1;
        if(identity)throw new IllegalArgumentException("Nothing to transform");
        double sx=Math.max(.05,o.sizeX),sy=Math.max(.05,o.sizeY),sz=Math.max(.05,o.sizeZ);

        var source=new LinkedHashMap<Point,BlockSnapshot>();
        for(Point at:selection.points()){plan.validate(at);source.put(at,BlockSnapshot.capture(plan.level,EditPlanner.pos(at)));}
        Point min=selection.min(),max=selection.max();
        var pivot=o.aboutSet?new Geometry.Vector(o.aboutX,o.aboutY,o.aboutZ):o.pivotSet?new Geometry.Vector(o.pivotX,o.pivotY,o.pivotZ)
            :new Geometry.Vector((min.x()+max.x()+1)/2d,(min.y()+max.y()+1)/2d,(min.z()+max.z()+1)/2d);

        var landed=place(source,min,max,pivot,sx,sy,sz,o);
        // A quarter turn about a point that is not on the right half-block loses cells: their centres fall on the
        // edges between blocks. Try pivots half a block away, nearest first, and keep the first that loses none.
        if(landed.size()<source.size()&&quarterTurns(o)&&sx==1&&sy==1&&sz==1){
            for(var shift:SHIFTS){
                var nudged=new Geometry.Vector(pivot.x()+shift[0],pivot.y()+shift[1],pivot.z()+shift[2]);
                var trial=place(source,min,max,nudged,sx,sy,sz,o);
                if(trial.size()>=source.size()){landed=trial;break;}
            }
        }
        if(landed.size()>Selection.LIMIT)throw new IllegalArgumentException("Selection exceeds limit");

        for(Point at:source.keySet())if(!landed.containsKey(at))plan.put(at,Blocks.AIR.defaultBlockState());
        for(var entry:landed.entrySet()){plan.validate(entry.getKey());plan.put(entry.getKey(),entry.getValue());}
        return new LinkedHashSet<>(landed.keySet());
    }

    /** Pivot shifts to try, smallest first. */
    private static final double[][] SHIFTS;
    static{
        var list=new ArrayList<double[]>();
        for(double x:new double[]{0,.5,-.5})for(double y:new double[]{0,.5,-.5})for(double z:new double[]{0,.5,-.5})if(x!=0||y!=0||z!=0)list.add(new double[]{x,y,z});
        list.sort(Comparator.comparingDouble(d->d[0]*d[0]+d[1]*d[1]+d[2]*d[2]));
        SHIFTS=list.toArray(new double[0][]);
    }

    private static boolean quarterTurns(Options o){
        for(double a:new double[]{o.yaw,o.pitch,o.roll})if(Math.abs(a/90-Math.rint(a/90))>1e-9)return false;
        return true;
    }

    /** Every cell the transformed shape covers, each with the block that lands there. */
    private static LinkedHashMap<Point,BlockSnapshot> place(Map<Point,BlockSnapshot> source,Point min,Point max,Geometry.Vector pivot,double sx,double sy,double sz,Options o){
        // Where the corners of the selection's box go, to know which cells to look at.
        double lx=Double.POSITIVE_INFINITY,ly=lx,lz=lx,hx=-lx,hy=-lx,hz=-lx;
        for(double x:new double[]{min.x(),max.x()+1})for(double y:new double[]{min.y(),max.y()+1})for(double z:new double[]{min.z(),max.z()+1}){
            var v=forward(new Geometry.Vector(x,y,z),pivot,sx,sy,sz,o);
            lx=Math.min(lx,v.x());ly=Math.min(ly,v.y());lz=Math.min(lz,v.z());hx=Math.max(hx,v.x());hy=Math.max(hy,v.y());hz=Math.max(hz,v.z());
        }
        int x0=(int)Math.floor(lx+1e-9),y0=(int)Math.floor(ly+1e-9),z0=(int)Math.floor(lz+1e-9);
        int x1=(int)Math.ceil(hx-1e-9),y1=(int)Math.ceil(hy-1e-9),z1=(int)Math.ceil(hz-1e-9);
        if((long)(x1-x0)*(y1-y0)*(z1-z0)>CELL_LIMIT)throw new IllegalArgumentException("That transform covers too large a volume");
        var landed=new LinkedHashMap<Point,BlockSnapshot>();
        for(int x=x0;x<x1;x++)for(int y=y0;y<y1;y++)for(int z=z0;z<z1;z++){
            var from=backward(new Geometry.Vector(x+.5,y+.5,z+.5),pivot,sx,sy,sz,o);
            var block=source.get(new Point((int)Math.floor(from.x()+1e-9),(int)Math.floor(from.y()+1e-9),(int)Math.floor(from.z()+1e-9)));
            if(block!=null)landed.put(new Point(x,y,z),block);
        }
        return landed;
    }

    /** Where a point goes: scaled, then rotated, about the pivot, then moved. */
    private static Geometry.Vector forward(Geometry.Vector p,Geometry.Vector pivot,double sx,double sy,double sz,Options o){
        var r=Geometry.rotate(new Geometry.Vector((p.x()-pivot.x())*sx,(p.y()-pivot.y())*sy,(p.z()-pivot.z())*sz),o.yaw,o.pitch,o.roll);
        return new Geometry.Vector(pivot.x()+r.x()+o.offsetX,pivot.y()+r.y()+o.offsetY,pivot.z()+r.z()+o.offsetZ);
    }

    /** Where a point came from; the reverse of {@link #forward}. */
    private static Geometry.Vector backward(Geometry.Vector p,Geometry.Vector pivot,double sx,double sy,double sz,Options o){
        var r=Geometry.inverseRotate(new Geometry.Vector(p.x()-o.offsetX-pivot.x(),p.y()-o.offsetY-pivot.y(),p.z()-o.offsetZ-pivot.z()),o.yaw,o.pitch,o.roll);
        return new Geometry.Vector(pivot.x()+r.x()/sx,pivot.y()+r.y()/sy,pivot.z()+r.z()/sz);
    }
}
