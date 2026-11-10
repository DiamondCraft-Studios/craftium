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
    private static final Map<AiKey,Session> AI_SESSIONS=new LinkedHashMap<>();
    /** Which AI workspace: a player's AI apps each have one, named by the id the app made up ("" for an app that predates sessions). Kept in the order they began. */
    private record AiKey(UUID owner,String id){}
    private static Job job;
    /** Keeps the chunks the running edit touches loaded until it is done, however far they are from every player. */
    private static ChunkLease jobLease=ChunkLease.NONE;
    /** How long one tick may spend writing an edit's blocks: 20 of its 50 ms, which leaves the rest of the tick its time and still finishes a large undo in a few ticks. */
    private static final long APPLY_BUDGET_NANOS=20_000_000L;
    private static int historyTicks;
    private record Job(UUID owner, ServerLevel level, Session session, Session.Edit edit,
                       boolean backwards, boolean history, int[] cursor, List<Session.Change> applied, long strokeId, boolean load, List<Session.Change> order, boolean record) {}
    /** How many blocks are checked and then written together; a tick looks at the clock between slices. */
    private static final int SLICE=1024;
    /** An edit's blocks grouped by chunk, each chunk's in the order the edit has them, so a chunk can be written in one pass (see {@link BulkPlace}). */
    private static List<Session.Change> byChunk(List<Session.Change> changes){
        var grouped=new ArrayList<>(changes);
        grouped.sort(Comparator.comparingLong((Session.Change c)->((long)(c.pos().x()>>4)<<32)|((c.pos().z()>>4)&0xffffffffL)));
        return grouped;
    }
    /** Checks which of these blocks the edit may still change, then writes them, a chunk at a time. */
    private static void applySlice(Job j,List<Session.Change> slice){
        var due=new ArrayList<Session.Change>(slice.size());
        for(var c:slice){
            BlockPos at=EditPlanner.pos(c.pos());var expected=j.backwards?c.after():c.before();
            if((j.load||j.level.hasChunk(at.getX()>>4,at.getZ()>>4)) && expected.matches(j.level,at)) {
                if(!ServerPlatform.canChange(j.owner,j.level,c.pos()))throw new IllegalArgumentException("Edit permission or region access changed");
                due.add(c);
            } else if(j.level.hasChunk(at.getX()>>4,at.getZ()>>4)) {
                // Skipped: resend the block as it is, so a client that predicted this edit puts it back.
                var now=j.level.getBlockState(at);j.level.sendBlockUpdated(at,now,now,2);
            }
        }
        j.applied.addAll(due); // recorded before they are written: undoing a block that was not written is a no-op, losing one that was is not
        int flags=j.edit.flags();
        for(int from=0;from<due.size();){
            int cx=due.get(from).pos().x()>>4,cz=due.get(from).pos().z()>>4,to=from;
            while(to<due.size()&&due.get(to).pos().x()>>4==cx&&due.get(to).pos().z()>>4==cz)to++;
            var run=due.subList(from,to);
            if(BulkPlace.applies(run.size(),flags))BulkPlace.place(j.level,j.level.getChunk(cx,cz),run,j.backwards,flags);
            else for(var c:run)(j.backwards?c.before():c.after()).place(j.level,EditPlanner.pos(c.pos()),flags);
            from=to;
        }
        if(!j.backwards&&!j.history&&j.edit.name().equals(IN_WORLD_BREAK))breakEffects(j.level,due);
    }
    /** The name of the edit a left click in the world makes while the break is the editor's, not the game's: infinite reach or symmetry. */
    static final String IN_WORLD_BREAK="Bulldozer";
    /** Most blocks that play the game's break effect for one edit, so a long held break does not flood clients with particles. */
    private static final int BREAK_EFFECTS=16;
    /** The sound and particles the game plays when a player breaks a block, which the editor's own break would otherwise skip. */
    private static void breakEffects(net.minecraft.server.level.ServerLevel level,List<Session.Change> broken){
        int played=0;
        for(var c:broken){
            var old=c.before().state();if(old.isAir()||played++>=BREAK_EFFECTS)continue;
            level.levelEvent(2001,EditPlanner.pos(c.pos()),net.minecraft.world.level.block.Block.getId(old)); // 2001 is the game's block break event
        }
    }
    private EditorServer() {}
    /** Starts applying an edit, first leasing every chunk it reaches so that a far-away one is loaded for as long as the edit needs it. */
    private static void begin(ServerPlayer player,Session s,Session.Edit edit,boolean backwards,boolean history,long stroke,boolean load,boolean record){
        var area=ChunkLease.area();
        for(var c:edit.changes())area.block(c.pos().x(),c.pos().z());
        for(var c:edit.biomes())area.box(c.cell().x()*4,c.cell().z()*4,c.cell().x()*4+3,c.cell().z()*4+3);
        jobLease=area.lease(player.level());
        s.busy=true;job=new Job(player.getUUID(),player.level(),s,edit,backwards,history,new int[]{0},new ArrayList<>(),stroke,load,byChunk(edit.changes()),record);
    }
    /** Ends the running edit and lets its chunks go. */
    private static void endJob(){job=null;jobLease.close();jobLease=ChunkLease.NONE;}
    public static void clear() { SESSIONS.clear();AI_SESSIONS.clear();job=null;AnnotationEdits.clear();AnimatedRebuild.clear();ChunkLease.releaseAll();jobLease=ChunkLease.NONE; }
    /** Gives the player back the game mode the editor replaced, if it still has one to give back. */
    private static void restoreMode(ServerPlayer player,Session s){
        var mode=s.previousMode;s.previousMode=null;
        if(mode==null||!player.isSpectator())return; // they changed mode themselves in the meantime
        player.setGameMode(mode);
        if(player.isCreative()){player.getAbilities().flying=true;player.onUpdateAbilities();}
    }
    public static void disconnect(ServerPlayer player){var s=SESSIONS.get(player.getUUID());if(s!=null)restoreMode(player,s);disconnect(player.getUUID());refreshShared(player.level().getServer());}
    public static void disconnect(UUID id) {var session=SESSIONS.get(id);if(session!=null)HistoryStore.save(session);if(AnimatedRebuild.owned(id)){AnimatedRebuild.cancel(id);return;}if(job==null || !job.owner.equals(id)){SESSIONS.remove(id);AI_SESSIONS.keySet().removeIf(key->key.owner().equals(id));}}
    public static void flushHistory(MinecraftServer server){
        AnimatedRebuild.finishOnStop(server);
        // Players are saved as the server stops, before they are disconnected, so a player still in the editor would be saved as a spectator.
        for(var entry:SESSIONS.entrySet()){var player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null)restoreMode(player,entry.getValue());}
        if(job!=null){var j=job;rememberInterrupted(j);j.session.busy=false;endJob();}
        for(var session:SESSIONS.values())HistoryStore.save(session);try{HistoryStore.flush();}catch(java.util.concurrent.CompletionException e){ServerPlatform.LOG.error("Could not finish writing editor history during shutdown",e);}
    }
    private static void rememberInterrupted(Job j){rememberInterrupted(j.session,j.edit,j.applied,j.backwards,j.history);}
    static void rememberInterrupted(Session session,Session.Edit edit,List<Session.Change> applied,boolean backwards,boolean history){
        var partial=new Session.Edit(edit.name()+" (interrupted)",List.copyOf(applied),List.of(),edit.flags()).withBuilder(edit.builder());
        if(!history){if(!applied.isEmpty())session.remember(partial);return;}
        var positions=new HashSet<Point>();for(var c:applied)positions.add(c.pos());
        var remaining=new Session.Edit(edit.name(),edit.changes().stream().filter(c->!positions.contains(c.pos())).toList(),edit.biomes(),edit.flags(),edit.entities(),edit.annotations()).withBuilder(edit.builder());
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
            var plan=new EditPlanner(level,s,o);plan.builder=true;plan.put(p,after); // the block is already there, so only its mirrors are left to change; masks apply as they do to the Builder tools
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
            if(!o.recordPlayerBuilding){send(player,s,"Placed "+changes.size()+(changes.size()==1?" block":" blocks"),false);return;} // by hand, and not kept in the history
            s.remember(new Session.Edit("Builder placement",changes,List.of(),mirrors.flags()).withBuilder(true),s.placeStroke);
            send(player,s,"Placed "+changes.size()+(changes.size()==1?" block":" blocks"),false);
        }catch(IllegalArgumentException e){send(player,s,e.getMessage(),true);}
    }
    /** Whether the game's check on how far a player moves in a tick is skipped for them: Craftium players flying in creative or spectator who may use the capabilities. */
    public static boolean fastMovement(ServerPlayer player){return (SESSIONS.containsKey(player.getUUID())||dev.craftium.core.Handshake.clientHas(player.getUUID(),dev.craftium.core.Handshake.MODIFY_EXPANSION))&&(player.isCreative()||player.isSpectator())&&player.getAbilities().flying&&ServerPlatform.permitted(player,"capabilities");}
    public static boolean noClip(ServerPlayer player){var s=SESSIONS.get(player.getUUID());return s!=null&&s.noClip&&player.isCreative()&&player.getAbilities().flying&&ServerPlatform.permitted(player,"capabilities");} // only while flying

    /** How many builder commits wait behind the edit being applied; past this the newest is turned down as before. */
    private static final int QUEUE_LIMIT=8;
    private static long PREVIEW_IDS;
    /** The most characters of status a packet carries, with room to spare: a packet may be at most 2 MiB, and the string is limited to 2,000,000 characters. */
    private static final int STATUS_LIMIT=1_500_000;
    private static boolean replaying;
    public static void handle(ServerPlayer player,String json) {
        var s=SESSIONS.computeIfAbsent(player.getUUID(),id->new Session());
        try {
            if(!ServerPlatform.permitted(player,"edit"))
                throw new IllegalArgumentException("You do not have permission to use Craftium on this server");
            var refusal=dev.craftium.core.Handshake.refused(player.getUUID());
            if(refusal!=null)throw new IllegalArgumentException(refusal);
            var r=Packets.JSON.fromJson(json,Packets.Request.class);
            if(r==null || r.action()==null || r.options()==null)throw new IllegalArgumentException("Invalid editor request");
            if(!ServerPlatform.permitted(player,r.action()))throw new IllegalArgumentException("You do not have permission for this Craftium action");
            Options o=r.options();o.validate();
            if(o.pluginTool!=null&&!ServerPlatform.permitted(player,o.pluginTool))throw new IllegalArgumentException("You do not have permission to use plugin tools on this server");
            boolean inWorld=r.action().equals("builder_place")||r.action().equals("builder_break");
            s.requestRecord=o.recordPlayerBuilding||!inWorld; // a click in the world is the player building by hand; the editor's own tools always are recorded
            s.requestStroke=r.action().equals("use")||inWorld?r.strokeId():0;
            if(s.dimension!=null && !s.dimension.equals(player.level().dimension())) {
                if(s.busy)throw new IllegalArgumentException("Wait for the current operation to finish");
                HistoryStore.save(s);
                s=new Session();SESSIONS.put(player.getUUID(),s);
            }
            s.dimension=player.level().dimension();
            HistoryStore.attach(player,s);
            if(o.noClip&&!s.noClip&&player.isCreative()){player.getAbilities().flying=true;player.onUpdateAbilities();}
            s.reach=o.reach==null?Options.ReachMode.NORMAL:o.reach;s.farReach=Math.clamp(o.farReach,6,64);s.noClip=o.noClip;s.blockStep=o.blockStep;s.options=o;
            s.confirmEdits=o.confirmEdits;
            s.currentAction=r.action();s.recordSelection=changesSelection(r.action(),o);
            // The builder and the clipboard placement preview have their own confirmation, and dropping a block in the world is one, so they apply straight away.
            s.bypassConfirm=r.action().startsWith("builder_")||r.action().equals("bucket_fill")||r.action().equals("paste_at")||r.action().equals("paste_select")||r.action().equals("pending_confirm")||r.action().equals("stroke_end")||r.action().equals("use")&&(o.tool==Tool.EXTRUDE||o.tool==Tool.FLOODFILL||o.tool==Tool.PAINT_BUCKET)
                ||!o.enterToBuild&&!r.action().equals("use"); // with Enter to build off, a stroke is staged until the button is let go (stroke_end) and any other action is built at once
            if(r.action().equals("status")) { s.sharedKey=null;s.sentAi.clear();send(player,s,"Ready",false);return; }
            if(r.action().equals("capabilities")){send(player,s,"Builder capabilities synchronized",false);return;}
            if(r.action().equals("place_stroke")||r.action().equals("place_stroke_end")){s.placeStroke=r.action().equals("place_stroke")?r.strokeId():0;return;} // the game's own placements while the button is held; never throttled or answered
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
            if(r.action().equals("stroke_end")){ // a Sculpt stroke was released: build what it staged, with nothing said when it staged nothing
                var edit=s.pendingEdit;
                if(edit==null)return;
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
                s.pendingEdit=null;s.pendingSelectionBefore=null;s.pendingChain=null;s.pendingChainBase=s.pendingChainEdit=null;s.pendingDirty=true;s.selectionBefore=null;s.queued.clear();
                s.selection.clear();s.corner=s.ruler=s.line=null;s.selectedEntity=null;s.historyRemaining=0;s.pulse="";
                if((job==null||!job.owner.equals(player.getUUID()))&&!AnimatedRebuild.owned(player.getUUID()))s.busy=false;
                send(player,s,"Editor reset",false);return;
            }
            long now=System.nanoTime();
            // A builder commit is a deliberate click, not a stream, so it is never dropped by the throttle below: a lost one would leave the selection placed on the client only.
            // The export pop-up asks for a new preview as fields change and must not lose one, or an export, to a request just before it.
            boolean popup=Set.of("structure_preview","structure_export_file","structure_import_file").contains(r.action());
            if(!replaying&&!r.action().equals("builder_commit")&&now-s.lastRequest<65_000_000L&&!popup&&!r.action().equals("blueprint_list")&&!r.action().equals("hotbar_list"))return;
            if(!popup)s.lastRequest=now;
            if(r.action().equals("tool_preview")){toolPreview(player,s,o,r.target(),r.normal());return;} // the ghost of a plugin's tool, which is not an edit and answers with the ghost alone
            if(r.action().startsWith("ai_selection_named:")){ // one of the player's saved selections, read by the name it has in the Selections window
                ServerPlatform.send(player,new Packets.AiReply(aiSelectionNamed(player,r.action().substring("ai_selection_named:".length()))));return;
            }
            if(r.action().startsWith(SavedSelectionActions.PREFIX)){ // the Selections window (before the plugin actions, as "saved_selection:save" has the shape of one): swaps entries in and out of the live selection, never changes a block
                if(!Handshake.clientHas(player.getUUID(),Handshake.SAVED_SELECTIONS))throw new IllegalArgumentException("This client does not know saved selections");
                send(player,s,SavedSelectionActions.run(s,r.action()),false);return;
            }
            if(ActionRegistry.isNamespaced(r.action())){ // an action of a plugin's: only the registry answers, and a name nobody registered is an error, never one of the editor's
                var handler=ActionRegistry.find(r.action());
                if(handler==null)throw new IllegalArgumentException("Unknown action "+r.action());
                handler.run(new ActionUse(player,s,o,r.target(),r.normal()));return;
            }
            if(r.action().equals("selection_restore")){
                if(!s.undo.isEmpty()&&s.undo.peekFirst().selectionOnly())s.undo.removeFirst();
                if(s.selectionBefore!=null){s.selection.restore(s.selectionBefore);s.selectionBefore=null;}send(player,s,"Selection cancelled",false);return;}
            if(r.action().equals("select_box")||r.action().equals("lasso_select")||r.action().equals("use")&&o.tool.selection())s.selectionBefore=new ArrayList<>(s.selection.points());
            s.fromBuilder=r.action().startsWith("builder_");
            s.shapeAtRequest=r.action().equals("use")&&o.tool==Tool.SHAPE&&o.shapeKind==Options.ShapeKind.BRUSH&&r.target()!=null
                ?String.format(java.util.Locale.ROOT,"%d,%d,%d,%s,%s,%s,%s,%s,%s",r.target().x(),r.target().y(),r.target().z(),o.yaw,o.pitch,o.roll,o.sizeX,o.sizeY,o.sizeZ):null;
            s.pivotBeforeRequest=s.pivotNow;s.pivotNow=pivotText(o,0,0,0);
            if(r.action().equals("select_box")||r.action().equals("select_box_adjust")){if(o.position1!=null&&o.position2!=null)s.cornersNow=dev.craftium.core.Restore.points(o.position1,o.position2);}
            else if(changesSelection(r.action(),o))s.cornersNow="none";
            if(r.action().equals("gamemode")||r.action().startsWith("gamemode_")) {
                s.previousMode=null; // choosing a mode by hand replaces whatever the editor would have restored
                boolean wasSpectator=player.isSpectator();
                player.setGameMode(r.action().equals("gamemode")?(player.isCreative()?GameType.SPECTATOR:GameType.CREATIVE):GameType.byName(r.action().substring(9)));
                if(wasSpectator&&player.isCreative()){player.getAbilities().flying=true;player.onUpdateAbilities();}
                send(player,s,"Game mode switched",false);return;
            }
            if(!player.isCreative()&&!player.isSpectator())throw new IllegalArgumentException("Switch to Creative or Spectator mode to edit");
            if((r.action().equals("undo")||r.action().equals("rebuild_cancel"))&&AnimatedRebuild.owned(player.getUUID())){AnimatedRebuild.cancel(player.getUUID());send(player,s,"Cancelling rebuild and restoring the original structure…",false);return;}
            if(r.action().equals("hand_set")){ // a block dragged to the Hand slot: the editor's player is a spectator, which cannot use the creative menu, so the inventory is set here
                if(!player.isCreative()&&!(player.isSpectator()&&s.previousMode==GameType.CREATIVE))throw new IllegalArgumentException("Only a creative player can hold it");
                var name=o.block==null?"":o.block;int bracket=name.indexOf('[');var id=dev.craftium.compat.Compat.tryParse(bracket<0?name:name.substring(0,bracket));
                var item=id==null?null:net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).map(b->b.asItem()).orElse(null);
                if(item==null||item==net.minecraft.world.item.Items.AIR)throw new IllegalArgumentException("That block has no item to hold");
                // What is in the hand is not thrown away: the block goes to the first empty hotbar slot, which is then the one held. Only a full hotbar replaces the held item.
                var inventory=player.getInventory();int slot=inventory.getSelectedSlot();
                if(!inventory.getItem(slot).isEmpty())for(int i=0;i<9;i++)if(inventory.getItem(i).isEmpty()){slot=i;break;}
                inventory.setItem(slot,new net.minecraft.world.item.ItemStack(item));inventory.setSelectedSlot(slot);
                player.inventoryMenu.broadcastChanges();return;
            }
            if(r.action().equals("hotbar_next")||r.action().equals("hotbar_previous")){send(player,s,HotbarLibrary.cycle(player,s,o,r.action().equals("hotbar_next")?1:-1),false);return;}
            if(r.action().equals("hotbar_save")||r.action().equals("hotbar_load")||r.action().equals("hotbar_list")){
                String result=HotbarLibrary.apply(r.action(),player,o);if(r.action().equals("hotbar_list"))ServerPlatform.send(player,new Packets.Report(result));send(player,s,r.action().equals("hotbar_list")?"Saved hotbars listed":result,false);return;
            }
            if(s.busy){
                // A builder commit waits its turn instead of being lost; it is planned once the edit before it has landed.
                if(r.action().equals("builder_commit")&&job!=null&&job.owner.equals(player.getUUID())&&!job.history){
                    if(s.queued.size()>=QUEUE_LIMIT)throw new IllegalArgumentException("Too many edits waiting · let them finish");
                    s.queued.addLast(json);return;
                }
                throw new IllegalArgumentException("Applying edit… please wait");
            }
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
            if(r.action().startsWith("ai_preview_blocks:")){ // "ai_preview_blocks:<view>:<format>": the two settings of the read are part of the action, not of the shared options
                var part=r.action().split(":",3);if(part.length!=3)throw new IllegalArgumentException("Invalid preview read");
                ServerPlatform.send(player,new Packets.AiReply(AiWorld.previewBlocks(player,aiForRead(player,o),r.target(),r.normal(),part[1],part[2])));return;
            }
            switch(r.action()){
                case "ai_blocks" -> {ServerPlatform.send(player,new Packets.AiReply(AiWorld.blocks(player,r.target(),r.normal())));return;}
                case "ai_selection","ai_selection_refresh" -> {ServerPlatform.send(player,new Packets.AiReply(aiSelection(player,aiForRead(player,o),r.action().endsWith("refresh"))));return;}
                case "ai_surface","ai_surface_floor" -> {ServerPlatform.send(player,new Packets.AiReply(AiWorld.surface(player,r.target(),r.normal(),r.action().equals("ai_surface_floor"))));return;}
                case "ai_build_preview","ai_build_preview_add","ai_apply","ai_cancel","ai_undo","ai_redo","ai_session_stop","ai_session_resume","ai_session_rename","ai_session_forget","ai_session_teleport","ai_selection_pin" -> {aiAction(player,r.action(),o);return;}
                default -> {}
            }
            Point target=r.target(),normal=r.normal()==null?new Point(0,1,0):r.normal();
            if(Math.abs(normal.x())+Math.abs(normal.y())+Math.abs(normal.z())!=1)throw new IllegalArgumentException("Invalid surface normal");
            if(target!=null) {
                // The builder has no range limit; it still stops at the world's edge and at chunks that are not loaded.
                if(!r.action().startsWith("builder_")&&target.distanceSquared(new Point((int)player.getX(),(int)player.getY(),(int)player.getZ()))>256*256)
                    throw new IllegalArgumentException("Move within 256 blocks of the target");
                // A builder commit sends its box's corner as the target, and the box may be far from every player: its chunks are leased while it is planned.
                if(!r.action().equals("builder_commit"))new EditPlanner(player.level(),s,o).validate(target);
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
                case "entity_spawn","entity_update","entity_move","entity_duplicate","entity_delete","entity_transform","entity_gizmo","entity_reset","entity_group","entity_ungroup","entity_group_transform" -> {if(target==null)throw new IllegalArgumentException("Point at a block");var prior=s.selectedEntity;var edit=EntityEdits.plan(r.action(),player,s,o,target);start(player,s,edit.withSelection(Session.SelectionChange.restoring(Map.of("entity",entityText(prior)),Map.of("entity",entityText(s.selectedEntity)))));}
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
                case "selection_move", "selection_expand", "selection_shrink", "selection_invert", "selection_convex", "selection_outline" -> {
                    var next=new LinkedHashSet<Point>(s.selection.points());
                    if(r.action().equals("selection_outline")){next=new LinkedHashSet<>(SelectionEdge.outline(next,Math.max(1,o.selectionResize>0?o.selectionResize:o.expandAmount),o.pixelPerfect));}
                    else if(r.action().equals("selection_move")){next.clear();for(Point p:s.selection.points())next.add(p.add(o.offsetX,o.offsetY,o.offsetZ));}
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
                    int count=r.action().equals("stack")?o.copies:1;
                    if((long)s.selection.points().size()*count>Selection.LIMIT)throw new IllegalArgumentException("Stack exceeds "+String.format("%,d",Selection.LIMIT)+" blocks");
                    // The selection and where it lands may both be far from every player: lease their chunks while the edit is planned and applied.
                    var reach=ChunkLease.area().points(s.selection.points(),0,0);
                    for(int i=1;i<=count;i++)reach.points(s.selection.points(),o.offsetX*i,o.offsetZ*i);
                    ChunkLease.planning(reach.lease(player.level()));
                    var plan=new EditPlanner(player.level(),s,o);
                    var source=new LinkedHashMap<Point,BlockSnapshot>();
                    for(Point p:s.selection.points()){plan.validate(p);source.put(p,BlockSnapshot.capture(player.level(),EditPlanner.pos(p)));}
                    if(r.action().equals("move"))for(Point p:source.keySet())plan.put(p,Blocks.AIR.defaultBlockState());
                    for(int i=1;i<=count;i++)for(var e:source.entrySet()) {
                        var p=e.getKey();if(e.getValue().state().isAir()&&!o.pasteAir)continue;
                        plan.put(p.add(o.offsetX*i,o.offsetY*i,o.offsetZ*i),e.getValue());
                    }
                    start(player,s,plan.finish(r.action().equals("move")?"Move selection":r.action().equals("clone")?"Clone selection":"Stack selection"));
                }
                case "builder_undo", "builder_redo" -> { // the chords outside the builder reach only what the builder made; the editor's own edits stay with the editor
                    boolean back=r.action().equals("builder_undo");var top=(back?s.undo:s.redo).peekFirst();
                    if(top==null||!top.builder()){send(player,s,back?"Nothing to undo in the builder":"Nothing to redo in the builder",false);return;}
                    s.historyRemaining=0;if(back)undoAnimated(player,s);else history(player,s,false);
                }
                case "undo", "redo" -> {
                    s.historyRemaining=0;
                    // a saved selection deleted after the last edit comes back first: Undo takes back the most recent thing
                    if(r.action().equals("undo")&&s.saved.undoDelete(s.undo.isEmpty()?0:s.undo.peekFirst().time())!=null){send(player,s,"Saved selection back",false);return;}
                    if(r.action().equals("undo"))undoAnimated(player,s);else history(player,s,false);
                }
                case "fill", "delete" -> {
                    requireSelection(s);var plan=new EditPlanner(player.level(),s,o);
                    boolean deleting=r.action().equals("delete");
                    for(Point p:s.selection.points())plan.put(p,deleting?Blocks.AIR.defaultBlockState():plan.material);
                    var edit=plan.finish(deleting?DELETE_SELECTION:"Fill selection");
                    // Deleting the blocks lets go of the selection too, once the delete applies; undo gives the selection back with the blocks.
                    if(deleting)edit=edit.withSelection(new Session.SelectionChange(new ArrayList<>(s.selection.points()),List.of(),null,null,Map.of("corners",s.cornersNow),Map.of("corners","none")));
                    boolean nothing=deleting&&edit.changes().isEmpty()&&edit.biomes().isEmpty()&&edit.entities().isEmpty()&&edit.annotations().isEmpty();
                    start(player,s,edit);
                    if(nothing)letGoOfSelection(s); // there was nothing left to delete, and the selection is still let go of
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
                        var handlesBefore=Map.of("corners",s.cornersNow);
                        s.selection.combine(placed,Selection.Mode.REPLACE);
                        // The box handles belonged to the selection that was there, so they go with it: the pasted blocks are the selection now, and undo gives the old one back with its handles.
                        s.corner=null;s.cornersNow=s.cornersHistory="none";s.historySelection=new ArrayList<>(placed);s.historyRevision=s.selection.revision();
                        s.restoreUpdate=dev.craftium.core.Restore.encode(Map.of("corners","none"));
                        selected=new Session.SelectionChange(before,new ArrayList<>(placed),null,null,handlesBefore,Map.of("corners","none"));
                    }
                    var edit=plan.finish("Paste clipboard");start(player,s,new Session.Edit(edit.name(),edit.changes(),edit.biomes(),edit.flags(),o.pasteEntities?ClipboardEntities.paste(player,s,origin):List.of(),List.of(),selected));
                }
                case "use", "builder_place", "builder_break" -> {
                    if(target==null)throw new IllegalArgumentException("Point at a block within reach");
                    var plan=new EditPlanner(player.level(),s,o);
                    // A click in the world is not a Builder tool, but it follows the same rule: the tool masks apply only where "Use in Builder tools" is on.
                    if(!r.action().equals("use"))plan.builder=true;
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
                        plan.put(target,Blocks.AIR.defaultBlockState());start(player,s,plan.finish(IN_WORLD_BREAK));
                    } else use(player,s,o,target,normal,plan);
                }
                case "bucket_fill" -> {
                    // Dragging a block into the world: the Magic Wand, always comparing by block, then a replace with the active block.
                    if(target==null)throw new IllegalArgumentException("Drop the block onto a block within reach");
                    var plan=new EditPlanner(player.level(),s,o);
                    if(s.selection.contains(target)){ // dropped on the selection: the block fills all of it
                        for(Point p:s.selection.points())plan.put(p,plan.material);
                        start(player,s,plan.finish("Fill selection"));
                        return;
                    }
                    if(plan.state(target).isAir())throw new IllegalArgumentException("Drop the block onto a block, not empty space");
                    start(player,s,BuiltinTools.bucketFill(player,plan,o,target,Options.Compare.BLOCK,"Bucket fill"));
                }
                default -> throw new IllegalArgumentException("Unknown editor action");
            }
        } catch(IllegalArgumentException e) {s.historyRemaining=0;send(player,s,e.getMessage(),true);}
        catch(Exception e) {ServerPlatform.LOG.error("Editor request failed",e);send(player,s,"Operation failed; see the Craftium log",true);}
        finally {ChunkLease.endPlanning();}
    }
    /** The points a selection tool would add, less any the block mask rules out. */
    static List<Point> masked(EditPlanner plan,Options o,Iterable<Point> points){
        var mask=BlockMask.of(o);var kept=new ArrayList<Point>();
        for(Point p:points)if(mask.allows(plan.state(p)))kept.add(p);
        return kept;
    }
    /** What the player's tool does with a click on the world: whatever the tool's behaviour makes of it. */
    private static void use(ServerPlayer player,Session s,Options o,Point p,Point normal,EditPlanner plan) {
        if(o.pluginTool==null){ToolBehaviours.of(o.tool).use(new ToolUse(player,s,o,p,normal,plan));return;}
        var tool=pluginTool(o);
        if(s.pluginPreview!=null){s.pluginPreview=null;s.pluginPreviewDirty=true;} // the click is what the ghost showed, so it has had its say
        tool.behaviour().use(new ToolUse(player,s,o,p,normal,plan,true,tool.settings(o.toolSettings)));
    }
    private static PluginTools.Registered pluginTool(Options o){
        var tool=PluginTools.find(o.pluginTool);
        if(tool==null)throw new IllegalArgumentException("Unknown tool "+o.pluginTool);
        return tool;
    }
    /**
     * Asks the plugin's tool what it would make for the block the cursor is over, and keeps that as the ghost the player is shown. The reply carries the ghost and
     * nothing else, so moving the cursor never changes the status line. A tool that shows nothing, or whose plan is past the block limit, shows no ghost.
     */
    private static void toolPreview(ServerPlayer player,Session s,Options o,Point target,Point normal){
        Session.Edit edit=null;
        if(o.pluginTool!=null&&target!=null){
            var tool=pluginTool(o);
            try{
                edit=tool.behaviour().preview(new ToolUse(player,s,o,target,normal,new EditPlanner(player.level(),s,o),true,tool.settings(o.toolSettings)));
                if(edit!=null)EditGate.checkLimit(edit,EditGate.limit(o));
            }catch(IllegalArgumentException e){edit=null;}
        }
        if(edit==null&&s.pluginPreview==null)return;
        s.pluginPreview=edit;s.pluginPreviewDirty=true;
        sendPluginPreview(player,s);
    }
    private static void sendPluginPreview(ServerPlayer player,Session s){
        if(s.pluginPreviewDirty){s.pluginPreviewData=s.pluginPreview==null?"":GhostCodec.encodeEdit(s.pluginPreview);s.pluginPreviewDirty=false;}
        var json=Packets.JSON.toJson(Packets.pluginOnly(s.pluginPreviewData));
        ServerPlatform.send(player,new Packets.Update(StatusFit.fit(json,STATUS_LIMIT,"Too big to draw: the preview is not shown")));
    }
    private static void requireSelection(Session s) {if(s.selection.size()==0)throw new IllegalArgumentException("Create a selection first");}
    private static void copy(ServerPlayer player,Session s,Options o,boolean cut) {
        requireSelection(s);Point min=s.selection.min();
        var next=new LinkedHashMap<Point,BlockSnapshot>();int bytes=0;
        for(Point p:s.selection.points()) {
            BlockPos at=EditPlanner.pos(p);
            if(!player.level().hasChunk(p.x()>>4,p.z()>>4))throw new IllegalArgumentException("Selection reaches unloaded chunks");
            var snapshot=BlockSnapshot.capture(player.level(),at);bytes+=128+(snapshot.data()==null?0:snapshot.data().toString().length()*2);
            if(bytes>Selection.HISTORY_BYTES)throw new IllegalArgumentException("Clipboard exceeds "+Selection.HISTORY_BYTES/1024/1024+" MiB; select a smaller area");
            next.put(p.subtract(min),snapshot);
        }
        var entities=o.copyEntities?ClipboardEntities.capture(player,s):new ClipboardEntities.Capture(List.of(),List.of());
        for(var tag:entities.relative()){bytes+=tag.toString().length()*2;if(bytes>Selection.HISTORY_BYTES)throw new IllegalArgumentException("Clipboard exceeds "+Selection.HISTORY_BYTES/1024/1024+" MiB including entities");}
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
    /** The ghost data of an AI workspace's waiting preview, worked out when it is first wanted after a change. */
    private static String aiData(Session ai){
        if(ai.pendingDirty){ // an AI preview describes more than the player's own edits do
            try{ai.pendingData=ai.pendingEdit==null?"":GhostCodec.encodeEdit(ai.pendingEdit,GhostCodec.AI_LIMIT);}
            catch(RuntimeException e){
                // This runs in the server tick: a preview that cannot be drawn is dropped and logged, so it cannot stop the server.
                ServerPlatform.LOG.error("Dropped an AI preview that could not be drawn",e);
                ai.pendingEdit=null;ai.pendingData="";
            }
            ai.pendingDirty=false;
        }
        return ai.pendingData;
    }

    /**
     * The other players' AI previews this player is to be shown: those of players in the same dimension who are still connected, if this player may
     * use the editor at all. Only players with Craftium receive a status, so they are the ones who see them.
     */
    private static List<SharedPreviews.Shown> sharedNow(ServerPlayer viewer){
        boolean allowed;try{allowed=ServerPlatform.permitted(viewer,"status");}catch(IllegalArgumentException e){allowed=false;}
        if(!allowed)return List.of();
        var server=viewer.level().getServer();var all=new ArrayList<SharedPreviews.Entry>();
        for(var entry:AI_SESSIONS.entrySet()){
            var ai=entry.getValue();if(ai.pendingEdit==null)continue;
            var owner=server.getPlayerList().getPlayer(entry.getKey().owner());
            all.add(new SharedPreviews.Entry(entry.getKey().owner(),owner==null?"":owner.getName().getString()+(ai.label.isEmpty()?"":" · "+ai.label),owner==null?null:owner.level(),owner!=null,aiData(ai),ai.previewId));
        }
        return SharedPreviews.select(viewer.getUUID(),viewer.level(),all);
    }

    /** What to put in this player's status: the other players' AI previews if they differ from what was last sent, else null (unchanged). */
    private static List<Packets.SharedPreview> sharedFor(ServerPlayer viewer,Session s){
        var shown=sharedNow(viewer);var key=SharedPreviews.key(shown);
        if(key.equals(s.sharedKey))return null;
        s.sharedKey=key;
        return shown.stream().map(one->new Packets.SharedPreview(one.owner(),one.data())).toList();
    }

    /**
     * Tells every player whose view of other players' AI previews has changed. Called when an AI preview is made, applied or discarded, when its
     * owner leaves, and now and then, which catches an owner who changed dimension without doing anything. It sends a status of its own kind
     * ({@link Packets#sharedOnly}) that the client does not take for the reply to anything it asked, and sends nothing when nothing changed.
     */
    /**
     * The list of plugin tools for the status that is going to this player, or null when they already have the current one, are not allowed plugin tools, or
     * have a client that does not know them. Asking marks the list as sent.
     */
    private static List<Packets.PluginTool> pluginToolsFor(ServerPlayer player,Session s){
        int version=PluginTools.version();
        if(s.pluginToolsSent==version||!Handshake.clientHas(player.getUUID(),Handshake.PLUGIN_TOOLS)||!ServerPlatform.permitted(player,PLUGIN_LIST))return null;
        s.pluginToolsSent=version;
        return PluginTools.infos();
    }
    /** The name a player's permission to see and use plugin tools is asked under; it has a colon, so the host answers as it does for a plugin's own actions. */
    private static final String PLUGIN_LIST="plugins:list";
    private static int pluginToolsPushed;
    /** Tells every editor player when the set of plugin tools changed, as a status of its own that is nothing else. Cheap to ask every tick: it compares one number. */
    static void refreshPluginTools(MinecraftServer server){
        int version=PluginTools.version();
        if(version==pluginToolsPushed)return;
        pluginToolsPushed=version;
        for(var player:server.getPlayerList().getPlayers()){
            var s=SESSIONS.get(player.getUUID());if(s==null||!ServerPlatform.canSend(player))continue;
            var tools=pluginToolsFor(player,s);if(tools==null)continue;
            ServerPlatform.send(player,new Packets.Update(StatusFit.fit(Packets.JSON.toJson(Packets.toolsOnly(tools)),STATUS_LIMIT,"")));
        }
    }
    static void refreshShared(MinecraftServer server){
        for(var player:server.getPlayerList().getPlayers()){
            var s=SESSIONS.get(player.getUUID());if(s==null||!ServerPlatform.canSend(player))continue;
            var shared=sharedFor(player,s);if(shared==null)continue;
            ServerPlatform.send(player,new Packets.Update(StatusFit.fit(Packets.JSON.toJson(Packets.sharedOnly(shared)),STATUS_LIMIT,"")));
        }
    }

    private static final String STOPPED="The player stopped this AI session. Do not retry or build anything else; tell the player what you were doing and wait for their instructions.";
    private static AiKey aiKey(ServerPlayer player,Options o){return new AiKey(player.getUUID(),AiSessions.cleanId(o==null?"":o.aiSession));}
    /** This player's AI sessions, in the order they began. */
    private static List<Session> aiSessionsOf(UUID owner){var out=new ArrayList<Session>();for(var e:AI_SESSIONS.entrySet())if(e.getKey().owner().equals(owner))out.add(e.getValue());return out;}
    /** The AI session of this player that last had a request, or null: what a status that is not about any AI session describes, to an older client. */
    private static Session latestAi(UUID owner){Session best=null;for(var a:aiSessionsOf(owner))if(best==null||a.lastActive>=best.lastActive)best=a;return best;}
    /** The player's own session, which every status describes. */
    private static Session playerSession(ServerPlayer player){return SESSIONS.computeIfAbsent(player.getUUID(),id->new Session());}
    /**
     * The AI's workspace for the AI session this request names, made when it is first needed (at most {@link AiSessions#MAX_SESSIONS} per player), and
     * started afresh when the player has changed dimension; who it is and its label stay.
     */
    private static Session aiSession(ServerPlayer player,Options o){
        var key=aiKey(player,o);
        var ai=AI_SESSIONS.get(key);
        Session previous=null;
        if(ai!=null&&ai.dimension!=null&&!ai.dimension.equals(player.level().dimension())){
            if(ai.busy)throw new IllegalArgumentException("Wait for the current AI build to finish");
            previous=ai;ai=null;
        }
        if(ai==null){
            if(previous==null&&aiSessionsOf(player.getUUID()).size()>=AiSessions.MAX_SESSIONS)
                throw new IllegalArgumentException("This player already has "+AiSessions.MAX_SESSIONS+" AI build sessions. Tell the player to forget or stop one in Craftium's AI menu (Build sessions), or wait for one to expire after an hour without requests, then try again.");
            long now=System.currentTimeMillis();
            ai=new Session();ai.ai=true;ai.aiOwner=player.getUUID();ai.aiId=key.id();ai.dimension=player.level().dimension();ai.createdAt=now;ai.lastActive=now;
            if(previous!=null){ai.label=previous.label;ai.renamed=previous.renamed;ai.createdAt=previous.createdAt;ai.stopped=previous.stopped;ai.stoppedAt=previous.stoppedAt;}
            AI_SESSIONS.put(key,ai);
        }
        return ai;
    }
    /** The AI session a read is for. A read is not a build, but a session the player stopped is stopped for reads too. */
    private static Session aiForRead(ServerPlayer player,Options o){
        var ai=aiSession(player,o);
        if(ai.stopped)throw new IllegalArgumentException(STOPPED);
        ai.lastActive=System.currentTimeMillis();ai.lastTool="read";
        return ai;
    }
    /** Drops the AI sessions that have had no request for an hour and are not building, and tells their players. */
    private static void expireAi(MinecraftServer server){
        long now=System.currentTimeMillis();var gone=new ArrayList<Map.Entry<AiKey,Session>>();
        for(var e:AI_SESSIONS.entrySet())if(AiSessions.expired(e.getValue().lastActive,now,e.getValue().busy))gone.add(e);
        for(var e:gone){
            AI_SESSIONS.remove(e.getKey());
            var player=server.getPlayerList().getPlayer(e.getKey().owner());var s=SESSIONS.get(e.getKey().owner());
            if(player!=null&&s!=null)send(player,s,"AI session"+(e.getValue().label.isEmpty()?"":" \""+e.getValue().label+"\"")+" expired after an hour without requests",false);
        }
    }
    /**
     * Where {@code box}, the build an AI session is making, runs into what other AI sessions are holding or built lately in the same dimension, as
     * lines for the AI: it can move the build or tell the player. A warning only; the build is still allowed.
     */
    private static List<String> overlaps(MinecraftServer server,Session ai,AiSessions.Region box){
        var out=new ArrayList<String>();if(box==null)return out;long now=System.currentTimeMillis();
        for(var e:AI_SESSIONS.entrySet()){
            var other=e.getValue();
            if(other==ai||other.region==null||!Objects.equals(other.dimension,ai.dimension))continue;
            boolean holding=other.pendingEdit!=null;
            if(!holding&&now-other.regionAt>AiSessions.RECENT_MS)continue;
            if(!box.intersects(other.region))continue;
            var owner=server.getPlayerList().getPlayer(e.getKey().owner());
            String who=(other.label.isEmpty()?"another AI session":"\""+other.label+"\"")+(e.getKey().owner().equals(ai.aiOwner)||owner==null?"":" of "+owner.getName().getString());
            out.add("This build overlaps "+who+", which "+(holding?"has a preview waiting":"built")+" at "+other.region.text()+". Move this build or ask the player.");
        }
        return out;
    }
    /**
     * Notes the player's selection as the one this AI session works with: when it has none yet and the player has a selection, or whenever
     * {@code refresh} says the AI session was told to use the player's new one. A selection the player makes later does not change it.
     */
    private static void pinSelection(ServerPlayer player,Session ai,boolean refresh){
        var mine=SESSIONS.get(player.getUUID());
        var live=mine==null?Set.<Point>of():mine.selection.points();
        if(!refresh&&(ai.pinned!=null||live.isEmpty()))return;
        ai.pinned=live.isEmpty()?null:new LinkedHashSet<>(live);
        ai.pinnedRevision=mine==null?0:mine.selection.revision();ai.pinnedAt=System.currentTimeMillis();
    }
    /** Whether the player's own selection is not the one this AI session pinned. */
    private static boolean pinChanged(ServerPlayer player,Session ai){
        if(ai.pinned==null)return false;
        var mine=SESSIONS.get(player.getUUID());
        return mine==null||mine.selection.revision()!=ai.pinnedRevision;
    }
    /** What read_selection answers: the selection this AI session pinned, and when the player's own has moved on, where it is now and what to do about it. */
    private static String aiSelection(ServerPlayer player,Session ai,boolean refresh){
        pinSelection(player,ai,refresh);
        var extra=new com.google.gson.JsonObject();
        var mine=SESSIONS.get(player.getUUID());
        var live=mine==null?Set.<Point>of():mine.selection.points();
        if(ai.pinned==null)return AiWorld.selection(player,live,extra); // nothing selected yet: the player's selection as it is, and it is noted once there is one
        extra.addProperty("pinned",true);
        extra.addProperty("pinnedSecondsAgo",Math.max(0,(System.currentTimeMillis()-ai.pinnedAt)/1000));
        if(refresh)extra.addProperty("refreshed",true);
        else if(pinChanged(player,ai)){
            extra.addProperty("playerSelectionChanged",true);
            var box=AiSessions.around(live);
            if(box!=null){var now=new com.google.gson.JsonObject();now.addProperty("blocks",live.size());now.addProperty("box",box.text());extra.add("playerSelectionNow",now);}
            else extra.addProperty("playerSelectionNow","nothing selected");
            extra.addProperty("note","This is the selection you started with, and you keep working with it. The player has selected something else since. Use the new one only if the player says to (a new request for a new place, or \"use my new selection\"), by calling read_selection with refresh true.");
        }
        return AiWorld.selection(player,ai.pinned,extra);
    }
    /** The blocks of the player's saved selection with that name (any case), or an error that says which names there are. */
    private static Set<Point> savedByName(ServerPlayer player,String name){
        var mine=SESSIONS.get(player.getUUID());var entry=mine==null?null:mine.saved.byName(name);
        if(entry==null){
            var names=mine==null?List.<String>of():mine.saved.entries().stream().map(e->e.name()).toList();
            throw new IllegalArgumentException("The player has no saved selection named \""+name+"\". "+(names.isEmpty()?"They have saved none: ask them to select the blocks and press Save selection in the Selections window":"They have: "+String.join(", ",names)));
        }
        return new LinkedHashSet<>(entry.points());
    }
    /** What read_selection answers for a saved selection: the same report as for the selection itself, with its name, and where the player has it. */
    private static String aiSelectionNamed(ServerPlayer player,String name){
        var points=savedByName(player,name);var mine=SESSIONS.get(player.getUUID());var entry=mine.saved.byName(name);
        var extra=new com.google.gson.JsonObject();
        extra.addProperty("name",entry.name());extra.addProperty("saved",true);
        if(mine.saved.active()==entry)extra.addProperty("shownByPlayer",mine.saved.editing()?"editing":"viewing");
        return AiWorld.selection(player,points,extra);
    }
    /** What a player's session tells the client about each of that player's AI sessions; ghost data only when a preview is new to this player. */
    private static List<Packets.AiSession> aiInfos(ServerPlayer player,Session viewer){
        var out=new ArrayList<Packets.AiSession>();long now=System.currentTimeMillis();var live=new HashSet<String>();
        for(var a:aiSessionsOf(player.getUUID())){
            String drawn=aiData(a); // may drop a preview that cannot be drawn, so the state is read after it
            boolean preview=a.pendingEdit!=null;live.add(a.aiId);
            String state=a.stopped?"stopped":a.busy?"applying":preview?"preview":!a.undo.isEmpty()?"applied":"idle";
            String data;
            if(!preview){data="";viewer.sentAi.remove(a.aiId);}
            else{var sent=viewer.sentAi.get(a.aiId);if(sent!=null&&sent==a.previewId)data=null;else{data=drawn;viewer.sentAi.put(a.aiId,a.previewId);}}
            var box=a.region;
            var pin=a.pinned==null?null:AiSessions.around(a.pinned);
            out.add(new Packets.AiSession(a.aiId,a.label,state,preview?a.pendingEdit.changes().size():0,
                box==null?null:new Point(box.minX(),box.minY(),box.minZ()),box==null?null:new Point(box.maxX(),box.maxY(),box.maxZ()),
                a.dimension==null?"":a.dimension.toString(),a.undo.size(),a.undo.isEmpty()?"":a.undo.peekFirst().name(),
                Math.max(0,(a.stopped?a.stoppedAt:now)-a.createdAt),Math.max(0,now-a.lastActive),a.stopped,a.lastTool,a.previewId,data,
                pin==null?null:new Point(pin.minX(),pin.minY(),pin.minZ()),pin==null?null:new Point(pin.maxX(),pin.maxY(),pin.maxZ()),a.pinned==null?0:a.pinned.size(),pinChanged(player,a)));
        }
        viewer.sentAi.keySet().retainAll(live);
        return out;
    }
    /**
     * What an AI client can do with its workspace: hold a build as a preview, apply it, discard it or undo the last build it applied. None of
     * it touches the player's selection, preview or undo history, and none of it needs the player nearby.
     */
    private static void aiAction(ServerPlayer player,String action,Options o){
        try{aiActionNow(player,action,o);}
        finally{refreshShared(player.level().getServer());} // a preview made, refused, applied or discarded changes what other players are shown
    }
    /** Stopping, resuming, renaming and forgetting an AI session: the player's controls over it, which the AI app cannot reach. */
    private static void aiManage(ServerPlayer player,String action,Options o){
        var key=aiKey(player,o);var ai=AI_SESSIONS.get(key);
        if(ai==null)throw new IllegalArgumentException("That AI session is gone");
        String name=ai.label.isEmpty()?"":" \""+ai.label+"\"";
        switch(action){
            case "ai_session_stop" -> {
                if(!ai.stopped)ai.stoppedAt=System.currentTimeMillis(); // its clock stops here
                ai.stopped=true;ai.queued.clear();
                if(ai.pendingEdit!=null){ai.pendingEdit=null;ai.pendingDirty=true;ai.region=null;}
                var running=job;
                if(running!=null&&running.session()==ai){ // an apply in progress ends where it is; what it wrote stays in the history, like any interrupted edit
                    rememberInterrupted(running);ai.busy=false;endJob();
                }
                send(player,playerSession(player),"Stopped AI session"+name,false);
            }
            case "ai_session_resume" -> {
                if(ai.stopped)ai.createdAt+=System.currentTimeMillis()-ai.stoppedAt; // the time it was stopped does not count
                ai.stopped=false;send(player,playerSession(player),"Resumed AI session"+name,false);}
            case "ai_session_rename" -> {
                String label=AiSessions.cleanLabel(o.aiLabel);
                ai.label=label;ai.renamed=!label.isEmpty(); // a name the player chose is not replaced by the AI app's own
                send(player,playerSession(player),label.isEmpty()?"AI session name cleared":"Renamed AI session to \""+label+"\"",false);
            }
            case "ai_session_teleport" -> {
                var box=ai.region;
                if(box==null)throw new IllegalArgumentException("That AI session has not placed anything yet");
                if(!Objects.equals(ai.dimension,player.level().dimension()))throw new IllegalArgumentException("That AI session is building in another dimension");
                // On top of the middle of its build, which is clear air whatever it is made of; a player who is not flying lands on it.
                player.teleportTo((box.minX()+box.maxX()+1)/2.0,box.maxY()+1,(box.minZ()+box.maxZ()+1)/2.0);
                send(player,playerSession(player),"Teleported to AI session"+name,false);
            }
            case "ai_session_forget" -> {
                if(ai.busy)throw new IllegalArgumentException("Stop this AI session first: it is still building");
                AI_SESSIONS.remove(key);
                send(player,playerSession(player),"Forgot AI session"+name,false);
            }
            default -> throw new IllegalArgumentException("Unknown AI session action");
        }
    }
    private static void aiActionNow(ServerPlayer player,String action,Options o){
        if(action.startsWith("ai_session_")){aiManage(player,action,o);return;}
        var ai=aiSession(player,o);
        if(ai.stopped&&!action.equals("ai_cancel"))throw new IllegalArgumentException(STOPPED);
        ai.lastActive=System.currentTimeMillis();ai.lastTool=action.substring(3);
        if(action.equals("ai_selection_pin")){pinSelection(player,ai,false);send(player,playerSession(player),"",false);return;}
        switch(action){
            case "ai_build_preview","ai_build_preview_add" -> {
                if(ai.busy)throw new IllegalArgumentException("Wait for the current AI build to finish");
                if(!o.aiLabel.isEmpty()&&!ai.renamed)ai.label=o.aiLabel;
                // A new preview replaces the last only once it is accepted: a spec that is refused (a typo, a block that does not exist) leaves
                // the waiting preview as it was, so the player's ghost does not vanish and the AI can fix the spec and try again.
                // Added to the waiting preview, the spec is planned on top of it and the two become one edit, applied and undone as one.
                var onto=action.equals("ai_build_preview_add")?ai.pendingEdit:null;
                pinSelection(player,ai,false); // a replace_selection works on the selection this session started with, not on one the player has made since
                var edit=BuildPlanner.plan(player,ai,o,ai.pinned==null?Set.<Point>of():ai.pinned,onto,name->savedByName(player,name));
                var added=onto==null?edit:addedTo(onto,edit);
                // Blocks the new spec put back to what the world holds (a doorway carved through a preview wall in open air) leave the edit
                // altogether, so they are not in "added": count them, or a spec that only carves would look like one that did nothing.
                int takenBack=onto==null?0:takenBack(onto,edit);
                if(added.changes().isEmpty()&&takenBack==0){send(player,ai,"No matching blocks changed"+(ai.pendingEdit==null?"":"; the earlier preview is still waiting"),false);return;}
                if(edit.estimatedBytes()>Selection.HISTORY_BYTES)throw new IllegalArgumentException("Edit exceeds the "+Selection.HISTORY_BYTES/1024/1024+" MiB history budget; use a smaller build");
                EditProtection.check(player,edit);
                // The ghost is worked out now, in the request, so a preview that cannot be drawn is refused here with the reason and the earlier
                // preview stays. Left to the tick, it would vanish without a word, for the AI and for the players who watch it.
                String ghost;
                try{ghost=GhostCodec.encodeEdit(edit,GhostCodec.AI_LIMIT);}
                catch(RuntimeException e){ServerPlatform.LOG.error("An AI preview could not be drawn",e);throw new IllegalArgumentException("The preview could not be drawn ("+e+"), so it was not kept"+(ai.pendingEdit==null?"":" and the earlier preview is still waiting")+". Please report this with the spec that caused it");}
                var box=AiSessions.around(edit.changes().stream().map(Session.Change::pos).toList());
                var conflicts=overlaps(player.level().getServer(),ai,box); // against the other sessions, before this one's own box is set
                ai.pendingEdit=edit;ai.pendingData=ghost;ai.pendingDirty=false;ai.previewId=++PREVIEW_IDS;ai.region=box;ai.regionAt=System.currentTimeMillis();
                ServerPlatform.send(player,new Packets.AiReply(AiWorld.previewSummary(added,(net.minecraft.server.level.ServerLevel)player.level(),edit,ai.specWarnings,takenBack,conflicts))); // before the status, which the AI client waits for, so it has arrived by then
                send(player,ai,onto==null?"AI preview ready: "+edit.changes().size()+" blocks":"Added "+added.changes().size()+" blocks to the AI preview"+(takenBack>0?" and took back "+takenBack:"")+": "+edit.changes().size()+" blocks in all",false);
            }
            case "ai_apply" -> {
                var edit=ai.pendingEdit;
                if(edit==null){send(player,ai,"No AI preview to apply",false);return;}
                if(job!=null||AnimatedRebuild.active())throw new IllegalArgumentException("Another edit or animated rebuild is applying; try again in a moment");
                EditProtection.check(player,edit);
                ai.pendingEdit=null;ai.pendingDirty=true;ai.regionAt=System.currentTimeMillis();
                begin(player,ai,edit,false,false,0,true,true);
                send(player,ai,"Applying "+edit.name()+"…",false);
            }
            case "ai_cancel" -> {
                if(ai.pendingEdit!=null){ai.pendingEdit=null;ai.pendingDirty=true;ai.region=null;send(player,ai,"AI preview discarded",false);}
                else send(player,ai,"No AI preview to discard",false);
            }
            case "ai_redo" -> {ai.historyRemaining=0;history(player,ai,false);}
            default -> {ai.historyRemaining=0;history(player,ai,true);} // ai_undo
        }
    }
    /** The changes of {@code merged} that {@code earlier} did not already hold: what an addition to a preview changed. */
    /** How many positions {@code earlier} changed that {@code merged} no longer does: preview blocks a later spec put back as the world has them. */
    private static int takenBack(Session.Edit earlier,Session.Edit merged){
        var kept=new HashSet<Point>();for(var c:merged.changes())kept.add(c.pos());
        int gone=0;for(var c:earlier.changes())if(!kept.contains(c.pos()))gone++;
        return gone;
    }
    private static Session.Edit addedTo(Session.Edit earlier,Session.Edit merged){
        var before=new HashMap<Point,Session.Change>();for(var c:earlier.changes())before.put(c.pos(),c);
        var out=new ArrayList<Session.Change>();
        for(var c:merged.changes()){var old=before.get(c.pos());if(old==null||old.after().state()!=c.after().state()||!Objects.equals(old.after().data(),c.after().data()))out.add(c);}
        return new Session.Edit(merged.name(),out,List.of(),merged.flags());
    }
    /** Every edit comes in here: it is checked by the {@link EditGate}, then built, or held as a ghost until the player confirms it. */
    static void start(ServerPlayer player,Session s,Session.Edit edit) {
        if(s.fromBuilder)edit=edit.withBuilder(true);
        if(s.shapeAtRequest!=null&&!edit.changes().isEmpty()){ // a shape that was built goes back to waiting to be built when it is undone
            var change=edit.selection()==null?Session.SelectionChange.restoring(Map.of(),Map.of()):edit.selection();
            edit=edit.withSelection(change.with(Map.of("shape",s.shapeAtRequest),Map.of("shape","none")));
        }
        if(edit.changes().isEmpty() && edit.biomes().isEmpty()&&edit.entities().isEmpty()&&edit.annotations().isEmpty()){var why=s.nothingChangedBecause;s.nothingChangedBecause=null;send(player,s,why==null?"No matching blocks changed":"No matching blocks changed: "+why,false);return;}
        EditGate.check(player,edit);
        if(s.confirmEdits&&!s.bypassConfirm){stage(player,s,edit);return;}
        if(job!=null||AnimatedRebuild.active())throw new IllegalArgumentException("Another edit or animated rebuild is applying; try again in a moment");
        begin(player,s,edit,false,false,s.requestStroke,false,s.requestRecord);
        send(player,s,"Applying "+edit.name()+"…",false);
    }
    /** Holds the edit as a ghost for the player to confirm; edits made while one is waiting are added to it. */
    /** The player's session, for commands that work on their selection and waiting preview. */
    static Session session(ServerPlayer player){return playerSession(player);}
    /** Holds an edit as the player's waiting preview in place of whatever waited before; Enter builds it and Esc cancels it, like any staged edit. */
    static void preview(ServerPlayer player,Session.Edit edit,String message){
        var s=playerSession(player);
        s.pendingEdit=edit;s.pendingSelectionBefore=null;s.pendingDirty=true;
        send(player,s,message,false);
    }
    /** Builds the waiting preview, as the Enter key does. */
    static void applyPreview(ServerPlayer player){
        var s=playerSession(player);var edit=s.pendingEdit;
        if(edit==null){send(player,s,"Nothing to confirm",false);return;}
        s.pendingEdit=null;s.pendingSelectionBefore=null;s.pendingDirty=true;
        s.bypassConfirm=true; // confirming is the confirmation, so the edit is built and not held again
        try{start(player,s,edit);}finally{s.bypassConfirm=false;}
    }
    /** Drops the waiting preview, as the Escape key does. */
    static void cancelPreview(ServerPlayer player){
        var s=playerSession(player);
        if(s.pendingEdit==null){send(player,s,"Nothing to cancel",false);return;}
        s.pendingEdit=null;s.pendingSelectionBefore=null;s.pendingDirty=true;
        send(player,s,"Edit cancelled",false);
    }
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
        return new Session.Edit(a.name(),new ArrayList<>(changes.values()),biomes,a.flags()|b.flags(),entities,annotations,selection).withBuilder(a.builder()&&b.builder());
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
        if(values.isEmpty())return;
        var entity=values.get("entity"); // the entity that was selected goes with the edit, as the selection does
        if(entity!=null)s.selectedEntity=entity.equals("none")?null:UUID.fromString(entity);
        s.restoreUpdate=dev.craftium.core.Restore.encode(values);
    }
    private static String entityText(UUID entity){return entity==null?"none":entity.toString();}
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
        // Undoing a far-away edit loads the chunks it needs for as long as it takes, instead of asking the player to walk there.
        EditProtection.check(player,edit);
        from.removeFirst();begin(player,s,edit,backwards,true,0,s.ai,true);
        s.lastStroke=0;
        send(player,s,backwards?"Undoing…":"Redoing…",false);
    }
    public static void tick(MinecraftServer server) {
        refreshPluginTools(server);
        if(!AI_SESSIONS.isEmpty()&&server.getTickCount()%20==0){expireAi(server);refreshShared(server);} // an owner who changed dimension, or whose build has applied, without doing anything else
        AnimatedRebuild.tick(server);
        if(++historyTicks%100==0)for(var session:SESSIONS.values())HistoryStore.save(session);
        var reachId=dev.craftium.compat.Compat.id("craftium","builder_reach");
        var stepId=dev.craftium.compat.Compat.id("craftium","block_step");
        for(var player:server.getPlayerList().getPlayers()){
            var s=SESSIONS.get(player.getUUID());
            var range=player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
            // Reach is the game's own block interaction range, raised for as long as the capability is on, so every vanilla check agrees with it.
            var mode=s!=null&&player.isCreative()&&ServerPlatform.permitted(player,"capabilities")?s.reach:Options.ReachMode.NORMAL;
            // Block step is the game's own step height, raised to a whole block for as long as the capability is on, so walking up a block works like a slab.
            var step=player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
            if(step!=null){
                double lift=s!=null&&s.blockStep&&player.isCreative()&&ServerPlatform.permitted(player,"capabilities")?Math.max(0,1-step.getBaseValue()):0;
                var held=step.getModifier(stepId);
                if(held!=null&&(lift==0||held.amount()!=lift))step.removeModifier(stepId);
                if(lift!=0&&step.getModifier(stepId)==null)step.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(stepId,lift,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
            if(range!=null){
                double extra=switch(mode){case NORMAL->0;case FAR->s.farReach-range.getBaseValue();case INFINITE->250;};
                var current=range.getModifier(reachId);
                if(current!=null&&(extra==0||current.amount()!=extra))range.removeModifier(reachId);
                if(extra!=0&&range.getModifier(reachId)==null)range.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(reachId,extra,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
        }
        if(job==null)return;var j=job;
        try {
            var list=j.order();long deadline=System.nanoTime()+APPLY_BUDGET_NANOS;
            // Only the time budget limits a tick: a fixed block cap left most of it unused.
            while(j.cursor[0]<list.size() && System.nanoTime()<deadline) {
                int from=j.cursor[0],to=Math.min(list.size(),from+SLICE);j.cursor[0]=to;
                applySlice(j,list.subList(from,to));
            }
            if(j.cursor[0]<list.size())return;
            if(!EditProtection.allowed(j.owner,j.level,new Session.Edit(j.edit.name(),List.of(),List.of(),j.edit.flags(),j.edit.entities(),j.edit.annotations())))throw new IllegalArgumentException("Entity or annotation access changed");
            var appliedBiomes=applyBiomes(j);
            var appliedEntities=EntityEdits.apply(j.level,j.edit.entities(),j.backwards);
            var appliedAnnotations=AnnotationEdits.apply(j.level,j.edit.annotations(),j.backwards);
            var actual=new Session.Edit(j.edit.name(),List.copyOf(j.applied),appliedBiomes,j.edit.flags(),appliedEntities,appliedAnnotations,j.edit.selection(),j.edit.time(),j.edit.builder());
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
            } else if(j.record){
                j.session.remember(actual,j.strokeId);
                if(actual.name().equals(DELETE_SELECTION)&&actual.selection()!=null)letGoOfSelection(j.session);
            }
            j.session.busy=false;endJob();
            if(j.history&&!j.backwards&&j.session.historyRemaining==0&&!j.applied.isEmpty()&&j.session.options!=null&&j.session.options.historyPulse)j.session.pulse="redo:700:"+SelectionCodec.encode(pulsePoints(j.applied.stream().map(Session.Change::pos).toList()));
            var player=server.getPlayerList().getPlayer(j.owner);
            if(player!=null)send(player,j.session,(j.backwards?"Undid ":j.history?"Redid ":"Applied ")+j.edit.name()+" · "+(j.applied.size()+appliedBiomes.size()+appliedEntities.size()+appliedAnnotations.size())+" changes"+(skipped>0?" · "+skipped+" changed or unavailable cells skipped":""),false);
            else release(j);
            if(!appliedAnnotations.isEmpty())for(var other:server.getPlayerList().getPlayers())if(!other.getUUID().equals(j.owner)&&other.level()==j.level){var session=SESSIONS.get(other.getUUID());if(session!=null)send(other,session,"Annotations updated",false);}
            if(player!=null&&j.history&&j.session.historyRemaining!=0){boolean backwards=j.session.historyRemaining>0;j.session.historyRemaining+=backwards?-1:1;try{history(player,j.session,backwards);}catch(IllegalArgumentException e){j.session.historyRemaining=0;send(player,j.session,e.getMessage(),true);}}
            if(player!=null)drain(player,j.session);else j.session.queued.clear();
        } catch(Exception e) {
            j.session.queued.clear();
            ServerPlatform.LOG.error("Craftium edit interrupted",e);
            rememberInterrupted(j);
            j.session.busy=false;endJob();var player=server.getPlayerList().getPlayer(j.owner);
            if(player!=null)send(player,j.session,"Edit interrupted. Applied changes remain in history.",true);
            else release(j);
        }
    }
    /** Runs the next waiting builder commit, if the edit before it is done. A queued request that fails is reported like any other, and the ones behind it still run. */
    private static void drain(ServerPlayer player,Session s){
        while(!s.busy&&!s.queued.isEmpty()){
            replaying=true;
            try{handle(player,s.queued.removeFirst());}finally{replaying=false;}
        }
    }
    /** Lets go of a finished job's session when its player has left: the player's is saved and dropped, the AI's is just dropped. */
    private static void release(Job j){
        if(j.session.ai){AI_SESSIONS.remove(new AiKey(j.owner,j.session.aiId));return;}
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
            case "select_box","select_box_adjust","lasso_select","clear","selection_move","selection_expand","selection_shrink","selection_invert","selection_convex","selection_outline",
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
            case "selection_outline"->"Select outline";
            case "selection_move"->"Move selection";
            default->"Select "+size+" blocks";
        };
    }
    private static final String DELETE_SELECTION="Delete selection";
    /** Empties the selection, and the box handles with it, without a history entry of its own: the delete's entry carries the way back. */
    private static void letGoOfSelection(Session s){
        s.selection.clear();s.corner=null;
        s.cornersNow=s.cornersHistory="none";s.historySelection=List.of();s.historyRevision=s.selection.revision();
        s.restoreUpdate=dev.craftium.core.Restore.encode(Map.of("corners","none"));
    }
    /** Adds the selection's latest change to the history, and remembers the selection as it is now. */
    private static void noteSelection(Session s){
        if(s.selection.revision()==s.historyRevision)return;
        var after=new ArrayList<>(s.selection.points());
        if(s.recordSelection&&!after.equals(s.historySelection)){
            var before=s.historySelection;
            Map<String,String> handlesBefore=Map.of(),handlesAfter=Map.of();
            if(!s.cornersNow.equals(s.cornersHistory)){handlesBefore=Map.of("corners",s.cornersHistory);handlesAfter=Map.of("corners",s.cornersNow);}
            // Dragging a box handle redoes the same selection many times: keep one entry, from where it began.
            if(s.currentAction.equals("select_box_adjust")&&!s.undo.isEmpty()&&s.undo.peekFirst().selectionOnly()){
                var began=s.undo.removeFirst().selection();before=began.before();handlesBefore=began.restoreBefore();
            }
            // Deselecting also lets go of the pivot, so undo gives it back with the selection.
            boolean pivotLet=s.currentAction.equals("clear")&&!s.pivotBeforeRequest.equals(s.pivotNow);
            s.remember(Session.Edit.selecting(selectionName(s.currentAction,after.size()),new Session.SelectionChange(before,after,pivotLet?s.pivotBeforeRequest:null,pivotLet?s.pivotNow:null,handlesBefore,handlesAfter)));
        }
        s.historySelection=after;s.historyRevision=s.selection.revision();s.cornersHistory=s.cornersNow;
    }
    /** How long ago the edit was made, in milliseconds; -1 for none, and a very large age when its time is unknown. */
    private static long age(Session.Edit edit){return edit==null?-1:edit.time()==0?Long.MAX_VALUE/4:Math.max(0,System.currentTimeMillis()-edit.time());}
    static void send(ServerPlayer player,Session s,String message,boolean error) {
        // A status always describes the player's own session, so an AI action never changes what the player sees of their selection or edits;
        // the AI's preview and undo count ride along in fields of their own.
        var ai=s.ai?s:latestAi(player.getUUID());
        if(s.ai)s=SESSIONS.computeIfAbsent(player.getUUID(),id->new Session());
        noteSelection(s);
        s.saved.sync(s.selection); // while one is being edited, whatever changed the selection is written to it
        if(!ServerPlatform.canSend(player))return;
        String entityId="",entityNbt="";if(s.selectedEntity!=null){var entity=player.level().getEntity(s.selectedEntity);if(entity!=null){entityId=s.selectedEntity.toString();String nbt=EntityEdits.snapshot(entity,player.level()).toString();if(nbt.length()<12000)entityNbt=nbt;}}
        if(s.selectionRevision!=s.selection.revision()){s.selectionRevision=s.selection.revision();s.selectionData=SelectionCodec.encode(s.selection.points());}
        if(s.clipboardDirty){s.clipboardGhostDirty=true;s.clipboardData=SelectionCodec.encode(s.clipboard.entrySet().stream().filter(e->!e.getValue().state().isAir()).map(Map.Entry::getKey).toList());s.clipboardDirty=false;}
        if(s.pendingDirty){s.pendingData=s.pendingEdit==null?"":GhostCodec.encodeEdit(s.pendingEdit);s.pendingDirty=false;}
        if(s.pluginPreviewDirty){s.pluginPreviewData=s.pluginPreview==null?"":GhostCodec.encodeEdit(s.pluginPreview);s.pluginPreviewDirty=false;}
        if(ai!=null)aiData(ai); // one place makes the AI preview's data, so the owner and the players who see it always get the same
        if(s.clipboardGhostDirty){s.clipboardGhost=GhostCodec.encodeClipboard(s.clipboard);s.clipboardGhostDirty=false;}
        var status=new Packets.Status(message,error,s.busy||(ai!=null&&ai.busy),s.selection.size(),s.selection.min(),s.selection.max(),s.clipboard.size(),s.undo.stream().map(edit->edit.selectionOnly()?edit.name():edit.name()+" · "+(edit.changes().size()+edit.biomes().size()+edit.entities().size()+edit.annotations().size())).toList(),s.undo.size(),s.redo.size(),entityId,entityNbt,AnnotationEdits.list(player.level()),s.selectionData,s.clipboardData,s.library,s.clipboardEntities.size(),s.pendingData,s.clipboardGhost,s.pivotUpdate,age(s.undo.peekFirst()),age(s.redo.peekFirst()),s.undo.isEmpty()?"":s.undo.peekFirst().name(),s.redo.isEmpty()?"":s.redo.peekFirst().name(),s.pulse,ai==null||Handshake.clientHas(player.getUUID(),Handshake.AI_SESSIONS)?"":ai.pendingData,ai==null?0:ai.undo.size(),ai==null||ai.undo.isEmpty()?"":ai.undo.peekFirst().name(),s.restoreUpdate,s.clipboardOrigin,sharedFor(player,s),false,aiInfos(player,s),s.pluginPreviewData,pluginToolsFor(player,s),SavedSelectionActions.forStatus(s,Handshake.clientHas(player.getUUID(),Handshake.SAVED_SELECTIONS)));s.library=List.of();s.pivotUpdate="";s.restoreUpdate="";s.pulse="";
        // A status that is too long for a packet cannot be written and would drop the player's connection, so one that is too big is sent without the largest drawn data.
        var json=Packets.JSON.toJson(status);var fitted=StatusFit.fit(json,STATUS_LIMIT,"Too big to draw: the selection or preview is still there, but not shown");
        if(fitted.length()!=json.length())s.sentAi.clear(); // what was left out was not drawn, so the next status carries the previews again
        ServerPlatform.send(player,new Packets.Update(fitted));
    }
}
