package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import dev.craftium.runtime.ServerPlatform;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.io.*;
import java.util.*;

/** The export pop-up's three requests: a preview, the file itself, and importing a structure file into the clipboard. */
final class StructureLibrary {
    private StructureLibrary(){}
    /** What one transfer to the client can hold: the packet allows 1.4 million characters of Base64. */
    private static final int TRANSFER_LIMIT=1_000_000;

    /** Returns the message for the player, or empty when the request was a preview and has nothing to say. */
    static String act(String action,EditPlanner p,ServerPlayer player)throws IOException{
        return switch(action){
            case "structure_preview"->{
                var built=StructureBuilder.build(p,player);String data=built.preview().encode();
                if(data.length()>1_300_000)throw new IllegalArgumentException("The structure is too detailed to preview");
                ServerPlatform.send(player,new Packets.Download("preview","preview",data,""));yield "";
            }
            case "structure_export_file"->{
                var built=StructureBuilder.build(p,player);var s=built.settings;byte[] bytes;
                if(s.bedrock())bytes=built.bedrockBytes();
                else{var out=new ByteArrayOutputStream();NbtIo.writeCompressed(s.schematic()?built.schematicNbt():built.javaNbt(),out);bytes=out.toByteArray();}
                if(bytes.length>TRANSFER_LIMIT)throw new IllegalArgumentException("The file is "+bytes.length/1024+" KiB, over the 1 MiB limit for sending it to you. Use a smaller selection or size.");
                ServerPlatform.send(player,new Packets.Download("structure",s.fileName(),Base64.getEncoder().encodeToString(bytes),""));
                yield "Exported "+s.fileName()+" · "+built.blocks()+" blocks"+(built.notes.isEmpty()?"":" · "+String.join("; ",built.notes));
            }
            case "structure_import_file"->importFile(p);
            default->throw new IllegalArgumentException("Unknown structure action");
        };
    }

    private static String importFile(EditPlanner p)throws IOException{
        String name=p.o.name;byte[] data=Base64.getDecoder().decode(p.o.assetData);
        if(data.length>1_048_576)throw new IllegalArgumentException("Structure import limit: 1 MiB");
        Map<Point,BlockSnapshot> blocks;List<CompoundTag> entities=List.of();String note="";
        if(name.toLowerCase(Locale.ROOT).endsWith(".mcstructure")){
            var result=readBedrock(data,p);blocks=result.blocks;
            if(!result.missing.isEmpty())note=" · "+result.missing.size()+" unknown blocks left out ("+String.join(", ",result.missing.stream().limit(3).toList())+(result.missing.size()>3?", …":"")+")";
            if(result.approximate>0)note+=" · "+result.approximate+" blocks used their usual state";
            if(result.jigsaws>0)note+=" · "+result.jigsaws+" jigsaw blocks kept their connections";
        }else{
            var tag=NbtIo.readCompressed(new ByteArrayInputStream(data),NbtAccounter.create(32*1024*1024));
            blocks=BlueprintLibrary.read(tag,p);entities=AssetNbt.entities(tag,p);
        }
        blocks=normalized(blocks);
        p.session.clipboard.clear();p.session.clipboardOrigin=null;p.session.clipboard.putAll(blocks);
        p.session.clipboardEntities.clear();p.session.clipboardEntities.addAll(entities);p.session.clipboardDirty=true;
        return "Loaded "+name+" · "+blocks.size()+" blocks"+note+" · paste with Ctrl+V";
    }

    private record Bedrock(Map<Point,BlockSnapshot> blocks,Set<String> missing,int approximate,int jigsaws){}

    /** A Bedrock jigsaw's data as the block entity Java keeps in a template; the game sets the position when it is pasted. */
    private static CompoundTag javaJigsaw(Map<String,Object> data){
        var fields=JigsawEntity.fromBedrock(data,BedrockBlockTable.get(),null);var tag=new CompoundTag();
        tag.putString("id","minecraft:jigsaw");tag.putString("name",fields.name());tag.putString("target",fields.target());tag.putString("pool",fields.pool());
        tag.putString("joint",fields.joint());tag.putString("final_state",fields.finalState());
        tag.putInt("selection_priority",fields.selectionPriority());tag.putInt("placement_priority",fields.placementPriority());
        return tag;
    }

