package dev.craftium.client.compat;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/** Builders for client commands; Fabric API renamed the holder class between releases. 26.2 implementation. */
public final class CommandCompat {
    private CommandCompat(){}

    public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name){return ClientCommands.literal(name);}
    public static <T> RequiredArgumentBuilder<FabricClientCommandSource,T> argument(String name,ArgumentType<T> type){return ClientCommands.argument(name,type);}
}
