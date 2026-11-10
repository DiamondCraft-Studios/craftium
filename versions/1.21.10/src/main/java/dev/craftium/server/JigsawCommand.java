package dev.craftium.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.craftium.runtime.ServerPlatform;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /craftium jigsaw preview <pool> [seed] [depth] [distance]} lays a jigsaw structure out as the player's waiting preview, at the
 * selection (its middle, on its lowest layer) or at the player's feet. {@code reroll} runs it again with a new seed, {@code apply} builds
 * the preview as one undoable edit and {@code cancel} drops it.
 */
final class JigsawCommand {
    private JigsawCommand() {}

    private record Last(String pool, int depth, int distance) {}
    private static final Map<UUID, Last> LAST = new ConcurrentHashMap<>();
    private static final int DEPTH = 5, DISTANCE = 80;

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var pool = Commands.argument("pool", ResourceLocationArgument.id()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(JigsawPlanner.poolNames(context.getSource().getServer()), builder));
        var distance = Commands.argument("distance", IntegerArgumentType.integer(1, 128))
                .executes(c -> preview(c, IntegerArgumentType.getInteger(c, "depth"), IntegerArgumentType.getInteger(c, "distance"), LongArgumentType.getLong(c, "seed")));
        var depth = Commands.argument("depth", IntegerArgumentType.integer(1, 20))
                .executes(c -> preview(c, IntegerArgumentType.getInteger(c, "depth"), DISTANCE, LongArgumentType.getLong(c, "seed")))
                .then(distance);
        var seed = Commands.argument("seed", LongArgumentType.longArg())
                .executes(c -> preview(c, DEPTH, DISTANCE, LongArgumentType.getLong(c, "seed")))
                .then(depth);
        var preview = Commands.literal("preview").then(pool.executes(c -> preview(c, DEPTH, DISTANCE, newSeed())).then(seed));
        var jigsaw = Commands.literal("jigsaw").requires(dev.craftium.compat.Compat::isGamemaster)
                .then(preview)
                .then(Commands.literal("reroll").executes(JigsawCommand::reroll))
                .then(Commands.literal("apply").executes(c -> { EditorServer.applyPreview(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("cancel").executes(c -> { EditorServer.cancelPreview(c.getSource().getPlayerOrException()); return 1; }));
        dispatcher.register(Commands.literal("craftium").then(jigsaw));
    }

    private static long newSeed() { return ThreadLocalRandom.current().nextLong(1_000_000); }

    private static CommandSyntaxException problem(String message) { return new SimpleCommandExceptionType(Component.literal(message)).create(); }

    private static int reroll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var last = LAST.get(player.getUUID());
        if (last == null) throw problem("Nothing to reroll yet: run /craftium jigsaw preview <pool> first");
        return run(c.getSource(), player, last.pool(), last.depth(), last.distance(), newSeed());
    }

    private static int preview(CommandContext<CommandSourceStack> c, int depth, int distance, long seed) throws CommandSyntaxException {
        return run(c.getSource(), c.getSource().getPlayerOrException(), ResourceLocationArgument.getId(c, "pool").toString(), depth, distance, seed);
    }

    private static int run(CommandSourceStack source, ServerPlayer player, String pool, int depth, int distance, long seed) throws CommandSyntaxException {
        var level = (ServerLevel) player.level();
        var session = EditorServer.session(player);
        try {
            long began = System.nanoTime();
            var result = JigsawPlanner.run(level, pool, "", seed, depth, distance, originOf(player, session));
            var edit = JigsawPlanner.toEdit(level, session, result, "Jigsaw " + pool);
            if (edit.changes().isEmpty()) throw new IllegalArgumentException("The structure would not change any block");
            if (edit.estimatedBytes() > dev.craftium.core.Selection.HISTORY_BYTES) throw new IllegalArgumentException("The structure is too big to keep in the undo history; lower the depth");
            EditProtection.check(player, edit);
            LAST.put(player.getUUID(), new Last(pool, depth, distance));
            long ms = (System.nanoTime() - began) / 1_000_000;
            var message = "Jigsaw preview: " + result.pieces().size() + " pieces, " + edit.changes().size() + " blocks, seed " + seed + " (" + ms + " ms)"
                    + (result.unsupported() > 0 ? "; " + result.unsupported() + " feature pieces are not shown" : "");
            EditorServer.preview(player, edit, message + " · Enter to build, Esc to cancel");
            source.sendSuccess(() -> Component.literal(message + ". /craftium jigsaw reroll for another seed, /craftium jigsaw apply to build it."), false);
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
