package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrockStructureTest {
    @Test void littleEndianNbtRoundTripsEveryTag(){
        var root=new LinkedHashMap<String,Object>();
        root.put("byte",(byte)-3);root.put("short",(short)-300);root.put("int",-70000);root.put("long",1L<<40);root.put("float",1.5f);root.put("double",-2.25);root.put("string","héllo");
        root.put("ints",new BedrockNbt.IntList(new int[]{1,-1,70000}));root.put("empty",new ArrayList<Object>());
        root.put("nested",new LinkedHashMap<>(Map.of("list",List.of(1,2,3))));
        var back=BedrockNbt.read(BedrockNbt.write(root));
        assertEquals((byte)-3,back.get("byte"));assertEquals((short)-300,back.get("short"));assertEquals(-70000,back.get("int"));assertEquals(1L<<40,back.get("long"));
        assertEquals(1.5f,back.get("float"));assertEquals(-2.25,back.get("double"));assertEquals("héllo",back.get("string"));
        assertEquals(List.of(1,-1,70000),back.get("ints"));assertEquals(List.of(),back.get("empty"));
        assertEquals(List.of(1,2,3),((Map<?,?>)back.get("nested")).get("list"));
    }
    @Test void writesLittleEndianBytes(){
        // compound tag, empty name, one int "a"=1, end
        assertArrayEquals(new byte[]{10,0,0,3,1,0,'a',1,0,0,0,0},BedrockNbt.write(new LinkedHashMap<>(Map.of("a",1))));
    }
    @Test void rejectsTruncatedAndOversizedLists(){
        var bytes=BedrockNbt.write(new LinkedHashMap<>(Map.of("a",List.of(1,2,3))));
        assertThrows(IllegalArgumentException.class,()->BedrockNbt.read(Arrays.copyOf(bytes,bytes.length-4)));
        var huge=bytes.clone();huge[huge.length-18]=(byte)0x7f; // the list length, far past the data
        assertThrows(IllegalArgumentException.class,()->BedrockNbt.read(huge));
    }

    @Test void tableMapsJavaToBedrockAndBack(){
        var table=BedrockBlockTable.get();assertTrue(table.size()>30_000);
        var stone=table.toBedrock("minecraft:stone",Map.of());assertEquals("minecraft:stone",stone.name());assertTrue(stone.states().isEmpty());
        var door=table.toBedrock("minecraft:oak_door",Map.of("facing","north","half","upper","hinge","left","open","false","powered","false"));
        assertEquals("minecraft:wooden_door",door.name());assertEquals((byte)1,door.states().get("upper_block_bit"));assertEquals("east",door.states().get("minecraft:cardinal_direction"));
        var stairs=table.toBedrock("minecraft:oak_stairs",Map.of("facing","north","half","top","shape","straight","waterlogged","false"));
        assertEquals((byte)1,stairs.states().get("upside_down_bit"));assertEquals(3,stairs.states().get("weirdo_direction"));
        var exact=new boolean[1];var java=table.toJava(stairs.name(),stairs.states(),exact);
        assertTrue(exact[0]);assertEquals("minecraft:oak_stairs",java.name());assertEquals("false",java.properties().get("waterlogged"),"importing prefers the dry state");
        assertEquals("north",java.properties().get("facing"));assertEquals("top",java.properties().get("half"));
    }
    @Test void unknownBedrockStatesFallBackToTheUsualState(){
        var exact=new boolean[1];var java=BedrockBlockTable.get().toJava("minecraft:stone",Map.of("no_such_state",(byte)1),exact);
        assertFalse(exact[0]);assertEquals("minecraft:stone",java.name());
        assertNull(BedrockBlockTable.get().toJava("minecraft:not_a_block",Map.of(),null));
    }

    @Test void previewSurvivesItsEncoding(){
        var cells=new int[2*3*4];Arrays.fill(cells,StructurePreview.VOID);cells[5]=0;cells[6]=0;cells[7]=StructurePreview.AIR;cells[23]=1;
        var preview=new StructurePreview(2,3,4,List.of(new StructurePreview.Block("minecraft:stone",new int[]{0,0,0,16,16,16},true),new StructurePreview.Block("minecraft:torch",new int[]{7,0,7,9,10,9},false)),cells,List.of("note"),3,new int[]{2,3,4});
        var back=StructurePreview.decode(preview.encode());
        assertArrayEquals(cells,back.cells());assertEquals(preview.palette().get(1).name(),back.palette().get(1).name());assertArrayEquals(new int[]{7,0,7,9,10,9},back.palette().get(1).bounds());
        assertEquals(List.of("note"),back.notes());assertEquals(4,back.sizeZ());
        assertThrows(IllegalArgumentException.class,()->StructurePreview.decode("AAAA"));
    }
    @Test void rasterDrawsBlocksAndRespondsToTheCamera(){
        var cells=new int[3*3*3];Arrays.fill(cells,0);
        var preview=new StructurePreview(3,3,3,List.of(new StructurePreview.Block("minecraft:stone",new int[]{0,0,0,16,16,16},true)),cells,List.of(),27,new int[]{3,3,3});
        int w=96,h=96;int[] a=new int[w*h],b=new int[w*h];
        StructureRaster.render(preview,new int[]{0xff808080},new StructureRaster.View(30,30,1,false,false,false),a,w,h);
        StructureRaster.render(preview,new int[]{0xff808080},new StructureRaster.View(120,30,1,false,false,false),b,w,h);
        long drawn=Arrays.stream(a).filter(p->p!=StructureRaster.BACKGROUND).count();
        assertTrue(drawn>500&&drawn<w*h,"the cube covers part of the view: "+drawn);
        assertFalse(Arrays.equals(a,b));
        var voided=new int[27];Arrays.fill(voided,StructurePreview.VOID);
        var empty=new StructurePreview(3,3,3,preview.palette(),voided,List.of(),0,new int[]{3,3,3});var c=new int[w*h];
        StructureRaster.render(empty,new int[]{0xff808080},new StructureRaster.View(30,30,1,false,false,false),c,w,h);
        assertEquals(0,Arrays.stream(c).filter(p->p!=StructureRaster.BACKGROUND).count());
        StructureRaster.render(empty,new int[]{0xff808080},new StructureRaster.View(30,30,1,true,true,false),c,w,h);
        assertTrue(Arrays.stream(c).anyMatch(p->p==StructureRaster.VOID_MARK||p==StructureRaster.BOUNDS),"void cells and the bounding box show on request");
    }
    @Test void boundingBoxIsHiddenBehindBlocksAndAxesAreColoured(){
        // A 1-block cube in front of the back edges of a 6-wide box: with depth testing the box must lose pixels to it.
        var cells=new int[6*6*6];Arrays.fill(cells,StructurePreview.VOID);for(int y=2;y<4;y++)for(int z=2;z<4;z++)for(int x=2;x<4;x++)cells[(y*6+z)*6+x]=0;
        var preview=new StructurePreview(6,6,6,List.of(new StructurePreview.Block("minecraft:stone",new int[]{0,0,0,16,16,16},true)),cells,List.of(),8,new int[]{6,6,6});
        int w=160,h=160;int[] without=new int[w*h],with=new int[w*h];
        StructureRaster.render(preview,new int[]{0xff808080},new StructureRaster.View(30,30,1,false,false,false),without,w,h);
        StructureRaster.render(preview,new int[]{0xff808080},new StructureRaster.View(30,30,1,true,false,true),with,w,h);
        long cubeBefore=Arrays.stream(without).filter(p->p==0xff808080||p!=StructureRaster.BACKGROUND).count();
        long cubeAfter=0;for(int i=0;i<with.length;i++)if(without[i]!=StructureRaster.BACKGROUND&&with[i]!=without[i])cubeAfter++;
        assertTrue(cubeBefore>0);assertTrue(cubeAfter<cubeBefore/20,"lines are drawn over "+cubeAfter+" of the cube's "+cubeBefore+" pixels");
        for(int colour:new int[]{StructureRaster.AXIS_X,StructureRaster.AXIS_Y,StructureRaster.AXIS_Z}){final int c=colour;assertTrue(Arrays.stream(with).anyMatch(p->p==c),"axis colour "+Integer.toHexString(c)+" is drawn");}
        assertTrue(Arrays.stream(with).anyMatch(p->p==StructureRaster.BOUNDS));
    }
    @Test void settingsClampAndNameFiles(){
        var s=new StructureSettings();s.name="Bad Name!";s.rotation=-90;s.integrity=400;s.sizeX=9999;s.mirror="sideways";s.validate();
        assertEquals("structure",s.name);assertEquals(270,s.rotation);assertEquals(100,s.integrity);assertEquals(StructureSettings.MAX_SIZE,s.sizeX);assertEquals("none",s.mirror);
        s.name="my_pack:castle/keep";s.validate();assertEquals("my_pack",s.namespace());assertEquals("castle/keep",s.path());assertEquals("keep.nbt",s.fileName());
        s.format=StructureSettings.BEDROCK;assertEquals("keep.mcstructure",s.fileName());
    }
}
