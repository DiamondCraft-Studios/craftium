package dev.craftium.server;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/** The jigsaw planner is built for 1.21.10 first; on this version the command is not there yet. */
final class JigsawCommand {
    private JigsawCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {}
}
