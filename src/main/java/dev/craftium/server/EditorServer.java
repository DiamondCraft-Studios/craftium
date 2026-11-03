package dev.craftium.server;

import dev.craftium.runtime.ServerPlatform;
import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.*;

public final class EditorServer {
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private static Job job;
    private static int historyTicks;
    private record Job(UUID owner, ServerLevel level, Session session, Session.Edit edit,
                       boolean backwards, boolean history, int[] cursor, List<Session.Change> applied, long strokeId) {}
    private EditorServer() {}
    public static void clear() { SESSIONS.clear();job=null;AnnotationEdits.clear();AnimatedRebuild.clear(); }
    public static void disconnect(UUID id) {var session=SESSIONS.get(id);if(session!=null)HistoryStore.save(session);if(AnimatedRebuild.owned(id)){AnimatedRebuild.cancel(id);return;}if(job==null || !job.owner.equals(id))SESSIONS.remove(id);}
    public static void flushHistory(MinecraftServer server){
        AnimatedRebuild.finishOnStop(server);
        if(job!=null){var j=job;rememberInterrupted(j);j.session.busy=false;job=null;}
        for(var session:SESSIONS.values())HistoryStore.save(session);try{HistoryStore.flush();}catch(java.util.concurrent.CompletionException e){ServerPlatform.LOG.error("Could not finish writing editor history during shutdown",e);}
    }
    private static void rememberInterrupted(Job j){rememberInterrupted(j.session,j.edit,j.applied,j.backwards,j.history);}
    static void rememberInterrupted(Session session,Session.Edit edit,List<Session.Change> applied,boolean backwards,boolean history){
        var partial=new Session.Edit(edit.name()+" (interrupted)",List.copyOf(applied),List.of(),edit.flags());
        if(!history){if(!applied.isEmpty())session.remember(partial);return;}
        var positions=new HashSet<Point>();for(var c:applied)positions.add(c.pos());
        var remaining=new Session.Edit(edit.name(),edit.changes().stream().filter(c->!positions.contains(c.pos())).toList(),edit.biomes(),edit.flags(),edit.entities(),edit.annotations());
        if(!applied.isEmpty())(backwards?session.redo:session.undo).addFirst(partial);
        if(!remaining.changes().isEmpty()||!remaining.biomes().isEmpty()||!remaining.entities().isEmpty()||!remaining.annotations().isEmpty())(backwards?session.undo:session.redo).addFirst(remaining);
        while(session.undo.size()>32)session.undo.removeLast();
        while(session.redo.size()>32)session.redo.removeLast();
        session.recalculateBytes();session.historyDirty=true;
    }
    public static boolean noClip(ServerPlayer player){var s=SESSIONS.get(player.getUUID());return s!=null&&s.noClip&&player.isCreative()&&ServerPlatform.permitted(player,"capabilities");}

