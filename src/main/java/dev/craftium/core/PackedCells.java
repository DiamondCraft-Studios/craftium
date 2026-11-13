package dev.craftium.core;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Block states by world cell, kept packed: a cell is one {@code long} (the layout of a block position: 26 bits of x, 26 of z, 12 of y)
 * and its state is a 16-bit index into a palette of state texts, so a cell costs about 10 bytes where a {@link Point} costs about 36.
 * It holds the blocks of a BuildObject that are not in the world (dormant), and the world blocks an object wrote over, to give back.
 * A state is the block state as text, for example {@code minecraft:oak_stairs[facing=north]}. A cell with a block entity (a chest, a sign) also keeps its data
 * as text (SNBT), so what a block holds is not lost while it is out of the world; few cells have any, so it is kept apart. Not thread safe.
 */
public final class PackedCells{
    /** The most states one store can name. */
    public static final int MAX_STATES=65_534;
    /** A store with nothing in it, shared: never written to. */
    public static final PackedCells EMPTY=new PackedCells(true);

    private final boolean frozen;
    private long[] keys=new long[0];
    private char[] values=new char[0]; // palette index + 1; 0 is an empty slot
    private int size;
    private final List<String> palette=new ArrayList<>();
    private final Map<String,Integer> paletteIndex=new HashMap<>();
    private final Map<Long,String> data=new HashMap<>();

    public PackedCells(){this(false);}
    private PackedCells(boolean frozen){this.frozen=frozen;}

    public int size(){return size;}
    public boolean isEmpty(){return size==0;}

    public static long pack(Point p){return pack(p.x(),p.y(),p.z());}
    public static long pack(int x,int y,int z){return ((long)(x&0x3FFFFFF)<<38)|((long)(z&0x3FFFFFF)<<12)|(y&0xFFFL);}
    public static Point unpack(long key){return new Point((int)(key>>38),(int)(key<<52>>52),(int)(key<<26>>38));}

    public boolean has(Point cell){return indexOf(pack(cell))>=0;}
    /** The state at a cell, or null. */
    public String get(Point cell){int i=indexOf(pack(cell));return i<0?null:palette.get(values[i]-1);}
    /** The block entity data of a cell as text, or null when it has none (or is not here). */
    public String data(Point cell){return data.get(pack(cell));}

    /** Sets a cell with no block entity data; a null or blank state removes it. Returns whether the store changed. */
    public boolean put(Point cell,String state){return put(cell,state,null);}

    /** Sets a cell and the data of its block entity (null or blank for none); a null or blank state removes it. Returns whether the store changed. */
    public boolean put(Point cell,String state,String blockData){
        if(frozen)throw new UnsupportedOperationException("EMPTY is shared");
        if(state==null||state.isBlank())return remove(cell);
        long key=pack(cell);int at=indexOf(key);int index=index(state)+1;
        String wanted=blockData==null||blockData.isBlank()?null:blockData;
        if(at>=0){
            boolean same=values[at]==index&&Objects.equals(data.get(key),wanted);
            if(same)return false;
            values[at]=(char)index;if(wanted==null)data.remove(key);else data.put(key,wanted);return true;
        }
        if((size+1)*4>keys.length*3)grow();
        insert(key,(char)index);size++;if(wanted!=null)data.put(key,wanted);
        return true;
    }

    /** Removes a cell. Returns whether it was there. */
    public boolean remove(Point cell){
        if(frozen)throw new UnsupportedOperationException("EMPTY is shared");
        int i=indexOf(pack(cell));if(i<0)return false;
        int mask=keys.length-1;
        // Backward-shift deletion keeps the probe sequences intact without tombstones.
        int hole=i;int j=i;
        while(true){
            j=(j+1)&mask;
            if(values[j]==0)break;
            int home=slot(keys[j],mask);
            if(((j-home)&mask)>=((j-hole)&mask)){keys[hole]=keys[j];values[hole]=values[j];hole=j;}
        }
        values[hole]=0;size--;data.remove(pack(cell));
        return true;
    }

    /** Calls {@code each} for every cell, in no particular order. */
    public void forEach(BiConsumer<Point,String> each){
        for(int i=0;i<keys.length;i++)if(values[i]!=0)each.accept(unpack(keys[i]),palette.get(values[i]-1));
    }
    /** The cells, in no particular order. */
    public List<Point> points(){var out=new ArrayList<Point>(size);for(int i=0;i<keys.length;i++)if(values[i]!=0)out.add(unpack(keys[i]));return out;}

