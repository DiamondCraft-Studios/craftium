package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Per-world/player/dimension history. Immutable edit snapshots are compressed off the server thread. */
final class HistoryStore {
    private record Snapshot(Path file,List<Session.Edit> undo,List<Session.Edit> redo){}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"Craftium history writer");thread.setDaemon(true);return thread;});
    private static final Map<Path,Snapshot> QUEUED=new ConcurrentHashMap<>();
    private static CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    private HistoryStore(){}
    static void attach(ServerPlayer player,Session session){
        if(session.historyPath!=null)return;session.historyPath=player.level().getServer().getWorldPath(LevelResource.ROOT).resolve("craftium/history").resolve(player.getUUID()+"-"+dev.craftium.compat.Compat.keyName(player.level().dimension()).replace(':','_')+".nbt");
        try{flush();}catch(CompletionException e){dev.craftium.runtime.ServerPlatform.LOG.warn("A prior history write did not finish successfully",e);}load(player,session);
    }
    static void load(ServerPlayer player,Session session){
        if(!Files.exists(session.historyPath))return;
        try{
            var root=NbtIo.readCompressed(session.historyPath,NbtAccounter.create(96*1024*1024));if(root.getIntOr("format",0)!=1||root.getIntOr("DataVersion",0)!=net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version())throw new IllegalArgumentException("History version differs from this Minecraft build");
            var undo=readEdits(root.getListOrEmpty("undo"),player);var redo=readEdits(root.getListOrEmpty("redo"),player);int bytes=0;for(var e:undo)bytes+=e.estimatedBytes();for(var e:redo)bytes+=e.estimatedBytes();if(bytes>34*1024*1024)throw new IllegalArgumentException("Stored history exceeds memory budget");
            session.undo.clear();session.undo.addAll(undo);session.redo.clear();session.redo.addAll(redo);session.recalculateBytes();session.historyDirty=false;session.lastStroke=0;
        }catch(Exception e){dev.craftium.runtime.ServerPlatform.LOG.warn("Cannot load Craftium history {}",session.historyPath,e);}
    }
    static void save(Session session){
        if(session.historyPath==null||!session.historyDirty)return;session.historyDirty=false;
        Snapshot snapshot=new Snapshot(session.historyPath,session.undo.stream().filter(e->!e.selectionOnly()&&!e.hierarchyOnly()).toList(),session.redo.stream().filter(e->!e.selectionOnly()&&!e.hierarchyOnly()).toList());QUEUED.put(snapshot.file,snapshot);
        synchronized(HistoryStore.class){pending=pending.handle((v,e)->null).thenRunAsync(()->{Snapshot next=QUEUED.remove(snapshot.file);if(next!=null)write(next);},IO);}
    }
    static void flush(){CompletableFuture<Void> task;synchronized(HistoryStore.class){task=pending;}task.join();}
    private static void write(Snapshot snapshot){try{var root=new CompoundTag();root.putInt("format",1);root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());root.put("undo",edits(snapshot.undo));root.put("redo",edits(snapshot.redo));Files.createDirectories(snapshot.file.getParent());Path temp=snapshot.file.resolveSibling(snapshot.file.getFileName()+".tmp");NbtIo.writeCompressed(root,temp);try{Files.move(temp,snapshot.file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,snapshot.file,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){dev.craftium.runtime.ServerPlatform.LOG.error("Cannot save Craftium history {}",snapshot.file,e);throw new CompletionException(e);}}
    private static CompoundTag snapshot(BlockSnapshot block){var tag=new CompoundTag();tag.put("state",NbtUtils.writeBlockState(block.state()));if(block.data()!=null)tag.put("data",block.data().copy());return tag;}
    private static BlockSnapshot snapshot(CompoundTag tag,ServerPlayer player){var state=tag.getCompoundOrEmpty("state");var id=dev.craftium.compat.Compat.parse(state.getStringOr("Name","minecraft:air"));if(!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("History uses missing block "+id);return new BlockSnapshot(NbtUtils.readBlockState(player.registryAccess().lookupOrThrow(Registries.BLOCK),state),tag.getCompound("data").orElse(null));}
    private static Point point(CompoundTag tag,String name){int[] coordinates=tag.getIntArray(name).orElseThrow();if(coordinates.length!=3)throw new IllegalArgumentException("Invalid history position");return new Point(coordinates[0],coordinates[1],coordinates[2]);}
    private static void point(CompoundTag tag,String name,Point p){tag.putIntArray(name,new int[]{p.x(),p.y(),p.z()});}
    private static ListTag edits(List<Session.Edit> edits){
        var list=new ListTag();for(var edit:edits){var tag=new CompoundTag();tag.putString("name",edit.name());tag.putInt("flags",edit.flags());tag.putLong("time",edit.time());var blocks=new ListTag();
            for(var c:edit.changes()){var item=new CompoundTag();point(item,"pos",c.pos());item.put("before",snapshot(c.before()));item.put("after",snapshot(c.after()));blocks.add(item);}tag.put("blocks",blocks);
            var biomes=new ListTag();for(var c:edit.biomes()){var item=new CompoundTag();point(item,"pos",c.cell());item.putString("before",dev.craftium.compat.Compat.keyName(c.before().unwrapKey().orElseThrow()));item.putString("after",dev.craftium.compat.Compat.keyName(c.after().unwrapKey().orElseThrow()));biomes.add(item);}tag.put("biomes",biomes);
            var entities=new ListTag();for(var c:edit.entities()){var item=new CompoundTag();item.putString("uuid",c.id().toString());item.putBoolean("moving",c.moving());if(c.before()!=null)item.put("before",c.before().copy());if(c.after()!=null)item.put("after",c.after().copy());entities.add(item);}tag.put("entities",entities);
            tag.putString("annotations",Packets.JSON.toJson(edit.annotations()));list.add(tag);
        }return list;
    }
    private static List<Session.Edit> readEdits(ListTag list,ServerPlayer player){
        if(list.size()>32)throw new IllegalArgumentException("Invalid history length");var result=new ArrayList<Session.Edit>();
        for(int i=0;i<list.size();i++){var tag=list.getCompoundOrEmpty(i);var blocks=new ArrayList<Session.Change>();var entries=tag.getListOrEmpty("blocks");if(entries.size()>Selection.LIMIT)throw new IllegalArgumentException("Stored edit exceeds cell limit");
            for(int j=0;j<entries.size();j++){var item=entries.getCompoundOrEmpty(j);blocks.add(new Session.Change(point(item,"pos"),snapshot(item.getCompoundOrEmpty("before"),player),snapshot(item.getCompoundOrEmpty("after"),player)));}
            var biomes=new ArrayList<Session.BiomeChange>();entries=tag.getListOrEmpty("biomes");if(entries.size()>Selection.LIMIT)throw new IllegalArgumentException("Stored biome edit exceeds limit");var registry=player.registryAccess().lookupOrThrow(Registries.BIOME);
            for(int j=0;j<entries.size();j++){var item=entries.getCompoundOrEmpty(j);biomes.add(new Session.BiomeChange(point(item,"pos"),registry.get(ResourceKey.create(Registries.BIOME,dev.craftium.compat.Compat.parse(item.getStringOr("before","")))).orElseThrow(),registry.get(ResourceKey.create(Registries.BIOME,dev.craftium.compat.Compat.parse(item.getStringOr("after","")))).orElseThrow()));}
            var entities=new ArrayList<Session.EntityChange>();entries=tag.getListOrEmpty("entities");if(entries.size()>128)throw new IllegalArgumentException("Stored entity edit exceeds limit");for(int j=0;j<entries.size();j++){var item=entries.getCompoundOrEmpty(j);entities.add(new Session.EntityChange(UUID.fromString(item.getStringOr("uuid","")),item.getCompound("before").orElse(null),item.getCompound("after").orElse(null),item.getBooleanOr("moving",false)));}
            var notes=Packets.JSON.fromJson(tag.getStringOr("annotations","[]"),Annotation.Change[].class);if(notes==null||notes.length>128)throw new IllegalArgumentException("Invalid stored annotations");result.add(new Session.Edit(tag.getStringOr("name","Edit"),List.copyOf(blocks),List.copyOf(biomes),tag.getIntOr("flags",2),List.copyOf(entities),List.of(notes),null,tag.getLongOr("time",0L)));
        }return List.copyOf(result);
    }
}
