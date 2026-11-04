package dev.craftium.client;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.craftium.Craftium;
import dev.craftium.core.Point;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;

/**
 * Keeps the rulers of each world and dimension in a file of their own, so they are still there when the world is loaded again.
 * It watches which world the player is in: leaving one saves its rulers, and entering one brings back its rulers (or none).
 * They are saved as soon as they change.
 */
final class RulerStore {
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("craftium-rulers.json");
    /** One ruler as saved: the blocks at its ends, the second being null while it is only started. */
    private record Saved(int[] a,int[] b,String mode){}
    private static String current;
    /** What was last written or read for {@link #current}, to tell when the rulers have changed. */
    private static List<Saved> known=List.of();
    private RulerStore(){}

    /** Called every client tick. */
    static void tick(Minecraft mc){
        String now=key(mc);
        if(!Objects.equals(now,current)){
            save(); // whatever the player left behind
            current=now;ClientState.rulers.clear();
            known=List.of();
            if(now!=null){known=read(now);for(var s:known)ClientState.rulers.add(ruler(s));}
            return;
        }
        if(current!=null&&!same(snapshot(),known))save();
    }

    /** The world and dimension the player is in, or null when in none. */
    private static String key(Minecraft mc){
        if(mc.level==null||mc.player==null)return null;
        String world;
        var integrated=mc.getSingleplayerServer();
        if(integrated!=null)world="world:"+integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        else{var server=mc.getCurrentServer();world="server:"+(server==null?"unknown":server.ip);}
        return world+"|"+mc.level.dimension();
    }

    private static List<Saved> snapshot(){
        var out=new ArrayList<Saved>();
        for(var r:ClientState.rulers)out.add(new Saved(array(r.a),r.b==null?null:array(r.b),r.mode.name()));
        return out;
    }
    private static int[] array(Point p){return new int[]{p.x(),p.y(),p.z()};}
    private static Ruler ruler(Saved s){
        var r=new Ruler(new Point(s.a()[0],s.a()[1],s.a()[2]));
        if(s.b()!=null)r.b=new Point(s.b()[0],s.b()[1],s.b()[2]);
        try{if(s.mode()!=null)r.mode=dev.craftium.core.Options.RulerMode.valueOf(s.mode());}catch(IllegalArgumentException ignored){}
        return r;
    }
    /** Two snapshots are the same when every ruler has the same ends. */
    private static boolean same(List<Saved> a,List<Saved> b){
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!Arrays.equals(a.get(i).a(),b.get(i).a())||!Arrays.equals(a.get(i).b(),b.get(i).b())||!Objects.equals(a.get(i).mode(),b.get(i).mode()))return false;
        return true;
    }

    private static Map<String,List<Saved>> all(){
        try{
            if(!Files.exists(FILE))return new LinkedHashMap<>();
            Map<String,List<Saved>> loaded=new GsonBuilder().create().fromJson(Files.readString(FILE),new TypeToken<LinkedHashMap<String,List<Saved>>>(){}.getType());
            return loaded==null?new LinkedHashMap<>():loaded;
        }catch(Exception e){Craftium.LOG.warn("Could not read the saved rulers",e);return new LinkedHashMap<>();}
    }
    private static List<Saved> read(String key){
        var list=new ArrayList<Saved>();
        for(var s:all().getOrDefault(key,List.of()))if(s!=null&&s.a()!=null&&s.a().length==3&&(s.b()==null||s.b().length==3))list.add(s);
        return list;
    }
    private static void save(){
        if(current==null)return;
        var now=snapshot();
        if(same(now,known))return;
        try{
            var map=all();
            if(now.isEmpty())map.remove(current);else map.put(current,now);
            Files.createDirectories(FILE.getParent());Path temp=FILE.resolveSibling("craftium-rulers.json.tmp");
            Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(map));
            Files.move(temp,FILE,StandardCopyOption.REPLACE_EXISTING);
            known=now;
        }catch(Exception e){Craftium.LOG.warn("Could not save the rulers",e);}
    }
}
