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
        /** What applies when the file is there but cannot be read and there is nothing better: nobody but operators may change anything. */
        static final Config CLOSED;
        static{var none=new HashMap<String,Boolean>();for(var verb:VERBS)none.put(verb,false);CLOSED=new Config(Map.copyOf(none),Map.of(),true);}
    }
    private static Config config=Config.OPEN;private static Path loadedFrom;private static long stamp=-1,checkedAt;
    /** The last config that was read without a problem. */
    private static Config lastGood;
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
            loadedFrom=file;stamp=modified; // read again only when the file changes, so a mistake is reported once
            config=readOrKeep(Files.readString(file),file.toString());
        }catch(Exception e){
            ServerPlatform.LOG.warn("Cannot read {}: keeping the BuildObject permissions as they were",file,e);
        }
        return config;
    }
    /**
     * The config in {@code json}; when it is not valid (a mistake in the file) the last one that was, so a restriction is not dropped because of a typo, and
     * with none before it {@link Config#CLOSED}. The mistake is logged with where it is.
     */
    static Config readOrKeep(String json,String where){
        try{lastGood=parse(json);return lastGood;}
        catch(RuntimeException e){
            ServerPlatform.LOG.error("{} is not a valid BuildObject permissions file ({}): {}",where,e.getMessage(),lastGood!=null?"the last good one stays in force":"only operators may change BuildObjects until it is fixed");
            return lastGood!=null?lastGood:Config.CLOSED;
        }
    }
    /** Forgets the last good config, for a test. */
    static void forgetForTest(){lastGood=null;}
    static Config parse(String json){
        var root=JsonParser.parseString(json).getAsJsonObject();
        if(root.has("operatorsBypass")&&!(root.get("operatorsBypass").isJsonPrimitive()&&root.get("operatorsBypass").getAsJsonPrimitive().isBoolean()))throw new IllegalArgumentException("\"operatorsBypass\" must be true or false");
        return new Config(flags(root.get("everyone"),"everyone"),players(root.get("players")),!root.has("operatorsBypass")||root.get("operatorsBypass").getAsBoolean());
    }
    /** The actions and whether each is allowed. Anything else is a mistake and an error: a misspelled action would otherwise be silently not restricted. */
    private static Map<String,Boolean> flags(JsonElement element,String whose){
        var out=new HashMap<String,Boolean>();
        if(element==null||element.isJsonNull())return out;
        if(!element.isJsonObject())throw new IllegalArgumentException("\""+whose+"\" must be an object of "+VERBS);
        for(var e:element.getAsJsonObject().entrySet()){
            if(!VERBS.contains(e.getKey()))throw new IllegalArgumentException("\""+e.getKey()+"\" under "+whose+" is not one of "+VERBS);
            if(!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("\""+e.getKey()+"\" under "+whose+" must be true or false");
            out.put(e.getKey(),e.getValue().getAsBoolean());
        }
        return out;
    }
    private static Map<String,Map<String,Boolean>> players(JsonElement element){
        var out=new HashMap<String,Map<String,Boolean>>();
        if(element==null||element.isJsonNull())return out;
        if(!element.isJsonObject())throw new IllegalArgumentException("\"players\" must be an object");
        for(var e:element.getAsJsonObject().entrySet())out.put(e.getKey().toLowerCase(Locale.ROOT),flags(e.getValue(),e.getKey()));
        return out;
    }
    private static String template(){
        var everyone=new JsonObject();for(var verb:VERBS)everyone.addProperty(verb,true);
        var root=new JsonObject();root.add("everyone",everyone);root.add("players",new JsonObject());root.addProperty("operatorsBypass",true);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root)+"\n";
    }
}
