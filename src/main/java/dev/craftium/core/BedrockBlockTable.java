package dev.craftium.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Java block states against Bedrock block states, from {@code bedrock_blocks.txt.gz} (GeyserMC's MIT-licensed block
 * mappings, re-keyed by name; see {@code scripts/generate_bedrock_blocks.py} and THIRD-PARTY-NOTICES.md). It knows nothing
 * of Minecraft classes: states are a name and a property map, so it can be tested on its own.
 */
public final class BedrockBlockTable {
    /** A Bedrock block: a namespaced name and its states, each a {@code Byte}, {@code Integer} or {@code String}. */
    public record Bedrock(String name,SortedMap<String,Object> states){}
    /** A Java block state: a namespaced name and its properties as text. */
    public record Java(String name,SortedMap<String,String> properties){}
    private record Entry(String bedrockName,String states){}

    private static final String RESOURCE="/assets/craftium/bedrock_blocks.txt.gz";
    private static BedrockBlockTable instance;

    private final Map<String,Entry> forward=new HashMap<>();
    private final Map<String,String> reverse=new HashMap<>(),reverseByName=new HashMap<>();
    private final Map<String,Integer> rank=new HashMap<>();
    /** The Bedrock name of each Java block, whatever its state: the name a pool's processor rule matches by. */
    private final Map<String,String> names=new HashMap<>();

    /** The table shipped with the mod; the first call reads it. */
    public static synchronized BedrockBlockTable get(){
        if(instance==null){
            try(var in=BedrockBlockTable.class.getResourceAsStream(RESOURCE)){
                if(in==null)throw new IllegalStateException("The Bedrock block table is missing from this build");
                instance=new BedrockBlockTable(in);
            }catch(IOException e){throw new UncheckedIOException(e);}
        }
        return instance;
    }

    public BedrockBlockTable(InputStream gzipped)throws IOException{
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(gzipped),StandardCharsets.UTF_8))){
            String block=null,line;
            while((line=reader.readLine())!=null){
                if(line.isEmpty()||line.charAt(0)=='#')continue;
                if(line.charAt(0)=='@'){block=line.substring(1);continue;}
                if(block==null)throw new IOException("State line before any block");
                boolean preferred=line.charAt(0)=='*';if(preferred)line=line.substring(1);
                String[] parts=line.split("\t",-1);if(parts.length!=3)throw new IOException("Malformed block line");
                String bedrock=parts[1].isEmpty()?block.substring(block.indexOf(':')+1):parts[1];
                if(bedrock.indexOf(':')<0)bedrock="minecraft:"+bedrock;
                forward.put(javaKey(block,parts[0]),new Entry(bedrock,parts[2]));
                names.putIfAbsent(block,bedrock);
                // Several Java states share one Bedrock state (waterlogging, redstone power). Importing picks the likeliest.
                String key=bedrock+"|"+parts[2],candidate=javaKey(block,parts[0]);
                int score=(preferred?0:2)+(parts[0].contains("waterlogged=true")?1:0);
                Integer best=rank.get(key);
                if(best==null||score<best){rank.put(key,score);reverse.put(key,candidate);}
                Integer bestByName=rank.get("|"+bedrock);
                if(bestByName==null||score<bestByName){rank.put("|"+bedrock,score);reverseByName.put(bedrock,candidate);}
            }
        }
    }

    private static String javaKey(String name,String properties){return properties.isEmpty()?name:name+"["+properties+"]";}
    private static String javaKey(Java state){
        if(state.properties().isEmpty())return state.name();
        var out=new StringBuilder(state.name()).append('[');
        for(var entry:state.properties().entrySet()){if(out.charAt(out.length()-1)!='[')out.append(',');out.append(entry.getKey()).append('=').append(entry.getValue());}
        return out.append(']').toString();
    }

    public int size(){return forward.size();}

    /** The Bedrock name of a Java block ({@code minecraft:} is added when missing), or null when Bedrock has no such block. */
    public String bedrockNameOf(String javaName){return names.get(javaName.indexOf(':')<0?"minecraft:"+javaName:javaName);}

    /** The Bedrock block for a Java state, or null when the table has none. */
    public Bedrock toBedrock(String name,Map<String,String> properties){
        var entry=forward.get(javaKey(new Java(name,new TreeMap<>(properties))));
        if(entry==null)return null;
        var states=new TreeMap<String,Object>();
        if(!entry.states().isEmpty())for(String pair:entry.states().split(";")){
            int equals=pair.indexOf('='),colon=pair.indexOf(':',equals);String key=pair.substring(0,equals),value=pair.substring(colon+1);
            states.put(key,switch(pair.charAt(equals+1)){case 'b'->Byte.valueOf(Byte.parseByte(value));case 'i'->Integer.valueOf(Integer.parseInt(value));default->value;});
        }
        return new Bedrock(entry.bedrockName(),states);
    }

    /**
     * The Java state for a Bedrock block. When the states are not in the table, which happens with files from another game
     * version, the block's usual state stands in, and {@code exact} (if given) is cleared.
     */
    public Java toJava(String bedrockName,Map<String,Object> states,boolean[] exact){
        if(bedrockName.indexOf(':')<0)bedrockName="minecraft:"+bedrockName;
        var text=new StringBuilder();
        for(var entry:new TreeMap<>(states).entrySet()){
            if(!text.isEmpty())text.append(';');
            Object value=entry.getValue();
            text.append(entry.getKey()).append('=').append(value instanceof Byte?'b':value instanceof Integer?'i':'s').append(':').append(value);
        }
        if(exact!=null&&exact.length>0)exact[0]=true;
        String key=reverse.get(bedrockName+"|"+text);
        if(key==null){key=reverseByName.get(bedrockName);if(exact!=null&&exact.length>0)exact[0]=false;}
        return key==null?null:parse(key);
    }

    private static Java parse(String key){
        int open=key.indexOf('[');var properties=new TreeMap<String,String>();
        if(open>=0)for(String pair:key.substring(open+1,key.length()-1).split(",")){int equals=pair.indexOf('=');properties.put(pair.substring(0,equals),pair.substring(equals+1));}
        return new Java(open<0?key:key.substring(0,open),properties);
    }
}
