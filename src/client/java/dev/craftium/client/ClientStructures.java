package dev.craftium.client;

import dev.craftium.core.StructurePreview;
import dev.craftium.core.StructureSettings;
import dev.craftium.net.Packets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * The client's half of exporting and importing structures: the preview the server sends, the file dialogs, and writing the
 * exported file to the place the player chose. A file is only written for a request this client made, to the path it chose.
 */
final class ClientStructures {
    private static final long WAIT=60_000;
    private static final int LIMIT=1_048_576;
    /** The newest preview from the server, and how many have arrived, which the pop-up compares to know when to redraw. */
    static StructurePreview preview;
    static int previews;
    private static Path pending;private static String pendingName="";private static long pendingUntil;
    private static final Map<String,Integer> colours=new HashMap<>();
    private ClientStructures(){}

    static void receivePreview(Packets.Download download){
        try{preview=StructurePreview.decode(download.data());previews++;}
        catch(RuntimeException e){preview=null;previews++;ClientState.notify(e.getMessage());}
    }

    /** Only the file this client asked for is accepted, and it goes where the player chose and nowhere else. */
    static void receiveExport(Packets.Download download){
        Path path=pending;
        if(path==null||System.currentTimeMillis()>pendingUntil||!download.name().equals(pendingName))return;
        pending=null;
        try{
            byte[] bytes=Base64.getDecoder().decode(download.data());if(bytes.length>LIMIT)throw new IllegalArgumentException("Export limit: 1 MiB");
            if(path.getParent()!=null)Files.createDirectories(path.getParent());
            Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.write(temp,bytes);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
            ClientState.notify("Saved "+path);
        }catch(Exception e){ClientState.notify("Could not save the structure: "+e.getMessage());}
    }

    /** Asks the server for the file and writes it to {@code path} when it arrives. */
    static void exportTo(Path path,String fileName){
        pending=path;pendingName=fileName;pendingUntil=System.currentTimeMillis()+WAIT;
        CraftiumClient.send("structure_export_file",null,null);
    }
    static boolean exporting(){return pending!=null&&System.currentTimeMillis()<pendingUntil;}

    /** The world's {@code generated/<namespace>/structures} folder, where a structure block in this world looks for the file. */
    static Path defaultDirectory(StructureSettings s){
        var server=Minecraft.getInstance().getSingleplayerServer();
        Path base=server!=null?server.getWorldPath(LevelResource.GENERATED_DIR).resolve(s.namespace()).resolve("structures"):FabricLoader.getInstance().getGameDir().resolve("structures");
        String path=s.path();int slash=path.lastIndexOf('/');
        return slash<0?base:base.resolve(path.substring(0,slash));
    }

    /** The system's save dialog, started in {@link #defaultDirectory} with the structure's file name; {@code then} runs on the game thread. */
    static void chooseExportFile(StructureSettings s,Consumer<Path> then){
        Path directory=defaultDirectory(s);String name=s.fileName(),pattern=s.bedrock()?"*.mcstructure":"*.nbt",description=s.bedrock()?"Bedrock structure (*.mcstructure)":"Java structure (*.nbt)";
        try{Files.createDirectories(directory);}catch(Exception ignored){ /* the dialog opens elsewhere */ }
        String start=directory.resolve(name).toString();
        var thread=new Thread(()->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                PointerBuffer filters=stack.mallocPointer(1);filters.put(stack.UTF8(pattern)).flip();
                String chosen=TinyFileDialogs.tinyfd_saveFileDialog("Export structure",start,filters,description);
                if(chosen==null)return;
                Path path=Path.of(chosen);String file=path.getFileName().toString();
                if(!file.toLowerCase(Locale.ROOT).endsWith(s.extension()))path=path.resolveSibling(file+s.extension());
                Path result=path;Minecraft.getInstance().execute(()->then.accept(result));
            }catch(Exception e){Minecraft.getInstance().execute(()->ClientState.notify("Could not open the file dialog: "+e.getMessage()));}
        },"Craftium structure export");
        thread.setDaemon(true);thread.start();
    }

    /** Picks a structure file (Java NBT, Sponge schematic or Bedrock) and loads it into the clipboard on the server. */
    static void chooseImport(){
        var thread=new Thread(()->{
            try(MemoryStack stack=MemoryStack.stackPush()){
                PointerBuffer filters=stack.mallocPointer(4);
                for(String pattern:new String[]{"*.nbt","*.schem","*.schematic","*.mcstructure"})filters.put(stack.UTF8(pattern));
                filters.flip();
                String chosen=TinyFileDialogs.tinyfd_openFileDialog("Import structure",defaultDirectory(ClientState.options.structure).toString()+java.io.File.separator,filters,"Structures (nbt, schem, mcstructure)",false);
                if(chosen==null)return;
                Path path=Path.of(chosen);
                if(Files.size(path)>LIMIT)throw new IllegalArgumentException("Structure import limit: 1 MiB");
                byte[] data=Files.readAllBytes(path);
                Minecraft.getInstance().execute(()->{
                    var o=ClientState.options;String name=path.getFileName().toString();
                    if(!name.matches("(?i).+\\.(nbt|schem|schematic|mcstructure)")){ClientState.notify("Choose a .nbt, .schem, .schematic or .mcstructure file");return;}
                    String priorName=o.name,priorData=o.assetData;
                    o.name=name.length()>80?name.substring(name.length()-80):name;o.assetData=Base64.getEncoder().encodeToString(data);
                    try{CraftiumClient.send("structure_import_file",null,null);}finally{o.name=priorName;o.assetData=priorData;}
                });
            }catch(Exception e){Minecraft.getInstance().execute(()->ClientState.notify(e.getMessage()==null?"Could not import the structure":e.getMessage()));}
        },"Craftium structure import");
        thread.setDaemon(true);thread.start();
    }

    /** A block's colour in the preview: the average of its textures when known, otherwise its map colour. */
    static int colour(String id){
        return colours.computeIfAbsent(id,key->{
            var entry=BlockColours.get(key);if(entry!=null)return 0xff000000|entry.rgb();
            var block=BuiltInRegistries.BLOCK.getValue(dev.craftium.compat.Compat.parse(key));
            return 0xff000000|block.defaultMapColor().col;
        });
    }
    static int[] colours(StructurePreview preview){
        BlockColours.build();var result=new int[preview.palette().size()];
        for(int i=0;i<result.length;i++)result[i]=colour(preview.palette().get(i).name());
        return result;
    }
}