    /**
     * Keeps the blocks and moves them so the lowest corner is 0,0,0. The editor outlines and transforms the clipboard by its
     * blocks, and measures from that corner, so a file whose first layers are air must not leave the clipboard offset from it.
     */
    private static Map<Point,BlockSnapshot> normalized(Map<Point,BlockSnapshot> blocks){
        int x=Integer.MAX_VALUE,y=Integer.MAX_VALUE,z=Integer.MAX_VALUE;
        for(var entry:blocks.entrySet())if(!entry.getValue().state().isAir()){var at=entry.getKey();x=Math.min(x,at.x());y=Math.min(y,at.y());z=Math.min(z,at.z());}
        var result=new LinkedHashMap<Point,BlockSnapshot>();if(x==Integer.MAX_VALUE)return result;
        for(var entry:blocks.entrySet())if(!entry.getValue().state().isAir())result.put(entry.getKey().add(-x,-y,-z),entry.getValue());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Bedrock readBedrock(byte[] data,EditPlanner p){
        var root=BedrockNbt.read(data);
        var size=(List<Object>)root.get("size");var structure=(Map<String,Object>)root.get("structure");
        if(size==null||size.size()!=3||structure==null)throw new IllegalArgumentException("Not a Bedrock structure file");
        int sx=(Integer)size.get(0),sy=(Integer)size.get(1),sz=(Integer)size.get(2);
        if(sx<1||sy<1||sz<1||(long)sx*sy*sz>StructurePreview.MAX_VOLUME)throw new IllegalArgumentException("Invalid structure size");
        var layers=(List<Object>)structure.get("block_indices");if(layers==null||layers.isEmpty())throw new IllegalArgumentException("The structure has no blocks");
        var primary=(List<Object>)layers.get(0);var secondary=layers.size()>1?(List<Object>)layers.get(1):List.<Object>of();
        var defaults=(Map<String,Object>)((Map<String,Object>)structure.getOrDefault("palette",Map.of())).get("default");
        var palette=defaults==null?List.<Object>of():(List<Object>)defaults.getOrDefault("block_palette",List.of());
        int volume=sx*sy*sz;if(primary.size()!=volume)throw new IllegalArgumentException("The block list does not match the size");
        var table=BedrockBlockTable.get();var states=new BlockState[palette.size()];var water=new boolean[palette.size()];var missing=new TreeSet<String>();int[] approximate={0};
        for(int i=0;i<palette.size();i++){
            var entry=(Map<String,Object>)palette.get(i);String name=String.valueOf(entry.getOrDefault("name","minecraft:air"));
            var blockStates=(Map<String,Object>)entry.getOrDefault("states",Map.of());
            water[i]=name.equals("minecraft:water")||name.equals("minecraft:flowing_water");
            var exact=new boolean[1];var java=table.toJava(name,blockStates,exact);
            states[i]=java==null?null:BedrockBlocks.toJava(java,p);
            if(states[i]==null)missing.add(name);else if(!exact[0])approximate[0]++;
        }
        var positions=defaults==null?Map.<String,Object>of():(Map<String,Object>)defaults.getOrDefault("block_position_data",Map.of());
        var result=new LinkedHashMap<Point,BlockSnapshot>();int jigsaws=0;
        for(int x=0;x<sx;x++)for(int y=0;y<sy;y++)for(int z=0;z<sz;z++){
            int at=(x*sy+y)*sz+z;int id=(Integer)primary.get(at);BlockState state=null;
            if(id>=0&&id<states.length)state=states[id];
            int under=at<secondary.size()?(Integer)secondary.get(at):-1;
            boolean flooded=under>=0&&under<water.length&&water[under];
            if(state==null){if(!flooded)continue;state=Blocks.WATER.defaultBlockState();}
            else if(flooded&&state.hasProperty(BlockStateProperties.WATERLOGGED))state=state.setValue(BlockStateProperties.WATERLOGGED,true);
            var snapshot=new BlockSnapshot(state);
            if(state.getBlock()==Blocks.JIGSAW&&positions.get(String.valueOf(at)) instanceof Map<?,?> position&&position.get("block_entity_data") instanceof Map<?,?> entity
                    &&JigsawEntity.isBedrockJigsaw((Map<String,Object>)entity)){snapshot=new BlockSnapshot(state,javaJigsaw((Map<String,Object>)entity));jigsaws++;}
            result.put(new Point(x,y,z),snapshot);
            if(result.size()>Selection.LIMIT)throw new IllegalArgumentException("Structure too large to import: "+Selection.LIMIT+" blocks");
        }
        return new Bedrock(result,missing,approximate[0],jigsaws);
    }
}
