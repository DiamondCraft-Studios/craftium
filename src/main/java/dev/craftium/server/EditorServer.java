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
    /** The AI's workspace of each player: the preview it is holding and the builds it can undo. Never saved, and dropped when the player leaves. */
    private static final Map<UUID,Session> AI_SESSIONS=new HashMap<>();
    private static Job job;
    private static int historyTicks;
    private record Job(UUID owner, ServerLevel level, Session session, Session.Edit edit,
                       boolean backwards, boolean history, int[] cursor, List<Session.Change> applied, long strokeId, boolean load) {}
    private EditorServer() {}
    public static void clear() { SESSIONS.clear();AI_SESSIONS.clear();job=null;AnnotationEdits.clear();AnimatedRebuild.clear(); }
    /** Gives the player back the game mode the editor replaced, if it still has one to give back. */
    private static void restoreMode(ServerPlayer player,Session s){
        var mode=s.previousMode;s.previousMode=null;
        if(mode==null||!player.isSpectator())return; // they changed mode themselves in the meantime
        player.setGameMode(mode);
        if(player.isCreative()){player.getAbilities().flying=true;player.onUpdateAbilities();}
    }
    public static void disconnect(ServerPlayer player){var s=SESSIONS.get(player.getUUID());if(s!=null)restoreMode(player,s);disconnect(player.getUUID());}
    public static void disconnect(UUID id) {var session=SESSIONS.get(id);if(session!=null)HistoryStore.save(session);if(AnimatedRebuild.owned(id)){AnimatedRebuild.cancel(id);return;}if(job==null || !job.owner.equals(id)){SESSIONS.remove(id);AI_SESSIONS.remove(id);}}
    public static void flushHistory(MinecraftServer server){
        AnimatedRebuild.finishOnStop(server);
        // Players are saved as the server stops, before they are disconnected, so a player still in the editor would be saved as a spectator.
        for(var entry:SESSIONS.entrySet()){var player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null)restoreMode(player,entry.getValue());}
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
    /** The options of a player who is building: creative, permitted and known to the editor. Null otherwise. */
    public static Options capabilities(ServerPlayer player){var s=SESSIONS.get(player.getUUID());return s!=null&&s.options!=null&&player.isCreative()&&ServerPlatform.permitted(player,"capabilities")?s.options:null;}
    /** What is at a position just before vanilla places a block there, kept only when the placement is to be recorded. Null when it is not. */
    public static Object before(ServerPlayer player,BlockPos at){
        var o=capabilities(player);if(o==null||!o.placementCapabilities())return null;
        // The neighbours too: placing a door adds its other half, and a chest or bed changes its partner.
        var near=new LinkedHashMap<BlockPos,BlockSnapshot>();near.put(at.immutable(),BlockSnapshot.capture(player.level(),at));
        for(var d:net.minecraft.core.Direction.values()){var n=at.relative(d);near.put(n,BlockSnapshot.capture(player.level(),n));}
        return near;
    }
    /**
     * Vanilla has placed a block; the editor adds what it owns on top of that: the symmetric copies, and one undo step for all of them.
     * The placement itself, with its sound and swing, is left to the game.
     */
    @SuppressWarnings("unchecked")
    public static void placed(ServerPlayer player,BlockPos at,Object snapshot){
        var s=SESSIONS.get(player.getUUID());var o=capabilities(player);if(s==null||o==null)return;
        try{
            var level=player.level();Point p=EditPlanner.point(at);var near=(Map<BlockPos,BlockSnapshot>)snapshot;var before=near.get(at);var after=BlockSnapshot.capture(level,at);
            var plan=new EditPlanner(level,s,o);plan.put(p,after); // the block is already there, so only its mirrors are left to change
            var mirrors=plan.finish("Builder placement");
            if(!mirrors.changes().isEmpty())EditProtection.check(player,mirrors);
            var changes=new ArrayList<Session.Change>();
            if(!(before.state()==after.state()&&Objects.equals(before.data(),after.data())))changes.add(new Session.Change(p,before,after));
            for(var e:near.entrySet()){ // what vanilla changed beside the block: a door's other half, a chest's partner
                if(e.getKey().equals(at))continue;var now=BlockSnapshot.capture(level,e.getKey());
                if(!(e.getValue().state()==now.state()&&Objects.equals(e.getValue().data(),now.data())))changes.add(new Session.Change(EditPlanner.point(e.getKey()),e.getValue(),now));
            }
            for(var c:mirrors.changes()){
                if(!ServerPlatform.canChange(player.getUUID(),level,c.pos()))continue;
                c.after().place(level,EditPlanner.pos(c.pos()),mirrors.flags());changes.add(c);
            }
            if(changes.isEmpty())return;
            s.remember(new Session.Edit("Builder placement",changes,List.of(),mirrors.flags()));
            send(player,s,"Placed "+changes.size()+(changes.size()==1?" block":" blocks"),false);
        }catch(IllegalArgumentException e){send(player,s,e.getMessage(),true);}
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
            s.requestStroke=r.action().equals("use")||r.action().equals("builder_break")?r.strokeId():0;
            if(s.dimension!=null && !s.dimension.equals(player.level().dimension())) {
                if(s.busy)throw new IllegalArgumentException("Wait for the current operation to finish");
                HistoryStore.save(s);
                s=new Session();SESSIONS.put(player.getUUID(),s);
            }
            s.dimension=player.level().dimension();
            HistoryStore.attach(player,s);
            if(o.noClip&&!s.noClip&&player.isCreative()){player.getAbilities().flying=true;player.onUpdateAbilities();}
            s.reach=o.reach==null?Options.ReachMode.NORMAL:o.reach;s.farReach=Math.clamp(o.farReach,6,64);s.noClip=o.noClip;s.options=o;
            s.confirmEdits=o.confirmEdits;
            s.currentAction=r.action();s.recordSelection=changesSelection(r.action(),o);
            // The builder and the clipboard placement preview have their own confirmation, so they apply straight away.
            s.bypassConfirm=r.action().startsWith("builder_")||r.action().equals("paste_at")||r.action().equals("paste_select")||r.action().equals("pending_confirm");
            if(r.action().equals("status")) { send(player,s,"Ready",false);return; }
            if(r.action().equals("capabilities")){send(player,s,"Builder capabilities synchronized",false);return;}
            if(r.action().equals("gamemode_editor")){ // sent as the editor opens and closes: no status message, unlike the buttons, and never throttled
                if(!player.isSpectator()){s.previousMode=player.gameMode.getGameModeForPlayer();player.setGameMode(GameType.SPECTATOR);}
                return;
            }
            if(r.action().equals("gamemode_restore")){restoreMode(player,s);return;}
            if(r.action().equals("pending_confirm")){
                var edit=s.pendingEdit;
                if(edit==null){send(player,s,"Nothing to confirm",false);return;}
                s.pendingEdit=null;s.pendingSelectionBefore=null;s.pendingDirty=true;start(player,s,edit);return;
            }
            if(r.action().equals("pending_cancel")){
                if(s.pendingEdit!=null){
                    s.pendingEdit=null;s.pendingDirty=true;
                    if(s.pendingSelectionBefore!=null){s.selection.restore(s.pendingSelectionBefore);s.pendingSelectionBefore=null;}
                    send(player,s,"Edit cancelled",false);
                }
                return;
            }
            if(r.action().equals("editor_reset")){ // the way out of a stuck editor: forgets whatever is half done, but never an edit being applied
                s.pendingEdit=null;s.pendingSelectionBefore=null;s.pendingChain=null;s.pendingChainBase=s.pendingChainEdit=null;s.pendingDirty=true;s.selectionBefore=null;
                s.selection.clear();s.corner=s.ruler=s.line=null;s.selectedEntity=null;s.historyRemaining=0;s.pulse="";
                if((job==null||!job.owner.equals(player.getUUID()))&&!AnimatedRebuild.owned(player.getUUID()))s.busy=false;
                send(player,s,"Editor reset",false);return;
            }
            long now=System.nanoTime();
            // The export pop-up asks for a new preview as fields change and must not lose one, or an export, to a request just before it.
            boolean popup=Set.of("structure_preview","structure_export_file","structure_import_file").contains(r.action());
            if(now-s.lastRequest<65_000_000L&&!popup&&!r.action().equals("blueprint_list")&&!r.action().equals("hotbar_list"))return;
            if(!popup)s.lastRequest=now;
            if(r.action().equals("selection_restore")){
                if(!s.undo.isEmpty()&&s.undo.peekFirst().selectionOnly())s.undo.removeFirst();
                if(s.selectionBefore!=null){s.selection.restore(s.selectionBefore);s.selectionBefore=null;}send(player,s,"Selection cancelled",false);return;}
            if(r.action().equals("select_box")||r.action().equals("lasso_select")||r.action().equals("use")&&o.tool.selection())s.selectionBefore=new ArrayList<>(s.selection.points());
            if(r.action().equals("gamemode")||r.action().startsWith("gamemode_")) {
                s.previousMode=null; // choosing a mode by hand replaces whatever the editor would have restored
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
            // Reads for an AI client: the server's own view of the world, answered with a packet only the AI bridge looks at.
            // "target" and "normal" carry the two corners here, so this comes before "normal" is checked as a direction.
            switch(r.action()){
                case "ai_blocks" -> {ServerPlatform.send(player,new Packets.AiReply(AiWorld.blocks(player,r.target(),r.normal())));return;}
                case "ai_surface","ai_surface_floor" -> {ServerPlatform.send(player,new Packets.AiReply(AiWorld.surface(player,r.target(),r.normal(),r.action().equals("ai_surface_floor"))));return;}
                case "ai_build_preview","ai_apply","ai_cancel","ai_undo" -> {aiAction(player,r.action(),o);return;}
                default -> {}
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
                case "selection_transform" -> {
                    var plan=new EditPlanner(player.level(),s,o);
                    var before=new ArrayList<>(s.selection.points());
                    // While transforms wait for confirmation, a new one works from the original blocks, not from the preview of the last.
                    var staged=s.pendingEdit;boolean confirming=s.confirmEdits&&!s.bypassConfirm;
                    var chain=confirming&&staged!=null&&staged==s.pendingChainEdit?s.pendingChain:null;
                    var base=chain!=null?s.pendingChainBase:confirming?staged:null; // what was staged before the run of transforms began
                    if(base!=null)for(var c:base.changes())plan.changes.put(c.pos(),c);
                    var result=ModelTransform.apply(plan,s,o,chain);var moved=result.moved();
                    // The selection follows its blocks; if the edit is cancelled it goes back.
                    var priorBefore=s.pendingSelectionBefore;
                    var original=confirming&&priorBefore!=null?priorBefore:before; // undo puts back the selection from before the whole run
                    if(confirming&&priorBefore==null)s.pendingSelectionBefore=before;
                    s.selection.combine(moved,Selection.Mode.REPLACE);
                    try{
                        var edit=plan.finish("Transform selection").withSelection(new Session.SelectionChange(original,new ArrayList<>(moved),pivotText(o,0,0,0),pivotText(o,o.offsetX,o.offsetY,o.offsetZ)));
                        if(base!=null)edit=new Session.Edit(edit.name(),edit.changes(),base.biomes(),edit.flags(),base.entities(),base.annotations(),edit.selection());
                        if(confirming)s.pendingEdit=null; // the new edit already holds what was staged, so it replaces it
                        start(player,s,edit);
                        if(confirming&&s.pendingEdit!=null){s.pendingChain=result.chain();s.pendingChainBase=base;s.pendingChainEdit=s.pendingEdit;}
                    }
                    catch(RuntimeException e){s.selection.restore(before);s.pendingSelectionBefore=priorBefore;if(confirming)s.pendingEdit=staged;throw e;}
                }
                case "selection_filter","selection_bounds","selection_distort","selection_smooth","selection_scale","selection_gizmo" -> {var plan=new EditPlanner(player.level(),s,o);s.selection.combine(SelectionTransforms.apply(r.action(),plan),Selection.Mode.REPLACE);send(player,s,"Selection updated: "+s.selection.size()+" blocks",false);}
                case "clear" -> {s.selection.clear();s.corner=null;s.line=null;s.ruler=null;send(player,s,"Selection cleared",false);}
                case "ruler_clear" -> {s.ruler=null;send(player,s,"Measurement cleared",false);}
                case "asset_upload","blueprint_list","blueprint_save","blueprint_load","blueprint_data","structure_export","schematic_export","schematic_import","selection_csv" -> {
                    var plan=new EditPlanner(player.level(),s,o);String result=BlueprintLibrary.act(r.action(),plan);if(Set.of("blueprint_save","structure_export","schematic_export","selection_csv","blueprint_data").contains(r.action()))result+=BlueprintLibrary.download(r.action(),plan,player);send(player,s,result,false);
                }
                case "structure_preview","structure_export_file","structure_import_file" -> {
                    var plan=new EditPlanner(player.level(),s,o);String result=StructureLibrary.act(r.action(),plan,player);if(!result.isEmpty())send(player,s,result,false);
                }
                case "entity_copy","entity_cut" -> {
                    var entity=player.level().getEntity(UUID.fromString(o.entityUuid));
                    if(entity==null||!EntityEdits.editable(entity)||entity.distanceToSqr(player)>256*256)throw new IllegalArgumentException("Select a nearby display or marker");
                    new EditPlanner(player.level(),s,o).validate(EditPlanner.point(entity.blockPosition()));
                    var before=EntityEdits.snapshot(entity,player.level());var saved=before.copy();ClipboardEntities.offset(saved,-entity.getBlockX(),-entity.getBlockY(),-entity.getBlockZ());
                    if(saved.toString().length()>1_000_000)throw new IllegalArgumentException("Entity clipboard exceeds the data limit");
                    s.selectedEntity=entity.getUUID();s.clipboard.clear();s.clipboardOrigin=null;s.clipboardEntities.clear();s.clipboardEntities.add(saved);s.clipboardDirty=true;
                    if(r.action().equals("entity_cut"))start(player,s,new Session.Edit("Cut entity",List.of(),List.of(),2,List.of(new Session.EntityChange(entity.getUUID(),before,null))));
                    else send(player,s,"Copied entity to clipboard",false);
                }
                case "entity_select" -> {if(target==null)throw new IllegalArgumentException("Point near a display or marker");s.selectedEntity=EntityEdits.nearest(player.level(),target).getUUID();send(player,s,"Entity selected",false);}
                case "entity_select_uuid" -> {var entity=player.level().getEntity(UUID.fromString(o.entityUuid));if(entity==null||!EntityEdits.editable(entity)||entity.distanceToSqr(player)>256*256)throw new IllegalArgumentException("Select a nearby display or marker");s.selectedEntity=entity.getUUID();send(player,s,"Entity selected",false);}
                case "entity_spawn","entity_update","entity_move","entity_duplicate","entity_delete","entity_transform","entity_gizmo","entity_reset","entity_group","entity_ungroup","entity_group_transform" -> {if(target==null)throw new IllegalArgumentException("Point at a block");start(player,s,EntityEdits.plan(r.action(),player,s,o,target));}
                case "annotation_create","annotation_update","annotation_delete","annotation_clear" -> {if(target==null)target=o.position1;start(player,s,AnnotationEdits.plan(r.action(),player.level(),o,target));}
                case "select_box","select_box_adjust" -> {
                    // Adjusting a box starts again from the selection as it was before that box was made.
                    if(r.action().equals("select_box_adjust")&&s.selectionBefore!=null)s.selection.restore(s.selectionBefore);
                    var plan=new EditPlanner(player.level(),s,o);var points=Selection.box(o.position1,o.position2);
                    for(Point p:points)plan.validate(p);s.selection.combine(masked(plan,o,points),o.mode);send(player,s,"Selected "+s.selection.size()+" blocks",false);
                }
                case "lasso_select" -> {
                    var plan=new EditPlanner(player.level(),s,o);for(Point p:o.selectionPoints){plan.validate(p);if(p.distanceSquared(new Point((int)player.getX(),(int)player.getY(),(int)player.getZ()))>256*256)throw new IllegalArgumentException("Lasso reaches beyond editor range");}
                    s.selection.combine(masked(plan,o,o.selectionPoints),o.mode);send(player,s,"Lasso selected "+s.selection.size()+" blocks",false);
                }
                case "selection_move", "selection_expand", "selection_shrink", "selection_invert", "selection_convex" -> {
                    var next=new LinkedHashSet<Point>(s.selection.points());
                    if(r.action().equals("selection_move")){next.clear();for(Point p:s.selection.points())next.add(p.add(o.offsetX,o.offsetY,o.offsetZ));}
                    else if(r.action().equals("selection_invert")){var all=new LinkedHashSet<>(Selection.box(s.selection.min(),s.selection.max()));all.removeAll(next);next=all;}
                    else if(r.action().equals("selection_convex")){next=new LinkedHashSet<>(ConvexHull.fill(s.selection.points()));}
                    else for(int i=0;i<Math.max(1,o.selectionResize>0?o.selectionResize:(int)o.strength);i++){
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
                case "flip_x","flip_y","flip_z" -> transform(player,s,false,r.action().charAt(5)-'x');
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
                case "undo", "redo" -> {s.historyRemaining=0;if(r.action().equals("undo"))undoAnimated(player,s);else history(player,s,false);}
                case "fill", "delete" -> {
                    requireSelection(s);var plan=new EditPlanner(player.level(),s,o);
                    for(Point p:s.selection.points())plan.put(p,r.action().equals("delete")?Blocks.AIR.defaultBlockState():plan.material);
                    start(player,s,plan.finish(r.action().equals("delete")?"Delete selection":"Fill selection"));
                }
                case "paste", "paste_at", "paste_select", "paste_in_place" -> {
                    boolean inPlace=r.action().equals("paste_in_place");
                    if(inPlace&&s.clipboardOrigin==null)throw new IllegalArgumentException("These blocks were not copied from the world, so there is no place to paste them back to");
                    if(target==null&&!inPlace)throw new IllegalArgumentException("Point at a block to place the clipboard");
                    if(s.clipboard.isEmpty()&&s.clipboardEntities.isEmpty())throw new IllegalArgumentException("Copy a selection first");
                    var plan=new EditPlanner(player.level(),s,o);
                    Point origin=inPlace?s.clipboardOrigin:r.action().equals("paste")?target.add(normal.x(),normal.y(),normal.z()):target;
                    var placed=new LinkedHashSet<Point>();
                    for(var entry:s.clipboard.entrySet()) {
                        var relative=entry.getKey();var value=entry.getValue();
                        if(!o.pasteAir && value.state().isAir())continue;
                        Point at=origin.add(relative.x(),relative.y(),relative.z());plan.putClipboard(at,value);placed.add(at);
                    }
                    Session.SelectionChange selected=null;
                    if(r.action().equals("paste_select")){ // the pasted blocks become the selection, and undo takes it back
                        var before=new ArrayList<>(s.selection.points());
                        s.selection.combine(placed,Selection.Mode.REPLACE);
                        selected=new Session.SelectionChange(before,new ArrayList<>(placed));
                    }
                    var edit=plan.finish("Paste clipboard");start(player,s,new Session.Edit(edit.name(),edit.changes(),edit.biomes(),edit.flags(),o.pasteEntities?ClipboardEntities.paste(player,s,origin):List.of(),List.of(),selected));
                }
                case "use", "builder_place", "builder_break" -> {
                    if(target==null)throw new IllegalArgumentException("Point at a block within reach");
                    var plan=new EditPlanner(player.level(),s,o);
                    if(r.action().equals("builder_place")) {
                        if(o.tinker && player.getMainHandItem().isEmpty()) {
                            var old=plan.state(target);
                            var aim=dev.craftium.runtime.TinkerRules.Aim.of(normal==null?0:normal.x(),normal==null?1:normal.y(),normal==null?0:normal.z(),o.tinkerX,o.tinkerY,o.tinkerZ);
                            var result=dev.craftium.runtime.TinkerRules.apply(old,aim,o.tinkerManual?o.tinkerIndex:-1);
                            if(result==null)throw new IllegalArgumentException("This block has no properties to tinker with");
                            plan.put(target,result.state());
                        } else {
                            Point at=o.replaceMode?target:target.add(normal.x(),normal.y(),normal.z());
                            plan.put(at,plan.preserve(plan.state(target),plan.material));
                        }
                        start(player,s,plan.finish("Builder placement"));
                    } else if(r.action().equals("builder_break")) {
                        plan.put(target,Blocks.AIR.defaultBlockState());start(player,s,plan.finish("Bulldozer"));
                    } else use(player,s,o,target,normal,plan);
                }
                case "bucket_fill" -> {
                    // Dragging a block into the world: the Magic Wand, always comparing by block, then a replace with the active block.
                    if(target==null)throw new IllegalArgumentException("Drop the block onto a block within reach");
                    var plan=new EditPlanner(player.level(),s,o);var origin=plan.state(target);
                    if(origin.isAir())throw new IllegalArgumentException("Drop the block onto a block, not empty space");
                    var found=MagicWand.select(target,new MagicWand.World(){
                        public boolean loaded(Point at){return player.level().hasChunk(at.x()>>4,at.z()>>4);}
                        public boolean air(Point at){return plan.state(at).isAir();}
                        public boolean matches(Point at){return plan.state(at).is(origin.getBlock());}
                        public boolean surface(Point at){return plan.surface(at);}
                    },new MagicWand.Params(o.selectLimit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,128,Selection.LIMIT*4)).found();
                    if(found.isEmpty())throw new IllegalArgumentException("Bucket fill found no connected blocks there; check the Magic Wand range and surface settings");
                    for(Point at:found)plan.put(at,plan.preserve(plan.state(at),plan.material));
                    if(plan.changes.isEmpty()){
                        String why=origin.getBlock()==plan.material.getBlock()?"those blocks are already that block"
                            :!BlockMask.of(o).allows(origin)||!o.mask.isBlank()?"the block mask excludes them"
                            :o.inSelection?"Only in selection is on":"the Y range, or the block can't be placed there (enable force place)";
                        throw new IllegalArgumentException("Bucket fill changed nothing: "+why+" ("+found.size()+" blocks matched)");
                    }
                    start(player,s,plan.finish("Bucket fill"));
                }
                default -> throw new IllegalArgumentException("Unknown editor action");
            }
        } catch(IllegalArgumentException e) {s.historyRemaining=0;send(player,s,e.getMessage(),true);}
        catch(Exception e) {ServerPlatform.LOG.error("Editor request failed",e);send(player,s,"Operation failed; see the Craftium log",true);}
    }
    /** The points a selection tool would add, less any the block mask rules out. */
    private static List<Point> masked(EditPlanner plan,Options o,Iterable<Point> points){
        var mask=BlockMask.of(o);var kept=new ArrayList<Point>();
        for(Point p:points)if(mask.allows(plan.state(p)))kept.add(p);
        return kept;
    }
    private static void use(ServerPlayer player,Session s,Options o,Point p,Point normal,EditPlanner plan) {
        switch(o.tool) {
            case ANNOTATION -> start(player,s,AnnotationEdits.plan("annotation_create",player.level(),o,p));
            case BOX_SELECT -> {
                if(s.corner==null) {s.corner=p;send(player,s,"First corner: "+p.coordinates()+" · choose the opposite corner",false);}
                else {s.selection.combine(masked(plan,o,Selection.box(s.corner,p)),o.mode);s.corner=null;send(player,s,"Selected "+s.selection.size()+" blocks",false);}
            }
            case FREEHAND_SELECT -> {
                s.selection.combine(masked(plan,o,plan.brush(p)),o.mode);send(player,s,"Selected "+s.selection.size()+" blocks",false);
            }
            case MAGIC_SELECT -> {
                var origin=plan.state(p);var originData=o.compare==Options.Compare.NBT?AdvancedTools.data(plan,p):null;var blockMask=BlockMask.of(o);
                var found=MagicWand.select(p,new MagicWand.World(){
                    public boolean loaded(Point at){return player.level().hasChunk(at.x()>>4,at.z()>>4);}
                    public boolean air(Point at){return plan.state(at).isAir();}
                    public boolean matches(Point at){return blockMask.allows(plan.state(at))&&AdvancedTools.matches(plan,origin,originData,at);}
                    public boolean surface(Point at){return plan.surface(at);}
                },new MagicWand.Params(o.selectLimit,o.selectRange,o.selectCorners,o.selectUp,o.selectDown,o.selectHorizontal,o.surfaceOnly,128,Selection.LIMIT*4)).found();
                s.selection.combine(found,o.mode);send(player,s,"Magic Wand: "+s.selection.size()+" blocks (128-block search radius)",false);
            }
            case RULER -> {
                if(s.ruler==null) {s.ruler=p;send(player,s,"Ruler start: "+p.coordinates(),false);}
                else {Point a=s.ruler;double distance=Math.sqrt(a.distanceSquared(p));int manhattan=Math.abs(a.x()-p.x())+Math.abs(a.y()-p.y())+Math.abs(a.z()-p.z());
                    String message=String.format(Locale.ROOT,"Ruler: %.2f blocks · Manhattan %d · Δ %s",distance,manhattan,p.subtract(a).coordinates());
                    s.event(message);s.ruler=null;send(player,s,message,false);}
            }
            case LINE -> {
                if(s.line==null) {s.line=p;send(player,s,"Line start set · choose the endpoint",false);}
                else {for(Point point:BrushMath.line(s.line,p))for(Point at:BrushMath.brush(point,Math.min(o.radius,4),o.shape,o.hollow,o.outlineThickness,o.pixelPerfect))plan.put(at,plan.material);
                    s.line=null;start(player,s,plan.finish("Draw line"));}
            }
            case SHAPE -> {
                plan.tool(p,normal);
                var edit=plan.finish(o.tool.label);
                // What was placed becomes the selection, so the transform tools can take hold of it. Carving leaves nothing to select.
                var placed=new LinkedHashSet<Point>();
                if(!o.invert)for(var change:edit.changes())placed.add(change.pos());
                if(placed.isEmpty()){start(player,s,edit);return;}
                var before=new ArrayList<>(s.selection.points());
                if(s.confirmEdits&&!s.bypassConfirm&&s.pendingSelectionBefore==null)s.pendingSelectionBefore=before;
                s.selection.combine(placed,Selection.Mode.REPLACE);
                try{start(player,s,edit.withSelection(new Session.SelectionChange(before,new ArrayList<>(placed))));}
                catch(RuntimeException e){s.selection.restore(before);s.pendingSelectionBefore=null;throw e;}
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
        s.clipboardOrigin=min;
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
    /** The AI's workspace for this player, made when it is first needed, and started afresh when the player has changed dimension. */
    private static Session aiSession(ServerPlayer player){
        var ai=AI_SESSIONS.get(player.getUUID());
        if(ai!=null&&ai.dimension!=null&&!ai.dimension.equals(player.level().dimension())){
            if(ai.busy)throw new IllegalArgumentException("Wait for the current AI build to finish");
            ai=null;
        }
        if(ai==null){ai=new Session();ai.ai=true;ai.dimension=player.level().dimension();AI_SESSIONS.put(player.getUUID(),ai);}
        return ai;
    }
    /**
     * What an AI client can do with its workspace: hold a build as a preview, apply it, discard it or undo the last build it applied. None of
     * it touches the player's selection, preview or undo history, and none of it needs the player nearby.
     */
    private static void aiAction(ServerPlayer player,String action,Options o){
        var ai=aiSession(player);
        switch(action){
            case "ai_build_preview" -> {
                if(ai.busy)throw new IllegalArgumentException("Wait for the current AI build to finish");
                ai.pendingEdit=null;ai.pendingDirty=true; // a new preview replaces the last, and stays gone if this one is refused
                var edit=BuildPlanner.plan(player,ai,o);
                if(edit.changes().isEmpty()){send(player,ai,"No matching blocks changed",false);return;}
                if(edit.estimatedBytes()>32*1024*1024)throw new IllegalArgumentException("Edit exceeds the 32 MiB history budget; use a smaller build");
                EditProtection.check(player,edit);
                ai.pendingEdit=edit;
                send(player,ai,"AI preview ready: "+edit.changes().size()+" blocks",false);
            }
            case "ai_apply" -> {
                var edit=ai.pendingEdit;
                if(edit==null){send(player,ai,"No AI preview to apply",false);return;}
                if(job!=null||AnimatedRebuild.active())throw new IllegalArgumentException("Another edit or animated rebuild is applying; try again in a moment");
                EditProtection.check(player,edit);
                ai.pendingEdit=null;ai.pendingDirty=true;
                ai.busy=true;job=new Job(player.getUUID(),player.level(),ai,edit,false,false,new int[]{0},new ArrayList<>(),0,true);
                send(player,ai,"Applying "+edit.name()+"…",false);
            }
            case "ai_cancel" -> {
                if(ai.pendingEdit!=null){ai.pendingEdit=null;ai.pendingDirty=true;send(player,ai,"AI preview discarded",false);}
                else send(player,ai,"No AI preview to discard",false);
            }
            default -> {ai.historyRemaining=0;history(player,ai,true);} // ai_undo
        }
    }
    private static void start(ServerPlayer player,Session s,Session.Edit edit) {
        if(edit.changes().isEmpty() && edit.biomes().isEmpty()&&edit.entities().isEmpty()&&edit.annotations().isEmpty()){send(player,s,"No matching blocks changed",false);return;}
        if(edit.estimatedBytes()>32*1024*1024)throw new IllegalArgumentException("Edit exceeds the 32 MiB history budget; use a smaller region");
        EditProtection.check(player,edit);
        if(s.confirmEdits&&!s.bypassConfirm){stage(player,s,edit);return;}
        if(job!=null||AnimatedRebuild.active())throw new IllegalArgumentException("Another edit or animated rebuild is applying; try again in a moment");
        s.busy=true;job=new Job(player.getUUID(),player.level(),s,edit,false,false,new int[]{0},new ArrayList<>(),s.requestStroke,false);
        send(player,s,"Applying "+edit.name()+"…",false);
    }
    /** Holds the edit as a ghost for the player to confirm; edits made while one is waiting are added to it. */
    private static void stage(ServerPlayer player,Session s,Session.Edit edit){
        s.pendingEdit=s.pendingEdit==null?edit:merge(s.pendingEdit,edit);s.pendingDirty=true;
        send(player,s,"Edit ready: "+s.pendingEdit.changes().size()+" blocks · Enter to apply, Esc to cancel",false);
    }
    private static Session.Edit merge(Session.Edit a,Session.Edit b){
        var changes=new LinkedHashMap<Point,Session.Change>();
        for(var c:a.changes())changes.put(c.pos(),c);
        for(var c:b.changes()){var earlier=changes.get(c.pos());changes.put(c.pos(),earlier==null?c:new Session.Change(c.pos(),earlier.before(),c.after()));}
        var biomes=new ArrayList<>(a.biomes());biomes.addAll(b.biomes());
        var entities=new ArrayList<>(a.entities());entities.addAll(b.entities());
        var annotations=new ArrayList<>(a.annotations());annotations.addAll(b.annotations());
        var selection=a.selection()!=null&&b.selection()!=null?a.selection().then(b.selection()):a.selection()!=null?a.selection():b.selection();
        return new Session.Edit(a.name(),new ArrayList<>(changes.values()),biomes,a.flags()|b.flags(),entities,annotations,selection);
    }
    private static final int PULSE_MS=600,PULSE_POINTS=8192;
    /** The points a pulse shows: all of them, or an even sample when there are too many to draw. */
    private static List<Point> pulsePoints(List<Point> all){
        if(all.size()<=PULSE_POINTS)return all;
        var sample=new ArrayList<Point>(PULSE_POINTS);double step=all.size()/(double)PULSE_POINTS;
        for(int i=0;i<PULSE_POINTS;i++)sample.add(all.get((int)(i*step)));
        return sample;
    }
    /** Undo straight away, with the blocks it removes sent along for the client to pulse red; a refused undo takes the pulse back. */
    private static void undoAnimated(ServerPlayer player,Session s){
        var edit=s.undo.peekFirst();
        if(edit!=null&&!edit.selectionOnly()&&!edit.changes().isEmpty()&&s.options!=null&&s.options.historyPulse)
            s.pulse="undo:"+PULSE_MS+":"+SelectionCodec.encode(pulsePoints(edit.changes().stream().map(Session.Change::pos).toList()));
        try{history(player,s,true);}catch(IllegalArgumentException e){s.pulse="";throw e;}
    }
    /** Undo or redo puts back what the edit changed besides blocks: the player's selection and pivot, and client-side state such as the builder's box. */
    private static void handBack(Session s,Session.SelectionChange change,boolean backwards){
        if(change.hasPoints())s.selection.restore(backwards?change.before():change.after());
        String pivot=backwards?change.pivotBefore():change.pivotAfter();
        if(pivot!=null)s.pivotUpdate=pivot;
        var values=backwards?change.restoreBefore():change.restoreAfter();
        if(!values.isEmpty())s.restoreUpdate=dev.craftium.core.Restore.encode(values);
    }
    private static void history(ServerPlayer player,Session s,boolean backwards) {
        if(job!=null||AnimatedRebuild.active())throw new IllegalArgumentException("Wait for the current edit or animated rebuild to finish");
        var from=backwards?s.undo:s.redo;
        if(from.isEmpty()){s.historyRemaining=0;send(player,s,backwards?"Nothing to undo":"Nothing to redo",false);return;}
        var edit=from.peekFirst();
        if(edit.selectionOnly()){ // a selection has no blocks: put it back directly
            from.removeFirst();
            handBack(s,edit.selection(),backwards);
            (backwards?s.redo:s.undo).addFirst(edit);
            s.recalculateBytes();s.historyDirty=true;s.event((backwards?"Undo ":"Redo ")+edit.name());
            send(player,s,(backwards?"Undid ":"Redid ")+edit.name(),false);return;
        }
        // The AI's builds can be far from the player, so its undo loads the chunks it needs as it goes instead of asking the player to move.
        if(!s.ai){
            for(var c:edit.changes())if(!player.level().hasChunk(c.pos().x()>>4,c.pos().z()>>4))throw new IllegalArgumentException("Move closer to the edited chunks before undo/redo");
            for(var c:edit.biomes())if(!player.level().hasChunk(c.cell().x()>>2,c.cell().z()>>2))throw new IllegalArgumentException("Move closer to the biome chunks before undo/redo");
        }
        EditProtection.check(player,edit);
        from.removeFirst();s.busy=true;job=new Job(player.getUUID(),player.level(),s,edit,backwards,true,new int[]{0},new ArrayList<>(),0,s.ai);
        s.lastStroke=0;
        send(player,s,backwards?"Undoing…":"Redoing…",false);
    }
    public static void tick(MinecraftServer server) {
        AnimatedRebuild.tick(server);
        if(++historyTicks%100==0)for(var session:SESSIONS.values())HistoryStore.save(session);
        var reachId=dev.craftium.compat.Compat.id("craftium","builder_reach");
        for(var player:server.getPlayerList().getPlayers()){
            var s=SESSIONS.get(player.getUUID());
            var range=player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
            // Reach is the game's own block interaction range, raised for as long as the capability is on, so every vanilla check agrees with it.
            var mode=s!=null&&player.isCreative()&&ServerPlatform.permitted(player,"capabilities")?s.reach:Options.ReachMode.NORMAL;
            if(range!=null){
                double extra=switch(mode){case NORMAL->0;case FAR->s.farReach-range.getBaseValue();case INFINITE->250;};
                var current=range.getModifier(reachId);
                if(current!=null&&(extra==0||current.amount()!=extra))range.removeModifier(reachId);
                if(extra!=0&&range.getModifier(reachId)==null)range.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(reachId,extra,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
        }
        if(job==null)return;var j=job;
        try {
            var list=j.edit.changes();long deadline=System.nanoTime()+8_000_000L;int count=0;
            while(j.cursor[0]<list.size() && count++<2048 && System.nanoTime()<deadline) {
                var c=list.get(j.cursor[0]++);BlockPos at=EditPlanner.pos(c.pos());
                var expected=j.backwards?c.after():c.before();var desired=j.backwards?c.before():c.after();
                if((j.load||j.level.hasChunk(at.getX()>>4,at.getZ()>>4)) && expected.matches(j.level,at)) {
                    if(!ServerPlatform.canChange(j.owner,j.level,c.pos()))throw new IllegalArgumentException("Edit permission or region access changed");
                    desired.place(j.level,at,j.edit.flags());j.applied.add(c);
                }
            }
            if(j.cursor[0]<list.size())return;
            if(!EditProtection.allowed(j.owner,j.level,new Session.Edit(j.edit.name(),List.of(),List.of(),j.edit.flags(),j.edit.entities(),j.edit.annotations())))throw new IllegalArgumentException("Entity or annotation access changed");
            var appliedBiomes=applyBiomes(j);
            var appliedEntities=EntityEdits.apply(j.level,j.edit.entities(),j.backwards);
            var appliedAnnotations=AnnotationEdits.apply(j.level,j.edit.annotations(),j.backwards);
            var actual=new Session.Edit(j.edit.name(),List.copyOf(j.applied),appliedBiomes,j.edit.flags(),appliedEntities,appliedAnnotations,j.edit.selection(),j.edit.time());
            int skipped=j.edit.changes().size()-j.applied.size()+j.edit.biomes().size()-appliedBiomes.size()+j.edit.entities().size()-appliedEntities.size()+j.edit.annotations().size()-appliedAnnotations.size();
            if(j.history) {
                // Retain only the cells actually restored; conflicting subsequent edits are left intact.
                (j.backwards?j.session.redo:j.session.undo).addFirst(actual);
                // The selection goes back or forward with the blocks it was moved with.
                if(j.edit.selection()!=null){
                    handBack(j.session,j.edit.selection(),j.backwards);
                }
                j.session.recalculateBytes();
                j.session.historyDirty=true;
                j.session.event((j.backwards?"Undo ":"Redo ")+j.edit.name());
            } else j.session.remember(actual,j.strokeId);
            j.session.busy=false;job=null;
            if(j.history&&!j.backwards&&j.session.historyRemaining==0&&!j.applied.isEmpty()&&j.session.options!=null&&j.session.options.historyPulse)j.session.pulse="redo:700:"+SelectionCodec.encode(pulsePoints(j.applied.stream().map(Session.Change::pos).toList()));
            var player=server.getPlayerList().getPlayer(j.owner);
            if(player!=null)send(player,j.session,(j.backwards?"Undid ":j.history?"Redid ":"Applied ")+j.edit.name()+" · "+(j.applied.size()+appliedBiomes.size()+appliedEntities.size()+appliedAnnotations.size())+" changes"+(skipped>0?" · "+skipped+" changed or unavailable cells skipped":""),false);
            else release(j);
            if(!appliedAnnotations.isEmpty())for(var other:server.getPlayerList().getPlayers())if(!other.getUUID().equals(j.owner)&&other.level()==j.level){var session=SESSIONS.get(other.getUUID());if(session!=null)send(other,session,"Annotations updated",false);}
            if(player!=null&&j.history&&j.session.historyRemaining!=0){boolean backwards=j.session.historyRemaining>0;j.session.historyRemaining+=backwards?-1:1;try{history(player,j.session,backwards);}catch(IllegalArgumentException e){j.session.historyRemaining=0;send(player,j.session,e.getMessage(),true);}}
        } catch(Exception e) {
            ServerPlatform.LOG.error("Craftium edit interrupted",e);
            rememberInterrupted(j);
            j.session.busy=false;job=null;var player=server.getPlayerList().getPlayer(j.owner);
            if(player!=null)send(player,j.session,"Edit interrupted. Applied changes remain in history.",true);
            else release(j);
        }
    }
    /** Lets go of a finished job's session when its player has left: the player's is saved and dropped, the AI's is just dropped. */
    private static void release(Job j){
        if(j.session.ai){AI_SESSIONS.remove(j.owner);return;}
        HistoryStore.save(j.session);SESSIONS.remove(j.owner);
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
    /** True for the actions that exist to change the selection, which the history records so they can be undone. */
    private static boolean changesSelection(String action,Options o){
        return switch(action){
            case "select_box","select_box_adjust","lasso_select","clear","selection_move","selection_expand","selection_shrink","selection_invert","selection_convex",
                 "selection_filter","selection_bounds","selection_distort","selection_smooth","selection_scale","selection_gizmo"->true;
            case "use"->o.tool.selection();
            default->false;
        };
    }
    /** The player's pivot as text, moved by an offset, for the history to hand back on undo: "none" when it is the selection's centre. */
    private static String pivotText(Options o,int dx,int dy,int dz){
        return o.pivotSet?String.format(java.util.Locale.ROOT,"%s,%s,%s",o.pivotX+dx,o.pivotY+dy,o.pivotZ+dz):"none";
    }
    private static String selectionName(String action,int size){
        return switch(action){
            case "clear"->"Deselect";
            case "selection_expand"->"Expand selection";
            case "selection_shrink"->"Shrink selection";
            case "selection_invert"->"Invert selection";
            case "selection_move"->"Move selection";
            default->"Select "+size+" blocks";
        };
    }
    /** Adds the selection's latest change to the history, and remembers the selection as it is now. */
    private static void noteSelection(Session s){
        if(s.selection.revision()==s.historyRevision)return;
        var after=new ArrayList<>(s.selection.points());
        if(s.recordSelection&&!after.equals(s.historySelection)){
            var before=s.historySelection;
            // Dragging a box handle redoes the same selection many times: keep one entry, from where it began.
            if(s.currentAction.equals("select_box_adjust")&&!s.undo.isEmpty()&&s.undo.peekFirst().selectionOnly()){before=s.undo.removeFirst().selection().before();}
            s.remember(Session.Edit.selecting(selectionName(s.currentAction,after.size()),new Session.SelectionChange(before,after)));
        }
        s.historySelection=after;s.historyRevision=s.selection.revision();
    }
    /** How long ago the edit was made, in milliseconds; -1 for none, and a very large age when its time is unknown. */
    private static long age(Session.Edit edit){return edit==null?-1:edit.time()==0?Long.MAX_VALUE/4:Math.max(0,System.currentTimeMillis()-edit.time());}
    static void send(ServerPlayer player,Session s,String message,boolean error) {
        // A status always describes the player's own session, so an AI action never changes what the player sees of their selection or edits;
        // the AI's preview and undo count ride along in fields of their own.
        var ai=s.ai?s:AI_SESSIONS.get(player.getUUID());
        if(s.ai)s=SESSIONS.computeIfAbsent(player.getUUID(),id->new Session());
        noteSelection(s);
        if(!ServerPlatform.canSend(player))return;
        String entityId="",entityNbt="";if(s.selectedEntity!=null){var entity=player.level().getEntity(s.selectedEntity);if(entity!=null){entityId=s.selectedEntity.toString();String nbt=EntityEdits.snapshot(entity,player.level()).toString();if(nbt.length()<12000)entityNbt=nbt;}}
        if(s.selectionRevision!=s.selection.revision()){s.selectionRevision=s.selection.revision();s.selectionData=SelectionCodec.encode(s.selection.points());}
        if(s.clipboardDirty){s.clipboardGhostDirty=true;s.clipboardData=SelectionCodec.encode(s.clipboard.entrySet().stream().filter(e->!e.getValue().state().isAir()).map(Map.Entry::getKey).toList());s.clipboardDirty=false;}
        if(s.pendingDirty){s.pendingData=s.pendingEdit==null?"":GhostCodec.encodeEdit(s.pendingEdit);s.pendingDirty=false;}
        if(ai!=null&&ai.pendingDirty){ai.pendingData=ai.pendingEdit==null?"":GhostCodec.encodeEdit(ai.pendingEdit);ai.pendingDirty=false;}
        if(s.clipboardGhostDirty){s.clipboardGhost=GhostCodec.encodeClipboard(s.clipboard);s.clipboardGhostDirty=false;}
        var status=new Packets.Status(message,error,s.busy||(ai!=null&&ai.busy),s.selection.size(),s.selection.min(),s.selection.max(),s.clipboard.size(),s.undo.stream().map(edit->edit.selectionOnly()?edit.name():edit.name()+" · "+(edit.changes().size()+edit.biomes().size()+edit.entities().size()+edit.annotations().size())).toList(),s.undo.size(),s.redo.size(),entityId,entityNbt,AnnotationEdits.list(player.level()),s.selectionData,s.clipboardData,s.library,s.clipboardEntities.size(),s.pendingData,s.clipboardGhost,s.pivotUpdate,age(s.undo.peekFirst()),age(s.redo.peekFirst()),s.undo.isEmpty()?"":s.undo.peekFirst().name(),s.redo.isEmpty()?"":s.redo.peekFirst().name(),s.pulse,ai==null?"":ai.pendingData,ai==null?0:ai.undo.size(),ai==null||ai.undo.isEmpty()?"":ai.undo.peekFirst().name(),s.restoreUpdate);s.library=List.of();s.pivotUpdate="";s.restoreUpdate="";s.pulse="";
        ServerPlatform.send(player,new Packets.Update(Packets.JSON.toJson(status)));
    }
}
