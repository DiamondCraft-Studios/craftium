package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.runtime.ServerPlatform;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The BuildObjects of each dimension of the world: one tree per dimension, shared by everyone who builds there, kept in the world's folder
 * ({@code craftium/buildobjects/<dimension>.nbt}) so they are there after a reload or a rejoin. A player sees the shared tree through a
 * {@link SavedSelections} of their own, which holds what they are looking at. Everything here runs on the server thread except the writing.
 */
final class BuildObjectStore {
    /** How often, in ticks, a tree that changed is written: 5 seconds. A tree is also written when the server stops. */
    private static final int WRITE_EVERY=100;
    static final class Slot{final Path file;final SavedSelections.Tree tree;long written;boolean overlaid;Slot(Path file,SavedSelections.Tree tree,long written){this.file=file;this.tree=tree;this.written=written;}}
    private static final Map<Path,Slot> SLOTS=new HashMap<>();
    /**
     * What an edit that has not finished (one waiting for Enter, or being applied over several ticks) has already done to the BuildObjects, as it was before it. The world is
     * unchanged until the edit is applied, so this is what gets written meanwhile: the BuildObjects then agree with the world however the game ends. Fills {@code own} (the
     * blocks of each BuildObject) and {@code held} (what it keeps hidden) for the BuildObjects of {@code tree}.
     */
    interface InFlight{void collect(SavedSelections.Tree tree,Map<Integer,List<Point>> own,Map<Integer,SavedSelections.Held> held);}
    static InFlight inFlight=EditorServer::collectInFlight;
    private static volatile boolean flushNow;
    /** Writes what changed at the next tick instead of at the next turn of the five seconds: an edit has just been applied or cancelled, and what is on disk is the older state. */
    static void flushSoon(){flushNow=true;}
    /** Starts keeping a tree for a file (what {@link #attach} does for a player's dimension). */
    static Slot track(Path file,SavedSelections.Tree tree){var slot=new Slot(file,tree,-1);SLOTS.put(file,slot);return slot;}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"Craftium BuildObject writer");thread.setDaemon(true);return thread;});
    private static CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    private static int ticks;
    private BuildObjectStore(){}

    /** Gives the session a view of the shared tree of the dimension the player is in. Done once per session (a new dimension is a new session). */
    static void attach(ServerPlayer player,Session session){
        if(session.savedAttached)return;
        session.saved=new SavedSelections(slot(player).tree);session.savedAttached=true;session.savedSent=-1;session.savedDataSent.clear();session.savedHiddenSent.clear();
    }
    private static Slot slot(ServerPlayer player){
        Path file=player.level().getServer().getWorldPath(LevelResource.ROOT).resolve("craftium/buildobjects").resolve(dev.craftium.compat.Compat.keyName(player.level().dimension()).replace(':','_')+".nbt");
        return SLOTS.computeIfAbsent(file,f->{var tree=load(f);return new Slot(f,tree,tree.mutations());});
    }

    /** Writes what changed, now and then. */
    static void tick(MinecraftServer server){
        if(flushNow){flushNow=false;saveChanged();return;}
        if(++ticks%WRITE_EVERY!=0)return;
        saveChanged();
    }
    static void saveChanged(){
        for(var slot:SLOTS.values()){
            var own=new HashMap<Integer,List<Point>>();var held=new HashMap<Integer,SavedSelections.Held>();
            if(inFlight!=null)inFlight.collect(slot.tree,own,held);
            boolean overlaid=!own.isEmpty()||!held.isEmpty();
            if(slot.tree.mutations()==slot.written&&overlaid==slot.overlaid)continue; // nothing changed, and it is not the end of an edit that was being kept out of it
            slot.written=slot.tree.mutations();slot.overlaid=overlaid;
            var rows=slot.tree.saved(own,held);var groups=slot.tree.savedGroups();int nextId=slot.tree.nextId(),nextGroupId=slot.tree.nextGroupId();Path file=slot.file;
            synchronized(BuildObjectStore.class){pending=pending.handle((v,e)->null).thenRunAsync(()->write(file,nextId,rows,nextGroupId,groups),IO);}
        }
    }
    /** At server stop: writes everything that changed, waits for it, and forgets the trees (a new server, even in the same game, loads them again). */
    static void shutdown(){
        saveChanged();
        try{CompletableFuture<Void> task;synchronized(BuildObjectStore.class){task=pending;}task.join();}
        catch(CompletionException e){ServerPlatform.LOG.error("Could not finish writing BuildObjects during shutdown",e);}
        SLOTS.clear();
    }

    static void write(Path file,int nextId,List<SavedSelections.Saved> rows){write(file,nextId,rows,1,List.of());}
    static void write(Path file,int nextId,List<SavedSelections.Saved> rows,int nextGroupId,List<SavedSelections.SavedGroup> groups){
        try{
            boolean held=rows.stream().anyMatch(r->!r.dormant().isEmpty()||!r.replaced().isEmpty());
            var root=new CompoundTag();root.putInt("format",held?2:1); // 2 only when something holds dormant blocks, so a world that does not use them still opens in an older build
            root.putInt("nextId",nextId);root.putInt("nextGroupId",nextGroupId);
            var groupList=new ListTag();
            for(var g:groups){var tag=new CompoundTag();tag.putInt("id",g.id());tag.putString("guid",g.guid());tag.putString("name",g.name());tag.putBoolean("folded",g.folded());groupList.add(tag);}
            root.put("groups",groupList);
            var list=new ListTag();
            for(var r:rows){
                var tag=new CompoundTag();tag.putInt("id",r.id());tag.putString("guid",r.guid());tag.putString("name",r.name());tag.putInt("parent",r.parentId());tag.putInt("color",r.color());tag.putInt("group",r.groupId());tag.putBoolean("folded",r.folded());
                var flat=new int[r.points().size()*3];int i=0;for(var p:r.points()){flat[i++]=p.x();flat[i++]=p.y();flat[i++]=p.z();}
                tag.putIntArray("points",flat);putCells(tag,"dormant",r.dormant());putCells(tag,"replaced",r.replaced());list.add(tag);
            }
            root.put("entries",list);
            Files.createDirectories(file.getParent());
            Path temp=file.resolveSibling(file.getFileName()+".tmp");
            NbtIo.writeCompressed(root,temp);
            try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){ServerPlatform.LOG.error("Cannot write BuildObjects {}",file,e);}
    }
    /** Writes packed cells as {@code name}: sorted cell keys, the states in use as one text of lines (a block state never has a line break), and each cell's index. Nothing is written for none. */
    static void putCells(CompoundTag tag,String name,PackedCells cells){
        if(cells.isEmpty())return;
        var c=new CompoundTag();c.putLongArray("cells",cells.keysArray());c.putString("states",String.join("\n",cells.paletteList()));c.putIntArray("index",cells.indexesArray());
        if(cells.dataKeysArray().length>0){ // block entities (a chest's items), as text, one entry each
            c.putLongArray("dataCells",cells.dataKeysArray());
            var texts=new ListTag();for(var d:cells.dataList()){var t=new CompoundTag();t.putString("v",d);texts.add(t);}
            c.put("data",texts);
        }
        tag.put(name,c);
    }
    /** The cells {@link #putCells} wrote, or none when absent. A store that does not add up throws, so the file is kept as .bad like any damaged one. */
    static PackedCells readCells(CompoundTag tag,String name){
        var c=tag.getCompoundOrEmpty(name);
        long[] keys=c.getLongArray("cells").orElse(new long[0]);
        if(keys.length==0)return PackedCells.EMPTY;
        if(keys.length>Selection.LIMIT)throw new IllegalArgumentException("Invalid BuildObject dormant blocks");
        var states=List.of(c.getStringOr("states","").split("\n",-1));
        long[] dataKeys=c.getLongArray("dataCells").orElse(new long[0]);
        var list=c.getListOrEmpty("data");var texts=new ArrayList<String>();
        for(int i=0;i<list.size();i++)texts.add(list.getCompoundOrEmpty(i).getStringOr("v",""));
        return PackedCells.of(keys,states,c.getIntArray("index").orElse(new int[0]),dataKeys,texts);
    }
    static SavedSelections.Tree load(Path file){
        if(!Files.exists(file))return new SavedSelections.Tree();
        try{
            var root=NbtIo.readCompressed(file,NbtAccounter.create(256L*1024*1024));
            int format=root.getIntOr("format",0);if(format!=1&&format!=2)throw new IllegalArgumentException("Unknown BuildObject file format");
            var list=root.getListOrEmpty("entries");
            if(list.size()>SavedSelections.MAX)throw new IllegalArgumentException("Too many BuildObjects");
            var rows=new ArrayList<SavedSelections.Saved>();
            for(int i=0;i<list.size();i++){
                var tag=list.getCompoundOrEmpty(i);
                int[] flat=tag.getIntArray("points").orElse(new int[0]);
                if(flat.length%3!=0||flat.length/3>Selection.LIMIT)throw new IllegalArgumentException("Invalid BuildObject blocks");
                var points=new ArrayList<Point>(flat.length/3);for(int j=0;j<flat.length;j+=3)points.add(new Point(flat[j],flat[j+1],flat[j+2]));
                rows.add(new SavedSelections.Saved(tag.getIntOr("id",0),tag.getStringOr("guid",UUID.randomUUID().toString()),tag.getStringOr("name","BuildObject"),tag.getIntOr("parent",0),tag.getIntOr("color",0),points,tag.getIntOr("group",0),tag.getBooleanOr("folded",false),format>=2?readCells(tag,"dormant"):PackedCells.EMPTY,format>=2?readCells(tag,"replaced"):PackedCells.EMPTY));
            }
            var groups=new ArrayList<SavedSelections.SavedGroup>();var groupList=root.getListOrEmpty("groups");
            for(int i=0;i<groupList.size();i++){var tag=groupList.getCompoundOrEmpty(i);groups.add(new SavedSelections.SavedGroup(tag.getIntOr("id",0),tag.getStringOr("guid",UUID.randomUUID().toString()),tag.getStringOr("name","New Group"),tag.getBooleanOr("folded",false)));}
            return SavedSelections.Tree.load(root.getIntOr("nextId",1),rows,root.getIntOr("nextGroupId",1),groups);
        }catch(Exception e){
            ServerPlatform.LOG.warn("Cannot load BuildObjects {}: starting with none, and keeping the file as {}.bad",file,file.getFileName(),e);
            try{Files.move(file,file.resolveSibling(file.getFileName()+".bad"),StandardCopyOption.REPLACE_EXISTING);}catch(Exception ignored){}
            return new SavedSelections.Tree();
        }
    }
}
