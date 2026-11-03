package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import com.google.gson.*;
import dev.craftium.core.ColourMath;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import javax.imageio.ImageIO;
import java.util.*;

/** Texture averages are derived from the player's active resources, including resource packs. */
final class BlockColours {
    record Entry(String id,int rgb,double[] lab,boolean full,boolean solid,boolean opaque,boolean uniform){}
    private static final List<Entry> entries=new ArrayList<>();
    private static final Map<String,Integer> averages=new HashMap<>();
    private static final Map<String,JsonObject> models=new HashMap<>();
    private static Object resources;
    private BlockColours(){}
    static void build(){
        var mc=Minecraft.getInstance();if(resources==mc.getResourceManager()&&!entries.isEmpty())return;resources=mc.getResourceManager();entries.clear();averages.clear();models.clear();
        for(var block:BuiltInRegistries.BLOCK){
            var state=block.defaultBlockState();if(state.isAir()||block.asItem()==net.minecraft.world.item.Items.AIR)continue;
            String id=BuiltInRegistries.BLOCK.getKey(block).toString();
            try{
                var key=dev.craftium.compat.Compat.parse(id);JsonObject blockstate=json(key.getNamespace(),"blockstates/"+key.getPath()+".json");
                String model=findModel(blockstate);if(model==null)continue;var textures=new LinkedHashMap<String,String>();readModel(model,textures,0);
                var paths=new LinkedHashSet<String>();for(String value:textures.values()){int attempts=0;while(value.startsWith("#")&&attempts++<16)value=textures.getOrDefault(value.substring(1),"");if(!value.isBlank()&&!value.startsWith("#"))paths.add(value);}
                if(paths.isEmpty())continue;long r=0,g=0,b=0;int n=0;
                for(String path:paths){int color=average(path);if(color<0)continue;r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255;n++;}if(n==0)continue;
                int rgb=(int)(r/n)<<16|(int)(g/n)<<8|(int)(b/n);
                int tint=ClientCompat.blockTint(mc,state,mc.player==null?BlockPos.ZERO:mc.player.blockPosition());
                if(tint!=-1)rgb=(((rgb>>16)&255)*((tint>>16)&255)/255)<<16|(((rgb>>8)&255)*((tint>>8)&255)/255)<<8|(rgb&255)*(tint&255)/255;
                boolean full=state.isCollisionShapeFullBlock(mc.level,BlockPos.ZERO),solid=!state.getCollisionShape(mc.level,BlockPos.ZERO).isEmpty();
                entries.add(new Entry(id,rgb,ColourMath.lab(rgb),full,solid,state.canOcclude(),paths.size()==1));
            }catch(Exception ignored){/* Blocks without an item model or a readable texture cannot be colour matched. */}
        }
    }
    private static JsonObject json(String namespace,String path)throws Exception{try(var reader=Minecraft.getInstance().getResourceManager().getResourceOrThrow(dev.craftium.compat.Compat.id(namespace,path)).openAsReader()){return JsonParser.parseReader(reader).getAsJsonObject();}}
    private static String findModel(JsonElement value){if(value.isJsonObject()){var obj=value.getAsJsonObject();if(obj.has("model"))return obj.get("model").getAsString();for(var child:obj.entrySet()){String found=findModel(child.getValue());if(found!=null)return found;}}else if(value.isJsonArray())for(var child:value.getAsJsonArray()){String found=findModel(child);if(found!=null)return found;}return null;}
    private static void readModel(String id,Map<String,String> textures,int depth)throws Exception{
        if(depth>16)return;JsonObject model=models.get(id);if(model==null){var key=dev.craftium.compat.Compat.parse(id);model=json(key.getNamespace(),"models/"+key.getPath()+".json");models.put(id,model);}
        if(model.has("parent"))readModel(model.get("parent").getAsString(),textures,depth+1);
        if(model.has("textures"))for(var entry:model.getAsJsonObject("textures").entrySet())textures.put(entry.getKey(),entry.getValue().getAsString());
    }
    private static int average(String texture){return averages.computeIfAbsent(texture,key->{
        var id=dev.craftium.compat.Compat.parse(key);try(var stream=Minecraft.getInstance().getResourceManager().getResourceOrThrow(dev.craftium.compat.Compat.id(id.getNamespace(),"textures/"+id.getPath()+".png")).open()){
            var image=ImageIO.read(stream);if(image==null)return -1;long r=0,g=0,b=0,weight=0;int height=Math.min(image.getHeight(),image.getWidth());
            for(int x=0;x<image.getWidth();x++)for(int y=0;y<height;y++){int c=image.getRGB(x,y),a=c>>>24;r+=((c>>16)&255)*a;g+=((c>>8)&255)*a;b+=(c&255)*a;weight+=a;}
            return weight==0?-1:(int)(r/weight)<<16|(int)(g/weight)<<8|(int)(b/weight);
        }catch(Exception e){return -1;}
    });}
    static List<Entry> nearest(int color,boolean full,boolean solid,boolean opaque,boolean uniform,int count){double[] lab=ColourMath.lab(color);return entries.stream().filter(e->(!full||e.full)&&(!solid||e.solid)&&(!opaque||e.opaque)&&(!uniform||e.uniform)).sorted(Comparator.comparingDouble(e->ColourMath.distance(lab,e.lab))).limit(count).toList();}
    static Entry get(String id){return entries.stream().filter(e->e.id.equals(id)).findFirst().orElse(null);}
    static java.util.List<dev.craftium.core.BlockColour> snapshot(){build();var result=new java.util.ArrayList<dev.craftium.core.BlockColour>();for(var e:entries){var id=dev.craftium.compat.Compat.parse(e.id);boolean entity=BuiltInRegistries.BLOCK.getValue(id).defaultBlockState().hasBlockEntity();int flags=(e.solid?1:0)|(e.opaque?2:0)|(e.full?4:0)|(e.uniform?8:0)|(!e.id.contains("_ore")&&!e.id.contains("ancient_debris")?16:0)|(!e.id.contains("glazed_terracotta")?32:0)|(!entity?64:0);result.add(new dev.craftium.core.BlockColour(e.id,e.rgb,flags));}return result;}
    static int size(){return entries.size();}
}
