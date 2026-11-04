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
        }else{
            var tag=NbtIo.readCompressed(new ByteArrayInputStream(data),NbtAccounter.create(32*1024*1024));
            blocks=BlueprintLibrary.read(tag,p);entities=AssetNbt.entities(tag,p);
        }
        p.session.clipboard.clear();p.session.clipboardOrigin=null;p.session.clipboard.putAll(blocks);
        p.session.clipboardEntities.clear();p.session.clipboardEntities.addAll(entities);p.session.clipboardDirty=true;
        return "Loaded "+name+" · "+blocks.size()+" blocks"+note+" · paste with Ctrl+V";
    }

    private record Bedrock(Map<Point,BlockSnapshot> blocks,Set<String> missing,int approximate){}

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
        var result=new LinkedHashMap<Point,BlockSnapshot>();
        for(int x=0;x<sx;x++)for(int y=0;y<sy;y++)for(int z=0;z<sz;z++){
            int at=(x*sy+y)*sz+z;int id=(Integer)primary.get(at);BlockState state=null;
            if(id>=0&&id<states.length)state=states[id];
            int under=at<secondary.size()?(Integer)secondary.get(at):-1;
            boolean flooded=under>=0&&under<water.length&&water[under];
            if(state==null){if(!flooded)continue;state=Blocks.WATER.defaultBlockState();}
            else if(flooded&&state.hasProperty(BlockStateProperties.WATERLOGGED))state=state.setValue(BlockStateProperties.WATERLOGGED,true);
            result.put(new Point(x,y,z),new BlockSnapshot(state));
            if(result.size()>Selection.LIMIT)throw new IllegalArgumentException("Structure too large to import: "+Selection.LIMIT+" blocks");
        }
        return new Bedrock(result,missing,approximate[0]);
    }
}
