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
    /** {@code block} is null for a block no installed mod provides: it is then matched by {@code name} against what a placeholder stands for. */
    private record Entry(Block block,String name,Map<String,String> properties){}
    private final List<Entry> entries=new ArrayList<>();
    private final boolean exclude;
    private BlockMask(Options options){
        exclude=options.maskExclude;
        if(!options.maskOff)for(String text:options.maskActive){
            int open=text.indexOf('[');var id=Compat.tryParse(open<0?text:text.substring(0,open));if(id==null)continue;
            var block=BuiltInRegistries.BLOCK.getOptional(id);
            var properties=new LinkedHashMap<String,String>();int close=text.lastIndexOf(']');
            if(open>=0&&close>open)for(String pair:text.substring(open+1,close).split(",")){String[] kv=pair.split("=",2);if(kv.length==2)properties.put(kv[0].trim(),kv[1].trim());}
            entries.add(new Entry(block.orElse(null),id.toString(),properties));
        }
    }
    public static BlockMask of(Options options){return new BlockMask(options);}
    /** Whether the block may be touched: with nothing toggled everything may; otherwise included blocks, or all but the excluded ones. */
    public boolean allows(BlockState state){return allows(state,null);}
    /**
     * As above, for a block that may be a placeholder: {@code missing} is what it stands for (null for any other block). A mask entry
     * naming a block no mod provides, like {@code othermod:marble}, picks the placeholders for that block and no others.
     */
    public boolean allows(BlockState state,dev.craftium.core.MissingBlockTable.Entry missing){
        if(entries.isEmpty())return true;
        for(Entry entry:entries)if(matches(entry,state,missing))return !exclude;
        return exclude;
    }
    private static boolean matches(Entry entry,BlockState state,dev.craftium.core.MissingBlockTable.Entry missing){
        if(entry.block()==null){
            if(missing==null||!missing.name().equals(entry.name()))return false;
            for(var wanted:entry.properties().entrySet())if(!wanted.getValue().equals(missing.properties().get(wanted.getKey())))return false;
            return true;
        }
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
