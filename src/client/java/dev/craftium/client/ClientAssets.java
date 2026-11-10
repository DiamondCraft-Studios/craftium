package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;

/** Only files explicitly requested by this client may be downloaded into its asset library. */
public final class ClientAssets {
    private static final Map<String,Long> expected=new HashMap<>();
    private ClientAssets(){}
    private static Path root(String folder){if(!Set.of("blueprints","exports").contains(folder))throw new IllegalArgumentException("Invalid asset library");return FabricLoader.getInstance().getConfigDir().resolve("craftium").resolve(folder).toAbsolutePath().normalize();}
    private static Path file(String folder,String name){if(name.length()>160||!name.matches("[A-Za-z0-9 _./-]+"))throw new IllegalArgumentException("Use a plain asset filename");for(String segment:name.split("/",-1))if(segment.isBlank()||segment.equals(".")||segment.equals(".."))throw new IllegalArgumentException("Invalid asset folder");Path path=root(folder).resolve(name).normalize();if(!path.startsWith(root(folder)))throw new IllegalArgumentException("Invalid asset path");return path;}
    static void expect(String action){var o=ClientState.options;if(!o.downloadExports&&!action.equals("blueprint_data"))return;String folder=action.equals("selection_csv")?"exports":"blueprints",name=o.name;String extension=switch(action){case "blueprint_save","structure_export"->".nbt";case "schematic_export"->".schem";case "selection_csv"->".csv";case "blueprint_data"->"";default->null;};if(extension==null)return;expected.entrySet().removeIf(e->e.getValue()<System.currentTimeMillis());if(expected.size()>=64)expected.clear();if(!name.endsWith(extension))name+=extension;expected.put(folder+"/"+name,System.currentTimeMillis()+60_000);}
    static void receive(Packets.Download download){
        if(download.folder().equals("preview")){ClientStructures.receivePreview(download);return;}
        if(download.folder().equals("structure")){ClientStructures.receiveExport(download);return;}
        Long expiry=expected.remove(download.folder()+"/"+download.name());if(expiry==null||expiry<System.currentTimeMillis())return;
        try{if(!download.name().matches("(?i).*\\.(nbt|schem|schematic|csv)"))throw new IllegalArgumentException("Unsupported download");byte[] bytes=Base64.getDecoder().decode(download.data());if(bytes.length>1_048_576)throw new IllegalArgumentException("Download limit: 1 MiB");Path path=file(download.folder(),download.name());write(path,bytes);
            if(!download.metadata().isBlank()){var info=Packets.JSON.fromJson(download.metadata(),BlueprintInfo.class);if(info!=null&&info.thumbnail()!=null&&info.thumbnail().size()<=256)write(path.resolveSibling(path.getFileName()+".json"),download.metadata().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            ClientState.libraryVersion++;ClientState.notify("Downloaded "+download.name()+" to your Craftium "+download.folder()+" folder");
        }catch(Exception e){ClientState.notify("Could not save download: "+e.getMessage());}
    }
    private static void write(Path path,byte[] bytes)throws java.io.IOException{Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.write(temp,bytes);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    static void clear(){expected.clear();}
    static void importFile(Path source){try{String name=source.getFileName().toString();if(!name.matches("(?i).*\\.(nbt|schem|schematic)"))throw new IllegalArgumentException("Choose a schematic or structure NBT file");if(Files.size(source)>1_048_576)throw new IllegalArgumentException("Blueprint limit: 1 MiB");write(file("blueprints",name),Files.readAllBytes(source));ClientState.libraryVersion++;ClientState.notify("Imported local blueprint: "+name);}catch(java.io.IOException e){throw new IllegalArgumentException("Could not import local blueprint",e);}}
    public static void load(String name){try{Path path=file("blueprints",name);if(Files.size(path)>1_048_576)throw new IllegalArgumentException("Blueprint limit: 1 MiB");var o=ClientState.options;String prior=o.assetData;o.name=name;o.assetData=Base64.getEncoder().encodeToString(Files.readAllBytes(path));CraftiumClient.send("blueprint_data",null,null);o.assetData=prior;}catch(java.io.IOException e){throw new IllegalArgumentException("Cannot read local blueprint",e);}}
    static List<BlueprintInfo> list(){var results=new ArrayList<BlueprintInfo>();try{Path directory=root("blueprints");if(!Files.isDirectory(directory))return List.of();try(var files=Files.walk(directory,5)){for(Path path:files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().matches("(?i).*\\.(nbt|schem|schematic)")).sorted().limit(256).toList()){String relative=directory.relativize(path).toString().replace('\\','/');BlueprintInfo info=null;Path sidecar=path.resolveSibling(path.getFileName()+".json");try{if(Files.exists(sidecar)&&Files.size(sidecar)<=32768)info=Packets.JSON.fromJson(Files.readString(sidecar),BlueprintInfo.class);}catch(Exception ignored){}if(info==null)info=new BlueprintInfo(relative,path.getFileName().toString(),"","",-1,new Point(0,0,0),List.of());if(!info.hasVoxels()){var described=dev.craftium.server.BlueprintFileVoxels.read(path);if(described!=null){info=new BlueprintInfo(relative,info.name(),info.author(),info.tags(),described.blocks(),described.size(),info.thumbnail()==null?List.of():info.thumbnail(),null,null,1).withVoxels(described.packed());try{write(sidecar,Packets.JSON.toJson(info).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(java.io.IOException ignored){}}} // an import or an older file has no picture data: read it from the file, once
            results.add(info.at(relative));}}}catch(Exception e){ClientState.notify("Cannot read the client blueprint library");}return List.copyOf(results);}
}
