package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Per-world/player/dimension history. Immutable edit snapshots are compressed off the server thread. */
final class HistoryStore {
    private record Snapshot(Path file, List<Session.Edit> undo, List<Session.Edit> redo) {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "Craftium history writer");
        thread.setDaemon(true);
        return thread;
    });
    /** The history files' format: 1 kept each block change as its own compounds, 2 keeps an edit's blocks as arrays and a palette. */
    private static final int FORMAT = 2;
    /** Guards {@link #QUEUED} and {@link #WRITING}, which the server thread and the writer both use; nothing slow is done while holding it. */
    private static final Object LOCK = new Object();
    /** The newest snapshot of each file that is waiting to be written. */
    private static final Map<Path, Snapshot> QUEUED = new HashMap<>();
    /** The snapshot of each file the writer is writing now. */
    private static final Map<Path, Snapshot> WRITING = new HashMap<>();

    private static CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

    private HistoryStore() {}

    static void attach(ServerPlayer player, Session session) {
        if (session.historyPath != null) return;
        session.historyPath = player.level()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("craftium/history")
                .resolve(player.getUUID() + "-"
                        + dev.craftium.compat.Compat.keyName(player.level().dimension())
                                .replace(':', '_') + ".nbt");
        // A write of this file that has not finished holds the newest history, so it is taken from memory: waiting for
        // the writer here would stall the server for as long as any player's history takes to write.
        Snapshot latest;
        synchronized (LOCK) {
            latest = QUEUED.get(session.historyPath);
            if (latest == null) latest = WRITING.get(session.historyPath);
        }
        if (latest != null) {
            session.undo.clear();
            session.undo.addAll(latest.undo);
            session.redo.clear();
            session.redo.addAll(latest.redo);
            session.recalculateBytes();
            session.historyDirty = false;
            session.lastStroke = 0;
            return;
        }
        load(player, session); // no write of it is waiting or running, so the file is the newest
    }

    static void load(ServerPlayer player, Session session) {
        if (!Files.exists(session.historyPath)) return;
        try {
            var root = NbtIo.readCompressed(session.historyPath, NbtAccounter.create(3L * Selection.HISTORY_BYTES));
            int format = root.getIntOr("format", 0);
            if (format != 1 && format != FORMAT
                    || root.getIntOr("DataVersion", 0)
                            != net.minecraft.SharedConstants.getCurrentVersion()
                                    .dataVersion()
                                    .version())
                throw new IllegalArgumentException("History version differs from this Minecraft build");
            var undo = readEdits(root.getListOrEmpty("undo"), player, format);
            var redo = readEdits(root.getListOrEmpty("redo"), player, format);
            int bytes = 0;
            for (var e : undo) bytes += e.estimatedBytes();
            for (var e : redo) bytes += e.estimatedBytes();
            if (bytes > Selection.HISTORY_BYTES)
                throw new IllegalArgumentException("Stored history exceeds memory budget");
            session.undo.clear();
            session.undo.addAll(undo);
            session.redo.clear();
            session.redo.addAll(redo);
            session.recalculateBytes();
            session.historyDirty = false;
            session.lastStroke = 0;
        } catch (Exception e) {
            dev.craftium.runtime.ServerPlatform.LOG.warn("Cannot load Craftium history {}", session.historyPath, e);
        }
    }

    static void save(Session session) {
        if (session.historyPath == null || !session.historyDirty) return;
        session.historyDirty = false;
        Snapshot snapshot = new Snapshot(
                session.historyPath,
                session.undo.stream()
                        .filter(e -> !e.selectionOnly() && !e.hierarchyOnly() && !e.clientOnly())
                        .toList(),
                session.redo.stream()
                        .filter(e -> !e.selectionOnly() && !e.hierarchyOnly() && !e.clientOnly())
                        .toList());
        synchronized (LOCK) {
            QUEUED.put(snapshot.file, snapshot);
        }
        synchronized (HistoryStore.class) {
            pending = pending.handle((v, e) -> null)
                    .thenRunAsync(
                            () -> {
                                Snapshot next;
                                synchronized (LOCK) {
                                    next = QUEUED.remove(snapshot.file);
                                    if (next != null) WRITING.put(next.file, next);
                                }
                                if (next == null) return;
                                try {
                                    write(next);
                                } finally {
                                    synchronized (LOCK) {
                                        WRITING.remove(next.file, next);
                                    }
                                }
                            },
                            IO);
        }
    }

    static void flush() {
        CompletableFuture<Void> task;
        synchronized (HistoryStore.class) {
            task = pending;
        }
        task.join();
    }

    private static void write(Snapshot snapshot) {
        try {
            var root = new CompoundTag();
            root.putInt("format", FORMAT);
            root.putInt(
                    "DataVersion",
                    net.minecraft.SharedConstants.getCurrentVersion()
                            .dataVersion()
                            .version());
            root.put("undo", edits(snapshot.undo));
            root.put("redo", edits(snapshot.redo));
            Files.createDirectories(snapshot.file.getParent());
            Path temp = snapshot.file.resolveSibling(snapshot.file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp);
            try {
                Files.move(temp, snapshot.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, snapshot.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            dev.craftium.runtime.ServerPlatform.LOG.error("Cannot save Craftium history {}", snapshot.file, e);
            throw new CompletionException(e);
        }
    }

    private static CompoundTag snapshot(BlockSnapshot block) {
        var tag = new CompoundTag();
        tag.put("state", NbtUtils.writeBlockState(block.state()));
        if (block.data() != null) tag.put("data", block.data().copy());
        return tag;
    }

    private static BlockSnapshot snapshot(CompoundTag tag, ServerPlayer player) {
        return snapshot(tag, player.registryAccess().lookupOrThrow(Registries.BLOCK));
    }

    private static BlockSnapshot snapshot(
            CompoundTag tag, net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blocks) {
        var state = tag.getCompoundOrEmpty("state");
        var id = dev.craftium.compat.Compat.parse(state.getStringOr("Name", "minecraft:air"));
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id))
            throw new IllegalArgumentException("History uses missing block " + id);
        return new BlockSnapshot(
                NbtUtils.readBlockState(blocks, state), tag.getCompound("data").orElse(null));
    }

    private static Point point(CompoundTag tag, String name) {
        int[] coordinates = tag.getIntArray(name).orElseThrow();
        if (coordinates.length != 3) throw new IllegalArgumentException("Invalid history position");
        return new Point(coordinates[0], coordinates[1], coordinates[2]);
    }

    private static void point(CompoundTag tag, String name, Point p) {
        tag.putIntArray(name, new int[] {p.x(), p.y(), p.z()});
    }

    private static ListTag edits(List<Session.Edit> edits) {
        var list = new ListTag();
        for (var edit : edits) {
            var tag = new CompoundTag();
            tag.putString("name", edit.name());
            tag.putInt("flags", edit.flags());
            tag.putLong("time", edit.time());
            tag.put("blocks", blocks(edit.changes()));
            var biomes = new ListTag();
            for (var c : edit.biomes()) {
                var item = new CompoundTag();
                point(item, "pos", c.cell());
                item.putString(
                        "before",
                        dev.craftium.compat.Compat.keyName(
                                c.before().unwrapKey().orElseThrow()));
                item.putString(
                        "after",
                        dev.craftium.compat.Compat.keyName(c.after().unwrapKey().orElseThrow()));
                biomes.add(item);
            }
            tag.put("biomes", biomes);
            var entities = new ListTag();
            for (var c : edit.entities()) {
                var item = new CompoundTag();
                item.putString("uuid", c.id().toString());
                item.putBoolean("moving", c.moving());
                if (c.before() != null) item.put("before", c.before().copy());
                if (c.after() != null) item.put("after", c.after().copy());
                entities.add(item);
            }
            tag.put("entities", entities);
            tag.putString("annotations", Packets.JSON.toJson(edit.annotations()));
            if (edit.selection() != null && edit.selection().hasHeld())
                tag.put("held", heldTag(edit.selection())); // the dormant blocks that went with them
            if (edit.selection() != null && edit.selection().hasObjects())
                tag.put(
                        "objects",
                        objectsTag(edit.selection())); // where the BuildObjects' blocks were and went, so Undo after a
            // restart moves them back with the blocks
            list.add(tag);
        }
        return list;
    }
    /**
     * What an edit did to the BuildObjects' blocks, by BuildObject id (ids last with the world): before and after, flattened to x, y, z. The BuildObjects made, renamed, moved
     * or deleted in the Hierarchy are not kept (a restart cannot give back the very objects those steps held), so Undo of one of those from before a restart is not possible.
     */
    static ListTag objectsTag(Session.SelectionChange change) {
        var list = new ListTag();
        for (var e : change.objectsBefore().entrySet()) {
            var after = change.objectsAfter().get(e.getKey());
            if (after == null) continue;
            var item = new CompoundTag();
            item.putInt("id", e.getKey());
            item.putIntArray("before", flatten(e.getValue()));
            item.putIntArray("after", flatten(after));
            list.add(item);
        }
        return list;
    }
    /** The change that {@link #objectsTag} wrote, or null when there was none. */
    static Session.SelectionChange readObjects(ListTag list) {
        if (list.isEmpty()) return null;
        if (list.size() > SavedSelections.MAX) throw new IllegalArgumentException("Invalid stored BuildObject change");
        var before = new LinkedHashMap<Integer, List<Point>>();
        var after = new LinkedHashMap<Integer, List<Point>>();
        for (int i = 0; i < list.size(); i++) {
            var item = list.getCompoundOrEmpty(i);
            int id = item.getIntOr("id", 0);
            if (id <= 0) throw new IllegalArgumentException("Invalid stored BuildObject id");
            before.put(id, unflatten(item.getIntArray("before").orElse(new int[0])));
            after.put(id, unflatten(item.getIntArray("after").orElse(new int[0])));
        }
        return Session.SelectionChange.restoring(Map.of(), Map.of()).withObjects(before, after);
    }
    /** What an edit did to the blocks BuildObjects keep outside the world (dormant blocks, and world blocks they replaced), by BuildObject id: before and after. */
    static ListTag heldTag(Session.SelectionChange change) {
        var list = new ListTag();
        for (var e : change.heldBefore().entrySet()) {
            var after = change.heldAfter().get(e.getKey());
            if (after == null) continue;
            var item = new CompoundTag();
            item.putInt("id", e.getKey());
            BuildObjectStore.putCells(item, "dormantBefore", e.getValue().dormant());
            BuildObjectStore.putCells(item, "replacedBefore", e.getValue().replaced());
            BuildObjectStore.putCells(item, "dormantAfter", after.dormant());
            BuildObjectStore.putCells(item, "replacedAfter", after.replaced());
            list.add(item);
        }
        return list;
    }
    /** The change that {@link #heldTag} wrote, put on {@code base}; just {@code base} (which may be null) when there was none. */
    static Session.SelectionChange readHeld(ListTag list, Session.SelectionChange base) {
        if (list.isEmpty()) return base;
        if (list.size() > SavedSelections.MAX) throw new IllegalArgumentException("Invalid stored BuildObject change");
        var before = new LinkedHashMap<Integer, SavedSelections.Held>();
        var after = new LinkedHashMap<Integer, SavedSelections.Held>();
        for (int i = 0; i < list.size(); i++) {
            var item = list.getCompoundOrEmpty(i);
            int id = item.getIntOr("id", 0);
            if (id <= 0) throw new IllegalArgumentException("Invalid stored BuildObject id");
            before.put(
                    id,
                    new SavedSelections.Held(
                            BuildObjectStore.readCells(item, "dormantBefore"),
                            BuildObjectStore.readCells(item, "replacedBefore")));
            after.put(
                    id,
                    new SavedSelections.Held(
                            BuildObjectStore.readCells(item, "dormantAfter"),
                            BuildObjectStore.readCells(item, "replacedAfter")));
        }
        var change = base != null ? base : Session.SelectionChange.restoring(Map.of(), Map.of());
        return change.withHeld(before, after);
    }

    private static int[] flatten(List<Point> points) {
        var flat = new int[points.size() * 3];
        int i = 0;
        for (var p : points) {
            flat[i++] = p.x();
            flat[i++] = p.y();
            flat[i++] = p.z();
        }
        return flat;
    }

    private static List<Point> unflatten(int[] flat) {
        if (flat.length % 3 != 0 || flat.length / 3 > Selection.LIMIT)
            throw new IllegalArgumentException("Invalid stored BuildObject blocks");
        var out = new ArrayList<Point>(flat.length / 3);
        for (int i = 0; i < flat.length; i += 3) out.add(new Point(flat[i], flat[i + 1], flat[i + 2]));
        return out;
    }

    /**
     * An edit's block changes as format 2 keeps them: the coordinates and the before and after palette indexes as five int arrays,
     * and each distinct snapshot once in the palette. A packed edit hands over its own coordinate arrays, so writing it makes
     * little more than the two index arrays, where format 1 made several compounds for every block.
     */
    static CompoundTag blocks(List<Session.Change> changes) {
        int[] x, y, z, before, after;
        BlockSnapshot[] palette;
        if (changes instanceof PackedChanges packed) {
            x = packed.xs();
            y = packed.ys();
            z = packed.zs();
            before = packed.indexes(false);
            after = packed.indexes(true);
            palette = packed.palette();
        } else {
            int n = changes.size();
            x = new int[n];
            y = new int[n];
            z = new int[n];
            before = new int[n];
            after = new int[n];
            var index = new HashMap<BlockSnapshot, Integer>();
            var entries = new ArrayList<BlockSnapshot>();
            for (int i = 0; i < n; i++) {
                var c = changes.get(i);
                x[i] = c.pos().x();
                y[i] = c.pos().y();
                z[i] = c.pos().z();
                before[i] = index.computeIfAbsent(c.before(), b -> {
                    entries.add(b);
                    return entries.size() - 1;
                });
                after[i] = index.computeIfAbsent(c.after(), a -> {
                    entries.add(a);
                    return entries.size() - 1;
                });
            }
            palette = entries.toArray(new BlockSnapshot[0]);
        }
        var tag = new CompoundTag();
        tag.putIntArray("x", x);
        tag.putIntArray("y", y);
        tag.putIntArray("z", z);
        tag.putIntArray("before", before);
        tag.putIntArray("after", after);
        var list = new ListTag();
        for (var snapshot : palette) list.add(snapshot(snapshot));
        tag.put("palette", list);
        return tag;
    }

    /** The block changes {@link #blocks} wrote, packed again when the palette fits, without making a change object for each block. */
    static List<Session.Change> readBlocks(
            CompoundTag tag, net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blocks) {
        int[] x = tag.getIntArray("x").orElse(new int[0]),
                y = tag.getIntArray("y").orElse(new int[0]),
                z = tag.getIntArray("z").orElse(new int[0]),
                before = tag.getIntArray("before").orElse(new int[0]),
                after = tag.getIntArray("after").orElse(new int[0]);
        int n = x.length;
        if (n > Selection.LIMIT) throw new IllegalArgumentException("Stored edit exceeds cell limit");
        if (y.length != n || z.length != n || before.length != n || after.length != n)
            throw new IllegalArgumentException("Invalid stored blocks");
        var list = tag.getListOrEmpty("palette");
        if (list.size() > 2L * n) throw new IllegalArgumentException("Invalid stored block palette");
        var palette = new BlockSnapshot[list.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = snapshot(list.getCompoundOrEmpty(i), blocks);
        for (int i = 0; i < n; i++)
            if (before[i] < 0 || before[i] >= palette.length || after[i] < 0 || after[i] >= palette.length)
                throw new IllegalArgumentException("Invalid stored block palette index");
        if (n == 0) return List.of();
        if (palette.length <= 0xFFFF) {
            var b = new char[n];
            var a = new char[n];
            for (int i = 0; i < n; i++) {
                b[i] = (char) before[i];
                a[i] = (char) after[i];
            }
            return PackedChanges.of(x, y, z, b, a, palette);
        }
        var changes = new ArrayList<Session.Change>(n);
        for (int i = 0; i < n; i++)
            changes.add(new Session.Change(new Point(x[i], y[i], z[i]), palette[before[i]], palette[after[i]]));
        return List.copyOf(changes);
    }

    private static List<Session.Edit> readEdits(ListTag list, ServerPlayer player, int format) {
        if (list.size() > 32) throw new IllegalArgumentException("Invalid history length");
        var result = new ArrayList<Session.Edit>();
        for (int i = 0; i < list.size(); i++) {
            var tag = list.getCompoundOrEmpty(i);
            List<Session.Change> blocks;
            ListTag entries;
            if (format == 1) {
                var legacy = new ArrayList<Session.Change>();
                entries = tag.getListOrEmpty("blocks");
                if (entries.size() > Selection.LIMIT)
                    throw new IllegalArgumentException("Stored edit exceeds cell limit");
                for (int j = 0; j < entries.size(); j++) {
                    var item = entries.getCompoundOrEmpty(j);
                    legacy.add(new Session.Change(
                            point(item, "pos"),
                            snapshot(item.getCompoundOrEmpty("before"), player),
                            snapshot(item.getCompoundOrEmpty("after"), player)));
                }
                blocks = List.copyOf(legacy);
            } else
                blocks = readBlocks(
                        tag.getCompoundOrEmpty("blocks"),
                        player.registryAccess().lookupOrThrow(Registries.BLOCK));
            var biomes = new ArrayList<Session.BiomeChange>();
            entries = tag.getListOrEmpty("biomes");
            if (entries.size() > Selection.LIMIT) throw new IllegalArgumentException("Stored biome edit exceeds limit");
            var registry = player.registryAccess().lookupOrThrow(Registries.BIOME);
            for (int j = 0; j < entries.size(); j++) {
                var item = entries.getCompoundOrEmpty(j);
                biomes.add(new Session.BiomeChange(
                        point(item, "pos"),
                        registry.get(ResourceKey.create(
                                        Registries.BIOME,
                                        dev.craftium.compat.Compat.parse(item.getStringOr("before", ""))))
                                .orElseThrow(),
                        registry.get(ResourceKey.create(
                                        Registries.BIOME,
                                        dev.craftium.compat.Compat.parse(item.getStringOr("after", ""))))
                                .orElseThrow()));
            }
            var entities = new ArrayList<Session.EntityChange>();
            entries = tag.getListOrEmpty("entities");
            if (entries.size() > 128) throw new IllegalArgumentException("Stored entity edit exceeds limit");
            for (int j = 0; j < entries.size(); j++) {
                var item = entries.getCompoundOrEmpty(j);
                entities.add(new Session.EntityChange(
                        UUID.fromString(item.getStringOr("uuid", "")),
                        item.getCompound("before").orElse(null),
                        item.getCompound("after").orElse(null),
                        item.getBooleanOr("moving", false)));
            }
            var notes = Packets.JSON.fromJson(tag.getStringOr("annotations", "[]"), Annotation.Change[].class);
            if (notes == null || notes.length > 128) throw new IllegalArgumentException("Invalid stored annotations");
            result.add(new Session.Edit(
                    tag.getStringOr("name", "Edit"),
                    blocks,
                    List.copyOf(biomes),
                    tag.getIntOr("flags", 2),
                    List.copyOf(entities),
                    List.of(notes),
                    readHeld(tag.getListOrEmpty("held"), readObjects(tag.getListOrEmpty("objects"))),
                    tag.getLongOr("time", 0L)));
        }
        return List.copyOf(result);
    }
}
