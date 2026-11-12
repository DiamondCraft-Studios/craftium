package dev.craftium.server;

import dev.craftium.core.MissingBlockTable;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.function.Predicate;

/**
 * Turns the blocks of a chunk that no installed mod provides into placeholders before the game reads the chunk. Left alone the game
 * turns each into air and the chunk forgets what stood there; read from the saved data first, the name and properties of each go into
 * the world's {@link MissingBlockTable} and the block becomes {@code craftium:missing_block} with a block entity holding its number.
 * <p>
 * Works on the saved chunk data, in the layout the game reads, so it does not depend on how the game decodes a chunk. A chunk
 * with nothing missing costs one registry lookup per palette entry and is otherwise untouched.
 */
public final class MissingBlockRewrite {
    private MissingBlockRewrite(){}
    public static final String BLOCK="craftium:missing_block";
    /** The block entity's number in the table. */
    public static final String KEY="key";
    /** What a block entity of the missing block held, kept so it comes back with the block. */
    public static final String KEPT="kept";

    /** @return how many blocks were turned into placeholders */
    public static int apply(CompoundTag chunk,MissingBlockTable table,Predicate<String> exists){
        var sections=chunk.getListOrEmpty("sections");
        if(sections.isEmpty())return 0;
        int chunkX=chunk.getIntOr("xPos",0),chunkZ=chunk.getIntOr("zPos",0),replaced=0;
        Map<Long,CompoundTag> placed=null;
        for(int i=0;i<sections.size();i++){
            var section=sections.getCompoundOrEmpty(i);
            var states=section.getCompoundOrEmpty("block_states");
            var palette=states.getListOrEmpty("palette");
            int[] keys=null,unknown=null;int missing=0;
            for(int j=0;j<palette.size();j++){
                var entry=palette.getCompoundOrEmpty(j);
                String name=entry.getStringOr("Name","minecraft:air");
                if(exists.test(name))continue;
                if(keys==null){keys=new int[palette.size()];Arrays.fill(keys,-1);}
                var properties=new TreeMap<String,String>();
                var tag=entry.getCompoundOrEmpty("Properties");
                for(String property:tag.keySet())properties.put(property,tag.getStringOr(property,""));
                keys[j]=table.intern(new MissingBlockTable.Entry(name,properties));
                missing++;
            }
            if(keys==null)continue;
            // Every missing state reads as the same placeholder block, so a palette that listed two of them would hold the same entry twice.
            int[] indices=indices(states,palette.size());
            int placeholder=-1;int[] remap=null;
            if(missing>1){
                remap=new int[palette.size()];var packed=new ListTag();
                for(int j=0;j<palette.size();j++){
                    if(keys[j]<0){remap[j]=packed.size();packed.add(palette.getCompoundOrEmpty(j));}
                    else{if(placeholder<0){placeholder=packed.size();packed.add(placeholderEntry());}remap[j]=placeholder;}
                }
                palette=packed;
            }else for(int j=0;j<palette.size();j++)if(keys[j]>=0){palette.set(j,placeholderEntry());}
            if(placed==null)placed=blockEntities(chunk);
            int base=section.getByteOr("Y",(byte)0)*16;
            for(int cell=0;cell<4096;cell++){
                int index=indices==null?0:indices[cell];
                if(index>=keys.length||keys[index]<0)continue;
                int x=chunkX*16+(cell&15),y=base+(cell>>8),z=chunkZ*16+((cell>>4)&15);
                var entity=new CompoundTag();
                entity.putString("id",BLOCK);entity.putInt("x",x);entity.putInt("y",y);entity.putInt("z",z);entity.putInt(KEY,keys[index]);
                var old=placed.get(pack(x,y,z));
                // A block entity the block had stays, for the day its mod is back; the game would drop it as it has no type for it.
                if(old!=null)entity.put(KEPT,old);
                placed.put(pack(x,y,z),entity);
                replaced++;
            }
            if(remap!=null){
                states.put("palette",palette);
                // Written back through the new numbering, in as few bits as the shorter palette needs.
                if(palette.size()<=1)states.remove("data");
                else{int[] packed=new int[4096];for(int cell=0;cell<4096;cell++)packed[cell]=remap[indices==null?0:indices[cell]];states.putLongArray("data",pack(packed,palette.size()));}
            }
        }
        if(placed!=null){
            var list=new ListTag();
            for(var entity:placed.values())list.add(entity);
            chunk.put("block_entities",list);
        }
        return replaced;
    }

    private static CompoundTag placeholderEntry(){var entry=new CompoundTag();entry.putString("Name",BLOCK);return entry;}
    private static long pack(int x,int y,int z){return ((long)(x&0x3FFFFFF)<<38)|((long)(z&0x3FFFFFF)<<12)|(y&0xFFF);}

    /** The chunk's block entities by position, in the order they were saved, so one is replaced where it stood. */
    private static Map<Long,CompoundTag> blockEntities(CompoundTag chunk){
        var result=new LinkedHashMap<Long,CompoundTag>();
        var list=chunk.getListOrEmpty("block_entities");
        for(int i=0;i<list.size();i++){
            var entity=list.getCompoundOrEmpty(i);
            result.put(pack(entity.getIntOr("x",0),entity.getIntOr("y",0),entity.getIntOr("z",0)),entity);
        }
        return result;
    }

    /** The palette number of each of the 4096 cells, or null when the section is one block throughout (it has no data then). */
    static int[] indices(CompoundTag states,int paletteSize){
        if(paletteSize<=1)return null;
        long[] data=states.getLongArray("data").orElse(null);
        if(data==null)return null;
        int bits=bits(paletteSize),perLong=64/bits;long mask=(1L<<bits)-1;
        int[] out=new int[4096];
        // Values never straddle two longs; the spare bits at the top of each long stay unused.
        for(int cell=0;cell<4096;cell++){
            int word=cell/perLong;
            out[cell]=word<data.length?(int)((data[word]>>>((cell%perLong)*bits))&mask):0;
        }
        return out;
    }
    static long[] pack(int[] indices,int paletteSize){
        int bits=bits(paletteSize),perLong=64/bits;
        long[] out=new long[(4096+perLong-1)/perLong];
        for(int cell=0;cell<4096;cell++)out[cell/perLong]|=(long)indices[cell]<<((cell%perLong)*bits);
        return out;
    }
    static int bits(int paletteSize){return Math.max(4,32-Integer.numberOfLeadingZeros(paletteSize-1));}
}
