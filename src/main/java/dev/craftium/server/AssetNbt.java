package dev.craftium.server;

import dev.craftium.core.Point;
import net.minecraft.nbt.*;
import net.minecraft.core.registries.*;
import net.minecraft.util.datafix.fixes.References;
import java.util.*;

/** Schema adaptation is kept separate from clipboard geometry and server application. */
final class AssetNbt {
    private AssetNbt(){}
    static int current(){return net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();}
    static CompoundTag upgrade(CompoundTag tag,com.mojang.datafixers.DSL.TypeReference type,int version,EditPlanner p){if(version>current())throw new IllegalArgumentException("This asset is from a newer Minecraft version");if(version==current())return tag;var value=p.level.getServer().getFixerUpper().update(type,new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE,tag),version,current()).getValue();if(value instanceof CompoundTag result)return result;throw new IllegalArgumentException("Asset data upgrade failed");}
    static CompoundTag structure(CompoundTag root,EditPlanner p){if(root.contains("palette")&&root.getIntOr("DataVersion",current())<current()){var upgraded=net.minecraft.util.datafix.DataFixTypes.STRUCTURE.updateToCurrentVersion(p.level.getServer().getFixerUpper(),root,root.getIntOr("DataVersion",current()));upgraded.putInt("DataVersion",current());return upgraded;}return root;}
    static net.minecraft.world.level.block.state.BlockState state(CompoundTag tag,EditPlanner p){String name=tag.getStringOr("Name","");if(!BuiltInRegistries.BLOCK.containsKey(dev.craftium.compat.Compat.parse(name)))throw new IllegalArgumentException("Asset requires missing block "+name);return NbtUtils.readBlockState(p.level.registryAccess().lookupOrThrow(Registries.BLOCK),tag);}
    static net.minecraft.world.level.block.state.BlockState state(String text,int version,EditPlanner p){if(version>=current())return p.parse(text);var tag=new CompoundTag();int start=text.indexOf('[');tag.putString("Name",start<0?text:text.substring(0,start));if(start>=0){var properties=new CompoundTag();for(String pair:text.substring(start+1,text.length()-1).split(",")){String[] kv=pair.split("=",2);if(kv.length!=2)throw new IllegalArgumentException("Invalid asset block state");properties.putString(kv[0],kv[1]);}tag.put("Properties",properties);}return state(upgrade(tag,References.BLOCK_STATE,version,p),p);}
    // --- Blocks no installed mod provides: kept as placeholders going in, and written as the block they stand for going out ---

    /** The block of a file entry, or a placeholder for it when no mod provides it; block entity data it had rides along inside the placeholder. */
    static BlockSnapshot snapshot(CompoundTag tag,CompoundTag data,EditPlanner p){
        String name=tag.getStringOr("Name","");
        if(MissingBlocks.exists(name))return new BlockSnapshot(state(tag,p),data);
        var properties=new TreeMap<String,String>();var source=tag.getCompoundOrEmpty("Properties");
        for(String key:source.keySet())properties.put(key,source.getStringOr(key,""));
        return missing(new dev.craftium.core.MissingBlockTable.Entry(name,properties),data);
    }
    /** Same for a block written as text, like {@code mod:stone[axis=y]}. */
    static BlockSnapshot snapshot(String text,int version,EditPlanner p){
        int start=text.indexOf('[');
        if(MissingBlocks.exists(start<0?text:text.substring(0,start)))return new BlockSnapshot(state(text,version,p));
        return missing(dev.craftium.core.MissingBlockTable.Entry.parse(text),null);
    }
    static BlockSnapshot missing(dev.craftium.core.MissingBlockTable.Entry entry,CompoundTag kept){
        var table=MissingBlocks.table();
        if(table==null)throw new IllegalArgumentException("Asset requires missing block "+entry.name());
        var data=new CompoundTag();data.putString("id",MissingBlockRewrite.BLOCK);data.putInt(MissingBlockRewrite.KEY,table.intern(entry));
        if(kept!=null)data.put(MissingBlockRewrite.KEPT,kept);
        return new BlockSnapshot(MissingBlocks.BLOCK.defaultBlockState(),data);
    }
    /** The block with a block entity from a schematic: inside the placeholder when the block is one. */
    static BlockSnapshot withEntity(BlockSnapshot block,CompoundTag entity){
        if(!MissingBlocks.isMissing(block.state()))return new BlockSnapshot(block.state(),entity);
        var data=block.data().copy();data.put(MissingBlockRewrite.KEPT,entity);return new BlockSnapshot(block.state(),data);
    }
    /** The state a placeholder stands for, or null for any other block (and for a placeholder whose number the table does not know). */
    static dev.craftium.core.MissingBlockTable.Entry standsFor(BlockSnapshot s){
        if(s.data()==null||!MissingBlocks.isMissing(s.state()))return null;
        var table=MissingBlocks.table();return table==null?null:table.get(s.data().getIntOr(MissingBlockRewrite.KEY,-1));
    }
    /** What identifies the block in a file palette: equal for equal blocks, so one palette entry serves them all. */
    static Object paletteKey(BlockSnapshot s){var missing=standsFor(s);return missing==null?s.state():missing;}
    static CompoundTag paletteTag(BlockSnapshot s){
        var missing=standsFor(s);if(missing==null)return NbtUtils.writeBlockState(s.state());
        var tag=new CompoundTag();tag.putString("Name",missing.name());
        if(!missing.properties().isEmpty()){var properties=new CompoundTag();missing.properties().forEach(properties::putString);tag.put("Properties",properties);}
        return tag;
    }
    static String paletteText(BlockSnapshot s){var missing=standsFor(s);return missing==null?net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s.state()):missing.text();}
    /** The block entity data a file holds for the block: what the real block had, for a placeholder. */
    static CompoundTag fileData(BlockSnapshot s){
        if(standsFor(s)==null)return s.data();
        return s.data().getCompound(MissingBlockRewrite.KEPT).orElse(null);
    }
    static CompoundTag blockEntity(CompoundTag source,int version,EditPlanner p){var tag=source.getCompound("Data").orElse(source).copy();String id=source.getStringOr("Id",tag.getStringOr("id",""));if(id.isBlank())throw new IllegalArgumentException("Schematic block entity is missing its type");tag.putString("id",id);tag.remove("Id");tag.remove("Pos");return upgrade(tag,References.BLOCK_ENTITY,version,p);}
    static List<CompoundTag> entities(CompoundTag raw,EditPlanner p){
        CompoundTag root=raw.contains("Schematic")?raw.getCompoundOrEmpty("Schematic"):raw;boolean structure=root.contains("palette");if(structure)root=structure(root,p);int version=root.getIntOr("DataVersion",current());
        var entries=root.getListOrEmpty(structure?"entities":"Entities");if(entries.size()>128)throw new IllegalArgumentException("Asset entity limit: 128 roots");var result=new ArrayList<CompoundTag>();int[] count={0};
        for(int i=0;i<entries.size();i++){var entry=entries.getCompoundOrEmpty(i);CompoundTag tag;
            if(structure){tag=entry.getCompoundOrEmpty("nbt").copy();tag.put("Pos",entry.getListOrEmpty("pos").copy());}
            else {tag=entry.getCompound("Data").orElse(entry).copy();String id=entry.getStringOr("Id",tag.getStringOr("id",""));tag.putString("id",id);tag.remove("Id");tag.put("Pos",entry.getListOrEmpty("Pos").copy());}
            int from=raw.get("Blocks") instanceof ByteArrayTag?raw.getIntOr("DataVersion",tag.getStringOr("id","").contains(":")?1343:99):version;tag=upgrade(tag,References.ENTITY_TREE,from,p);
            ClipboardEntities.walk(tag,item->{if(++count[0]>512)throw new IllegalArgumentException("Asset entity limit: 512 including passengers");var pos=item.getListOrEmpty("Pos");if(pos.size()!=3)throw new IllegalArgumentException("Invalid asset entity position");});result.add(tag);
        }return List.copyOf(result);
    }
    static ListTag structureEntities(Session session){return structureEntities(session.clipboardEntities);}
    static ListTag structureEntities(List<CompoundTag> entities){var result=new ListTag();for(var saved:entities){var entry=new CompoundTag();var at=saved.getListOrEmpty("Pos");entry.put("pos",at.copy());var blockPos=new ListTag();for(int i=0;i<3;i++)blockPos.add(IntTag.valueOf((int)Math.floor(at.getDoubleOr(i,0))));entry.put("blockPos",blockPos);entry.put("nbt",saved.copy());result.add(entry);}return result;}
    static ListTag schematicEntities(Session session){return schematicEntities(session.clipboardEntities);}
    static ListTag schematicEntities(List<CompoundTag> entities){var result=new ListTag();for(var saved:entities){var tag=saved.copy();tag.putString("Id",tag.getStringOr("id",""));tag.remove("id");tag.remove("UUID");result.add(tag);}return result;}
}
