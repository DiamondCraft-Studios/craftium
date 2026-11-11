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
    /** The colour of the top, the sides (their average) and the underside of a block, from the textures its model puts on them. */
    record Faces(int top,int side,int bottom){}
    private static final Map<String,Faces> faceMap=new HashMap<>();
    /** What a block's faces are made from, kept so their pictures can be read when wanted. */
    private record Source(JsonArray elements,Map<String,String> textures,int tint){}
    private static final Map<String,Source> sources=new HashMap<>();
    private static final Map<String,int[]> pictures=new HashMap<>();
    private static final List<Entry> entries=new ArrayList<>();
    private static final Map<String,Integer> averages=new HashMap<>();
    private static final Map<String,JsonObject> models=new HashMap<>();
    private static Object resources;
    private BlockColours(){}
    static void build(){
        var mc=Minecraft.getInstance();if(resources==mc.getResourceManager()&&!entries.isEmpty())return;resources=mc.getResourceManager();entries.clear();averages.clear();models.clear();faceMap.clear();sources.clear();pictures.clear();
        for(var block:BuiltInRegistries.BLOCK){
            var state=block.defaultBlockState();if(state.isAir()||block.asItem()==net.minecraft.world.item.Items.AIR)continue;
            String id=BuiltInRegistries.BLOCK.getKey(block).toString();
            try{
                var key=dev.craftium.compat.Compat.parse(id);JsonObject blockstate=json(key.getNamespace(),"blockstates/"+key.getPath()+".json");
                String model=findModel(blockstate);if(model==null)continue;var textures=new LinkedHashMap<String,String>();var elements=new JsonArray[1];readModel(model,textures,0,elements);
                var paths=new LinkedHashSet<String>();for(String value:textures.values()){int attempts=0;while(value.startsWith("#")&&attempts++<16)value=textures.getOrDefault(value.substring(1),"");if(!value.isBlank()&&!value.startsWith("#"))paths.add(value);}
                if(paths.isEmpty())continue;long r=0,g=0,b=0;int n=0;
                for(String path:paths){int color=average(path);if(color<0)continue;r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255;n++;}if(n==0)continue;
                int rgb=(int)(r/n)<<16|(int)(g/n)<<8|(int)(b/n);
                int tint=ClientCompat.blockTint(mc,state,mc.player==null?BlockPos.ZERO:mc.player.blockPosition());
                if(tint!=-1)rgb=(((rgb>>16)&255)*((tint>>16)&255)/255)<<16|(((rgb>>8)&255)*((tint>>8)&255)/255)<<8|(rgb&255)*(tint&255)/255;
                boolean full=state.isCollisionShapeFullBlock(mc.level,BlockPos.ZERO),solid=!state.getCollisionShape(mc.level,BlockPos.ZERO).isEmpty();
                entries.add(new Entry(id,rgb,ColourMath.lab(rgb),full,solid,state.canOcclude(),paths.size()==1));faceMap.put(id,facesOf(elements[0],textures,tint,rgb));sources.put(id,new Source(elements[0],textures,tint));
            }catch(Exception ignored){/* Blocks without an item model or a readable texture cannot be colour matched. */}
        }
    }
    private static JsonObject json(String namespace,String path)throws Exception{try(var reader=Minecraft.getInstance().getResourceManager().getResourceOrThrow(dev.craftium.compat.Compat.id(namespace,path)).openAsReader()){return JsonParser.parseReader(reader).getAsJsonObject();}}
    private static String findModel(JsonElement value){if(value.isJsonObject()){var obj=value.getAsJsonObject();if(obj.has("model"))return obj.get("model").getAsString();for(var child:obj.entrySet()){String found=findModel(child.getValue());if(found!=null)return found;}}else if(value.isJsonArray())for(var child:value.getAsJsonArray()){String found=findModel(child);if(found!=null)return found;}return null;}
    private static void readModel(String id,Map<String,String> textures,int depth,JsonArray[] elements)throws Exception{
        if(depth>16)return;JsonObject model=models.get(id);if(model==null){var key=dev.craftium.compat.Compat.parse(id);model=json(key.getNamespace(),"models/"+key.getPath()+".json");models.put(id,model);}
        if(model.has("parent"))readModel(model.get("parent").getAsString(),textures,depth+1,elements);
        if(model.has("textures"))for(var entry:model.getAsJsonObject("textures").entrySet())textures.put(entry.getKey(),entry.getValue().getAsString());
        if(model.has("elements"))elements[0]=model.getAsJsonArray("elements"); // the most derived model that has its own elements wins
    }
    private static final String[] SIDE_FACES={"north","south","east","west"};
    /** The colours of a block's faces: the top, the underside and the average of the sides, with the plain average where the model says nothing about a face. */
    private static Faces facesOf(JsonArray elements,Map<String,String> textures,int tint,int fallback){
        int top=faceColour(elements,textures,"up",tint),bottom=faceColour(elements,textures,"down",tint);
        long r=0,g=0,b=0;int n=0;
        for(String side:SIDE_FACES){int c=faceColour(elements,textures,side,tint);if(c>=0){r+=(c>>16)&255;g+=(c>>8)&255;b+=c&255;n++;}}
        int sides=n==0?-1:(int)(r/n)<<16|(int)(g/n)<<8|(int)(b/n);
        if(sides<0)sides=top>=0?top:bottom>=0?bottom:fallback;
        if(top<0)top=sides;
        if(bottom<0)bottom=sides;
        return new Faces(top,sides,bottom);
    }
    /** The colour of one face of a model, from the first element that has that face; -1 when none does or its texture cannot be read. A face the model tints is tinted. */
    private static int faceColour(JsonArray elements,Map<String,String> textures,String direction,int tint){
        if(elements==null)return -1;
        for(var element:elements){
            var faces=element.isJsonObject()&&element.getAsJsonObject().has("faces")?element.getAsJsonObject().getAsJsonObject("faces"):null;
            if(faces==null||!faces.has(direction))continue;
            var face=faces.getAsJsonObject(direction);
            String texture=face.has("texture")?face.get("texture").getAsString():"";
            int attempts=0;while(texture.startsWith("#")&&attempts++<16)texture=textures.getOrDefault(texture.substring(1),"");
            if(texture.isEmpty())continue;
            int colour=average(texture);if(colour<0)continue;
            if(face.has("tintindex")&&tint!=-1)colour=(((colour>>16)&255)*((tint>>16)&255)/255)<<16|(((colour>>8)&255)*((tint>>8)&255)/255)<<8|(colour&255)*(tint&255)/255;
            return colour;
        }
        return -1;
    }
    /**
     * The pictures of a block's top, side and underside, each 16 by 16 pixels of 0xAARRGGBB, tinted where the model tints the face; null for a block with no
     * model to read them from. A face the model says nothing about falls back to the side, or to the block's plain colour.
     */
    static int[][] pictures(String id){
        build();var source=sources.get(id);if(source==null)return null;
        int plain=0xff000000|faceMap.get(id).side();
        int[] top=picture(source,"up"),bottom=picture(source,"down"),side=null;
        for(String name:new String[]{"north","south","east","west"}){side=picture(source,name);if(side!=null)break;}
        if(side==null)side=top!=null?top:bottom!=null?bottom:flat(plain);
        return new int[][]{top!=null?top:side,side,bottom!=null?bottom:side};
    }
    private static int[] flat(int colour){int[] out=new int[256];Arrays.fill(out,colour);return out;}
    private static int[] picture(Source source,String direction){
        if(source.elements()==null)return null;
        for(var element:source.elements()){
            var faces=element.isJsonObject()&&element.getAsJsonObject().has("faces")?element.getAsJsonObject().getAsJsonObject("faces"):null;
            if(faces==null||!faces.has(direction))continue;
            var face=faces.getAsJsonObject(direction);
            String texture=face.has("texture")?face.get("texture").getAsString():"";
            int attempts=0;while(texture.startsWith("#")&&attempts++<16)texture=source.textures().getOrDefault(texture.substring(1),"");
            if(texture.isEmpty())continue;
            var pixels=pictures.computeIfAbsent(texture,BlockColours::read);
            if(pixels.length==0)continue;
            int tint=face.has("tintindex")?source.tint():-1;
            if(tint==-1)return pixels;
            int[] out=new int[256];
            for(int i=0;i<256;i++){int c=pixels[i];out[i]=(c&0xff000000)|(((c>>16)&255)*((tint>>16)&255)/255)<<16|(((c>>8)&255)*((tint>>8)&255)/255)<<8|(c&255)*(tint&255)/255;}
            return out;
        }
        return null;
    }
    /** The first frame of a texture, shrunk or stretched to 16 by 16 by taking the nearest pixel; empty when it cannot be read. */
    private static int[] read(String texture){
        var id=dev.craftium.compat.Compat.parse(texture);
        try(var stream=Minecraft.getInstance().getResourceManager().getResourceOrThrow(dev.craftium.compat.Compat.id(id.getNamespace(),"textures/"+id.getPath()+".png")).open()){
            var image=ImageIO.read(stream);if(image==null)return new int[0];
            int frame=Math.min(image.getWidth(),image.getHeight());int[] out=new int[256];
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)out[y*16+x]=image.getRGB(x*frame/16,y*frame/16);
            return out;
        }catch(Exception e){return new int[0];}
    }
    /** A texture (such as minecraft:block/stone) as 16 by 16 pixels of 0xAARRGGBB; empty when it cannot be read. */
    static int[] texture(String name){build();return pictures.computeIfAbsent(name,BlockColours::read);}
    /** The tint the game gives a block (grass, leaves, water), or -1 for one it does not tint. */
    static int tint(String id){build();var source=sources.get(id);return source==null?-1:source.tint();}
    /** The faces of a block, or null for one with no model to read them from. */
    static Faces faces(String id){build();return faceMap.get(id);}
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
