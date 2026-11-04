package dev.craftium.server;

import dev.craftium.core.BedrockBlockTable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import java.util.TreeMap;

/** Minecraft block states against {@link BedrockBlockTable}, which holds the mappings and nothing of the game. */
final class BedrockBlocks {
    private BedrockBlocks(){}

    /** The Bedrock block for a state, or null when Bedrock has no equivalent. */
    static BedrockBlockTable.Bedrock toBedrock(BlockState state){
        var tag=NbtUtils.writeBlockState(state);var properties=new TreeMap<String,String>();
        var source=tag.getCompoundOrEmpty("Properties");for(String key:source.keySet())properties.put(key,source.getStringOr(key,""));
        return BedrockBlockTable.get().toBedrock(tag.getStringOr("Name",""),properties);
    }

    /** The state for a table entry, or null when this game version has no such block. */
    static BlockState toJava(BedrockBlockTable.Java java,EditPlanner p){
        var tag=new CompoundTag();tag.putString("Name",java.name());
        if(!java.properties().isEmpty()){var properties=new CompoundTag();java.properties().forEach(properties::putString);tag.put("Properties",properties);}
        try{return AssetNbt.state(tag,p);}catch(IllegalArgumentException e){return null;}
    }
}
