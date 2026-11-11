package dev.craftium.server;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import java.util.LinkedHashMap;
import java.util.Map;
public final class ServerCommands {
    private ServerCommands(){}
    /** /craftium gamerule <rule> <true|false>: the world rules Craftium adds, saved with the world. Typed on the client, the rest of /craftium is answered there and this part is sent on to the server. */
    private static final Map<String,String> RULES=new LinkedHashMap<>(Map.of("doBlockDrops","world_block_drops","doBlockGravity","world_block_gravity","doTrampleFarmland","world_trample_farmland","doDestructiveExplosions","world_destructive_explosions","playerInvulnerability","world_player_invulnerability"));
    public static void register(){CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
        var gamerule=Commands.literal("gamerule").requires(source->dev.craftium.compat.Compat.isGamemaster(source));
        for(var entry:RULES.entrySet())gamerule.then(Commands.literal(entry.getKey()).then(Commands.argument("value",BoolArgumentType.bool()).executes(context->{String result=WorldRules.set(context.getSource().getLevel(),entry.getValue(),BoolArgumentType.getBool(context,"value"));context.getSource().sendSuccess(()->dev.craftium.core.Chat.say(result),true);return 1;})));
        dispatcher.register(Commands.literal("craftium").then(gamerule));
        JigsawCommand.register(dispatcher);
    });}
}