    /** Whether both hold the same cells, states and block entity data, whatever order they were put in. */
    public boolean sameContent(PackedCells o){
        return size==o.size&&Arrays.equals(keysArray(),o.keysArray())&&paletteList().equals(o.paletteList())&&Arrays.equals(indexesArray(),o.indexesArray())
            &&Arrays.equals(dataKeysArray(),o.dataKeysArray())&&dataList().equals(o.dataList());
    }

    /** A copy that shares nothing with this one. */
    public PackedCells copy(){
        var c=new PackedCells();c.keys=keys.clone();c.values=values.clone();c.size=size;c.palette.addAll(palette);c.paletteIndex.putAll(paletteIndex);c.data.putAll(data);
        return c;
    }

    // --- saving: three plain arrays, in a fixed order, with the palette trimmed to the states in use ---

    /** The packed cells, sorted, so the same store always saves the same way. */
    public long[] keysArray(){
        var out=new long[size];int n=0;for(int i=0;i<keys.length;i++)if(values[i]!=0)out[n++]=keys[i];
        Arrays.sort(out);return out;
    }
    /** The palette of states in use, in order of first use by the sorted cells. */
    public List<String> paletteList(){return trimmed().palette;}
    /** For each cell of {@link #keysArray()}, the index into {@link #paletteList()}. */
    public int[] indexesArray(){return trimmed().indexes;}

    /** The cells that have block entity data, sorted, and the data of each in the same order. */
    public long[] dataKeysArray(){var out=data.keySet().stream().mapToLong(Long::longValue).sorted().toArray();return out;}
    public List<String> dataList(){var out=new ArrayList<String>();for(long k:dataKeysArray())out.add(data.get(k));return out;}

    private record Trim(List<String> palette,int[] indexes){}
    private Trim trimmed(){
        long[] sorted=keysArray();var used=new LinkedHashMap<String,Integer>();var indexes=new int[sorted.length];
        for(int i=0;i<sorted.length;i++)indexes[i]=used.computeIfAbsent(palette.get(values[indexOf(sorted[i])]-1),s->used.size());
        return new Trim(new ArrayList<>(used.keySet()),indexes);
    }

    /** A store from what {@link #keysArray()}, {@link #paletteList()} and {@link #indexesArray()} gave. */
    public static PackedCells of(long[] keys,List<String> palette,int[] indexes){
        if(keys.length!=indexes.length)throw new IllegalArgumentException("Cells and states differ in number");
        if(palette.size()>MAX_STATES)throw new IllegalArgumentException("Too many states");
        var out=new PackedCells();
        for(int i=0;i<keys.length;i++){
            if(indexes[i]<0||indexes[i]>=palette.size())throw new IllegalArgumentException("Bad state index "+indexes[i]);
            out.put(unpack(keys[i]),palette.get(indexes[i]));
        }
        return out;
    }

    /** As {@link #of(long[],List,int[])}, also with block entity data for some cells: {@code dataKeys} are packed cells that must be in {@code keys}. */
    public static PackedCells of(long[] keys,List<String> palette,int[] indexes,long[] dataKeys,List<String> dataTexts){
        var out=of(keys,palette,indexes);
        if(dataKeys.length!=dataTexts.size())throw new IllegalArgumentException("Data and cells differ in number");
        for(int i=0;i<dataKeys.length;i++){
            var cell=unpack(dataKeys[i]);var state=out.get(cell);
            if(state==null)throw new IllegalArgumentException("Data for a cell that is not there");
            out.put(cell,state,dataTexts.get(i));
        }
        return out;
    }

    // --- internals ---

    private int index(String state){
        Integer known=paletteIndex.get(state);if(known!=null)return known;
        if(palette.size()>=MAX_STATES)throw new IllegalArgumentException("Too many different block states");
        palette.add(state);paletteIndex.put(state,palette.size()-1);return palette.size()-1;
    }
    private static int slot(long key,int mask){long h=key*0x9E3779B97F4A7C15L;return (int)(h>>>32)&mask;}
    private int indexOf(long key){
        if(size==0)return -1;
        int mask=keys.length-1;int i=slot(key,mask);
        while(values[i]!=0){if(keys[i]==key)return i;i=(i+1)&mask;}
        return -1;
    }
    private void insert(long key,char value){
        int mask=keys.length-1;int i=slot(key,mask);
        while(values[i]!=0)i=(i+1)&mask;
        keys[i]=key;values[i]=value;
    }
    private void grow(){
        long[] oldKeys=keys;char[] oldValues=values;
        int capacity=Math.max(16,oldKeys.length*2);keys=new long[capacity];values=new char[capacity];
        for(int i=0;i<oldKeys.length;i++)if(oldValues[i]!=0)insert(oldKeys[i],oldValues[i]);
    }
}
