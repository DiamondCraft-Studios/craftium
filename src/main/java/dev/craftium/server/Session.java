package dev.craftium.server;

import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import java.util.*;

final class Session {
    final Selection selection = new Selection();
    final LinkedHashMap<Point,BlockSnapshot> clipboard = new LinkedHashMap<>();
    final List<net.minecraft.nbt.CompoundTag> clipboardEntities=new ArrayList<>();
    final Deque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    final List<String> events = new ArrayList<>();
    ResourceKey<Level> dimension;
    /**
     * True for the AI's own workspace: the preview it is holding ({@link #pendingEdit}) and the builds it can undo, kept apart from the
     * player's. It has no selection, is never saved, and its statuses are described through the player's session.
     */
    boolean ai;
    /** For an AI workspace: whose it is, which of their AI sessions it is ("" for the default one), and the label its AI app gave the build. */
    UUID aiOwner;String aiId="",label="";
    /** For an AI workspace: when it began and when it last had a request (epoch milliseconds), the tool of that request, and whether the player stopped it. */
    long createdAt,lastActive;String lastTool="";boolean stopped;
    /** For an AI workspace: whether the player chose its label, so the AI app's own is no longer put over it. */
    boolean renamed;
    /** For an AI workspace: the box its waiting preview or latest build fills, and when that was set. Null when it has none. Other sessions' previews are checked against it. */
    dev.craftium.core.AiSessions.Region region;long regionAt;
    /** For a player's own session: the preview each of their AI sessions had when this player was last sent it (AI session id to preview number), so only a change carries ghost data again. */
    final Map<String,Long> sentAi=new HashMap<>();
    Point corner, ruler, line;
    long lastRequest;
    /** The game mode the player had before the editor made them a spectator; null when nothing is waiting to be restored. */
    net.minecraft.world.level.GameType previousMode;
    /** Where the clipboard's blocks were copied from (its minimum corner), or null when they came from elsewhere, like a blueprint. */
    Point clipboardOrigin;
    /** An edit worked out but not yet applied, waiting for the player to confirm it. Null when there is none. */
    Edit pendingEdit;
    /** Which preview this AI workspace is holding, numbered as each is made, so another player's client can tell it changed. */
    long previewId;
    /** What this player was last shown of other players' AI previews, so only a change is sent. Null makes the next status carry them again. */
    String sharedKey;
    String pendingData="";boolean pendingDirty;
    /** What the last AI spec planned looked wrong in (see BuildSpec.Result#warnings), for the reply that describes its preview. */
    List<String> specWarnings=List.of();
    /** The clipboard's blocks as a ghost for the placement preview, and whether it needs encoding again. */
    String clipboardGhost="";boolean clipboardGhostDirty=true;
    /** Copied from the player's options with each request: whether edits wait for confirmation, and whether this request is exempt. */
    boolean confirmEdits,bypassConfirm;
    /** Why the last planned edit changed nothing, for the "No matching blocks changed" message; null when unknown. */
    String nothingChangedBecause;
    /** A pivot for the client to take up after an undo or redo, as "none" or "x,y,z"; empty when there is nothing to say. */
    String pivotUpdate="";
    /** Client-side state for the client to take up after an undo or redo, as {@link dev.craftium.core.Restore} text; empty when there is nothing to say. */
    String restoreUpdate="";
    /** The corners of the Box Select tool's box as of this request, and as of the last time a selection was noted in the history: "none" or points. */
    String cornersNow="none",cornersHistory="none";
    /** The pivot the player had as of this request and as of the one before it, as "none" or "x,y,z". */
    String pivotNow="none",pivotBeforeRequest="none";
    /** The shape the Shape tool was asked to build, as "x,y,z,yaw,pitch,roll,sizeX,sizeY,sizeZ", for undo to put back as waiting to be built; null for any other request. */
    String shapeAtRequest;
    /** True while handling a request made with the builder tool, so the edit it makes is marked as the builder's. */
    boolean fromBuilder;
    /** The selection as it was before the last selection action, so a mistaken one can be taken back. Null when there is none. */
    List<Point> selectionBefore;
    /** The selection as of the last time it was noted in the history, the revision it had, and whether the current action is a selection action. */
    List<Point> historySelection=List.of();long historyRevision;boolean recordSelection;String currentAction="";
    /** The selection before a transform that is waiting for confirmation moved it, so cancelling can put it back. */
    List<Point> pendingSelectionBefore;
    /**
     * The transforms behind {@link #pendingEdit} when it holds a run of them, the edit that was staged before the first of them, and the
     * pending edit they produced (to tell that nothing else has been added to it since). A further transform starts again from the
     * original blocks instead of from the preview.
     */
    ModelTransform.Chain pendingChain;Edit pendingChainBase,pendingChainEdit;
    long requestStroke, lastStroke;
    /** The stroke the game's own placements belong to while the place button is held, so they undo as one; 0 when no stroke is open. */
    long placeStroke;
    /** Whether the edit this request starts goes into the undo history. */
    boolean requestRecord=true;
    boolean busy;
    /** Builder commits that arrived while an edit was being applied, as the requests were sent; each is planned when its turn comes, against the world as the earlier ones left it. */
    final ArrayDeque<String> queued=new ArrayDeque<>();
    /** A pulse for the client to show with the next status, as "undo|redo:milliseconds:points"; empty when there is none. */
    String pulse="";
    UUID selectedEntity;
    long selectionRevision=-1;
    String selectionData="";
    String clipboardData="";
    boolean clipboardDirty=true;
    List<dev.craftium.core.BlueprintInfo> library=List.of();
    boolean noClip;
    /** Whether the player steps up a whole block on their own, as they step up a slab. */
    boolean blockStep;
    dev.craftium.core.Options.ReachMode reach=dev.craftium.core.Options.ReachMode.NORMAL;
    int farReach=32;
    /** The options of the player's latest request, which vanilla block placement reads for the capabilities it bends. */
    dev.craftium.core.Options options;
    int currentHotbar=-1;
    java.nio.file.Path historyPath;
    boolean historyDirty;
    int historyRemaining;
    int bytes;
    record Change(Point pos, BlockSnapshot before, BlockSnapshot after) {}
    record BiomeChange(Point cell, Holder<Biome> before, Holder<Biome> after) {}
    record EntityChange(UUID id,net.minecraft.nbt.CompoundTag before,net.minecraft.nbt.CompoundTag after,boolean moving){EntityChange(UUID id,net.minecraft.nbt.CompoundTag before,net.minecraft.nbt.CompoundTag after){this(id,before,after,false);}}
    /** What a selection action changed: the selection before and after, so undo and redo can put either back. */
    record SelectionChange(List<Point> before,List<Point> after,String pivotBefore,String pivotAfter,Map<String,String> restoreBefore,Map<String,String> restoreAfter){
        SelectionChange(List<Point> before,List<Point> after,String pivotBefore,String pivotAfter){this(before,after,pivotBefore,pivotAfter,Map.of(),Map.of());}
        SelectionChange(List<Point> before,List<Point> after){this(before,after,null,null);}
        /** A change to client-side state alone (see {@link dev.craftium.core.Restore}): the player's own selection is left as it is. */
        static SelectionChange restoring(Map<String,String> before,Map<String,String> after){return new SelectionChange(null,null,null,null,before,after);}
        /** True when undo and redo have a selection of the player's to put back, as opposed to only client-side state. */
        boolean hasPoints(){return before!=null&&after!=null;}
        /** The same change, also handing back these values of client-side state. */
        SelectionChange with(Map<String,String> before,Map<String,String> after){
            var b=new LinkedHashMap<>(restoreBefore);b.putAll(before);var a=new LinkedHashMap<>(restoreAfter);a.putAll(after);
            return new SelectionChange(this.before,this.after,pivotBefore,pivotAfter,b,a);
        }
        /** This change followed by a later one, as one: it starts where this began and ends where the later one did. */
        SelectionChange then(SelectionChange later){
            var b=new LinkedHashMap<>(later.restoreBefore);b.putAll(restoreBefore);var a=new LinkedHashMap<>(restoreAfter);a.putAll(later.restoreAfter);
            return new SelectionChange(before!=null?before:later.before,later.after!=null?later.after:after,pivotBefore,later.pivotAfter,b,a);
        }
    }
    /** {@code time} is when the edit was made, in epoch milliseconds, or 0 when unknown (history from before it was kept). */
    record Edit(String name, List<Change> changes, List<BiomeChange> biomes, int flags,List<EntityChange> entities,List<dev.craftium.core.Annotation.Change> annotations,SelectionChange selection,long time,boolean builder) {
        Edit(String name, List<Change> changes, List<BiomeChange> biomes, int flags,List<EntityChange> entities,List<dev.craftium.core.Annotation.Change> annotations,SelectionChange selection,long time){this(name,changes,biomes,flags,entities,annotations,selection,time,false);}
        Edit(String name, List<Change> changes, List<BiomeChange> biomes, int flags,List<EntityChange> entities,List<dev.craftium.core.Annotation.Change> annotations,SelectionChange selection){this(name,changes,biomes,flags,entities,annotations,selection,System.currentTimeMillis());}
        /** The same edit, marked as made with the builder tool (or not), which is what the builder's own undo and redo reach. */
        Edit withBuilder(boolean made){return new Edit(name,changes,biomes,flags,entities,annotations,selection,time,made);}
        Edit(String name,List<Change> changes,List<BiomeChange> biomes,int flags,List<EntityChange> entities,List<dev.craftium.core.Annotation.Change> annotations){this(name,changes,biomes,flags,entities,annotations,null);}
        /** True when this entry only changes the selection, so there are no blocks to put back. */
        boolean selectionOnly(){return selection!=null&&selection.hasPoints()&&changes.isEmpty()&&biomes.isEmpty()&&entities.isEmpty()&&annotations.isEmpty();}
        /** The same edit, also moving the selection from {@code before} to {@code after} when it is undone or redone. */
        Edit withSelection(SelectionChange change){return new Edit(name,changes,biomes,flags,entities,annotations,change,time,builder);}
        /** An entry in the history for a change to the selection alone, which has no blocks to restore. */
        static Edit selecting(String name,SelectionChange change){return new Edit(name,List.of(),List.of(),0,List.of(),List.of(),change);}
        Edit(String name,List<Change> changes,List<BiomeChange> biomes,int flags){this(name,changes,biomes,flags,List.of(),List.of());}
        Edit(String name,List<Change> changes,List<BiomeChange> biomes,int flags,List<EntityChange> entities){this(name,changes,biomes,flags,entities,List.of());}
        int estimatedBytes() {
            int size = changes.size()*128 + biomes.size()*96;
            if(selection!=null&&selection.hasPoints())size+=(selection.before().size()+selection.after().size())*24;
            for (var c : changes) {
                if(c.before.data()!=null) size += c.before.data().toString().length()*2;
                if(c.after.data()!=null) size += c.after.data().toString().length()*2;
            }
            for(var e:entities){if(e.before()!=null)size+=e.before().toString().length()*2;if(e.after()!=null)size+=e.after().toString().length()*2;}
            for(var change:annotations){for(var note:new dev.craftium.core.Annotation[]{change.before(),change.after()})if(note!=null)size+=256+note.text().length()*2+(note.points()==null?0:note.points().size()*24)+(note.pixels()==null?0:note.pixels().size()*4);}
            return size;
        }
    }
    void event(String text) {
        events.addFirst(text); while(events.size()>12) events.removeLast();
    }
    void remember(Edit edit) {
        remember(edit,0);
    }
    void remember(Edit edit,long stroke) {
        historyDirty=true;
        if(stroke!=0 && stroke==lastStroke && !undo.isEmpty() && redo.isEmpty()
                && edit.entities().isEmpty() && edit.annotations().isEmpty()
                && undo.peekFirst().entities().isEmpty() && undo.peekFirst().annotations().isEmpty()) {
            Edit previous=undo.removeFirst();
            var combined=new LinkedHashMap<Point,Change>();
            for(var c:previous.changes())combined.put(c.pos(),c);
            for(var c:edit.changes()) {
                var old=combined.get(c.pos());
                combined.put(c.pos(),old==null?c:new Change(c.pos(),old.before(),c.after()));
            }
            var biomeMap=new LinkedHashMap<Point,BiomeChange>();
            for(var c:previous.biomes())biomeMap.put(c.cell(),c);
            for(var c:edit.biomes()) {
                var old=biomeMap.get(c.cell());
                biomeMap.put(c.cell(),old==null?c:new BiomeChange(c.cell(),old.before(),c.after()));
            }
            var merged=new Edit(previous.name(),List.copyOf(combined.values()),List.copyOf(biomeMap.values()),edit.flags()).withBuilder(previous.builder());
            if(merged.estimatedBytes()<=Selection.HISTORY_BYTES && merged.changes().size()+merged.biomes().size()<=Selection.LIMIT)edit=merged;
            else undo.addFirst(previous);
        }
        lastStroke=stroke;
        redo.clear(); undo.addFirst(edit); recalculateBytes();
        while (undo.size()>32 || (bytes>Selection.HISTORY_BYTES && undo.size()>1)) bytes-=undo.removeLast().estimatedBytes();
        event(edit.name()+" · "+(edit.changes().size()+edit.biomes().size()+edit.entities().size()+edit.annotations().size())+" changes");
    }
    void recalculateBytes() {bytes=0;for(var e:undo)bytes+=e.estimatedBytes();for(var e:redo)bytes+=e.estimatedBytes();}
}
