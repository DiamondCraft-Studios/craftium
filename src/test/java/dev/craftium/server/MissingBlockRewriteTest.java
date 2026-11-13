package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;
import dev.craftium.core.MissingBlockTable;
import net.minecraft.nbt.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MissingBlockRewriteTest {
    private static final Set<String> KNOWN=Set.of("minecraft:air","minecraft:stone",MissingBlockRewrite.BLOCK);

    private static CompoundTag entry(String name,String... properties){
        var tag=new CompoundTag();
        tag.putString("Name",name);
        if(properties.length>0){
            var p=new CompoundTag();
            for(int i=0;i<properties.length;i+=2)p.putString(properties[i],properties[i+1]);
            tag.put("Properties",p);
        }
        return tag;
    }
    /** A chunk at 1,-2 with one section at y 3 whose cells hold the given palette numbers. */
    private static CompoundTag chunk(int[] cells,CompoundTag... palette){
        var states=new CompoundTag();
        var list=new ListTag();
        for(var e:palette)list.add(e);
        states.put("palette",list);
        if(palette.length>1)states.putLongArray("data",MissingBlockRewrite.pack(cells,palette.length));
        var section=new CompoundTag();
        section.putByte("Y",(byte)3);
        section.put("block_states",states);
        var sections=new ListTag();
        sections.add(section);
        var chunk=new CompoundTag();
        chunk.putInt("xPos",1);
        chunk.putInt("zPos",-2);
        chunk.put("sections",sections);
        return chunk;
    }
    private static int[] cells(int fill){var c=new int[4096];Arrays.fill(c,fill);return c;}
    private static int[] numbers(CompoundTag chunk){
        var states=chunk.getListOrEmpty("sections").getCompoundOrEmpty(0).getCompoundOrEmpty("block_states");
        int size=states.getListOrEmpty("palette").size();
        var out=MissingBlockRewrite.indices(states,size);
        return out==null?new int[4096]:out;
    }

    @Test void aChunkWithNothingMissingIsLeftAlone(){
        var c=cells(0);
        c[5]=1;
        var chunk=chunk(c,entry("minecraft:air"),entry("minecraft:stone"));
        var before=chunk.copy();
        assertEquals(0,MissingBlockRewrite.apply(chunk,new MissingBlockTable(),KNOWN::contains));
        assertEquals(before,chunk);
    }
    @Test void oneMissingStateBecomesAPlaceholderWithABlockEntity(){
        var c=cells(0);
        c[0]=1;
        c[256+16*2+3]=1;   // x0 y0 z0, and x3 y1 z2
        var chunk=chunk(c,entry("minecraft:air"),entry("mod:marble","axis","y"));
        var table=new MissingBlockTable();
        assertEquals(2,MissingBlockRewrite.apply(chunk,table,KNOWN::contains));
        assertEquals("mod:marble[axis=y]",table.get(0).text());
        var palette=chunk.getListOrEmpty("sections").getCompoundOrEmpty(0).getCompoundOrEmpty("block_states").getListOrEmpty("palette");
        assertEquals(MissingBlockRewrite.BLOCK,palette.getCompoundOrEmpty(1).getStringOr("Name",""));
        var entities=chunk.getListOrEmpty("block_entities");
        assertEquals(2,entities.size());
        var first=entities.getCompoundOrEmpty(0);
        assertEquals(16,first.getIntOr("x",99));
        assertEquals(48,first.getIntOr("y",99));
        assertEquals(-32,first.getIntOr("z",99));
        assertEquals(0,first.getIntOr(MissingBlockRewrite.KEY,-1));
        assertEquals(MissingBlockRewrite.BLOCK,first.getStringOr("id",""));
        var second=entities.getCompoundOrEmpty(1);
        assertEquals(16+3,second.getIntOr("x",99));
        assertEquals(48+1,second.getIntOr("y",99));
        assertEquals(-32+2,second.getIntOr("z",99));
    }
    @Test void severalMissingStatesShareOnePlaceholderEntryAndKeepTheirNumbers(){
        var c=cells(0);
        c[1]=1;
        c[2]=2;
        c[3]=3;
        c[4]=1;
        var chunk=chunk(c,entry("minecraft:air"),entry("mod:a"),entry("mod:b"),entry("minecraft:stone"));
        var table=new MissingBlockTable();
        assertEquals(3,MissingBlockRewrite.apply(chunk,table,KNOWN::contains));
        var palette=chunk.getListOrEmpty("sections").getCompoundOrEmpty(0).getCompoundOrEmpty("block_states").getListOrEmpty("palette");
        assertEquals(3,palette.size(),"air, the placeholder once, stone");
        var n=numbers(chunk);
        assertEquals(0,n[0]);
        assertEquals(1,n[1]);
        assertEquals(1,n[2]);
        assertEquals(2,n[3]);
        assertEquals(1,n[4]);
        var keys=new TreeMap<Integer,Integer>();
        var entities=chunk.getListOrEmpty("block_entities");
        for(int i=0;i<entities.size();i++){
            var e=entities.getCompoundOrEmpty(i);
            keys.put(e.getIntOr("x",0)-16,e.getIntOr(MissingBlockRewrite.KEY,-1));
        }
        int a=table.find(MissingBlockTable.Entry.parse("mod:a")),b=table.find(MissingBlockTable.Entry.parse("mod:b"));
        assertEquals(Map.of(1,a,2,b,4,a),keys);
    }
    @Test void aSectionOfOnlyMissingBlocksNeedsNoData(){
        var chunk=chunk(cells(0),entry("mod:a"));
        assertEquals(4096,MissingBlockRewrite.apply(chunk,new MissingBlockTable(),KNOWN::contains));
        assertEquals(4096,chunk.getListOrEmpty("block_entities").size());
    }
    @Test void aBlockEntityTheBlockHadIsKept(){
        var c=cells(0);
        c[0]=1;
        var chunk=chunk(c,entry("minecraft:air"),entry("mod:chest"));
        var old=new CompoundTag();
        old.putString("id","mod:chest");
        old.putInt("x",16);
        old.putInt("y",48);
        old.putInt("z",-32);
        old.putString("Lock","x");
        var list=new ListTag();
        list.add(old);
        chunk.put("block_entities",list);
        MissingBlockRewrite.apply(chunk,new MissingBlockTable(),KNOWN::contains);
        var entities=chunk.getListOrEmpty("block_entities");
        assertEquals(1,entities.size());
        assertEquals(old,entities.getCompoundOrEmpty(0).getCompoundOrEmpty(MissingBlockRewrite.KEPT));
    }
    @Test void largePalettesRoundTrip(){
        var palette=new CompoundTag[40];
        var c=cells(0);
        for(int i=0;i<40;i++)palette[i]=entry(i==0?"minecraft:air":"mod:b"+i);
        for(int cell=0;cell<4096;cell++)c[cell]=cell%40;
        var chunk=chunk(c,palette);
        MissingBlockRewrite.apply(chunk,new MissingBlockTable(),KNOWN::contains);
        var n=numbers(chunk);
        for(int cell=0;cell<4096;cell++)assertEquals(cell%40==0?0:1,n[cell],"cell "+cell);
    }
}
