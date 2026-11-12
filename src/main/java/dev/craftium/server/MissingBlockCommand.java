package dev.craftium.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.craftium.block.MissingBlockEntity;
import dev.craftium.compat.Compat;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.network.chat.Component;
import java.util.stream.IntStream;

/**
 * /craftium missing: what the world's missing blocks were, and swapping one for a block that exists. A swap is an ordinary edit on the
 * player's selection, so it can be undone like any other.
 */
final class MissingBlockCommand {
    private MissingBlockCommand(){}

    static LiteralArgumentBuilder<CommandSourceStack> node(CommandBuildContext access){
        return Commands.literal("missing").requires(Compat::isGamemaster)
            .then(Commands.literal("list").executes(context->list(context.getSource())))
            .then(Commands.literal("replace")
                .then(Commands.argument("number",IntegerArgumentType.integer(0))
                    .suggests((context,builder)->{var table=MissingBlocks.table();return SharedSuggestionProvider.suggest(table==null?java.util.stream.Stream.<String>empty():IntStream.range(0,table.size()).filter(i->table.get(i)!=null).mapToObj(Integer::toString),builder);})
                    .then(Commands.argument("block",BlockStateArgument.block(access))
                        .executes(context->replace(context.getSource(),IntegerArgumentType.getInteger(context,"number"),BlockStateArgument.getBlock(context,"block").getState())))));
    }

    private static int list(CommandSourceStack source){
        var table=MissingBlocks.table();
        if(table==null||table.size()==0){source.sendSuccess(()->Component.literal("No block is missing from this world."),false);return 0;}
        var counts=selectionCounts(source);
        var out=new StringBuilder("Missing blocks (number: block"+(counts==null?"":", blocks in your selection")+")");
        var entries=table.entries();
        for(int i=0;i<entries.size();i++){
            if(entries.get(i)==null)continue;
            out.append("\n").append(i).append(": ").append(entries.get(i).text());
            if(counts!=null)out.append(", ").append(counts.getOrDefault(i,0));
        }
        source.sendSuccess(()->Component.literal(out.toString()),false);
        return entries.size();
    }

    /** How many blocks of each number are in the player's selection, or null with no selection. */
    private static java.util.Map<Integer,Integer> selectionCounts(CommandSourceStack source){
        var player=source.getPlayer();
        if(player==null)return null;
        var session=EditorServer.session(player);
        if(session==null||session.selection.size()==0)return null;
        var counts=new java.util.HashMap<Integer,Integer>();
        var level=player.level();
        for(Point at:session.selection.points()){
            if(!level.hasChunk(at.x()>>4,at.z()>>4))continue;
            if(level.getBlockEntity(EditPlanner.pos(at)) instanceof MissingBlockEntity missing)counts.merge(missing.key(),1,Integer::sum);
        }
        return counts;
    }

    private static int replace(CommandSourceStack source,int number,net.minecraft.world.level.block.state.BlockState state)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var player=source.getPlayerOrException();
        var table=MissingBlocks.table();
        if(table==null||table.get(number)==null){source.sendFailure(Component.literal("No missing block has the number "+number+". Use /craftium missing list."));return 0;}
        var session=EditorServer.session(player);
        if(session==null||session.selection.size()==0){source.sendFailure(Component.literal("Select the blocks to change first."));return 0;}
        try{
            var plan=new EditPlanner(player.level(),session,new Options());
            var replacement=new BlockSnapshot(state);
            int changed=0;
            for(Point at:session.selection.points()){
                plan.validate(at);
                if(plan.level.getBlockEntity(EditPlanner.pos(at)) instanceof MissingBlockEntity missing&&missing.key()==number){plan.put(at,replacement);changed++;}
            }
            if(changed==0){source.sendFailure(Component.literal("No block of number "+number+" is in your selection."));return 0;}
            EditorServer.start(player,session,plan.finish("replace missing blocks"));
            int total=changed;
            source.sendSuccess(()->Component.literal("Replacing "+total+" "+table.get(number).text()+" with "+net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state)),false);
            return changed;
        }catch(IllegalArgumentException e){source.sendFailure(Component.literal(e.getMessage()));return 0;}
    }
}
