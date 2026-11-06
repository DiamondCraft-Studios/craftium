package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Clear, timed wave restoration, and cancellation share one persistent recovery entry. */
final class AnimatedRebuild {
    private record Step(Point point,double time){}
    private static final BlockSnapshot AIR=new BlockSnapshot(Blocks.AIR.defaultBlockState());
    private static final class Run {
        UUID owner;ServerLevel level;Session session;Options options;Session.Edit recovery;
        LinkedHashMap<Point,BlockSnapshot> original=new LinkedHashMap<>(),removed=new LinkedHashMap<>();
        Set<Point> restored=new HashSet<>();List<Point> clear;List<Step> order;List<Point> rollback;
        int clearCursor,restoreCursor,rollbackCursor,ticks;boolean clearing=true,cancelled;
    }
    private static Run run;
    private AnimatedRebuild(){}
    static boolean active(){return run!=null;}
    static boolean owned(UUID id){return run!=null&&run.owner.equals(id);}
    static void clear(){run=null;}
    static void start(ServerPlayer player,Session session,Options options){
        if(run!=null)throw new IllegalArgumentException("An animated rebuild is already running");if(session.selection.size()==0)throw new IllegalArgumentException("Create a selection first");
        var next=new Run();next.owner=player.getUUID();next.level=player.level();next.session=session;next.options=options;var plan=new EditPlanner(next.level,session,options);var recovery=new ArrayList<Session.Change>();
        for(Point p:session.selection.points()){plan.validate(p);var snapshot=BlockSnapshot.capture(next.level,EditPlanner.pos(p));next.original.put(p,snapshot);if(!snapshot.state().isAir())recovery.add(new Session.Change(p,snapshot,AIR));}
        if(recovery.isEmpty())throw new IllegalArgumentException("Select a structure containing blocks");next.recovery=new Session.Edit("Animated rebuild recovery",List.copyOf(recovery),List.of(),2|16);
        if(next.recovery.estimatedBytes()>Selection.HISTORY_BYTES)throw new IllegalArgumentException("Rebuild exceeds the history budget");
        EditProtection.check(player,next.recovery);
        next.order=schedule(next);next.clear=new ArrayList<>(next.original.keySet());session.remember(next.recovery);HistoryStore.save(session);HistoryStore.flush();session.busy=true;run=next;EditorServer.send(player,session,"Animated rebuild preparing · Ctrl+Z cancels and restores the structure",false);
    }
    private static List<Step> schedule(Run r){
        var cost=new HashMap<Point,Double>();var queue=new PriorityQueue<Step>(Comparator.comparingDouble(Step::time));var points=new ArrayList<>(r.original.keySet());var min=r.session.selection.min();var max=r.session.selection.max();Point center=new Point((min.x()+max.x())/2,(min.y()+max.y())/2,(min.z()+max.z())/2);
        for(var e:r.original.entrySet())if(e.getValue().state().is(Blocks.STRUCTURE_BLOCK)){cost.put(e.getKey(),0d);queue.add(new Step(e.getKey(),0));}
        if(queue.isEmpty()){Point start=points.stream().min(Comparator.comparingDouble(p->p.distanceSquared(center))).orElseThrow();cost.put(start,0d);queue.add(new Step(start,0));}
        int seedCursor=0;var settled=new HashSet<Point>();var ordered=new ArrayList<Step>();
        while(settled.size()<points.size()){
            if(queue.isEmpty()){while(seedCursor<points.size()&&settled.contains(points.get(seedCursor)))seedCursor++;Point p=points.get(seedCursor);double initial=Math.sqrt(p.distanceSquared(center))*r.options.rebuildDifferentDelay*20;cost.put(p,initial);queue.add(new Step(p,initial));}
            Step at=queue.remove();if(!settled.add(at.point))continue;ordered.add(at);
            for(Point direction:EditPlanner.NEIGHBOURS){Point p=at.point.add(direction.x(),direction.y(),direction.z());var value=r.original.get(p);if(value==null||settled.contains(p))continue;double delay=value.state().isAir()?r.options.rebuildAirDelay:value.state().is(r.original.get(at.point).state().getBlock())?r.options.rebuildSameDelay:r.options.rebuildDifferentDelay;
                delay+=BrushMath.hash(p.x(),p.y(),p.z(),r.options.seed)*r.options.rebuildRandomDelay;delay=Math.min(delay,r.options.rebuildMaxDelay)*(direction.y()==0?r.options.rebuildHorizontalMultiplier:r.options.rebuildVerticalMultiplier)*20;double next=at.time+delay;
                if(next<cost.getOrDefault(p,Double.POSITIVE_INFINITY)){cost.put(p,next);queue.add(new Step(p,next));}
            }
        }
        ordered.sort(Comparator.comparingDouble(Step::time));return List.copyOf(ordered);
    }
    static void cancel(UUID owner){if(owned(owner)&&!run.cancelled){run.cancelled=true;run.rollback=new ArrayList<>(run.removed.keySet());run.rollbackCursor=0;}}
    static void tick(MinecraftServer server){
        if(run==null)return;Run r=run;long deadline=System.nanoTime()+8_000_000L;int count=0;
        try{
            if(r.cancelled){
                while(r.rollbackCursor<r.rollback.size()&&count++<2048&&System.nanoTime()<deadline){Point p=r.rollback.get(r.rollbackCursor++);var original=r.removed.get(p);if(r.level.hasChunk(p.x()>>4,p.z()>>4)){if(AIR.matches(r.level,EditPlanner.pos(p)))original.place(r.level,EditPlanner.pos(p),2|16);if(original.matches(r.level,EditPlanner.pos(p)))r.restored.add(p);}}
                if(r.rollbackCursor==r.rollback.size())finish(server,r);return;
            }
            if(r.clearing){
                while(r.clearCursor<r.clear.size()&&count++<2048&&System.nanoTime()<deadline){Point p=r.clear.get(r.clearCursor++);if(!dev.craftium.runtime.ServerPlatform.canChange(r.owner,r.level,p)){cancel(r.owner);return;}var original=r.original.get(p);if(!original.state().isAir()&&r.level.hasChunk(p.x()>>4,p.z()>>4)&&original.matches(r.level,EditPlanner.pos(p))){AIR.place(r.level,EditPlanner.pos(p),2|16);r.removed.put(p,original);}}
                if(r.clearCursor==r.clear.size()){r.clearing=false;r.ticks=0;}return;
            }
            r.ticks++;double age=r.ticks-r.options.rebuildStartDelay*20;
            while(r.restoreCursor<r.order.size()&&r.order.get(r.restoreCursor).time<=age&&count++<2048&&System.nanoTime()<deadline){Point p=r.order.get(r.restoreCursor++).point;var original=r.removed.get(p);if(original!=null&&r.level.hasChunk(p.x()>>4,p.z()>>4)&&AIR.matches(r.level,EditPlanner.pos(p))){original.place(r.level,EditPlanner.pos(p),2|16);r.restored.add(p);}}
            if(r.restoreCursor==r.order.size())finish(server,r);
            else if(r.ticks%20==0){var player=server.getPlayerList().getPlayer(r.owner);if(player!=null)EditorServer.send(player,r.session,"Animated rebuild · "+r.restoreCursor+" / "+r.order.size()+" cells · Ctrl+Z to restore now",false);}
        }catch(Exception e){dev.craftium.runtime.ServerPlatform.LOG.error("Animated rebuild interrupted",e);if(!r.cancelled)cancel(r.owner);else if(r.rollbackCursor>=r.rollback.size())finish(server,r);}
    }
    private static void finish(MinecraftServer server,Run r){
        var changes=new ArrayList<Session.Change>();int unresolved=0;
        for(var e:r.removed.entrySet()){Point p=e.getKey();var before=e.getValue();if(r.restored.contains(p))changes.add(new Session.Change(p,before,before));else if(!r.level.hasChunk(p.x()>>4,p.z()>>4)||AIR.matches(r.level,EditPlanner.pos(p))){changes.add(new Session.Change(p,before,AIR));unresolved++;}}
        if(r.session.undo.peekFirst()==r.recovery)r.session.undo.removeFirst();r.session.remember(new Session.Edit(r.cancelled?"Cancel animated rebuild":"Animated rebuild",List.copyOf(changes),List.of(),2|16));r.session.busy=false;run=null;HistoryStore.save(r.session);
        var player=server.getPlayerList().getPlayer(r.owner);if(player!=null)EditorServer.send(player,r.session,(r.cancelled?"Rebuild cancelled; original structure restored":"Animated rebuild completed")+(unresolved==0?"":" · "+unresolved+" unavailable cells remain recoverable with Undo"),false);else EditorServer.disconnect(r.owner);
    }
    static void finishOnStop(MinecraftServer server){if(run==null)return;cancel(run.owner);while(run!=null)tick(server);}
}
