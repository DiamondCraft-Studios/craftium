package dev.craftium.server;

import com.google.gson.*;
import dev.craftium.runtime.ServerPlatform;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.util.*;

/**
 * Who may change the BuildObjects, by what they do: {@code create}, {@code rename}, {@code move}, {@code delete}, {@code recolour} and {@code edit} (the blocks one
 * holds). Everyone may do all of them unless this says otherwise. It is read from {@code hierarchy-permissions.json} in Craftium's config folder, which is
 * written with everything allowed the first time, and read again when it changes:
 * <pre>
 * { "everyone": { "delete": false },
 *   "players":  { "Alex": { "delete": true }, "0a1b2c3d-...": { "move": false } },
 *   "operatorsBypass": true }
 * </pre>
 * A player's own entry (by name or UUID) wins over {@code everyone}; operators may do everything while {@code operatorsBypass} is on.
 */
final class HierarchyPermissions {
    static final List<String> VERBS=List.of("create","rename","move","delete","recolour","edit");
    record Config(Map<String,Boolean> everyone,Map<String,Map<String,Boolean>> players,boolean operatorsBypass){
        static final Config OPEN=new Config(Map.of(),Map.of(),true);
    }
    private static Config config=Config.OPEN;private static Path loadedFrom;private static long stamp=-1,checkedAt;
    private HierarchyPermissions(){}

    /** Whether the player may do this kind of thing to the BuildObjects. */
    static boolean allowed(ServerPlayer player,String verb){
        return allowed(current(),dev.craftium.compat.Compat.isGamemaster(player),player.getUUID().toString(),player.getName().getString(),verb);
    }
    /** The rule itself, apart from who is asking. */
    static boolean allowed(Config c,boolean operator,String uuid,String name,String verb){
        if(c.operatorsBypass()&&operator)return true;
        for(var key:List.of(uuid.toLowerCase(Locale.ROOT),name.toLowerCase(Locale.ROOT))){
            var mine=c.players().get(key);if(mine!=null&&mine.containsKey(verb))return mine.get(verb);
        }
        var all=c.everyone().get(verb);
        return all==null||all;
    }
    /** Throws what the player is told when they may not. */
    static void require(ServerPlayer player,String verb){
        if(!allowed(player,verb))throw new IllegalArgumentException("You do not have permission to "+verb+" BuildObjects here");
    }

    private static Config current(){
        long now=System.currentTimeMillis();
        if(now-checkedAt<2000)return config;
        checkedAt=now;
        Path file=ServerPlatform.assets().resolve("hierarchy-permissions.json");
        try{
            if(!Files.exists(file)){
                if(!file.equals(loadedFrom)){Files.createDirectories(file.getParent());Files.writeString(file,template());}
                loadedFrom=file;stamp=Files.getLastModifiedTime(file).toMillis();config=Config.OPEN;return config;
            }
            long modified=Files.getLastModifiedTime(file).toMillis();
            if(file.equals(loadedFrom)&&modified==stamp)return config;
            config=parse(Files.readString(file));loadedFrom=file;stamp=modified;
        }catch(Exception e){
            ServerPlatform.LOG.warn("Cannot read {}: everyone may change BuildObjects until it is fixed",file,e);config=Config.OPEN;loadedFrom=file;
        }
        return config;
    }
    static Config parse(String json){
        var root=JsonParser.parseString(json).getAsJsonObject();
        return new Config(flags(root.get("everyone")),players(root.get("players")),!root.has("operatorsBypass")||root.get("operatorsBypass").getAsBoolean());
    }
    private static Map<String,Boolean> flags(JsonElement element){
        var out=new HashMap<String,Boolean>();
        if(element!=null&&element.isJsonObject())for(var e:element.getAsJsonObject().entrySet())if(VERBS.contains(e.getKey())&&e.getValue().isJsonPrimitive()&&e.getValue().getAsJsonPrimitive().isBoolean())out.put(e.getKey(),e.getValue().getAsBoolean());
        return out;
    }
    private static Map<String,Map<String,Boolean>> players(JsonElement element){
        var out=new HashMap<String,Map<String,Boolean>>();
        if(element!=null&&element.isJsonObject())for(var e:element.getAsJsonObject().entrySet())out.put(e.getKey().toLowerCase(Locale.ROOT),flags(e.getValue()));
        return out;
    }
    private static String template(){
        var everyone=new JsonObject();for(var verb:VERBS)everyone.addProperty(verb,true);
        var root=new JsonObject();root.add("everyone",everyone);root.add("players",new JsonObject());root.addProperty("operatorsBypass",true);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root)+"\n";
    }
}
