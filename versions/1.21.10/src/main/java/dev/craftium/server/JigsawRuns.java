package dev.craftium.server;

import dev.craftium.runtime.ServerPlatform;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/**
 * What a player's jigsaw planning remembers and does, for the commands, the Studio panel and the file watcher alike: the last 20 runs, the pinned seeds,
 * what is drawn, and whether the preview runs again when the pool or template files change. A run is kept as the things it was made from, never as blocks.
 */
final class JigsawRuns {
    private JigsawRuns() {}

    /** What a run was made from: enough to make it again. */
    record Run(String pool, String start, int depth, int distance, long seed, BlockPos origin) {
        Run withSeed(long seed) { return new Run(pool, start, depth, distance, seed, origin); }
        Run withDepth(int depth) { return new Run(pool, start, depth, distance, seed, origin); }
    }
    enum View { BOTH, LAYOUT, BLOCKS }

    static final int DEPTH = 5, DISTANCE = 80, HISTORY = 20;
    private static final int POLL_TICKS = 10;

    static final class Memory {
        final List<Run> history = new ArrayList<>();
        int cursor = -1;
        final List<Long> pins = new ArrayList<>();
        View view = View.BOTH;
        boolean live = true;
        /** What the watched files looked like when the run on show was made, and a change seen once and waiting to be seen again before it is trusted. */
        long files, candidate;
        Run current() { return cursor < 0 ? null : history.get(cursor); }
    }
    private static final Map<UUID, Memory> MEMORY = new ConcurrentHashMap<>();
    static Memory memory(ServerPlayer player) { return MEMORY.computeIfAbsent(player.getUUID(), id -> new Memory()); }

    static long newSeed() { return ThreadLocalRandom.current().nextLong(1_000_000); }

    /** The middle of the selection on its lowest layer, or the block the player stands in when nothing is selected. */
    static BlockPos originOf(ServerPlayer player) {
        var session = EditorServer.session(player);
        if (session.selection.size() == 0) return player.blockPosition();
        var min = session.selection.min();
        var max = session.selection.max();
        return new BlockPos((min.x() + max.x()) / 2, min.y(), (min.z() + max.z()) / 2);
    }

    // ---- what a player can ask for; each returns what to tell them, and throws IllegalArgumentException with the reason when it cannot be done

    /** A new run at the selection or at the player's feet, which starts a new line of history. */
    static String start(ServerPlayer player, String pool, String start, int depth, int distance, long seed) {
        return record(player, new Run(pool, start, depth, distance, seed, originOf(player)));
    }

    /** The same run with one thing changed, in the same place. */
    static String change(ServerPlayer player, UnaryOperator<Run> vary, String nothing) {
        var current = memory(player).current();
        if (current == null) throw new IllegalArgumentException(nothing);
        return record(player, vary.apply(current));
    }

    static String walk(ServerPlayer player, int step) {
        var memory = memory(player);
        if (memory.cursor < 0) throw new IllegalArgumentException("No runs yet: generate one first");
        int to = memory.cursor + step;
        if (to < 0 || to >= memory.history.size()) throw new IllegalArgumentException(step < 0 ? "This is the first run kept" : "This is the latest run");
        memory.cursor = to;
        return show(player, memory, "run " + (to + 1) + " of " + memory.history.size());
    }

    static String view(ServerPlayer player, View view) {
        var memory = memory(player);
        memory.view = view;
        if (memory.current() == null) return "Showing " + view.name().toLowerCase(Locale.ROOT) + " from the next run on";
        return show(player, memory, "showing " + view.name().toLowerCase(Locale.ROOT));
    }

    static String pin(ServerPlayer player) {
        var memory = memory(player);
        var run = memory.current();
        if (run == null) throw new IllegalArgumentException("Nothing to pin yet: generate one first");
        if (!memory.pins.contains(run.seed())) memory.pins.add(run.seed());
        return "Pinned seed " + run.seed() + ". " + pins(player);
    }

    static String pins(ServerPlayer player) {
        var pins = memory(player).pins;
        return pins.isEmpty() ? "No pinned seeds. Pin keeps the current one."
                : "Pinned seeds: " + String.join(", ", pins.stream().map(String::valueOf).toList());
    }

    static String live(ServerPlayer player, boolean on) {
        memory(player).live = on;
        return on ? "Live: the preview runs again when the pool or template files change" : "Live is off";
    }

    /** Every pool the planner can start from, in the player's report. */
    static String pools(ServerPlayer player) {
        var names = JigsawPlanner.poolNames(player.level().getServer());
        EditorServer.session(player).jigsawReport(names.size() + " pools:\n" + String.join("\n", names.stream().limit(60).toList()) + (names.size() > 60 ? "\n...and " + (names.size() - 60) + " more" : ""));
        return names.size() + " pools found (listed in the report)";
    }

    // ---- running

    /** Adds a run to the history after the one on show, dropping any runs that were stepped back over, and shows it; a run that fails is not kept. */
    private static String record(ServerPlayer player, Run run) {
        var memory = memory(player);
        while (memory.history.size() > memory.cursor + 1) memory.history.remove(memory.history.size() - 1);
        memory.history.add(run);
        if (memory.history.size() > HISTORY) memory.history.remove(0);
        memory.cursor = memory.history.size() - 1;
        try { return show(player, memory, null); }
        catch (RuntimeException e) {
            memory.history.remove(memory.cursor);
            memory.cursor = memory.history.size() - 1;
            throw e;
        }
    }

    /** A refusal whose report has already been written, so it is not replaced by the reason alone. */
    private static final class Reported extends IllegalArgumentException { Reported(String message) { super(message); } }

