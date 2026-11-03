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
    Point corner, ruler, line;
    long lastRequest;
    /** The game mode the player had before the editor made them a spectator; null when nothing is waiting to be restored. */
    net.minecraft.world.level.GameType previousMode;
    /** Where the clipboard's blocks were copied from (its minimum corner), or null when they came from elsewhere, like a blueprint. */
    Point clipboardOrigin;
    /** An edit worked out but not yet applied, waiting for the player to confirm it. Null when there is none. */
    Edit pendingEdit;
    String pendingData="";boolean pendingDirty;
    /** The clipboard's blocks as a ghost for the placement preview, and whether it needs encoding again. */
    String clipboardGhost="";boolean clipboardGhostDirty=true;
    /** Copied from the player's options with each request: whether edits wait for confirmation, and whether this request is exempt. */
    boolean confirmEdits,bypassConfirm;
    /** The selection as it was before the last selection action, so a mistaken one can be taken back. Null when there is none. */
    List<Point> selectionBefore;
    /** The selection before a transform that is waiting for confirmation moved it, so cancelling can put it back. */
    List<Point> pendingSelectionBefore;
    long requestStroke, lastStroke;
    boolean busy;
    UUID selectedEntity;
    long selectionRevision=-1;
    String selectionData="";
    String clipboardData="";
    boolean clipboardDirty=true;
    List<dev.craftium.core.BlueprintInfo> library=List.of();
    boolean infiniteReach,noClip;
    int currentHotbar=-1;
    java.nio.file.Path historyPath;
    boolean historyDirty;
    int historyRemaining;
    int bytes;
    record Change(Point pos, BlockSnapshot before, BlockSnapshot after) {}
    record BiomeChange(Point cell, Holder<Biome> before, Holder<Biome> after) {}
    record EntityChange(UUID id,net.minecraft.nbt.CompoundTag before,net.minecraft.nbt.CompoundTag after,boolean moving){EntityChange(UUID id,net.minecraft.nbt.CompoundTag before,net.minecraft.nbt.CompoundTag after){this(id,before,after,false);}}
    record Edit(String name, List<Change> changes, List<BiomeChange> biomes, int flags,List<EntityChange> entities,List<dev.craftium.core.Annotation.Change> annotations) {
        Edit(String name,List<Change> changes,List<BiomeChange> biomes,int flags){this(name,changes,biomes,flags,List.of(),List.of());}
        Edit(String name,List<Change> changes,List<BiomeChange> biomes,int flags,List<EntityChange> entities){this(name,changes,biomes,flags,entities,List.of());}
        int estimatedBytes() {
            int size = changes.size()*128 + biomes.size()*96;
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
            var merged=new Edit(previous.name(),List.copyOf(combined.values()),List.copyOf(biomeMap.values()),edit.flags());
            if(merged.estimatedBytes()<=32*1024*1024 && merged.changes().size()+merged.biomes().size()<=Selection.LIMIT)edit=merged;
            else undo.addFirst(previous);
        }
        lastStroke=stroke;
        redo.clear(); undo.addFirst(edit); recalculateBytes();
        while (undo.size()>32 || (bytes>32*1024*1024 && undo.size()>1)) bytes-=undo.removeLast().estimatedBytes();
        event(edit.name()+" · "+(edit.changes().size()+edit.biomes().size()+edit.entities().size()+edit.annotations().size())+" changes");
    }
    void recalculateBytes() {bytes=0;for(var e:undo)bytes+=e.estimatedBytes();for(var e:redo)bytes+=e.estimatedBytes();}
}
