package dev.craftium.server;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import java.util.LinkedHashMap;
import java.util.Map;
public final class ServerCommands {
    private ServerCommands(){}
    /** /craftium gamerule <rule> <true|false>: the world rules Craftium adds, saved with the world. Typed on the client, the rest of /craftium is answered there and this part is sent on to the server. */
    private static final Map<String,String> RULES=new LinkedHashMap<>();
    static{RULES.put("doBlockDrops","world_block_drops");RULES.put("doBlockGravity","world_block_gravity");RULES.put("doTrampleFarmland","world_trample_farmland");RULES.put("doDestructiveExplosions","world_destructive_explosions");RULES.put("doFluidSpread","world_fluid_spread");RULES.put("doLeafDecay","world_leaf_decay");RULES.put("doBlockSpread","world_block_spread");RULES.put("doPlantGrowth","world_plant_growth");RULES.put("doBlockWeathering","world_block_weathering");RULES.put("doSnowAndIce","world_snow_and_ice");RULES.put("doFluidInteractions","world_fluid_interactions");RULES.put("doBlockSupportBreak","world_block_support_break");RULES.put("doDripstoneAndAmethyst","world_dripstone_and_amethyst");RULES.put("playerInvulnerability","world_player_invulnerability");}
    public static void register(){CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
        var gamerule=Commands.literal("gamerule").requires(source->dev.craftium.compat.Compat.isGamemaster(source));
        for(var entry:RULES.entrySet())gamerule.then(Commands.literal(entry.getKey()).then(Commands.argument("value",BoolArgumentType.bool()).executes(context->{String result=WorldRules.set(context.getSource().getLevel(),entry.getValue(),BoolArgumentType.getBool(context,"value"));context.getSource().sendSuccess(()->dev.craftium.core.Chat.say(result),true);return 1;})));
        dispatcher.register(Commands.literal("craftium").then(gamerule).then(MissingBlockCommand.node(access)));
        JigsawCommand.register(dispatcher);
    });}
}
