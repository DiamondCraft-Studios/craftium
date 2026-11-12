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
    private static Path file(String folder,String name){if(name.isEmpty()||name.endsWith("/"))throw new IllegalArgumentException("Use a plain asset filename");return dev.craftium.server.BlueprintFiles.resolve(root(folder),name);}
    static void expect(String action){var o=ClientState.options;if(!o.downloadExports&&!action.equals("blueprint_data"))return;String folder=action.equals("selection_csv")?"exports":"blueprints",name=o.name;String extension=switch(action){case "blueprint_save","structure_export"->".nbt";case "schematic_export"->".schem";case "selection_csv"->".csv";case "blueprint_data"->"";default->null;};if(extension==null)return;expected.entrySet().removeIf(e->e.getValue()<System.currentTimeMillis());if(expected.size()>=64)expected.clear();if(!name.endsWith(extension))name+=extension;expected.put(folder+"/"+name,System.currentTimeMillis()+60_000);}
    static void receive(Packets.Download download){
        if(download.folder().equals("preview")){ClientStructures.receivePreview(download);return;}
        if(download.folder().equals("structure")){ClientStructures.receiveExport(download);return;}
        if(download.folder().equals("bedrockpack")){ClientBedrockPack.receive(download);return;}
        Long expiry=expected.remove(download.folder()+"/"+download.name());if(expiry==null||expiry<System.currentTimeMillis())return;
        try{if(!download.name().matches("(?i).*\\.(nbt|schem|schematic|csv)"))throw new IllegalArgumentException("Unsupported download");byte[] bytes=Base64.getDecoder().decode(download.data());if(bytes.length>1_048_576)throw new IllegalArgumentException("Download limit: 1 MiB");Path path=file(download.folder(),download.name());write(path,bytes);Files.deleteIfExists(path.resolveSibling(path.getFileName()+".json"));
            if(!download.metadata().isBlank()){var info=Packets.JSON.fromJson(download.metadata(),BlueprintInfo.class);if(info!=null&&info.thumbnail()!=null&&info.thumbnail().size()<=256)write(path.resolveSibling(path.getFileName()+".json"),download.metadata().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            ClientState.libraryVersion++;ClientState.notify("Downloaded "+download.name()+" to your Craftium "+download.folder()+" folder");
        }catch(Exception e){ClientState.notify("Could not save download: "+e.getMessage());}
    }
    private static void write(Path path,byte[] bytes)throws java.io.IOException{Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.write(temp,bytes);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    static void clear(){expected.clear();}
    /** Imports a file into the library; a sidecar left by an older file of the same name is removed, so the browser reads this one for its picture, block count and size. */
    static void importFile(Path source){try{String name=source.getFileName().toString();if(!name.matches("(?i).*\\.(nbt|schem|schematic)"))throw new IllegalArgumentException("Choose a schematic or structure NBT file");if(Files.size(source)>1_048_576)throw new IllegalArgumentException("Blueprint limit: 1 MiB");Path target=file("blueprints",name);write(target,Files.readAllBytes(source));Files.deleteIfExists(target.resolveSibling(target.getFileName()+".json"));ClientState.libraryVersion++;ClientState.notify("Imported local blueprint: "+name);}catch(java.io.IOException e){throw new IllegalArgumentException("Could not import local blueprint",e);}}
    /** The bytes of one file of the client's blueprint library, for a request that carries it to the server. */
    static byte[] bytes(String name){try{Path path=file("blueprints",name);if(Files.size(path)>1_048_576)throw new IllegalArgumentException("Blueprint limit: 1 MiB");return Files.readAllBytes(path);}catch(java.io.IOException e){throw new IllegalArgumentException("Cannot read that blueprint",e);}}
    public static void load(String name){try{Path path=file("blueprints",name);if(Files.size(path)>1_048_576)throw new IllegalArgumentException("Blueprint limit: 1 MiB");var o=ClientState.options;String prior=o.assetData;o.name=name;o.assetData=Base64.getEncoder().encodeToString(Files.readAllBytes(path));CraftiumClient.send("blueprint_data",null,null);o.assetData=prior;}catch(java.io.IOException e){throw new IllegalArgumentException("Cannot read local blueprint",e);}}
    static List<BlueprintInfo> list(){try{return dev.craftium.server.BlueprintFiles.list(root("blueprints"),Integer.MAX_VALUE,Long.MAX_VALUE/2).files();}catch(Exception e){ClientState.notify("Cannot read the client blueprint library");return List.of();}}
    /** A file-menu action on the client's own library (see {@link dev.craftium.server.BlueprintFiles}); says what happened. */
    static String operate(dev.craftium.server.BlueprintFiles.Op op){try{Files.createDirectories(root("blueprints"));return dev.craftium.server.BlueprintFiles.run(root("blueprints"),op);}catch(java.io.IOException e){throw new IllegalArgumentException("Could not change the library: "+e.getMessage(),e);}}
}
