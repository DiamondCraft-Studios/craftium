package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
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

    /** One transform: its options, the pivot it works about, and its sizes with the flips kept as signs. */
    record Step(Options o,Geometry.Vector pivot,double sx,double sy,double sz){
        Step withPivot(Geometry.Vector next){return new Step(o,next,sx,sy,sz);}
    }
    /**
     * The blocks as they were before a run of transforms that are waiting for confirmation, and the transforms so far. A further transform
     * is worked out from these original blocks through all of the steps in one go, so the blocks are sampled once and turning
     * or scaling again and again never builds on a shape that has already been resampled.
     */
    record Chain(Map<Point,BlockSnapshot> source,Point min,Point max,List<Step> steps){}
    record Result(LinkedHashSet<Point> moved,Chain chain){}

    /**
     * Queues the changes in {@code plan} and returns the cells the blocks end up in, with the chain that made them. With a {@code prior}
     * chain the blocks come from its original source, and {@code plan} is expected to hold only what was staged before that chain.
     * Blocks staged but not yet built count as the blocks that are there.
     */
    static Result apply(EditPlanner plan,Session session,Options o,Chain prior){
        var selection=session.selection;
        plan.exact=true;
        if(selection.size()==0)throw new IllegalArgumentException("Create a selection first");
        boolean identity=o.offsetX==0&&o.offsetY==0&&o.offsetZ==0&&o.yaw==0&&o.pitch==0&&o.roll==0&&o.sizeX==1&&o.sizeY==1&&o.sizeZ==1;
        if(identity)throw new IllegalArgumentException("Nothing to transform");
        // A negative size flips the blocks along that axis.
        double sx=scale(o.sizeX),sy=scale(o.sizeY),sz=scale(o.sizeZ);

        Map<Point,BlockSnapshot> source;Point min,max;var steps=new ArrayList<Step>();
        Point now=selection.min(),end=selection.max(); // the selection as it stands, which is where the pivot is taken from
        if(prior==null){
            source=new LinkedHashMap<>();
            for(Point at:selection.points()){plan.validate(at);var staged=plan.changes.get(at);source.put(at,staged!=null?staged.after():BlockSnapshot.capture(plan.level,EditPlanner.pos(at)));}
            min=now;max=end;
        }else{source=prior.source();min=prior.min();max=prior.max();steps.addAll(prior.steps());}
        var pivot=o.aboutSet?new Geometry.Vector(o.aboutX,o.aboutY,o.aboutZ):o.pivotSet?new Geometry.Vector(o.pivotX,o.pivotY,o.pivotZ)
            :new Geometry.Vector((now.x()+end.x()+1)/2d,(now.y()+end.y()+1)/2d,(now.z()+end.z()+1)/2d);
        steps.add(new Step(o,pivot,sx,sy,sz));

        var landed=place(source,min,max,steps);
        // A quarter turn about a point that is not on the right half-block loses cells: their centres fall on the
        // edges between blocks. Try pivots half a block away, nearest first, and keep the first that loses none.
        if(landed.size()<source.size()&&steps.stream().allMatch(ModelTransform::quarterTurn)){
            for(var shift:SHIFTS){
                var trialSteps=new ArrayList<>(steps);
                trialSteps.set(trialSteps.size()-1,steps.getLast().withPivot(new Geometry.Vector(pivot.x()+shift[0],pivot.y()+shift[1],pivot.z()+shift[2])));
                var trial=place(source,min,max,trialSteps);
                if(trial.size()>=source.size()){landed=trial;steps=new ArrayList<>(trialSteps);break;}
            }
        }
        if(landed.size()>Selection.LIMIT)throw new IllegalArgumentException("Selection exceeds limit");

        for(Point at:source.keySet())if(!landed.containsKey(at))plan.put(at,Blocks.AIR.defaultBlockState());
        for(var entry:landed.entrySet()){plan.validate(entry.getKey());plan.put(entry.getKey(),entry.getValue());}
        return new Result(new LinkedHashSet<>(landed.keySet()),new Chain(source,min,max,List.copyOf(steps)));
    }

    /** Pivot shifts to try, smallest first. */
    private static final double[][] SHIFTS;
    static{
        var list=new ArrayList<double[]>();
        for(double x:new double[]{0,.5,-.5})for(double y:new double[]{0,.5,-.5})for(double z:new double[]{0,.5,-.5})if(x!=0||y!=0||z!=0)list.add(new double[]{x,y,z});
        list.sort(Comparator.comparingDouble(d->d[0]*d[0]+d[1]*d[1]+d[2]*d[2]));
        SHIFTS=list.toArray(new double[0][]);
    }

    private static double scale(double size){return (size<0?-1:1)*Math.max(.05,Math.abs(size));}

    /** The block as it stands after a turn and flips of whole quarters, for the Edit menu: stairs and the like face the new way. */
    private static BlockSnapshot turned(BlockSnapshot block,Options o){
        var state=block.state();
        if(o.sizeX<0)state=state.mirror(Mirror.FRONT_BACK);
        if(o.sizeY<0)state=BuilderEdits.verticalMirror(state);
        if(o.sizeZ<0)state=state.mirror(Mirror.LEFT_RIGHT);
        state=state.rotate(Rotation.values()[Math.floorMod((int)Math.round(o.yaw/90),4)]);
        return new BlockSnapshot(state,block.data());
    }

    private static boolean quarterTurn(Step step){
        var o=step.o();
        for(double a:new double[]{o.yaw,o.pitch,o.roll})if(Math.abs(a/90-Math.rint(a/90))>1e-9)return false;
        return step.sx()==1&&step.sy()==1&&step.sz()==1;
    }

    /** Every cell the transformed shape covers, each with the block that lands there. */
    private static LinkedHashMap<Point,BlockSnapshot> place(Map<Point,BlockSnapshot> source,Point min,Point max,List<Step> steps){
        // Where the corners of the original box go, to know which cells to look at.
        double lx=Double.POSITIVE_INFINITY,ly=lx,lz=lx,hx=-lx,hy=-lx,hz=-lx;
        for(double x:new double[]{min.x(),max.x()+1})for(double y:new double[]{min.y(),max.y()+1})for(double z:new double[]{min.z(),max.z()+1}){
            var v=new Geometry.Vector(x,y,z);
            for(var step:steps)v=forward(v,step);
            lx=Math.min(lx,v.x());ly=Math.min(ly,v.y());lz=Math.min(lz,v.z());hx=Math.max(hx,v.x());hy=Math.max(hy,v.y());hz=Math.max(hz,v.z());
        }
        int x0=(int)Math.floor(lx+1e-9),y0=(int)Math.floor(ly+1e-9),z0=(int)Math.floor(lz+1e-9);
        int x1=(int)Math.ceil(hx-1e-9),y1=(int)Math.ceil(hy-1e-9),z1=(int)Math.ceil(hz-1e-9);
        if((long)(x1-x0)*(y1-y0)*(z1-z0)>CELL_LIMIT)throw new IllegalArgumentException("That transform covers too large a volume");
        var landed=new LinkedHashMap<Point,BlockSnapshot>();
        for(int x=x0;x<x1;x++)for(int y=y0;y<y1;y++)for(int z=z0;z<z1;z++){
            // Back through every step without rounding in between, so the original blocks are sampled once.
            var from=new Geometry.Vector(x+.5,y+.5,z+.5);
            for(int i=steps.size()-1;i>=0;i--)from=backward(from,steps.get(i));
            var block=source.get(new Point((int)Math.floor(from.x()+1e-9),(int)Math.floor(from.y()+1e-9),(int)Math.floor(from.z()+1e-9)));
            if(block!=null){for(var step:steps)if(step.o().turnBlocks)block=turned(block,step.o());landed.put(new Point(x,y,z),block);}
        }
        return landed;
    }

    /** Where a point goes: scaled, then rotated, about the pivot, then moved. */
    private static Geometry.Vector forward(Geometry.Vector p,Step step){
        var o=step.o();var pivot=step.pivot();
        var r=Geometry.rotate(new Geometry.Vector((p.x()-pivot.x())*step.sx(),(p.y()-pivot.y())*step.sy(),(p.z()-pivot.z())*step.sz()),o.yaw,o.pitch,o.roll);
        return new Geometry.Vector(pivot.x()+r.x()+o.offsetX,pivot.y()+r.y()+o.offsetY,pivot.z()+r.z()+o.offsetZ);
    }

    /** Where a point came from; the reverse of {@link #forward}. */
    private static Geometry.Vector backward(Geometry.Vector p,Step step){
        var o=step.o();var pivot=step.pivot();
        var r=Geometry.inverseRotate(new Geometry.Vector(p.x()-o.offsetX-pivot.x(),p.y()-o.offsetY-pivot.y(),p.z()-o.offsetZ-pivot.z()),o.yaw,o.pitch,o.roll);
        return new Geometry.Vector(pivot.x()+r.x()/step.sx(),pivot.y()+r.y()/step.sy(),pivot.z()+r.z()/step.sz());
    }
}