    /** Runs the run on show and holds it as the player's waiting preview, with its report. */
    static String show(ServerPlayer player, Memory memory, String note) {
        var run = memory.current();
        var level = (ServerLevel) player.level();
        var session = EditorServer.session(player);
        try {
            long began = System.nanoTime();
            var result = JigsawPlanner.run(level, run.pool(), run.start(), run.seed(), run.depth(), run.distance(), run.origin());
            var edit = JigsawPlanner.toEdit(level, session, result, "Jigsaw " + run.pool());
            session.jigsawReport(String.join("\n", result.report()));
            memory.files = memory.candidate = fingerprint(level.getServer());
            if (edit.changes().isEmpty()) { // the pool check is what explains it, so it stays in the report beside the reason
                var why = "The structure would not change any block";
                session.jigsawReport(why + "\n" + String.join("\n", result.report()));
                throw new Reported(why);
            }
            if (edit.estimatedBytes() > dev.craftium.core.Selection.HISTORY_BYTES) throw new IllegalArgumentException("The structure is too big to keep in the undo history; lower the depth");
            EditProtection.check(player, edit);
            long ms = (System.nanoTime() - began) / 1_000_000;
            var layout = memory.view == View.BLOCKS ? new GhostCodec.Layout(new int[0], List.of()) : JigsawPlanner.layout(result);
            var message = "Jigsaw: " + result.pieces().size() + " pieces, " + edit.changes().size() + " blocks, seed " + run.seed() + ", depth " + run.depth()
                    + " (" + ms + " ms)" + (note == null ? "" : ", " + note) + (result.unsupported() > 0 ? "; " + result.unsupported() + " feature pieces are not shown" : "");
            EditorServer.preview(player, edit, message + " · Enter to build", layout, memory.view != View.LAYOUT);
            return message;
        } catch (IOException e) {
            ServerPlatform.LOG.error("The jigsaw planner failed", e);
            throw new IllegalArgumentException("The jigsaw planner could not read its files: " + e.getMessage());
        } catch (Reported e) {
            throw e;
        } catch (IllegalArgumentException e) {
            session.jigsawReport(e.getMessage() == null ? "" : e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            ServerPlatform.LOG.error("The jigsaw planner failed", e);
            session.jigsawReport("The jigsaw planner failed: " + e);
            throw new IllegalArgumentException("The jigsaw planner failed (" + e + "); see the server log");
        }
    }

    // ---- the watcher

    /** A number that changes when a file the planner reads is added, removed or saved: the craftium datapack and the structures the game saved. */
    static long fingerprint(MinecraftServer server) {
        long sum = 0;
        for (var root : new Path[]{server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(JigsawPlanner.PACK), server.getWorldPath(LevelResource.GENERATED_DIR)}) {
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root, 12)) {
                for (var file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    var name = file.getFileName().toString();
                    if (!(name.endsWith(".json") || name.endsWith(".nbt") || name.endsWith(".mcmeta"))) continue;
                    sum += file.toString().hashCode() * 31L + Files.getLastModifiedTime(file).toMillis() + Files.size(file) * 7919L;
                }
            } catch (IOException | RuntimeException e) { /* a file that is being written is looked at again at the next poll */ }
        }
        return sum;
    }

    /**
     * Every half second: players whose files changed get their run again with the same seed. A change has to be seen at two polls in a row, so a
     * template that is still being written is not read half done, and nothing happens while the player has another edit waiting.
     */
    static void tick(MinecraftServer server) {
        if (server.getTickCount() % POLL_TICKS != 0) return;
        Long now = null;
        for (var player : server.getPlayerList().getPlayers()) {
            var memory = MEMORY.get(player.getUUID());
            if (memory == null || !memory.live || memory.current() == null) continue;
            if (now == null) now = fingerprint(server);
            if (now == memory.files) { memory.candidate = now; continue; }
            if (now != memory.candidate) { memory.candidate = now; continue; }
            var session = EditorServer.session(player);
            if (session.pendingEdit != null && session.pendingEdit != session.layoutFor) { memory.files = now; continue; }
            memory.files = now;
            try { EditorServer.send(player, session, show(player, memory, "files changed"), false); }
            catch (IllegalArgumentException e) { EditorServer.send(player, session, "Jigsaw: " + e.getMessage(), true); }
        }
    }

    private static boolean registered;

    /** Starts the watcher and the Studio panel's actions, once for the whole game however often the commands are registered. */
    static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(JigsawRuns::tick);
        action("generate", use -> { var o = use.options(); say(use, start(use.player(), o.jigsawPool, o.jigsawStart, o.jigsawDepth, o.jigsawDistance, o.jigsawSeed)); });
        action("reroll", use -> say(use, change(use.player(), run -> run.withSeed(newSeed()), "Nothing to reroll yet: generate one first")));
        action("back", use -> say(use, walk(use.player(), -1)));
        action("next", use -> say(use, walk(use.player(), 1)));
        action("apply", use -> EditorServer.applyPreview(use.player()));
        action("cancel", use -> EditorServer.cancelPreview(use.player()));
        action("pin", use -> say(use, pin(use.player())));
        action("view", use -> { var memory = memory(use.player()); say(use, view(use.player(), View.values()[(memory.view.ordinal() + 1) % View.values().length])); });
        action("live", use -> say(use, live(use.player(), use.options().jigsawLive)));
        action("pools", use -> say(use, pools(use.player())));
        action("edit", use -> say(use, JigsawEditor.act(use.player(), use.options().jigsawEdit)));
    }

    private static void action(String name, ActionHandler handler) { ActionRegistry.register("craftium:jigsaw_" + name, handler); }
    private static void say(ActionUse use, String message) { use.say(message); }
}
