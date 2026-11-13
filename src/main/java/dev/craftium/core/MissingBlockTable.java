package dev.craftium.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;

/**
 * Every block state a world once held that no installed mod provides, numbered in the order it was first met. A placeholder block
 * stores only its number, so a world can hold any number of different missing states and each placed block costs one int.
 * <p>
 * The table only grows: a number is never reused or reassigned, so a block saved long ago still finds its state. It lives in one
 * text file with a state per line, the line being its number, so learning a new state is one appended line and the file never has
 * to be rewritten. Safe to use from the chunk-loading threads.
 */
public final class MissingBlockTable {
    /** A block state by name and properties, with the properties in a fixed order so equal states are equal entries. */
    public record Entry(String name,SortedMap<String,String> properties){
        public Entry{properties=Collections.unmodifiableSortedMap(new TreeMap<>(properties));}
        public static Entry of(String name,Map<String,String> properties){return new Entry(name,new TreeMap<>(properties));}
        /** Reads {@code namespace:name[a=b,c=d]}, the way it is written in a file and in a block-state command. */
        public static Entry parse(String text){
            int open=text.indexOf('[');
            if(open<0)return of(text.trim(),Map.of());
            if(!text.endsWith("]"))throw new IllegalArgumentException("Not a block state: "+text);
            var properties=new TreeMap<String,String>();
            String inside=text.substring(open+1,text.length()-1);
            if(!inside.isBlank())for(String pair:inside.split(",")){
                int eq=pair.indexOf('=');
                if(eq<=0)throw new IllegalArgumentException("Not a block state: "+text);
                properties.put(pair.substring(0,eq).trim(),pair.substring(eq+1).trim());
            }
            return new Entry(text.substring(0,open).trim(),properties);
        }
        /** {@code namespace:name} alone when there are no properties, else with them in brackets. */
        public String text(){
            if(properties.isEmpty())return name;
            var out=new StringBuilder(name).append('[');
            boolean first=true;
            for(var property:properties.entrySet()){if(!first)out.append(',');first=false;out.append(property.getKey()).append('=').append(property.getValue());}
            return out.append(']').toString();
        }
        @Override public String toString(){return text();}
    }

    private final ArrayList<Entry> entries=new ArrayList<>();
    private final HashMap<Entry,Integer> keys=new HashMap<>();
    private final Path file;
    /** Told of each state added, with its number, once it is saved. */
    private IntConsumer onAdded=key->{};

    /** A table kept in {@code file}; what the file holds is loaded, and a null file keeps the table in memory only. */
    public MissingBlockTable(Path file){
        this.file=file;
        if(file==null||!Files.exists(file))return;
        try{
            for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
                // A blank line is kept as a number of its own: the number is the line, so skipping it would shift every state after it.
                var entry=line.isBlank()?null:Entry.parse(line);
                entries.add(entry);
                if(entry!=null)keys.putIfAbsent(entry,entries.size()-1);
            }
        }catch(IOException e){throw new IllegalStateException("Cannot read the missing blocks of this world: "+file,e);}
    }
    public MissingBlockTable(){this(null);}

    public synchronized void onAdded(IntConsumer listener){onAdded=listener;}

    /** The number for {@code entry}, adding it to the table (and the file) the first time it is met. */
    public synchronized int intern(Entry entry){
        Integer known=keys.get(entry);
        if(known!=null)return known;
        int key=entries.size();
        // Saved before it is used: a block written to a chunk with a number the file never learned could not be read back.
        if(file!=null){
            try{
                Files.createDirectories(file.getParent());
                Files.writeString(file,entry.text()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }catch(IOException e){throw new IllegalStateException("Cannot save the missing blocks of this world: "+file,e);}
        }
        entries.add(entry);
        keys.put(entry,key);
        onAdded.accept(key);
        return key;
    }
    public int intern(String text){return intern(Entry.parse(text));}

    /** The state for {@code key}, or null when no such number was ever given out. */
    public synchronized Entry get(int key){return key>=0&&key<entries.size()?entries.get(key):null;}
    public synchronized int size(){return entries.size();}
    /** The number of {@code entry} if it is in the table, else -1; never adds. */
    public synchronized int find(Entry entry){Integer key=keys.get(entry);return key==null?-1:key;}
    /** A copy of the states in number order, with null where a number has no state. */
    public synchronized List<Entry> entries(){return new ArrayList<>(entries);}
}
