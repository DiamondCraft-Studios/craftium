package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.runtime.ServerPlatform;
import net.minecraft.nbt.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.Registries;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class BlueprintLibrary {
    private BlueprintLibrary(){}
    static Path file(String folder,String name){
        if(name.length()>160||!name.matches("[A-Za-z0-9 _./-]+"))throw new IllegalArgumentException("Use a plain asset filename");
        for(String segment:name.split("/",-1))if(segment.isBlank()||segment.equals(".")||segment.equals(".."))throw new IllegalArgumentException("Invalid asset folder");
        Path root=ServerPlatform.assets().resolve(folder).toAbsolutePath().normalize(),path=root.resolve(name).normalize();
        if(!path.startsWith(root))throw new IllegalArgumentException("Asset path leaves its library");return path;
    }
    static String act(String action,EditPlanner p) {
        try{
            String name=p.o.name;
            switch(action){
                case "blueprint_list" -> {p.session.library=list();return "Blueprint library · "+p.session.library.size()+" files";}
                case "asset_upload" -> {
                    if(!name.matches("(?i).+\\.(schem|schematic|nbt|png|ttf|otf)"))throw new IllegalArgumentException("Unsupported asset extension");
                    String lower=name.toLowerCase(Locale.ROOT);String folder=lower.endsWith(".png")?"heightmaps":lower.endsWith(".ttf")||lower.endsWith(".otf")?"fonts":"blueprints";
                    byte[] bytes=Base64.getDecoder().decode(p.o.assetData);if(bytes.length>1_048_576)throw new IllegalArgumentException("Asset limit: 1 MiB");
                    EditPlanner preview=null;if(folder.equals("blueprints")){var session=new Session();preview=new EditPlanner(p.level,session,p.o);var tag=NbtIo.readCompressed(new ByteArrayInputStream(bytes),NbtAccounter.create(32*1024*1024));session.clipboard.putAll(read(tag,preview));AssetNbt.entities(tag,preview);}
                    Path path=file(folder,name);Files.createDirectories(path.getParent());Files.write(path,bytes);if(preview!=null)saveInfo(path,name,preview);
                    if(folder.equals("heightmaps"))p.o.heightmap=name;else if(folder.equals("fonts"))p.o.fontFile=name;
                    return "Imported "+name;
                }
                case "blueprint_save","structure_export","schematic_export" -> {
                    if(p.session.clipboard.isEmpty()&&p.session.clipboardEntities.isEmpty())throw new IllegalArgumentException("Copy a selection first");
                    String extension=action.equals("schematic_export")?".schem":".nbt";if(!name.endsWith(extension))name+=extension;
                    Path path=file("blueprints",name);Files.createDirectories(path.getParent());
                    NbtIo.writeCompressed(action.equals("schematic_export")?schematic(p):structure(p),path);saveInfo(path,name,p);return "Saved "+name;
                }
                case "blueprint_load","schematic_import" -> {
                    if(!name.matches(".*\\.(nbt|schem|schematic)$"))name+=".nbt";
                    CompoundTag tag=NbtIo.readCompressed(file("blueprints",name),NbtAccounter.create(32*1024*1024));
                    var clipboard=read(tag,p);var entities=AssetNbt.entities(tag,p);p.session.clipboard.clear();p.session.clipboardOrigin=null;p.session.clipboard.putAll(clipboard);p.session.clipboardEntities.clear();p.session.clipboardEntities.addAll(entities);p.session.clipboardDirty=true;return "Loaded "+name+" · "+clipboard.size()+" blocks";
                }
                case "blueprint_data" -> {
                    byte[] data=Base64.getDecoder().decode(p.o.assetData);if(data.length>1_048_576)throw new IllegalArgumentException("Local blueprint limit: 1 MiB");var tag=NbtIo.readCompressed(new ByteArrayInputStream(data),NbtAccounter.create(32*1024*1024));var blocks=read(tag,p);var entities=AssetNbt.entities(tag,p);p.session.clipboard.clear();p.session.clipboardOrigin=null;p.session.clipboard.putAll(blocks);p.session.clipboardEntities.clear();p.session.clipboardEntities.addAll(entities);p.session.clipboardDirty=true;return "Loaded local "+name+" · "+blocks.size()+" blocks";
                }
                case "selection_csv" -> {
                    Path path=file("exports",name.endsWith(".csv")?name:name+".csv");Files.createDirectories(path.getParent());
                    try(var writer=Files.newBufferedWriter(path)){writer.write("x,y,z,state,nbt\n");for(Point at:p.session.selection.points()){var s=BlockSnapshot.capture(p.level,EditPlanner.pos(at));writer.write(at.x()+","+at.y()+","+at.z()+",\""+BlockStateParser.serialize(s.state()).replace("\"","\"\"")+"\",\""+(s.data()==null?"":s.data().toString().replace("\"","\"\""))+"\"\n");}}
                    return "Exported "+path.getFileName();
                }
                default -> throw new IllegalArgumentException("Unknown asset action");
            }
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Asset operation failed: "+e.getMessage(),e);}
    }
    private static void saveInfo(Path path,String name,EditPlanner p)throws IOException{Files.writeString(path.resolveSibling(path.getFileName()+".json"),dev.craftium.net.Packets.JSON.toJson(info(name,p)));}
    private static BlueprintInfo info(String name,EditPlanner p){
        int sx=1,sy=1,sz=1;for(Point at:p.session.clipboard.keySet()){sx=Math.max(sx,at.x()+1);sy=Math.max(sy,at.y()+1);sz=Math.max(sz,at.z()+1);}
        int[] pixels=new int[256],heights=new int[256];Arrays.fill(heights,Integer.MIN_VALUE);
        for(var entry:p.session.clipboard.entrySet()){Point at=entry.getKey();if(entry.getValue().state().isAir())continue;int x=Math.clamp(at.x()*16/sx,0,15),z=Math.clamp(at.z()*16/sz,0,15),index=z*16+x;if(at.y()<heights[index])continue;heights[index]=at.y();pixels[index]=0xff000000|entry.getValue().state().getMapColor(p.level,EditPlanner.pos(at)).col;}
        var thumbnail=new ArrayList<Integer>();for(int value:pixels)thumbnail.add(value);
        String title=name.substring(name.lastIndexOf('/')+1).replaceFirst("\\.[^.]+$","");return new BlueprintInfo(name,title,p.o.author,p.o.tags,p.session.clipboard.size(),new Point(sx,sy,sz),thumbnail);
    }
    static String download(String action,EditPlanner p,net.minecraft.server.level.ServerPlayer player)throws IOException{
        if(!p.o.downloadExports&&!action.equals("blueprint_data"))return "";String folder=action.equals("selection_csv")?"exports":"blueprints",name=p.o.name;byte[] bytes;String metadata="";
        if(action.equals("blueprint_data")){bytes=Base64.getDecoder().decode(p.o.assetData);metadata=dev.craftium.net.Packets.JSON.toJson(info(name,p));}
        else{String extension=action.equals("selection_csv")?".csv":action.equals("schematic_export")?".schem":".nbt";if(!name.endsWith(extension))name+=extension;Path path=file(folder,name);if(Files.size(path)>1_048_576)return " · too large for client download; saved on server";bytes=Files.readAllBytes(path);Path sidecar=path.resolveSibling(path.getFileName()+".json");if(!action.equals("selection_csv")&&Files.exists(sidecar))metadata=Files.readString(sidecar);}
        ServerPlatform.send(player,new dev.craftium.net.Packets.Download(folder,name,Base64.getEncoder().encodeToString(bytes),metadata));return " · downloaded to client";
    }
    private static List<BlueprintInfo> list()throws IOException{
        Path root=ServerPlatform.assets().resolve("blueprints");if(!Files.isDirectory(root))return List.of();var results=new ArrayList<BlueprintInfo>();
        try(var files=Files.walk(root,5)){for(Path path:files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().matches("(?i).*\\.(nbt|schem|schematic)")).sorted().limit(256).toList()){
            String relative=root.relativize(path).toString().replace('\\','/');Path sidecar=path.resolveSibling(path.getFileName()+".json");BlueprintInfo info=null;
            try{if(Files.exists(sidecar)&&Files.size(sidecar)<16384)info=dev.craftium.net.Packets.JSON.fromJson(Files.readString(sidecar),BlueprintInfo.class);}catch(Exception ignored){}
            if(info==null)info=new BlueprintInfo(relative,path.getFileName().toString(),"","",-1,new Point(0,0,0),List.of());
            results.add(new BlueprintInfo(relative,info.name(),info.author(),info.tags(),info.blocks(),info.size(),info.thumbnail()));
        }}return List.copyOf(results);
    }
    private static ListTag ints(int... values){var list=new ListTag();for(int i:values)list.add(IntTag.valueOf(i));return list;}
    private static CompoundTag structure(EditPlanner p){
        var root=new CompoundTag();var palette=new ListTag();var indices=new LinkedHashMap<net.minecraft.world.level.block.state.BlockState,Integer>();var blocks=new ListTag();int sx=1,sy=1,sz=1;
        for(var e:p.session.clipboard.entrySet()){
            var at=e.getKey();var s=e.getValue();sx=Math.max(sx,at.x()+1);sy=Math.max(sy,at.y()+1);sz=Math.max(sz,at.z()+1);
            int index=indices.computeIfAbsent(s.state(),state->{palette.add(NbtUtils.writeBlockState(state));return palette.size()-1;});
            var block=new CompoundTag();block.put("pos",ints(at.x(),at.y(),at.z()));block.putInt("state",index);if(s.data()!=null)block.put("nbt",s.data().copy());blocks.add(block);
        }
        if(palette.isEmpty())palette.add(NbtUtils.writeBlockState(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());root.put("size",ints(sx,sy,sz));root.put("palette",palette);root.put("blocks",blocks);root.put("entities",AssetNbt.structureEntities(p.session));return root;
    }
    private static CompoundTag schematic(EditPlanner p)throws IOException{
        var root=new CompoundTag();int sx=1,sy=1,sz=1;for(Point at:p.session.clipboard.keySet()){sx=Math.max(sx,at.x()+1);sy=Math.max(sy,at.y()+1);sz=Math.max(sz,at.z()+1);}
        if((long)sx*sy*sz>Selection.LIMIT)throw new IllegalArgumentException("Schematic bounding volume is too large");
        root.putInt("Version",2);root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());root.putShort("Width",(short)sx);root.putShort("Height",(short)sy);root.putShort("Length",(short)sz);root.putIntArray("Offset",new int[]{0,0,0});
        var palette=new CompoundTag();var indices=new LinkedHashMap<String,Integer>();var bytes=new ByteArrayOutputStream();var entities=new ListTag();
        for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){
            var snapshot=p.session.clipboard.get(new Point(x,y,z));String state=snapshot==null?"minecraft:air":BlockStateParser.serialize(snapshot.state());int index=indices.computeIfAbsent(state,s->indices.size());
            int value=index;while((value&~0x7f)!=0){bytes.write((value&0x7f)|0x80);value>>>=7;}bytes.write(value);
            if(snapshot!=null&&snapshot.data()!=null){var tag=snapshot.data().copy();tag.putString("Id",tag.getStringOr("id",""));tag.remove("id");tag.remove("x");tag.remove("y");tag.remove("z");tag.putIntArray("Pos",new int[]{x,y,z});entities.add(tag);}
        }
        indices.forEach(palette::putInt);root.put("Palette",palette);root.putInt("PaletteMax",indices.size());root.putByteArray("BlockData",bytes.toByteArray());root.put("BlockEntities",entities);root.put("Entities",AssetNbt.schematicEntities(p.session));return root;
    }
    static LinkedHashMap<Point,BlockSnapshot> read(CompoundTag root,EditPlanner p)throws IOException{
        if(root.contains("Schematic"))root=root.getCompoundOrEmpty("Schematic");root=AssetNbt.structure(root,p);var result=new LinkedHashMap<Point,BlockSnapshot>();
        if(root.get("Blocks") instanceof ByteArrayTag)root=upgradeLegacy(root,p);
        if(root.contains("palette")){
            var palette=root.getListOrEmpty("palette");var blocks=root.getListOrEmpty("blocks");if(blocks.size()>Selection.LIMIT)throw new IllegalArgumentException("Blueprint too large");
            for(int i=0;i<blocks.size();i++){var block=blocks.getCompoundOrEmpty(i);var at=block.getListOrEmpty("pos");int state=block.getIntOr("state",0);if(state<0||state>=palette.size())throw new IllegalArgumentException("Invalid palette index");
                result.put(new Point(at.getIntOr(0,0),at.getIntOr(1,0),at.getIntOr(2,0)),new BlockSnapshot(AssetNbt.state(palette.getCompoundOrEmpty(state),p),block.getCompound("nbt").orElse(null)));}
        }else{
            int sx=root.getShort("Width").orElse((short)0)&0xffff,sy=root.getShort("Height").orElse((short)0)&0xffff,sz=root.getShort("Length").orElse((short)0)&0xffff;
            if(sx==0||sy==0||sz==0||(long)sx*sy*sz>Selection.LIMIT)throw new IllegalArgumentException("Invalid schematic dimensions");
            var dataRoot=root.contains("Blocks")&&root.get("Blocks") instanceof CompoundTag?root.getCompoundOrEmpty("Blocks"):root;
            var palette=dataRoot.getCompoundOrEmpty("Palette");var states=new HashMap<Integer,net.minecraft.world.level.block.state.BlockState>();for(String key:palette.keySet())states.put(palette.getIntOr(key,0),AssetNbt.state(key,root.getIntOr("DataVersion",AssetNbt.current()),p));
            byte[] data=dataRoot.getByteArray(root.getIntOr("Version",2)>=3?"Data":"BlockData").orElse(new byte[0]);int cursor=0;
            for(int i=0;i<sx*sy*sz;i++){
                int value=0,shift=0,b;do{if(cursor>=data.length||shift>28)throw new IllegalArgumentException("Truncated schematic block data");b=data[cursor++]&255;value|=(b&127)<<shift;shift+=7;}while((b&128)!=0);
                var state=states.get(value);if(state==null)throw new IllegalArgumentException("Invalid schematic palette index");result.put(new Point(i%sx,i/(sx*sz),(i/sx)%sz),new BlockSnapshot(state));
            }
            var entities=dataRoot.getListOrEmpty("BlockEntities");for(int i=0;i<entities.size();i++){var tag=entities.getCompoundOrEmpty(i);var coordinates=tag.getIntArray("Pos").orElse(new int[0]);if(coordinates.length==3){Point at=new Point(coordinates[0],coordinates[1],coordinates[2]);var old=result.get(at);if(old!=null)result.put(at,new BlockSnapshot(old.state(),AssetNbt.blockEntity(tag,root.getIntOr("DataVersion",AssetNbt.current()),p)));}}
        }
        return result;
    }
    private static CompoundTag upgradeLegacy(CompoundTag source,EditPlanner p){
        int sx=source.getShort("Width").orElse((short)0)&65535,sy=source.getShort("Height").orElse((short)0)&65535,sz=source.getShort("Length").orElse((short)0)&65535;
        long volume=(long)sx*sy*sz;if(volume<1||volume>Selection.LIMIT)throw new IllegalArgumentException("Invalid legacy schematic dimensions");
        byte[] ids=source.getByteArray("Blocks").orElseThrow(),metadata=source.getByteArray("Data").orElseThrow(),extra=source.getByteArray("AddBlocks").orElse(new byte[0]);
        if(ids.length!=volume||metadata.length!=volume||(extra.length>0&&extra.length<(volume+1)/2))throw new IllegalArgumentException("Truncated legacy schematic");
        var palette=new ListTag();var indices=new HashMap<Integer,Integer>();var blocks=new ListTag();var blockEntities=new HashMap<Point,CompoundTag>();
        var tiles=source.getListOrEmpty("TileEntities");for(int i=0;i<tiles.size();i++){var tag=tiles.getCompoundOrEmpty(i);blockEntities.put(new Point(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0)),tag);}
        for(int i=0;i<volume;i++){
            int high=extra.length==0?0:((extra[i/2]&255)>>(i%2*4))&15,id=(ids[i]&255)|(high<<8),combined=(id<<4)|(metadata[i]&15);
            int index=indices.computeIfAbsent(combined,key->{
                var state=net.minecraft.util.datafix.fixes.BlockStateData.getTag(key).convert(NbtOps.INSTANCE).getValue();
                if(!(state instanceof CompoundTag tag))throw new IllegalArgumentException("Unsupported legacy block "+id);
                if(id!=0&&tag.getStringOr("Name","").equals("minecraft:air"))throw new IllegalArgumentException("Unsupported legacy numeric block "+id);
                palette.add(tag);return palette.size()-1;
            });
            Point at=new Point(i%sx,i/(sx*sz),(i/sx)%sz);var block=new CompoundTag();block.put("pos",ints(at.x(),at.y(),at.z()));block.putInt("state",index);
            blocks.add(block);
        }
        var structure=new CompoundTag();structure.put("size",ints(sx,sy,sz));structure.put("palette",palette);structure.put("blocks",blocks);structure.put("entities",new ListTag());
        var upgraded=net.minecraft.util.datafix.DataFixTypes.STRUCTURE.updateToCurrentVersion(p.level.getServer().getFixerUpper(),structure,1451);
        var upgradedBlocks=upgraded.getListOrEmpty("blocks");for(int i=0;i<upgradedBlocks.size();i++){var block=upgradedBlocks.getCompoundOrEmpty(i);var pos=block.getListOrEmpty("pos");var nbt=blockEntities.get(new Point(pos.getIntOr(0,0),pos.getIntOr(1,0),pos.getIntOr(2,0)));if(nbt!=null){int version=source.getIntOr("DataVersion",nbt.getStringOr("id","").contains(":")?1343:99);block.put("nbt",AssetNbt.upgrade(nbt.copy(),net.minecraft.util.datafix.fixes.References.BLOCK_ENTITY,version,p));}}
        return upgraded;
    }
}
