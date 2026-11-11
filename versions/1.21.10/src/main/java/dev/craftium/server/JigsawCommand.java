package dev.craftium.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /jigsawplan}, the same things the Studio panel's Jigsaw tab does (see {@link JigsawRuns}): {@code preview <pool> [seed] [depth] [distance]},
 * {@code reroll}, {@code back}, {@code next}, {@code depth <n>}, {@code view layout|blocks|both}, {@code live on|off}, {@code pin}, {@code pins},
 * {@code apply} and {@code cancel}. It is its own command because the client owns the {@code /craftium} one and rejects what it does not know.
 */
final class JigsawCommand {
    private JigsawCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        JigsawRuns.register();
        var pool = Commands.argument("pool", ResourceLocationArgument.id()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(JigsawPlanner.poolNames(context.getSource().getServer()), builder));
        var distance = Commands.argument("distance", IntegerArgumentType.integer(1, 128))
                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "depth"), IntegerArgumentType.getInteger(c, "distance"), LongArgumentType.getLong(c, "seed")));
        var depth = Commands.argument("depth", IntegerArgumentType.integer(1, 20))
                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "depth"), JigsawRuns.DISTANCE, LongArgumentType.getLong(c, "seed")))
                .then(distance);
        var seed = Commands.argument("seed", LongArgumentType.longArg())
                .executes(c -> start(c, JigsawRuns.DEPTH, JigsawRuns.DISTANCE, LongArgumentType.getLong(c, "seed")))
                .then(depth);
        var preview = Commands.literal("preview").then(pool.executes(c -> start(c, JigsawRuns.DEPTH, JigsawRuns.DISTANCE, JigsawRuns.newSeed())).then(seed));
        var root = Commands.literal("jigsawplan").requires(dev.craftium.compat.Compat::isGamemaster)
                .then(preview)
                .then(Commands.literal("reroll").executes(c -> tell(c, () -> JigsawRuns.change(player(c), run -> run.withSeed(JigsawRuns.newSeed()), "Nothing to reroll yet: run /jigsawplan preview <pool> first"))))
                .then(Commands.literal("depth").then(Commands.argument("depth", IntegerArgumentType.integer(1, 20))
                        .executes(c -> tell(c, () -> JigsawRuns.change(player(c), run -> run.withDepth(IntegerArgumentType.getInteger(c, "depth")), "Nothing to change yet: run /jigsawplan preview <pool> first")))))
                .then(Commands.literal("back").executes(c -> tell(c, () -> JigsawRuns.walk(player(c), -1))))
                .then(Commands.literal("next").executes(c -> tell(c, () -> JigsawRuns.walk(player(c), 1))))
                .then(Commands.literal("view").then(Commands.argument("what", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("both", "layout", "blocks"), b))
                        .executes(c -> tell(c, () -> JigsawRuns.view(player(c), view(StringArgumentType.getString(c, "what")))))))
                .then(Commands.literal("live").then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                        .executes(c -> tell(c, () -> JigsawRuns.live(player(c), com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "on"))))))
                .then(Commands.literal("pin").executes(c -> tell(c, () -> JigsawRuns.pin(player(c)))))
                .then(Commands.literal("pins").executes(c -> tell(c, () -> JigsawRuns.pins(player(c)))))
                .then(Commands.literal("pools").executes(c -> tell(c, () -> JigsawRuns.pools(player(c)))))
                .then(Commands.literal("apply").executes(c -> { EditorServer.applyPreview(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("cancel").executes(c -> { EditorServer.cancelPreview(c.getSource().getPlayerOrException()); return 1; }));
        dispatcher.register(root);
    }

    private static net.minecraft.server.level.ServerPlayer player(CommandContext<CommandSourceStack> c) {
        try { return c.getSource().getPlayerOrException(); }
        catch (CommandSyntaxException e) { throw new IllegalArgumentException("Only a player can use this command"); }
    }

    private static JigsawRuns.View view(String what) {
        try { return JigsawRuns.View.valueOf(what.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose layout, blocks or both"); }
    }

    private static int start(CommandContext<CommandSourceStack> c, int depth, int distance, long seed) throws CommandSyntaxException {
        return tell(c, () -> JigsawRuns.start(player(c), ResourceLocationArgument.getId(c, "pool").toString(), "", depth, distance, seed));
    }

    /** Does something for the player and says what it says, or what stopped it. */
    private static int tell(CommandContext<CommandSourceStack> c, Supplier<String> what) throws CommandSyntaxException {
        try {
            var message = what.get();
            c.getSource().sendSuccess(() -> Component.literal(message), false);
            return 1;
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(Component.literal(String.valueOf(e.getMessage()))).create();
        }
    }
}
