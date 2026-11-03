package dev.craftium.server;

import dev.craftium.compat.Compat;
import dev.craftium.core.Options;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;

/**
 * The mask palette's toggled blocks, resolved once so that a search can ask about thousands of blocks cheaply. It reads the
 * block ids and properties straight from the settings, so the client (hover preview) and the server (the click) agree.
 */
public final class BlockMask {
    private record Entry(Block block,Map<String,String> properties){}
    private final List<Entry> entries=new ArrayList<>();
    private final boolean exclude;
    private BlockMask(Options options){
        exclude=options.maskExclude;
        for(String text:options.maskActive){
            int open=text.indexOf('[');var id=Compat.tryParse(open<0?text:text.substring(0,open));if(id==null)continue;
            var block=BuiltInRegistries.BLOCK.getOptional(id);if(block.isEmpty())continue;
            var properties=new LinkedHashMap<String,String>();int close=text.lastIndexOf(']');
            if(open>=0&&close>open)for(String pair:text.substring(open+1,close).split(",")){String[] kv=pair.split("=",2);if(kv.length==2)properties.put(kv[0].trim(),kv[1].trim());}
            entries.add(new Entry(block.get(),properties));
        }
    }
    public static BlockMask of(Options options){return new BlockMask(options);}
    /** Whether the block may be touched: with nothing toggled everything may; otherwise included blocks, or all but the excluded ones. */
    public boolean allows(BlockState state){
        if(entries.isEmpty())return true;
        for(Entry entry:entries)if(matches(entry,state))return !exclude;
        return exclude;
    }
    private static boolean matches(Entry entry,BlockState state){
        if(state.getBlock()!=entry.block())return false;
        for(var wanted:entry.properties().entrySet()){
            boolean found=false;
            for(var property:state.getProperties())if(property.getName().equals(wanted.getKey())){if(!name(state,property).equals(wanted.getValue()))return false;found=true;break;}
            if(!found)return false;
        }
        return true;
    }
    private static <T extends Comparable<T>> String name(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
}
