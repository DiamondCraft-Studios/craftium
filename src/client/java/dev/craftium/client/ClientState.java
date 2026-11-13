package dev.craftium.client;

import dev.craftium.Craftium;
import dev.craftium.core.*;
import dev.craftium.net.Packets;
import dev.craftium.net.PluginToolCodec;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;

public final class ClientState {
    private static final Path CONFIG=FabricLoader.getInstance().getConfigDir().resolve("craftium.json");
    public static Options options=new Options();
    /** The blocks the world is missing, as the server last told us, in the order of its table. Empty when none is, which keeps the Missing blocks window away. */
    public static java.util.List<String> missingBlocks=java.util.List.of();
    /** How far the big edit the server is applying has got, for the Large edit window: null when there is none. A report not followed by another within {@link #PROGRESS_STALE_MS} is taken as the end. */
    public record EditProgress(String verb,String name,int done,int total,long startedAt,long updatedAt){}
    public static volatile EditProgress progress;
    public static final long PROGRESS_STALE_MS=5000;
    /** The server's report of a big edit: a total of 0 ends it, and a report of a different edit starts the clock again. */
    public static void setProgress(String verb,String name,int done,int total){
        long now=System.currentTimeMillis();var was=progress;
        if(total<=0){progress=null;return;}
        boolean same=was!=null&&was.verb().equals(verb)&&was.name().equals(name)&&done>=was.done();
        progress=new EditProgress(verb,name,done,total,same?was.startedAt():now,now);
    }
    /** The progress to show now, or null when there is none or the server has stopped reporting it. */
    public static EditProgress activeProgress(){var p=progress;return p!=null&&System.currentTimeMillis()-p.updatedAt()<PROGRESS_STALE_MS?p:null;}
    /** Takes the server's list; a longer one than before (the first on joining a world with some, or another found since) brings the window up again if it was closed. */
    public static void setMissingBlocks(java.util.List<String> ids){
        boolean more=ids.size()>missingBlocks.size();
        missingBlocks=ids;
        if(more){var window=WorkspaceLayout.windows.get("missing");if(window!=null&&!window.visible){window.visible=true;WorkspaceLayout.save();}}
    }
    public static Packets.Status status=new Packets.Status("Ready · Right-click to use your tool",false,false,0,new Point(0,0,0),new Point(0,0,0),0,List.of(),0,0);
    /** The values of the settings tools declare for themselves, by the id of the tool's spec. */
    public static final java.util.Map<String,dev.craftium.core.PropertyValues> toolProperties=new java.util.HashMap<>();
    public static String notice="";
    public static long noticeUntil;
    public static long strokeId;
    public static boolean duplicatePending;
    public static Point pendingPlacementOrigin;
    /** Set while a selection action is in flight, so its result can be checked for being suspiciously large. */
    /** An edit the server has worked out and is holding until it is confirmed with Enter. */
    public static GhostBlocks pending=GhostBlocks.EMPTY;
    /** The ghost the plugin tool in use wants shown, as the server last said. It is not an edit waiting to be confirmed. */
    public static GhostBlocks pluginPending=GhostBlocks.EMPTY;
    private static String pluginPendingData="";
    /** Takes in the plugin ghost a status carries. A status that does not carry one (null) leaves it as it was, and an unchanged one is not read again. */
    static void updatePluginPreview(Packets.Status status){
        String data=status.pluginPreviewData();
        if(data==null||data.equals(pluginPendingData))return;
        pluginPendingData=data;pluginPending=data.isEmpty()?GhostBlocks.EMPTY:GhostBlocks.parse(data);
    }
    /** The tools the server's plugins have added, as the server last said; empty on a server that has none or does not know them. */
    private static List<ToolSpec> pluginTools=List.of();
    public static List<ToolSpec> pluginTools(){return pluginTools;}
    /** Takes in the list of plugin tools a status carries. A status that does not carry one (null) leaves it as it was. */
    static void updatePluginTools(Packets.Status status){
        if(status.pluginTools()==null)return;
        pluginTools=List.copyOf(PluginToolCodec.fromWire(status.pluginTools()));
    }
    static void clearPluginTools(){pluginTools=List.of();}
    /** The selections the player saved, as the server last said; empty on a server that has none or does not know them. */
    private static List<Packets.SavedSelection> savedSelections=List.of();
    public static List<Packets.SavedSelection> savedSelections(){return savedSelections;}
    /** The saved selections by id, rebuilt with the list, so a parent is found without scanning it. */
    private static Map<Integer,Packets.SavedSelection> savedById=Map.of();
    private static void indexSaved(){var map=new HashMap<Integer,Packets.SavedSelection>(savedSelections.size()*2);for(var e:savedSelections)map.put(e.id(),e);savedById=map;}
    /** The saved selection with this id, or null. */
    public static Packets.SavedSelection savedById(int id){return savedById.get(id);}
    /** A number that changes whenever something the Hierarchy's rows depend on besides the lists themselves does: a fold, or the folds read back from this player's file. */
    public static int hierarchyVersion;
    /** The outline colour of each saved selection by id. A colour belongs to the client: it starts as the selection colour turned by the golden angle for each one saved before it, and the player can change it. */
    private static final Map<Integer,Integer> savedColors=new HashMap<>();
    /** The place in the hue wheel each entry's starting colour was taken from. */
    private static final Map<Integer,Integer> savedIndex=new HashMap<>();
    /** Takes in the saved selections a status carries. A status that does not carry them (null) leaves them as they were. */
    /** What the jigsaw planner said about its last run, as the server last sent it. */
    public static String jigsawReport="";
    static void updateJigsawReport(Packets.Status status){if(status.jigsawReport()!=null)jigsawReport=status.jigsawReport();if(status.jigsawModel()!=null){jigsawModel=status.jigsawModel();jigsawModelVersion++;}}
    /** The pool editor's model as the server last sent it (JSON text), and a number that changes with each one. */
    public static String jigsawModel="";
    public static int jigsawModelVersion;
    static void updateSavedSelections(Packets.Status status){
        if(status.savedSelections()==null)return;
        savedSelections=List.copyOf(status.savedSelections());
        indexSaved();hierarchyVersion++;
        loadView();
        // Folds belong to this player, not to the world: they are read from this player's own file by GUID, whatever the server has stored.
        if(status.savedGroups()!=null){savedGroups=List.copyOf(status.savedGroups());for(var g:savedGroups){if(g.guid()!=null&&view.folded(g.guid()))groupFolded.add(g.id());else groupFolded.remove(g.id());}}
        // The eye and the fold of each BuildObject come from what this player chose before, by GUID; one never seen starts with its eye closed (it shows only while it is selected).
        for(var saved:savedSelections){
            var guid=saved.guid();if(guid==null)continue;
            if(!view.knowsEye(guid)){view.setHidden(guid,true);viewChanged();}
            if(view.hidden(guid))savedHidden.add(saved.id());else savedHidden.remove(saved.id());
            if(view.folded(guid))savedFolded.add(saved.id());else savedFolded.remove(saved.id());
        }
        var present=new HashSet<Integer>();
        for(var saved:savedSelections){
            present.add(saved.id());
            if(!savedColorPending.containsKey(saved.id())){ // the colour everyone sees it in, unless this player is in the middle of changing it; none chosen is the selection colour
                if(saved.color()!=0)savedColors.put(saved.id(),saved.color());else savedColors.remove(saved.id());
            }
            if(saved.data()==null)continue; // unchanged: the outline already made for it is still right
            if(saved.data().isEmpty())savedShapes.remove(saved.id()); // left out because the status was too big: nothing is drawn for it
            else{var blocks=SelectionCodec.decode(saved.data());savedBlocks.put(saved.id(),blocks);savedShapes.put(saved.id(),OutlineShape.ofLater(blocks));}
        }
        savedShapes.keySet().retainAll(present);savedBlocks.keySet().retainAll(savedShapes.keySet());
        // an older server sends no GUID, so there is nothing to keep an eye by: a BuildObject seen for the first time starts closed
        for(var saved:savedSelections)if(saved.guid()==null&&savedWithoutGuid.add(saved.id()))savedHidden.add(saved.id());
        savedWithoutGuid.retainAll(present);
    }
    /** The groups of the Hierarchy, in order, as the server last said; empty on a server that predates them, and the Hierarchy then shows no groups. */
    private static List<Packets.SavedGroup> savedGroups=List.of();
    public static List<Packets.SavedGroup> savedGroups(){return savedGroups;}
    /** The groups folded in the Hierarchy, so what is in them is not listed. Like the fold of a BuildObject it belongs to this player, and is kept by GUID. */
    private static final Set<Integer> groupFolded=new HashSet<>();
    public static boolean groupFolded(int id){return groupFolded.contains(id);}
    public static void toggleGroupFolded(int id){
        boolean folded=!groupFolded.contains(id);
        if(folded)groupFolded.add(id);else groupFolded.remove(id);
        hierarchyVersion++;
        String guid=null;for(var g:savedGroups)if(g.id()==id)guid=g.guid();
        if(guid!=null&&view.setFolded(guid,folded))viewChanged();
    }
    /** The outline of each saved selection by id, drawn in the selection style. A selection with none is drawn as a box. */
    private static final Map<Integer,OutlineShape> savedShapes=new HashMap<>();
    static OutlineShape savedShape(int id){return savedShapes.get(id);}
    /** The blocks of each saved selection by id, kept to tell which BuildObject a block under the pointer belongs to. */
    private static final Map<Integer,Set<dev.craftium.core.Point>> savedBlocks=new HashMap<>();
    /** The BuildObject (the deepest one) that holds the block, or 0 when none does. */
    public static int savedAt(dev.craftium.core.Point block){
        int best=0,bestDepth=-1;
        for(var e:savedSelections){
            var blocks=savedBlocks.get(e.id());if(blocks==null||!blocks.contains(block))continue;
            int depth=0,n=0;for(var p=e;p!=null&&n++<64;){p=savedById.get(p.parent());if(p!=null)depth++;}
            if(depth>bestDepth){best=e.id();bestDepth=depth;}
        }
        return best;
    }
    /** The BuildObject the pointer is over in a viewport while Hover BuildObjects is on, or 0: it shows its own outline and is lit in the Hierarchy. */
    public static int hoverWorld;
    /** The BuildObjects whose eye is closed, which is every new one: the outline of one shows in the world only with its eye open, or while it is selected. Like the colours, they are kept by id when an entry is deleted, so Undo brings one back as it was. */
    private static final Set<Integer> savedHidden=new HashSet<>();
    public static boolean savedHidden(int id){return savedHidden.contains(id);}
    public static void toggleSavedHidden(int id){setSavedHidden(id,!savedHidden.contains(id));}
    public static void setSavedHidden(int id,boolean hidden){
        if(hidden)savedHidden.add(id);else savedHidden.remove(id);
        var guid=guidOf(id);if(guid!=null&&view.setHidden(guid,hidden))viewChanged();
    }
    private static String guidOf(int id){var e=savedById.get(id);return e==null?null:e.guid();}
    /** What this player chose to see of the Hierarchy, kept by GUID in {@code config/craftium/hierarchy-view.json} (see {@link dev.craftium.core.HierarchyView}). */
    private static final dev.craftium.core.HierarchyView view=new dev.craftium.core.HierarchyView();
    private static boolean viewLoaded,viewDirty;private static long viewChangedAt;
    private static java.nio.file.Path viewFile(){return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("craftium/hierarchy-view.json");}
    private static void loadView(){
        if(viewLoaded)return;viewLoaded=true;
        try{var file=viewFile();if(java.nio.file.Files.exists(file))view.load(java.nio.file.Files.readString(file));}catch(Exception ignored){}
    }
    private static void viewChanged(){viewDirty=true;viewChangedAt=System.currentTimeMillis();}
    /** Writes the choices once they have been still for a moment. Called every client tick. */
    static void flushView(){
        if(!viewDirty||System.currentTimeMillis()-viewChangedAt<1000)return;
        viewDirty=false;
        try{var file=viewFile();java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,view.toJson());}catch(Exception e){notify("Could not save the Hierarchy view");}
    }
    /** The BuildObject the pointer is on in the Hierarchy (or whose menu is open), or 0: it and what is under it are drawn in the world whatever their eyes say. */
    public static int hoverSaved;
    /**
     * The corners of the box round the blocks of the BuildObject and of everything under it (not only the blocks it holds itself, which an empty parent has none of), or null
     * when there are none.
     */
    public static dev.craftium.core.Point[] subtreeBounds(int id){
        dev.craftium.core.Point lo=null,hi=null;
        for(var e:savedSelections){
            if(e.id()!=id&&!savedUnder(e,id))continue;
            if(e.size()==0||e.min()==null||e.max()==null)continue;
            lo=lo==null?e.min():new dev.craftium.core.Point(Math.min(lo.x(),e.min().x()),Math.min(lo.y(),e.min().y()),Math.min(lo.z(),e.min().z()));
            hi=hi==null?e.max():new dev.craftium.core.Point(Math.max(hi.x(),e.max().x()),Math.max(hi.y(),e.max().y()),Math.max(hi.z(),e.max().z()));
        }
        return lo==null?null:new dev.craftium.core.Point[]{lo,hi};
    }
    /** How many blocks the BuildObject holds with everything under it. */
    public static int subtreeSize(int id){int n=0;for(var e:savedSelections)if(e.id()==id||savedUnder(e,id))n+=e.size();return n;}
    /** Whether {@code saved} is the BuildObject {@code ancestor} or somewhere under it. */
    public static boolean savedUnder(Packets.SavedSelection saved,int ancestor){
        int steps=0;var at=saved;
        while(at!=null&&steps++<64){
            if(at.id()==ancestor)return true;
            at=savedById.get(at.parent());
        }
        return false;
    }
    /** The BuildObjects folded in the Hierarchy, so what is under them is not listed. A fold belongs to the client. */
    private static final Set<Integer> savedFolded=new HashSet<>();
    public static boolean savedFolded(int id){return savedFolded.contains(id);}
    public static void toggleSavedFolded(int id){
        boolean folded=!savedFolded.contains(id);
        if(folded)savedFolded.add(id);else savedFolded.remove(id);
        hierarchyVersion++;
        var guid=guidOf(id);if(guid!=null&&view.setFolded(guid,folded))viewChanged();
    }
    static void clearSavedSelections(){savedSelections=List.of();savedById=Map.of();hierarchyVersion++;savedGroups=List.of();groupFolded.clear();savedColors.clear();savedHidden.clear();savedFolded.clear();savedColorPending.clear();savedShapes.clear();savedBlocks.clear();savedIndex.clear();hoverWorld=0;}
    public static int savedColor(int id){return savedColors.getOrDefault(id,options.selectionColor);}
    private static final Set<Integer> savedWithoutGuid=new HashSet<>();
    /** Whether a colour was chosen for it: every BuildObject is drawn in the selection colour until one is. */
    public static boolean hasSavedColor(int id){return savedColors.containsKey(id);}
    /** Back to the selection colour. */
    public static void resetSavedColor(int id){savedColors.remove(id);savedColorPending.put(id,0);savedColorChanged=System.currentTimeMillis();}
    public static void setSavedColor(int id,int color){savedColors.put(id,color);savedColorPending.put(id,color);savedColorChanged=System.currentTimeMillis();}
    /** Colours this player changed that the server has not been told: sent once the picker has been still for a moment, a request at a time (the server drops those that come too close together). */
    private static final Map<Integer,Integer> savedColorPending=new LinkedHashMap<>();private static long savedColorChanged,savedColorSent;
    static void flushSavedColours(){
        if(savedColorPending.isEmpty()||!ClientHandshake.serverHas(dev.craftium.core.Handshake.SAVED_SELECTIONS))return;
        long now=System.currentTimeMillis();if(now-savedColorChanged<350||now-savedColorSent<150)return;
        var present=new HashSet<Integer>();for(var e:savedSelections)present.add(e.id());
        savedColorPending.keySet().retainAll(present); // one deleted since is nothing to tell the server
        if(savedColorPending.isEmpty())return;
        var first=savedColorPending.entrySet().iterator().next();savedColorPending.remove(first.getKey());savedColorSent=now;
        CraftiumClient.send("saved_selection:colour:"+first.getKey()+":"+first.getValue(),null,null);
    }
    /** The saved selection being looked at or edited, or null. */
    public static Packets.SavedSelection activeSaved(){for(var saved:savedSelections)if(saved.active())return saved;return null;}
    /** How many blocks the ghost of the plugin tool in use has; 0 when there is none. */
    public static int pluginPreviewBlocks(){return pluginPending.total();}
    static void clearPluginPreview(){pluginPending=GhostBlocks.EMPTY;pluginPendingData="";}
    public static boolean hasPending(){return pending.total()>0;}
    /** Whether Escape, and leaving the editor, cancel the waiting edit: not a jigsaw preview, which is cancelled in the planner. */
    public static boolean escapeCancelsPending(){return hasPending()&&!pending.keepsOnEscape();}
    /**
     * One of the player's AI sessions as the server last described it, with the build it holds as a preview (kept apart from the player's own and
     * drawn in a colour of its own) and when that description arrived.
     */
    public record AiSessionView(Packets.AiSession info,GhostBlocks ghost,int slot,long receivedNanos){
        public String id(){return info.id();}
        /** How long the session has been going, counted on from when the server said, so it ticks between statuses. */
        public long ageMs(){return info.stopped()?info.age():info.age()+(System.nanoTime()-receivedNanos)/1_000_000;} // a stopped session's clock is stopped
        /** How long since the session last had a request. */
        public long idleMs(){return info.idle()+(System.nanoTime()-receivedNanos)/1_000_000;}
    }
    /** The player's AI sessions, oldest first. */
    public static volatile List<AiSessionView> aiSessions=List.of();
    /** True when {@link #aiSessions} came from a server that predates AI sessions: it describes one preview, which is the default session's. */
    public static volatile boolean aiLegacy;
    private static final java.util.Map<String,Integer> aiSlots=new java.util.HashMap<>();
    /** The AI session with this id, or null. On a server that predates sessions the one preview it has is every id's. */
    public static AiSessionView aiSession(String id){
        for(var view:aiSessions)if(view.id().equals(id))return view;
        return aiLegacy&&!aiSessions.isEmpty()?aiSessions.get(0):null;
    }
    /** The preview that AI session holds, or an empty one. */
    static GhostBlocks aiGhost(String id){var view=aiSession(id);return view==null?GhostBlocks.EMPTY:view.ghost();}
    public static boolean hasAiPending(){for(var view:aiSessions)if(view.ghost().total()>0)return true;return false;}
    /** How many of the AI sessions hold a preview, and how many blocks those hold in all. */
    public static int aiPendingCount(){int n=0;for(var view:aiSessions)if(view.ghost().total()>0)n++;return n;}
    public static int aiPendingBlocks(){int n=0;for(var view:aiSessions)n+=view.ghost().total();return n;}
    /** The AI sessions whose ghost the player chose to hide, and the one being flashed (until the time, in System.nanoTime). */
    public static final java.util.Set<String> aiHidden=new java.util.HashSet<>();
    public static String highlightId="";
    public static long highlightUntil;
    /** Forgets every AI session, as when the player leaves the world. */
    static void clearAi(){aiSessions=List.of();aiLegacy=false;aiSlots.clear();aiHidden.clear();highlightId="";AiThumbnails.releaseAll();}
    /** The colour slot of an AI session: the lowest not in use, kept for as long as the session is listed so its colour does not change. */
    private static int slotFor(String id){
        var own=aiSlots.get(id);if(own!=null)return own;
        var taken=new java.util.HashSet<>(aiSlots.values());int slot=0;while(taken.contains(slot))slot++;
        aiSlots.put(id,slot);return slot;
    }
    /** For a test: makes this ghost the preview of that AI session. */
    public static void setAiGhost(String id,GhostBlocks ghost){
        if(ghost.total()>0){ghost.ai=true;ghost.slot=slotFor(id);}
        var info=new Packets.AiSession(id,"",ghost.total()>0?"preview":"idle",ghost.total(),null,null,"",0,"",0,0,false,"",0,"",null,null,0,false);
        aiSessions=List.of(new AiSessionView(info,ghost,slotFor(id),System.nanoTime()));aiLegacy=false;
    }
    /** Takes in the AI sessions a status describes; a status without the list (a background one) leaves them as they were. */
    static void updateAiSessions(Packets.Status status){
        var list=status.aiSessions();
        if(list==null){
            if(status.background())return;
            // A server from before AI sessions describes the one preview, which is the default session's.
            var old=GhostBlocks.parse(status.aiPendingData());
            if(old.total()==0&&status.aiUndo()==0){clearAi();return;}
            aiLegacy=true;AiThumbnails.keepOnly(java.util.Set.of(""));old.slot=slotFor("");if(old.total()>0)old.ai=true;
            var info=new Packets.AiSession("","",old.total()>0?"preview":"applied",old.total(),null,null,"",status.aiUndo(),status.aiUndoName(),0,0,false,"",0,"",null,null,0,false);
            aiSessions=List.of(new AiSessionView(info,old,old.slot,System.nanoTime()));
            return;
        }
        aiLegacy=false;
        var previous=new java.util.HashMap<String,AiSessionView>();for(var view:aiSessions)previous.put(view.id(),view);
        var next=new ArrayList<AiSessionView>(list.size());var ids=new java.util.HashSet<String>();
        for(var info:list){
            ids.add(info.id());int slot=slotFor(info.id());
            GhostBlocks ghost;
            if(info.data()==null){var before=previous.get(info.id());ghost=before!=null&&before.info().previewId()==info.previewId()?before.ghost():GhostBlocks.EMPTY;} // unchanged: the one already here
            else ghost=GhostBlocks.parse(info.data());
            if(ghost.total()>0){ghost.ai=true;ghost.slot=slot;}
            next.add(new AiSessionView(info,ghost,slot,System.nanoTime()));
        }
        aiSlots.keySet().retainAll(ids);aiHidden.retainAll(ids);AiThumbnails.keepOnly(ids);
        aiSessions=List.copyOf(next);
    }
    /** Another player's AI preview, drawn beside the player's own in a colour of its own, with their name. */
    public record SharedAi(String owner,GhostBlocks ghost){}
    /** The AI previews other players in this dimension are holding, as the server last said. */
    public static List<SharedAi> sharedAi=List.of();
    /** Takes in the list a status brings of other players' AI previews; a status without one leaves it as it was. */
    static void updateShared(dev.craftium.net.Packets.Status status){
        var list=status.sharedAi();if(list==null)return;
        var next=new ArrayList<SharedAi>(list.size());
        for(var one:list){var ghost=GhostBlocks.parse(one.data());if(ghost.total()>0){ghost.shared=true;next.add(new SharedAi(one.owner(),ghost));}}
        sharedAi=List.copyOf(next);
    }
    /** The corners of the box being selected, which stay as handles the player can drag to resize it. At most two. */
    public static final List<Point> boxCorners=new ArrayList<>();
    /** When above zero, the editor shows a small popup asking whether this many selected blocks was meant. */
    public static int largeSelection;
    /** The selection the server just sent while the popup is waiting, kept aside so it is not built or drawn until confirmed. */
    public static String heldSelection="";
    public static boolean selectionPending;
    /** When the selection action in flight was sent, or 0. One that is still out after {@link #SELECTING_DELAY} milliseconds is a large one. */
    public static long selectingSince;
    public static final long SELECTING_DELAY=250;
    public static boolean selecting(){long since=selectingSince;return since!=0&&System.currentTimeMillis()-since>SELECTING_DELAY&&System.currentTimeMillis()-since<120_000;}
    public static int selectionSizeBefore;
    /** The measurements the Ruler tool has made, each with a knob at both ends. */
    public static final List<Ruler> rulers=new ArrayList<>();
    /** The ruler picked in the Rulers panel or in the world, or null. */
    public static Ruler selectedRuler;
    /** The picked ruler if it is still there, else null. */
    public static Ruler selectedRuler(){if(selectedRuler!=null&&!rulers.contains(selectedRuler))selectedRuler=null;return selectedRuler;}
    /** The ends of every ruler, one after the other, as one list: the tool's handles work on it, and adding a point places the next end. */
    public static final List<Point> rulerPoints=new java.util.AbstractList<>(){
        @Override public int size(){int count=0;for(var r:rulers)count+=r.complete()?2:1;return count;}
        @Override public Point get(int index){
            for(var r:rulers){if(index==0)return r.a;index--;if(r.complete()){if(index==0)return r.b;index--;}}
            throw new IndexOutOfBoundsException(index);
        }
        @Override public Point set(int index,Point point){
            for(var r:rulers){
                if(index==0){var old=r.a;r.a=point;return old;}index--;
                if(r.complete()){if(index==0){var old=r.b;r.b=point;return old;}index--;}
            }
            throw new IndexOutOfBoundsException(index);
        }
        /** Only the end of the list: the second end of the ruler waiting for one, or the first end of a new ruler. */
        @Override public void add(int index,Point point){
            if(index!=size())throw new UnsupportedOperationException("Ruler ends are added at the end");
            if(!rulers.isEmpty()&&!rulers.getLast().complete())rulers.getLast().b=point;
            else if(rulers.size()<256){var ruler=new Ruler(point);ruler.mode=options.rulerMode;rulers.add(ruler);} // a new ruler starts as the mode chosen for new ones
            else throw new IllegalArgumentException("Ruler limit: 256 rulers");
        }
        /** Taking an end away takes its whole ruler. */
        @Override public Point remove(int index){
            int at=index;
            for(var r:rulers){
                int ends=r.complete()?2:1;
                if(at<ends){var old=get(index);rulers.remove(r);return old;}
                at-=ends;
            }
            throw new IndexOutOfBoundsException(index);
        }
        @Override public void clear(){rulers.clear();}
    };
    public static List<BlueprintInfo> blueprints=List.of();
    public static int libraryVersion;
    public static final List<String> palette=new ArrayList<>(List.of("minecraft:stone","minecraft:andesite","minecraft:deepslate","minecraft:grass_block","minecraft:dirt","minecraft:sand","minecraft:oak_planks","minecraft:glass","minecraft:air"));
    private ClientState() {}
    public static boolean symmetry(){return options.symmetryX||options.symmetryY||options.symmetryZ||options.symmetryRotations>1;}
    public static void load() {
        if(!Files.exists(CONFIG))return;
        try {Options saved=Packets.JSON.fromJson(Files.readString(CONFIG),Options.class);saved.validate();saved.invert=false;options=saved;}
        catch(Exception e){Craftium.LOG.warn("Could not read craftium.json; using defaults",e);}
    }
    public static void save() {
        try {
            Files.createDirectories(CONFIG.getParent());Path temp=CONFIG.resolveSibling("craftium.json.tmp");
            Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(options));
            Files.move(temp,CONFIG,StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception e){Craftium.LOG.warn("Could not save Craftium settings",e);}
    }
    public static void notify(String message) {notice=message;noticeUntil=System.currentTimeMillis()+4500;}
    public static void pick(String block) {
        // A block already in the palette keeps its cell; only a new one is added, at the front.
        options.block=block;addToPalette(block);
    }
    /** The most blocks the palette keeps; the oldest fall off the end. */
    public static final int PALETTE_LIMIT=64;
    /** Adds a block to the front of the palette without making it the foreground. A block already there keeps its cell. */
    public static boolean addToPalette(String block) {
        if(palette.contains(block))return false;
        palette.addFirst(block);while(palette.size()>PALETTE_LIMIT)palette.removeLast();return true;
    }
}