    public static void handle(ServerPlayer player,String json) {
        var s=SESSIONS.computeIfAbsent(player.getUUID(),id->new Session());
        try {
            if(!ServerPlatform.permitted(player,"edit"))
                throw new IllegalArgumentException("You do not have permission to use Craftium on this server");
            var r=Packets.JSON.fromJson(json,Packets.Request.class);
            if(r==null || r.action()==null || r.options()==null)throw new IllegalArgumentException("Invalid editor request");
            if(!ServerPlatform.permitted(player,r.action()))throw new IllegalArgumentException("You do not have permission for this Craftium action");
            Options o=r.options();o.validate();
            s.requestStroke=r.action().equals("use")?r.strokeId():0;
            if(s.dimension!=null && !s.dimension.equals(player.level().dimension())) {
                if(s.busy)throw new IllegalArgumentException("Wait for the current operation to finish");
                HistoryStore.save(s);
                s=new Session();SESSIONS.put(player.getUUID(),s);
            }
            s.dimension=player.level().dimension();
            HistoryStore.attach(player,s);
            if(o.noClip&&!s.noClip&&player.isCreative()){player.getAbilities().flying=true;player.onUpdateAbilities();}
            s.infiniteReach=o.infiniteReach;s.noClip=o.noClip;
            if(r.action().equals("status")) { send(player,s,"Ready",false);return; }
            if(r.action().equals("capabilities")){send(player,s,"Builder capabilities synchronized",false);return;}
            long now=System.nanoTime();
            if(now-s.lastRequest<65_000_000L&&!r.action().equals("blueprint_list")&&!r.action().equals("hotbar_list"))return;
            s.lastRequest=now;
            if(r.action().equals("gamemode_editor")){ // sent when the editor opens: no status message, unlike the buttons
                if(!player.isSpectator())player.setGameMode(GameType.SPECTATOR);
                return;
            }
            if(r.action().equals("gamemode")||r.action().startsWith("gamemode_")) {
                boolean wasSpectator=player.isSpectator();
                player.setGameMode(r.action().equals("gamemode")?(player.isCreative()?GameType.SPECTATOR:GameType.CREATIVE):GameType.byName(r.action().substring(9)));
                if(wasSpectator&&player.isCreative()){player.getAbilities().flying=true;player.onUpdateAbilities();}
                send(player,s,"Game mode switched",false);return;
            }
            if(!player.isCreative()&&!player.isSpectator())throw new IllegalArgumentException("Switch to Creative or Spectator mode to edit");
            if((r.action().equals("undo")||r.action().equals("rebuild_cancel"))&&AnimatedRebuild.owned(player.getUUID())){AnimatedRebuild.cancel(player.getUUID());send(player,s,"Cancelling rebuild and restoring the original structure…",false);return;}
            if(r.action().equals("hotbar_next")||r.action().equals("hotbar_previous")){send(player,s,HotbarLibrary.cycle(player,s,o,r.action().equals("hotbar_next")?1:-1),false);return;}
            if(r.action().equals("hotbar_save")||r.action().equals("hotbar_load")||r.action().equals("hotbar_list")){
                String result=HotbarLibrary.apply(r.action(),player,o);if(r.action().equals("hotbar_list"))ServerPlatform.send(player,new Packets.Report(result));send(player,s,r.action().equals("hotbar_list")?"Saved hotbars listed":result,false);return;
            }
            if(s.busy)throw new IllegalArgumentException("Applying edit… please wait");
            if(r.action().startsWith("world_")||r.action().equals("view_save")||r.action().equals("view_load")){send(player,s,WorldControls.apply(r.action(),player,o),false);return;}
            if(r.action().equals("flight")) {
                player.getAbilities().setFlyingSpeed(.05f*o.flySpeed);
                player.onUpdateAbilities();send(player,s,"Flight speed: "+o.flySpeed+"×",false);return;
            }
            if(r.action().equals("hotbar")) {
                var inv=player.getInventory();
                for(int i=0;i<9;i++) {var item=inv.getItem(i).copy();inv.setItem(i,inv.getItem(i+9).copy());inv.setItem(i+9,item);}
                player.inventoryMenu.broadcastChanges();send(player,s,"Hotbar swapped with inventory row 1",false);return;
            }
            if(r.action().equals("palette_hotbar")){
                var plan=new EditPlanner(player.level(),s,o);var blocks=MaterialList.split(o.materials);
                for(int i=0;i<Math.min(9,blocks.size());i++)player.getInventory().setItem(i,new net.minecraft.world.item.ItemStack(plan.parse(blocks.get(i)).getBlock()));
                player.inventoryMenu.broadcastChanges();send(player,s,"Gradient copied to hotbar",false);return;
            }
            Point target=r.target(),normal=r.normal()==null?new Point(0,1,0):r.normal();
            if(Math.abs(normal.x())+Math.abs(normal.y())+Math.abs(normal.z())!=1)throw new IllegalArgumentException("Invalid surface normal");
            if(target!=null) {
                if(target.distanceSquared(new Point((int)player.getX(),(int)player.getY(),(int)player.getZ()))>256*256)
                    throw new IllegalArgumentException("Move within 256 blocks of the target");
                new EditPlanner(player.level(),s,o).validate(target);
            }
            switch(r.action()) {
                case "pick_block" -> {
                    if(target==null)throw new IllegalArgumentException("Point at a block first");var pos=EditPlanner.pos(target);var state=player.level().getBlockState(pos);var stack=state.getCloneItemStack(player.level(),pos,true);if(stack.isEmpty())throw new IllegalArgumentException("This block has no item");
                    var properties=net.minecraft.world.item.component.BlockItemStateProperties.EMPTY;for(var property:state.getProperties())properties=properties.with(property,state);stack.set(net.minecraft.core.component.DataComponents.BLOCK_STATE,properties);
                    var entity=player.level().getBlockEntity(pos);if(entity!=null){var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,player.level().registryAccess());entity.saveCustomOnly(out);entity.removeComponentsFromTag(out);net.minecraft.world.item.BlockItem.setBlockEntityData(stack,entity.getType(),out);stack.applyComponents(entity.collectComponents());}
                    player.getInventory().setItem(player.getInventory().getSelectedSlot(),stack);player.inventoryMenu.broadcastChanges();send(player,s,"Copied block state and data to hotbar",false);
                }
                case "builder_commit","builder_extrude","builder_erase_connected" -> {if(!r.action().equals("builder_commit")&&target==null)throw new IllegalArgumentException("Point at a block first");start(player,s,BuilderEdits.plan(player,s,o,target,normal,r.action()));}
                case "colour_field" -> {requireSelection(s);var plan=new EditPlanner(player.level(),s,o);for(var entry:o.colourField){if(!s.selection.contains(entry.point()))throw new IllegalArgumentException("Colour field points must be selected");plan.put(entry.point(),plan.parse(entry.block()));}start(player,s,plan.finish("Generate colour field"));}
                case "animated_rebuild" -> {if(job!=null)throw new IllegalArgumentException("Wait for the current edit");AnimatedRebuild.start(player,s,o);}
                case "rebuild_cancel" -> throw new IllegalArgumentException("No animated rebuild is active for this player");
                case "history_save" -> {HistoryStore.save(s);HistoryStore.flush();send(player,s,"History saved with this world",false);}
                case "history_reload" -> {HistoryStore.save(s);HistoryStore.flush();HistoryStore.load(player,s);send(player,s,"Saved history reloaded",false);}
                case "history_undo_many","history_redo_many" -> {s.historyRemaining=(r.action().equals("history_undo_many")?1:-1)*(o.historySteps-1);history(player,s,r.action().equals("history_undo_many"));}
                case "analyze" -> {var plan=new EditPlanner(player.level(),s,o);String report=SelectionTransforms.analyze(plan);ServerPlatform.send(player,new Packets.Report(report));send(player,s,"Selection analyzed",false);}
                case "selection_filter","selection_bounds","selection_distort","selection_smooth","selection_scale","selection_gizmo" -> {var plan=new EditPlanner(player.level(),s,o);s.selection.combine(SelectionTransforms.apply(r.action(),plan),Selection.Mode.REPLACE);send(player,s,"Selection updated: "+s.selection.size()+" blocks",false);}
                case "clear" -> {s.selection.clear();s.corner=null;s.line=null;s.ruler=null;send(player,s,"Selection cleared",false);}
                case "ruler_clear" -> {s.ruler=null;send(player,s,"Measurement cleared",false);}
                case "asset_upload","blueprint_list","blueprint_save","blueprint_load","blueprint_data","structure_export","schematic_export","schematic_import","selection_csv" -> {
                    var plan=new EditPlanner(player.level(),s,o);String result=BlueprintLibrary.act(r.action(),plan);if(Set.of("blueprint_save","structure_export","schematic_export","selection_csv","blueprint_data").contains(r.action()))result+=BlueprintLibrary.download(r.action(),plan,player);send(player,s,result,false);
                }
                case "entity_copy","entity_cut" -> {
                    var entity=player.level().getEntity(UUID.fromString(o.entityUuid));
                    if(entity==null||!EntityEdits.editable(entity)||entity.distanceToSqr(player)>256*256)throw new IllegalArgumentException("Select a nearby display or marker");
                    new EditPlanner(player.level(),s,o).validate(EditPlanner.point(entity.blockPosition()));
                    var before=EntityEdits.snapshot(entity,player.level());var saved=before.copy();ClipboardEntities.offset(saved,-entity.getBlockX(),-entity.getBlockY(),-entity.getBlockZ());
                    if(saved.toString().length()>1_000_000)throw new IllegalArgumentException("Entity clipboard exceeds the data limit");
                    s.selectedEntity=entity.getUUID();s.clipboard.clear();s.clipboardEntities.clear();s.clipboardEntities.add(saved);s.clipboardDirty=true;
                    if(r.action().equals("entity_cut"))start(player,s,new Session.Edit("Cut entity",List.of(),List.of(),2,List.of(new Session.EntityChange(entity.getUUID(),before,null))));
                    else send(player,s,"Copied entity to clipboard",false);
                }
                case "entity_select" -> {if(target==null)throw new IllegalArgumentException("Point near a display or marker");s.selectedEntity=EntityEdits.nearest(player.level(),target).getUUID();send(player,s,"Entity selected",false);}
                case "entity_select_uuid" -> {var entity=player.level().getEntity(UUID.fromString(o.entityUuid));if(entity==null||!EntityEdits.editable(entity)||entity.distanceToSqr(player)>256*256)throw new IllegalArgumentException("Select a nearby display or marker");s.selectedEntity=entity.getUUID();send(player,s,"Entity selected",false);}
                case "entity_spawn","entity_update","entity_move","entity_duplicate","entity_delete","entity_transform","entity_gizmo","entity_reset","entity_group","entity_ungroup","entity_group_transform" -> {if(target==null)throw new IllegalArgumentException("Point at a block");start(player,s,EntityEdits.plan(r.action(),player,s,o,target));}
                case "annotation_create","annotation_update","annotation_delete","annotation_clear" -> {if(target==null)target=o.position1;start(player,s,AnnotationEdits.plan(r.action(),player.level(),o,target));}
                case "select_box" -> {
                    var plan=new EditPlanner(player.level(),s,o);var points=Selection.box(o.position1,o.position2);
                    for(Point p:points)plan.validate(p);s.selection.combine(points,o.mode);send(player,s,"Selected "+s.selection.size()+" blocks",false);
                }
                case "lasso_select" -> {
                    var plan=new EditPlanner(player.level(),s,o);for(Point p:o.selectionPoints){plan.validate(p);if(p.distanceSquared(new Point((int)player.getX(),(int)player.getY(),(int)player.getZ()))>256*256)throw new IllegalArgumentException("Lasso reaches beyond editor range");}
                    s.selection.combine(o.selectionPoints,o.mode);send(player,s,"Lasso selected "+s.selection.size()+" blocks",false);
                }
                case "selection_move", "selection_expand", "selection_shrink", "selection_invert", "selection_convex" -> {
                    var next=new LinkedHashSet<Point>(s.selection.points());
                    if(r.action().equals("selection_move")){next.clear();for(Point p:s.selection.points())next.add(p.add(o.offsetX,o.offsetY,o.offsetZ));}
                    else if(r.action().equals("selection_invert")){var all=new LinkedHashSet<>(Selection.box(s.selection.min(),s.selection.max()));all.removeAll(next);next=all;}
                    else if(r.action().equals("selection_convex")){next=new LinkedHashSet<>(ConvexHull.fill(s.selection.points()));}
                    else for(int i=0;i<Math.max(1,(int)o.strength);i++){
                        var previous=next;var step=new LinkedHashSet<>(previous);
                        if(r.action().equals("selection_expand")){for(Point p:previous)for(Point n:EditPlanner.NEIGHBOURS)step.add(p.add(n.x(),n.y(),n.z()));}
                        else step.removeIf(p->{for(Point n:EditPlanner.NEIGHBOURS)if(!previous.contains(p.add(n.x(),n.y(),n.z())))return true;return false;});
                        if(step.size()>Selection.LIMIT)throw new IllegalArgumentException("Selection exceeds limit");next=step;
                    }
                    var plan=new EditPlanner(player.level(),s,o);for(Point p:next)plan.validate(p);s.selection.combine(next,Selection.Mode.REPLACE);send(player,s,"Selection updated: "+s.selection.size()+" blocks",false);
                }
                case "fill_outline","fill_walls","fill_top","fill_bottom","fill_nearest","replace","set_biome","autoshade","drain","waterlog","smooth_snow","gravity","trigger_updates","hollow","fill_gaps" -> {
                    var plan=new EditPlanner(player.level(),s,o);if(r.action().equals("autoshade"))Autoshade.apply(plan,player);else SelectionOperations.apply(r.action(),plan);start(player,s,plan.finish(r.action().replace('_',' ')));
                }
                case "rotate", "flip", "flip_facing" -> {var look=player.getLookAngle();int axis=r.action().equals("flip_facing")?net.minecraft.core.Direction.getApproximateNearest(look.x,look.y,look.z).getAxis().ordinal():0;transform(player,s,r.action().equals("rotate"),axis);}
                case "clipboard_transform" -> {ClipboardTransforms.transform(s,o);send(player,s,"Clipboard transformed with nearest-neighbour sampling",false);}
                case "copy" -> copy(player,s,o,false);
                case "cut" -> copy(player,s,o,true);
                case "move", "clone", "stack" -> {
                    requireSelection(s);
                    if(o.offsetX==0&&o.offsetY==0&&o.offsetZ==0)throw new IllegalArgumentException("Set a non-zero offset for the selection");
                    var plan=new EditPlanner(player.level(),s,o);
                    var source=new LinkedHashMap<Point,BlockSnapshot>();
                    for(Point p:s.selection.points()){plan.validate(p);source.put(p,BlockSnapshot.capture(player.level(),EditPlanner.pos(p)));}
                    if(r.action().equals("move"))for(Point p:source.keySet())plan.put(p,Blocks.AIR.defaultBlockState());
                    int count=r.action().equals("stack")?o.copies:1;
                    if((long)source.size()*count>Selection.LIMIT)throw new IllegalArgumentException("Stack exceeds 131,072 blocks");
                    for(int i=1;i<=count;i++)for(var e:source.entrySet()) {
                        var p=e.getKey();if(e.getValue().state().isAir()&&!o.pasteAir)continue;
                        plan.put(p.add(o.offsetX*i,o.offsetY*i,o.offsetZ*i),e.getValue());
                    }
                    start(player,s,plan.finish(r.action().equals("move")?"Move selection":r.action().equals("clone")?"Clone selection":"Stack selection"));
                }
                case "undo", "redo" -> {s.historyRemaining=0;history(player,s,r.action().equals("undo"));}
                case "fill", "delete" -> {
                    requireSelection(s);var plan=new EditPlanner(player.level(),s,o);
                    for(Point p:s.selection.points())plan.put(p,r.action().equals("delete")?Blocks.AIR.defaultBlockState():plan.material);
                    start(player,s,plan.finish(r.action().equals("delete")?"Delete selection":"Fill selection"));
                }
                case "paste", "paste_at", "paste_select" -> {
                    if(target==null)throw new IllegalArgumentException("Point at a block to place the clipboard");
                    if(s.clipboard.isEmpty()&&s.clipboardEntities.isEmpty())throw new IllegalArgumentException("Copy a selection first");
                    var plan=new EditPlanner(player.level(),s,o);
                    Point origin=r.action().equals("paste")?target.add(normal.x(),normal.y(),normal.z()):target;
                    var placed=new LinkedHashSet<Point>();
                    for(var entry:s.clipboard.entrySet()) {
                        var relative=entry.getKey();var value=entry.getValue();
                        if(!o.pasteAir && value.state().isAir())continue;
                        Point at=origin.add(relative.x(),relative.y(),relative.z());plan.putClipboard(at,value);placed.add(at);
                    }
                    if(r.action().equals("paste_select"))s.selection.combine(placed,Selection.Mode.REPLACE);
                    var edit=plan.finish("Paste clipboard");start(player,s,new Session.Edit(edit.name(),edit.changes(),edit.biomes(),edit.flags(),o.pasteEntities?ClipboardEntities.paste(player,s,origin):List.of()));
                }
                case "use", "builder_place", "builder_break" -> {
                    if(target==null)throw new IllegalArgumentException("Point at a block within reach");
                    var plan=new EditPlanner(player.level(),s,o);
                    if(r.action().equals("builder_place")) {
                        if(o.tinker && player.getMainHandItem().isEmpty()) {o.tool=Tool.TINKER;plan.tool(target,normal);}
                        else {
                            Point at=o.replaceMode?target:target.add(normal.x(),normal.y(),normal.z());
                            plan.put(at,plan.preserve(plan.state(target),plan.material));
                        }
                        start(player,s,plan.finish("Builder placement"));
                    } else if(r.action().equals("builder_break")) {
                        plan.put(target,Blocks.AIR.defaultBlockState());start(player,s,plan.finish("Bulldozer"));
                    } else use(player,s,o,target,normal,plan);
                }
                default -> throw new IllegalArgumentException("Unknown editor action");
            }
        } catch(IllegalArgumentException e) {s.historyRemaining=0;send(player,s,e.getMessage(),true);}
        catch(Exception e) {ServerPlatform.LOG.error("Editor request failed",e);send(player,s,"Operation failed; see the Craftium log",true);}
    }
    private static void use(ServerPlayer player,Session s,Options o,Point p,Point normal,EditPlanner plan) {
        switch(o.tool) {
            case ANNOTATION -> start(player,s,AnnotationEdits.plan("annotation_create",player.level(),o,p));
            case BOX_SELECT -> {
                if(s.corner==null) {s.corner=p;send(player,s,"First corner: "+p.coordinates()+" · choose the opposite corner",false);}
                else {s.selection.combine(Selection.box(s.corner,p),o.mode);s.corner=null;send(player,s,"Selected "+s.selection.size()+" blocks",false);}
            }
            case FREEHAND_SELECT -> {
                s.selection.combine(plan.brush(p),o.mode);send(player,s,"Selected "+s.selection.size()+" blocks",false);
            }
            case MAGIC_SELECT -> {
                var found=new LinkedHashSet<Point>();var visited=new HashSet<Point>();var queue=new ArrayDeque<Point>();
                var block=plan.state(p).getBlock();queue.add(p);visited.add(p);
                while(!queue.isEmpty() && found.size()<o.selectLimit) {
                    Point current=queue.removeFirst();
                    if(current.distanceSquared(p)>128*128 || !player.level().hasChunk(current.x()>>4,current.z()>>4))continue;
                    if(!AdvancedTools.matches(plan,plan.state(p),current) || plan.state(current).isAir())continue;
                    if((!o.selectUp&&current.y()>p.y())||(!o.selectDown&&current.y()<p.y())||(!o.selectHorizontal&&(current.x()!=p.x()||current.z()!=p.z())))continue;
                    if(!o.surfaceOnly || plan.surface(current))found.add(current);
                    for(int dx=-o.selectRange;dx<=o.selectRange;dx++)for(int dy=-o.selectRange;dy<=o.selectRange;dy++)for(int dz=-o.selectRange;dz<=o.selectRange;dz++){
                        int distance=Math.abs(dx)+Math.abs(dy)+Math.abs(dz);if(distance==0||(!o.selectCorners&&distance>o.selectRange)||Math.max(Math.abs(dx),Math.max(Math.abs(dy),Math.abs(dz)))>o.selectRange)continue;
                        var next=current.add(dx,dy,dz);if(visited.size()>=Selection.LIMIT*4)break;if(visited.add(next))queue.add(next);
                    }
                    if(visited.size()>Selection.LIMIT*4)break;
                }
                s.selection.combine(found,o.mode);send(player,s,"Magic selection: "+s.selection.size()+" blocks (128-block search radius)",false);
            }
            case RULER -> {
                if(s.ruler==null) {s.ruler=p;send(player,s,"Ruler start: "+p.coordinates(),false);}
                else {Point a=s.ruler;double distance=Math.sqrt(a.distanceSquared(p));int manhattan=Math.abs(a.x()-p.x())+Math.abs(a.y()-p.y())+Math.abs(a.z()-p.z());
                    String message=String.format(Locale.ROOT,"Ruler: %.2f blocks · Manhattan %d · Δ %s",distance,manhattan,p.subtract(a).coordinates());
                    s.event(message);s.ruler=null;send(player,s,message,false);}
            }
            case LINE -> {
                if(s.line==null) {s.line=p;send(player,s,"Line start set · choose the endpoint",false);}
                else {for(Point point:BrushMath.line(s.line,p))for(Point at:BrushMath.brush(point,Math.min(o.radius,4),o.shape,o.hollow))plan.put(at,plan.material);
                    s.line=null;start(player,s,plan.finish("Draw line"));}
            }
            default -> {plan.tool(p,normal);start(player,s,plan.finish(o.tool.label));}
        }
    }
    private static void requireSelection(Session s) {if(s.selection.size()==0)throw new IllegalArgumentException("Create a selection first");}
    private static void copy(ServerPlayer player,Session s,Options o,boolean cut) {
        requireSelection(s);Point min=s.selection.min();
        var next=new LinkedHashMap<Point,BlockSnapshot>();int bytes=0;
        for(Point p:s.selection.points()) {
            BlockPos at=EditPlanner.pos(p);
            if(!player.level().hasChunk(p.x()>>4,p.z()>>4))throw new IllegalArgumentException("Selection reaches unloaded chunks");
            var snapshot=BlockSnapshot.capture(player.level(),at);bytes+=128+(snapshot.data()==null?0:snapshot.data().toString().length()*2);
            if(bytes>32*1024*1024)throw new IllegalArgumentException("Clipboard exceeds 32 MiB; select a smaller area");
            next.put(p.subtract(min),snapshot);
        }
        var entities=o.copyEntities?ClipboardEntities.capture(player,s):new ClipboardEntities.Capture(List.of(),List.of());
        for(var tag:entities.relative()){bytes+=tag.toString().length()*2;if(bytes>32*1024*1024)throw new IllegalArgumentException("Clipboard exceeds 32 MiB including entities");}
        s.clipboard.clear();s.clipboard.putAll(next);s.clipboardEntities.clear();s.clipboardEntities.addAll(entities.relative());
        s.clipboardDirty=true;
        if(cut){var plan=new EditPlanner(player.level(),s,o);for(Point p:s.selection.points())plan.put(p,Blocks.AIR.defaultBlockState());var edit=plan.finish("Cut selection");start(player,s,new Session.Edit(edit.name(),edit.changes(),edit.biomes(),edit.flags(),entities.removals()));}
        else send(player,s,"Copied "+next.size()+" blocks",false);
    }
    private static void transform(ServerPlayer player,Session s,boolean rotate,int axis) {
        if(s.clipboard.isEmpty()&&s.clipboardEntities.isEmpty())throw new IllegalArgumentException("Copy a selection first");
        var next=new LinkedHashMap<Point,BlockSnapshot>();
        for(var e:s.clipboard.entrySet()) {
            Point p=e.getKey(),at=rotate?p.rotateY():switch(axis){case 1->new Point(p.x(),-p.y(),p.z());case 2->new Point(p.x(),p.y(),-p.z());default->p.mirrorX();};var v=e.getValue();
            var state=rotate?v.state().rotate(Rotation.CLOCKWISE_90):axis==1?BuilderEdits.verticalMirror(v.state()):v.state().mirror(axis==2?Mirror.LEFT_RIGHT:Mirror.FRONT_BACK);
            next.put(at,new BlockSnapshot(state,v.data()));
        }
        int minX=next.keySet().stream().mapToInt(Point::x).min().orElse(0),minY=next.keySet().stream().mapToInt(Point::y).min().orElse(0),minZ=next.keySet().stream().mapToInt(Point::z).min().orElse(0);
        s.clipboard.clear();next.forEach((p,v)->s.clipboard.put(p.add(-minX,-minY,-minZ),v));
        ClipboardEntities.transform(s,v->rotate?new Geometry.Vector(1-v.z()-minX,v.y()-minY,v.x()-minZ):new Geometry.Vector((axis==0?1-v.x():v.x())-minX,(axis==1?1-v.y():v.y())-minY,(axis==2?1-v.z():v.z())-minZ),rotate?90:axis==2?180:0,!rotate&&axis!=1);
        if(!rotate&&axis==1)for(var root:s.clipboardEntities)ClipboardEntities.walk(root,tag->{var old=tag.getListOrEmpty("Rotation");var rotation=new net.minecraft.nbt.ListTag();rotation.add(net.minecraft.nbt.FloatTag.valueOf(old.getFloatOr(0,0)));rotation.add(net.minecraft.nbt.FloatTag.valueOf(-old.getFloatOr(1,0)));tag.put("Rotation",rotation);});
        s.clipboardDirty=true;
        send(player,s,rotate?"Clipboard rotated 90°":"Clipboard mirrored on "+"XYZ".charAt(axis),false);
    }
    private static void start(ServerPlayer player,Session s,Session.Edit edit) {
        if(job!=null||AnimatedRebuild.active())throw new IllegalArgumentException("Another edit or animated rebuild is applying; try again in a moment");
        if(edit.changes().isEmpty() && edit.biomes().isEmpty()&&edit.entities().isEmpty()&&edit.annotations().isEmpty()){send(player,s,"No matching blocks changed",false);return;}
        if(edit.estimatedBytes()>32*1024*1024)throw new IllegalArgumentException("Edit exceeds the 32 MiB history budget; use a smaller region");
        EditProtection.check(player,edit);
        s.busy=true;job=new Job(player.getUUID(),player.level(),s,edit,false,false,new int[]{0},new ArrayList<>(),s.requestStroke);
        send(player,s,"Applying "+edit.name()+"…",false);
    }
    private static void history(ServerPlayer player,Session s,boolean backwards) {
        if(job!=null||AnimatedRebuild.active())throw new IllegalArgumentException("Wait for the current edit or animated rebuild to finish");
        var from=backwards?s.undo:s.redo;
        if(from.isEmpty()){s.historyRemaining=0;send(player,s,backwards?"Nothing to undo":"Nothing to redo",false);return;}
        var edit=from.peekFirst();
        for(var c:edit.changes())if(!player.level().hasChunk(c.pos().x()>>4,c.pos().z()>>4))throw new IllegalArgumentException("Move closer to the edited chunks before undo/redo");
        for(var c:edit.biomes())if(!player.level().hasChunk(c.cell().x()>>2,c.cell().z()>>2))throw new IllegalArgumentException("Move closer to the biome chunks before undo/redo");
        EditProtection.check(player,edit);
        from.removeFirst();s.busy=true;job=new Job(player.getUUID(),player.level(),s,edit,backwards,true,new int[]{0},new ArrayList<>(),0);
        s.lastStroke=0;
        send(player,s,backwards?"Undoing…":"Redoing…",false);
    }
    public static void tick(MinecraftServer server) {
        AnimatedRebuild.tick(server);
        if(++historyTicks%100==0)for(var session:SESSIONS.values())HistoryStore.save(session);
        var reachId=dev.craftium.compat.Compat.id("craftium","builder_reach");
        for(var player:server.getPlayerList().getPlayers()){
            var s=SESSIONS.get(player.getUUID());var range=player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
            boolean enabled=s!=null&&s.infiniteReach&&player.isCreative()&&ServerPlatform.permitted(player,"capabilities");
            if(range!=null){if(enabled&&!range.hasModifier(reachId))range.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(reachId,250,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));else if(!enabled&&range.hasModifier(reachId))range.removeModifier(reachId);}
        }
        if(job==null)return;var j=job;
        try {
            var list=j.edit.changes();long deadline=System.nanoTime()+8_000_000L;int count=0;
            while(j.cursor[0]<list.size() && count++<2048 && System.nanoTime()<deadline) {
                var c=list.get(j.cursor[0]++);BlockPos at=EditPlanner.pos(c.pos());
                var expected=j.backwards?c.after():c.before();var desired=j.backwards?c.before():c.after();
                if(j.level.hasChunk(at.getX()>>4,at.getZ()>>4) && expected.matches(j.level,at)) {
                    if(!ServerPlatform.canChange(j.owner,j.level,c.pos()))throw new IllegalArgumentException("Edit permission or region access changed");
                    desired.place(j.level,at,j.edit.flags());j.applied.add(c);
                }
            }
            if(j.cursor[0]<list.size())return;
            if(!EditProtection.allowed(j.owner,j.level,new Session.Edit(j.edit.name(),List.of(),List.of(),j.edit.flags(),j.edit.entities(),j.edit.annotations())))throw new IllegalArgumentException("Entity or annotation access changed");
            var appliedBiomes=applyBiomes(j);
            var appliedEntities=EntityEdits.apply(j.level,j.edit.entities(),j.backwards);
            var appliedAnnotations=AnnotationEdits.apply(j.level,j.edit.annotations(),j.backwards);
            var actual=new Session.Edit(j.edit.name(),List.copyOf(j.applied),appliedBiomes,j.edit.flags(),appliedEntities,appliedAnnotations);
            int skipped=j.edit.changes().size()-j.applied.size()+j.edit.biomes().size()-appliedBiomes.size()+j.edit.entities().size()-appliedEntities.size()+j.edit.annotations().size()-appliedAnnotations.size();
            if(j.history) {
                // Retain only the cells actually restored; conflicting subsequent edits are left intact.
                (j.backwards?j.session.redo:j.session.undo).addFirst(actual);
                j.session.recalculateBytes();
                j.session.historyDirty=true;
                j.session.event((j.backwards?"Undo ":"Redo ")+j.edit.name());
            } else j.session.remember(actual,j.strokeId);
            j.session.busy=false;job=null;
            var player=server.getPlayerList().getPlayer(j.owner);
            if(player!=null)send(player,j.session,(j.backwards?"Undid ":j.history?"Redid ":"Applied ")+j.edit.name()+" · "+(j.applied.size()+appliedBiomes.size()+appliedEntities.size()+appliedAnnotations.size())+" changes"+(skipped>0?" · "+skipped+" changed or unavailable cells skipped":""),false);
            else {HistoryStore.save(j.session);SESSIONS.remove(j.owner);}
            if(!appliedAnnotations.isEmpty())for(var other:server.getPlayerList().getPlayers())if(!other.getUUID().equals(j.owner)&&other.level()==j.level){var session=SESSIONS.get(other.getUUID());if(session!=null)send(other,session,"Annotations updated",false);}
            if(player!=null&&j.history&&j.session.historyRemaining!=0){boolean backwards=j.session.historyRemaining>0;j.session.historyRemaining+=backwards?-1:1;try{history(player,j.session,backwards);}catch(IllegalArgumentException e){j.session.historyRemaining=0;send(player,j.session,e.getMessage(),true);}}
        } catch(Exception e) {
            ServerPlatform.LOG.error("Craftium edit interrupted",e);
            rememberInterrupted(j);
            j.session.busy=false;job=null;var player=server.getPlayerList().getPlayer(j.owner);
            if(player!=null)send(player,j.session,"Edit interrupted. Applied changes remain in history.",true);
            else {HistoryStore.save(j.session);SESSIONS.remove(j.owner);}
        }
    }
    private static List<Session.BiomeChange> applyBiomes(Job j) {
        var applied=new ArrayList<Session.BiomeChange>();var groups=new LinkedHashMap<Long,Map<Point,Holder<Biome>>>();
        for(var c:j.edit.biomes()) {
            Point p=c.cell();boolean allowed=true;for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++)if(!ServerPlatform.canChange(j.owner,j.level,new Point(p.x()*4+x,p.y()*4+y,p.z()*4+z)))allowed=false;if(!allowed)continue;if(!j.level.hasChunk(p.x()>>2,p.z()>>2))continue;
            var expected=j.backwards?c.after():c.before();if(!j.level.getNoiseBiome(p.x(),p.y(),p.z()).equals(expected))continue;
            long key=dev.craftium.compat.Compat.chunkKey(p.x()>>2,p.z()>>2);
            groups.computeIfAbsent(key,k->new HashMap<>()).put(p,j.backwards?c.before():c.after());applied.add(c);
        }
        var chunks=new ArrayList<ChunkAccess>();
        for(var group:groups.entrySet()) {
            var chunk=j.level.getChunk(net.minecraft.world.level.ChunkPos.getX(group.getKey()),net.minecraft.world.level.ChunkPos.getZ(group.getKey()));
            var replacements=group.getValue();
            chunk.fillBiomesFromNoise((x,y,z,sampler)->replacements.getOrDefault(new Point(x,y,z),chunk.getNoiseBiome(x,y,z)),j.level.getChunkSource().randomState().sampler());
            chunk.markUnsaved();chunks.add(chunk);
        }
        if(!chunks.isEmpty())j.level.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
        return List.copyOf(applied);
    }
    static void send(ServerPlayer player,Session s,String message,boolean error) {
        if(!ServerPlatform.canSend(player))return;
        String entityId="",entityNbt="";if(s.selectedEntity!=null){var entity=player.level().getEntity(s.selectedEntity);if(entity!=null){entityId=s.selectedEntity.toString();String nbt=EntityEdits.snapshot(entity,player.level()).toString();if(nbt.length()<12000)entityNbt=nbt;}}
        if(s.selectionRevision!=s.selection.revision()){s.selectionRevision=s.selection.revision();s.selectionData=SelectionCodec.encode(s.selection.points());}
        if(s.clipboardDirty){s.clipboardData=SelectionCodec.encode(s.clipboard.entrySet().stream().filter(e->!e.getValue().state().isAir()).map(Map.Entry::getKey).toList());s.clipboardDirty=false;}
        var status=new Packets.Status(message,error,s.busy,s.selection.size(),s.selection.min(),s.selection.max(),s.clipboard.size(),s.undo.stream().map(edit->edit.name()+" · "+(edit.changes().size()+edit.biomes().size()+edit.entities().size()+edit.annotations().size())).toList(),s.undo.size(),s.redo.size(),entityId,entityNbt,AnnotationEdits.list(player.level()),s.selectionData,s.clipboardData,s.library,s.clipboardEntities.size());s.library=List.of();
        ServerPlatform.send(player,new Packets.Update(Packets.JSON.toJson(status)));
    }
}
