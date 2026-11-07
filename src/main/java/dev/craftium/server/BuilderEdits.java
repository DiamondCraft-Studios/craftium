package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import java.util.*;

public final class BuilderEdits {
    private BuilderEdits(){}
    static Session.Edit plan(ServerPlayer player,Session session,Options o,Point target,Point normal,String action){
        o.keepExisting=o.builderKeepExisting; // the Builder has its own Keep existing, so the editor's does not apply to it
        var p=new EditPlanner(player.level(),session,o);p.builder=true;
        if(action.equals("builder_extrude")){o.tool=Tool.EXTRUDE;o.extrudeCount=1;AdvancedTools.handle(p,target,normal);return p.finish(o.invert?"Shrink face":"Extrude face");}
        if(action.equals("builder_erase_connected")){
            var todo=new ArrayDeque<Point>();var seen=new HashSet<Point>();todo.add(target);BlockState base=p.state(target);int count=0;
            while(!todo.isEmpty()&&count<128){Point at=todo.removeFirst();if(!seen.add(at)||!p.state(at).is(base.getBlock())||base.isAir())continue;p.put(at,Blocks.AIR.defaultBlockState());count++;for(Point n:EditPlanner.NEIGHBOURS)todo.add(at.add(n.x(),n.y(),n.z()));}return p.finish("Erase connected blocks");
        }
        Collection<Point> points=cells(o);Point min=new Point(Math.min(o.position1.x(),o.position2.x()),Math.min(o.position1.y(),o.position2.y()),Math.min(o.position1.z(),o.position2.z()));Point size=new Point(Math.abs(o.position2.x()-o.position1.x())+1,Math.abs(o.position2.y()-o.position1.y())+1,Math.abs(o.position2.z()-o.position1.z())+1);
        if(o.builderTool==Options.BuilderTool.MOVE||o.builderTool==Options.BuilderTool.CLONE||o.builderTool==Options.BuilderTool.STACK||o.builderTool==Options.BuilderTool.SMEAR){
            // The box and where its copies land may be far from every player: lease their chunks until the edit is done. A rotated copy fits in a square of its longer side.
            int reach=Math.max(size.x(),size.z());var area=ChunkLease.area().points(points,0,0);
            if(o.builderTool==Options.BuilderTool.STACK){
                int sx=o.stackX,sz=o.stackZ;
                if(((long)Math.abs(sx)+1)*((long)Math.abs(o.stackY)+1)*((long)Math.abs(sz)+1)-1>Selection.LIMIT/Math.max(1,points.size()))throw new IllegalArgumentException("Builder stack exceeds the cell limit");
                area.box(min.x()+Math.min(0,sx)*size.x(),min.z()+Math.min(0,sz)*size.z(),min.x()+Math.max(0,sx)*size.x()+reach-1,min.z()+Math.max(0,sz)*size.z()+reach-1);
            }else{
                int ox=o.builderOffset.x(),oz=o.builderOffset.z();
                if(o.builderTool==Options.BuilderTool.SMEAR)area.box(min.x()+Math.min(0,ox),min.z()+Math.min(0,oz),min.x()+Math.max(0,ox)+size.x()-1,min.z()+Math.max(0,oz)+size.z()-1);
                else area.box(min.x()+ox,min.z()+oz,min.x()+ox+reach-1,min.z()+oz+reach-1);
            }
            ChunkLease.planning(area.lease(player.level()));
        }
        var source=new LinkedHashMap<Point,BlockSnapshot>();Point shift=o.builderOffset;for(Point at:points){p.validate(at);source.put(at,BlockSnapshot.capture(player.level(),EditPlanner.pos(at)));}
        if(o.builderTool==Options.BuilderTool.ERASE){for(Point at:points)p.put(at,Blocks.AIR.defaultBlockState());return p.finish("Erase builder selection");}
        if(o.builderTool==Options.BuilderTool.SMEAR){var line=BrushMath.line(new Point(0,0,0),shift);for(var e:source.entrySet())if(!e.getValue().state().isAir())for(int i=1;i<line.size();i++){Point delta=line.get(i),at=e.getKey().add(delta.x(),delta.y(),delta.z());if(!source.containsKey(at)&&p.solid(at))break;p.put(at,e.getValue());}return p.finish("Smear builder selection");}
        if(o.builderTool!=Options.BuilderTool.MOVE&&o.builderTool!=Options.BuilderTool.CLONE&&o.builderTool!=Options.BuilderTool.STACK)throw new IllegalArgumentException("Choose a selection builder tool");
        var entities=new ArrayList<Session.EntityChange>();var captureSession=new Session();captureSession.selection.combine(points,Selection.Mode.REPLACE);
        if(o.copyEntities){var capture=ClipboardEntities.capture(player,captureSession);captureSession.clipboardEntities.addAll(capture.relative());if(o.builderTool==Options.BuilderTool.MOVE)entities.addAll(capture.removals());}
        if(o.builderTool==Options.BuilderTool.MOVE){boolean keep=o.keepExisting;o.keepExisting=false;for(Point at:points)p.put(at,Blocks.AIR.defaultBlockState());o.keepExisting=keep;}
        for(var entry:source.entrySet()){Point q=entry.getKey().add(-min.x(),-min.y(),-min.z());Point transformed=BuilderTransform.apply(q,size,o.builderRotation,o.builderFlipAxis);var value=entry.getValue();BlockState state=value.state();state=turn(state,o.builderRotation,o.builderFlipAxis);captureSession.clipboard.put(transformed,new BlockSnapshot(state,value.data()));}
        ClipboardEntities.transform(captureSession,v->{var relative=new Geometry.Vector(v.x()-.5,v.y()-.5,v.z()-.5);Point base=relative.voxel();Point transformed=BuilderTransform.apply(base,size,o.builderRotation,o.builderFlipAxis);var delta=relative.subtract(Geometry.vector(base));if(o.builderFlipAxis==0)delta=new Geometry.Vector(-delta.x(),delta.y(),delta.z());if(o.builderFlipAxis==1)delta=new Geometry.Vector(delta.x(),-delta.y(),delta.z());if(o.builderFlipAxis==2)delta=new Geometry.Vector(delta.x(),delta.y(),-delta.z());delta=Geometry.rotate(delta,o.builderRotation*90,0,0);return Geometry.vector(transformed).add(delta).add(new Geometry.Vector(.5,.5,.5));},o.builderRotation*90,o.builderFlipAxis>=0);
        // A stack is whole copies laid edge to edge along each axis, so there are never gaps; the counts say how many in each direction (negative: the other way).
        var steps=new ArrayList<Point>();
        if(o.builderTool==Options.BuilderTool.STACK){
            int sx=o.stackX,sy=o.stackY,sz=o.stackZ; // no limit on how far, only on how many blocks: counted before any copy is laid out
            if(((long)Math.abs(sx)+1)*((long)Math.abs(sy)+1)*((long)Math.abs(sz)+1)-1>Selection.LIMIT/Math.max(1,source.size()))throw new IllegalArgumentException("Builder stack exceeds the cell limit");
            for(int i=Math.min(0,sx);i<=Math.max(0,sx);i++)for(int j=Math.min(0,sy);j<=Math.max(0,sy);j++)for(int k=Math.min(0,sz);k<=Math.max(0,sz);k++)if(i!=0||j!=0||k!=0)steps.add(new Point(i*size.x(),j*size.y(),k*size.z()));
            if(steps.isEmpty())throw new IllegalArgumentException("Scroll to choose how many copies to stack");
        }else steps.add(shift);
        if((long)source.size()*steps.size()>Selection.LIMIT)throw new IllegalArgumentException("Builder stack exceeds the cell limit");
        for(Point step:steps){Point origin=min.add(step.x(),step.y(),step.z());for(var e:captureSession.clipboard.entrySet()){if(!o.pasteAir&&e.getValue().state().isAir())continue;Point q=e.getKey();p.putClipboard(origin.add(q.x(),q.y(),q.z()),e.getValue());}if(o.copyEntities)entities.addAll(ClipboardEntities.paste(player,captureSession,origin));}
        if(entities.size()>128)throw new IllegalArgumentException("Builder entity limit: 128 changes");var edit=p.finish(o.builderTool.name().toLowerCase(Locale.ROOT)+" builder selection");
        // The builder's box lives on the client, so undo and redo hand it back there: a move takes it along with the blocks, a stack grows it.
        Point max=new Point(min.x()+size.x()-1,min.y()+size.y()-1,min.z()+size.z()-1);String before=box(min,max),after=null;
        if(o.builderTool==Options.BuilderTool.MOVE){
            Point a=BuilderTransform.apply(new Point(0,0,0),size,o.builderRotation,o.builderFlipAxis),b=BuilderTransform.apply(new Point(size.x()-1,size.y()-1,size.z()-1),size,o.builderRotation,o.builderFlipAxis);
            Point landing=min.add(o.builderOffset.x(),o.builderOffset.y(),o.builderOffset.z());
            after=box(new Point(Math.min(a.x(),b.x())+landing.x(),Math.min(a.y(),b.y())+landing.y(),Math.min(a.z(),b.z())+landing.z()),new Point(Math.max(a.x(),b.x())+landing.x(),Math.max(a.y(),b.y())+landing.y(),Math.max(a.z(),b.z())+landing.z()));
        }else if(o.builderTool==Options.BuilderTool.STACK){
            int sx=Math.clamp(o.stackX,-16,16),sy=Math.clamp(o.stackY,-16,16),sz=Math.clamp(o.stackZ,-16,16);
            after=box(new Point(min.x()+Math.min(0,sx)*size.x(),min.y()+Math.min(0,sy)*size.y(),min.z()+Math.min(0,sz)*size.z()),new Point(max.x()+Math.max(0,sx)*size.x(),max.y()+Math.max(0,sy)*size.y(),max.z()+Math.max(0,sz)*size.z()));
        }
        var result=new Session.Edit(edit.name(),edit.changes(),edit.biomes(),edit.flags(),List.copyOf(entities),List.of());
        return after==null?result:result.withSelection(Session.SelectionChange.restoring(Map.of("box",before),Map.of("box",after)));
    }
    /** The blocks the builder works on: the ones the client sent when the selection is not a plain box, each inside the corners it also sent, or else the whole box. */
    private static Collection<Point> cells(Options o){
        if(o.builderCells.isBlank())return Selection.box(o.position1,o.position2);
        var cells=SelectionCodec.decode(o.builderCells);
        int x0=Math.min(o.position1.x(),o.position2.x()),y0=Math.min(o.position1.y(),o.position2.y()),z0=Math.min(o.position1.z(),o.position2.z());
        int x1=Math.max(o.position1.x(),o.position2.x()),y1=Math.max(o.position1.y(),o.position2.y()),z1=Math.max(o.position1.z(),o.position2.z());
        for(Point at:cells)if(at.x()<x0||at.x()>x1||at.y()<y0||at.y()>y1||at.z()<z0||at.z()>z1)throw new IllegalArgumentException("Selected blocks lie outside the selection box");
        return cells;
    }
    private static String box(Point a,Point b){return a.x()+","+a.y()+","+a.z()+","+b.x()+","+b.y()+","+b.z();}
    /** A block's state after the builder's flip (axis 0, 1 or 2, or -1 for none) and quarter turns, so stairs and the like face the way they will land. The preview shares this. */
    public static BlockState turn(BlockState state,int turns,int flipAxis){
        if(flipAxis==0)state=state.mirror(Mirror.FRONT_BACK);else if(flipAxis==2)state=state.mirror(Mirror.LEFT_RIGHT);else if(flipAxis==1)state=verticalMirror(state);
        return state.rotate(Rotation.values()[Math.floorMod(turns,4)]);
    }
    static BlockState verticalMirror(BlockState state){
        state=StateTransforms.flipHalf(state);
        if(state.hasProperty(BlockStateProperties.FACING)&&state.getValue(BlockStateProperties.FACING).getAxis()==net.minecraft.core.Direction.Axis.Y)state=state.setValue(BlockStateProperties.FACING,state.getValue(BlockStateProperties.FACING).getOpposite());return state;
    }
}
