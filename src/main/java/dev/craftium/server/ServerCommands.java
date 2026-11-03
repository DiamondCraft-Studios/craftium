package dev.craftium.server;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.Map;
public final class ServerCommands {
    private ServerCommands(){}
    public static void register(){CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
        for(var entry:Map.of("craftiumDoBlockDrops","world_block_drops","craftiumDoBlockGravity","world_block_gravity","craftiumDoTrampleFarmland","world_trample_farmland","craftiumPlayerInvulnerability","world_player_invulnerability").entrySet())dispatcher.register(Commands.literal(entry.getKey()).requires(source->dev.craftium.compat.Compat.isGamemaster(source)).then(Commands.argument("value",BoolArgumentType.bool()).executes(context->{String result=WorldRules.set(context.getSource().getLevel(),entry.getValue(),BoolArgumentType.getBool(context,"value"));context.getSource().sendSuccess(()->Component.literal(result),true);return 1;})));
    });}
}
