package dev.craftium.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.craftium.runtime.ServerPlatform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /jigsawplan}: lays a jigsaw structure out as the player's waiting preview, an outline per piece and the blocks, at the selection (its middle,
 * on its lowest layer) or at the player's feet.
 * <ul>
 * <li>{@code preview <pool> [seed] [depth] [distance]} starts a run; {@code reroll} runs it again with a new seed in the same place.</li>
 * <li>{@code back} and {@code next} walk the last runs; {@code depth <n>} runs the same seed with another depth.</li>
 * <li>{@code view layout|blocks|both} chooses what is drawn: a layout alone is cheap at any size.</li>
 * <li>{@code pin} keeps the seed, {@code pins} lists them; {@code apply} builds the preview as one undoable edit and {@code cancel} drops it.</li>
 * </ul>
 */
final class JigsawCommand {
    private JigsawCommand() {}

    /** What a run was made from: enough to make it again, so history does not keep the blocks. */
    private record Run(String pool, int depth, int distance, long seed, BlockPos origin) {}
    private enum View { BOTH, LAYOUT, BLOCKS }
    private static final int DEPTH = 5, DISTANCE = 80, HISTORY = 20;

    private static final class Memory {
        final List<Run> history = new ArrayList<>();
        int cursor = -1;
        final List<Long> pins = new ArrayList<>();
        View view = View.BOTH;
        Run current() { return cursor < 0 ? null : history.get(cursor); }
    }
    private static final Map<UUID, Memory> MEMORY = new ConcurrentHashMap<>();
    private static Memory memory(ServerPlayer player) { return MEMORY.computeIfAbsent(player.getUUID(), id -> new Memory()); }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var pool = Commands.argument("pool", ResourceLocationArgument.id()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(JigsawPlanner.poolNames(context.getSource().getServer()), builder));
        var distance = Commands.argument("distance", IntegerArgumentType.integer(1, 128))
                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "depth"), IntegerArgumentType.getInteger(c, "distance"), LongArgumentType.getLong(c, "seed")));
        var depth = Commands.argument("depth", IntegerArgumentType.integer(1, 20))
                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "depth"), DISTANCE, LongArgumentType.getLong(c, "seed")))
                .then(distance);
        var seed = Commands.argument("seed", LongArgumentType.longArg())
                .executes(c -> start(c, DEPTH, DISTANCE, LongArgumentType.getLong(c, "seed")))
                .then(depth);
        var preview = Commands.literal("preview").then(pool.executes(c -> start(c, DEPTH, DISTANCE, newSeed())).then(seed));
        var root = Commands.literal("jigsawplan").requires(dev.craftium.compat.Compat::isGamemaster)
                .then(preview)
                .then(Commands.literal("reroll").executes(c -> change(c, run -> new Run(run.pool(), run.depth(), run.distance(), newSeed(), run.origin()), "Nothing to reroll yet: run /jigsawplan preview <pool> first")))
                .then(Commands.literal("depth").then(Commands.argument("depth", IntegerArgumentType.integer(1, 20))
                        .executes(c -> change(c, run -> new Run(run.pool(), IntegerArgumentType.getInteger(c, "depth"), run.distance(), run.seed(), run.origin()), "Nothing to change yet: run /jigsawplan preview <pool> first"))))
                .then(Commands.literal("back").executes(c -> walk(c, -1)))
                .then(Commands.literal("next").executes(c -> walk(c, 1)))
                .then(Commands.literal("view").then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("both", "layout", "blocks"), b)).executes(JigsawCommand::view)))
                .then(Commands.literal("pin").executes(JigsawCommand::pin))
                .then(Commands.literal("pins").executes(c -> { say(c.getSource(), pinList(memory(c.getSource().getPlayerOrException()))); return 1; }))
                .then(Commands.literal("apply").executes(c -> { EditorServer.applyPreview(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("cancel").executes(c -> { EditorServer.cancelPreview(c.getSource().getPlayerOrException()); return 1; }));
        dispatcher.register(root);
    }

    private static long newSeed() { return ThreadLocalRandom.current().nextLong(1_000_000); }
    private static CommandSyntaxException problem(String message) { return new SimpleCommandExceptionType(Component.literal(message)).create(); }
    private static void say(CommandSourceStack source, String message) { source.sendSuccess(() -> Component.literal(message), false); }

    /** A new run at the selection, or at the player's feet, which also starts a new line of history. */
    private static int start(CommandContext<CommandSourceStack> c, int depth, int distance, long seed) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var pool = ResourceLocationArgument.getId(c, "pool").toString();
        return record(c.getSource(), player, new Run(pool, depth, distance, seed, originOf(player, EditorServer.session(player))));
    }

    /** A variation of the current run: the same place and pool, one thing changed. */
    private static int change(CommandContext<CommandSourceStack> c, java.util.function.UnaryOperator<Run> vary, String nothing) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var current = memory(player).current();
        if (current == null) throw problem(nothing);
        return record(c.getSource(), player, vary.apply(current));
    }

    private static int walk(CommandContext<CommandSourceStack> c, int step) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var memory = memory(player);
        int to = memory.cursor + step;
        if (memory.cursor < 0) throw problem("No runs yet: run /jigsawplan preview <pool> first");
        if (to < 0 || to >= memory.history.size()) throw problem(step < 0 ? "This is the first run kept" : "This is the latest run");
        memory.cursor = to;
        return show(c.getSource(), player, memory, "run " + (to + 1) + " of " + memory.history.size());
    }

    private static int view(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var memory = memory(player);
        var what = StringArgumentType.getString(c, "what").toLowerCase(java.util.Locale.ROOT);
        try { memory.view = View.valueOf(what.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw problem("Choose layout, blocks or both"); }
        if (memory.current() == null) { say(c.getSource(), "Showing " + what + " from the next run on"); return 1; }
        return show(c.getSource(), player, memory, "showing " + what);
    }

    private static int pin(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var memory = memory(player);
        var run = memory.current();
        if (run == null) throw problem("Nothing to pin yet: run /jigsawplan preview <pool> first");
        if (!memory.pins.contains(run.seed())) memory.pins.add(run.seed());
        say(c.getSource(), "Pinned seed " + run.seed() + ". " + pinList(memory));
        return 1;
    }

    private static String pinList(Memory memory) {
        return memory.pins.isEmpty() ? "No pinned seeds. /jigsawplan pin keeps the current one."
                : "Pinned seeds: " + memory.pins.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + " (use one as /jigsawplan preview <pool> <seed>)";
    }

    /** Adds a run to the history after the one being looked at, dropping any runs that were stepped back over, and shows it. */
    private static int record(CommandSourceStack source, ServerPlayer player, Run run) throws CommandSyntaxException {
        var memory = memory(player);
        while (memory.history.size() > memory.cursor + 1) memory.history.remove(memory.history.size() - 1);
        memory.history.add(run);
        if (memory.history.size() > HISTORY) memory.history.remove(0);
        memory.cursor = memory.history.size() - 1;
        try { return show(source, player, memory, null); }
        catch (CommandSyntaxException e) { // a run that failed is not kept
            memory.history.remove(memory.cursor);
            memory.cursor = memory.history.size() - 1;
            throw e;
        }
    }

    /** Runs the current run of the history and holds it as the player's waiting preview. */
    private static int show(CommandSourceStack source, ServerPlayer player, Memory memory, String note) throws CommandSyntaxException {
        var run = memory.current();
        var level = (ServerLevel) player.level();
        var session = EditorServer.session(player);
        try {
            long began = System.nanoTime();
            var result = JigsawPlanner.run(level, run.pool(), "", run.seed(), run.depth(), run.distance(), run.origin());
            var edit = JigsawPlanner.toEdit(level, session, result, "Jigsaw " + run.pool());
            if (edit.changes().isEmpty()) throw new IllegalArgumentException("The structure would not change any block");
            if (edit.estimatedBytes() > dev.craftium.core.Selection.HISTORY_BYTES) throw new IllegalArgumentException("The structure is too big to keep in the undo history; lower the depth");
            EditProtection.check(player, edit);
            long ms = (System.nanoTime() - began) / 1_000_000;
            var layout = switch (memory.view) {
                case BLOCKS -> new GhostCodec.Layout(new int[0], List.of());
                default -> JigsawPlanner.layout(result);
            };
            var message = "Jigsaw: " + result.pieces().size() + " pieces, " + edit.changes().size() + " blocks, seed " + run.seed() + ", depth " + run.depth()
                    + " (" + ms + " ms)" + (note == null ? "" : ", " + note) + (result.unsupported() > 0 ? "; " + result.unsupported() + " feature pieces are not shown" : "");
            EditorServer.preview(player, edit, message + " · Enter to build, Esc to cancel", layout, memory.view != View.LAYOUT);
            say(source, message + ". /jigsawplan reroll, back, next, depth <n>, view, pin, apply");
            return result.pieces().size();
        } catch (IllegalArgumentException e) {
            throw problem(String.valueOf(e.getMessage()));
        } catch (java.io.IOException | RuntimeException e) {
            ServerPlatform.LOG.error("The jigsaw planner failed", e);
            throw problem("The jigsaw planner failed (" + e + "); see the server log");
        }
    }

    /** The middle of the selection on its lowest layer, or the block the player stands in when nothing is selected. */
    private static BlockPos originOf(ServerPlayer player, Session session) {
        if (session.selection.size() == 0) return player.blockPosition();
        var min = session.selection.min();
        var max = session.selection.max();
        return new BlockPos((min.x() + max.x()) / 2, min.y(), (min.z() + max.z()) / 2);
    }
}
