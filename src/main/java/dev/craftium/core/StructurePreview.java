package dev.craftium.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * What the export pop-up draws: the structure as the file will hold it, sent from the server so the preview never differs
 * from the export. Each cell is {@link #VOID} (nothing is placed there), {@link #AIR} (air is placed) or an index into
 * {@link #palette}.
 */
public record StructurePreview(int sizeX,int sizeY,int sizeZ,List<Block> palette,int[] cells,List<String> notes,int blocks){
    public static final int VOID=-1,AIR=-2,MAX_VOLUME=4_000_000;
    /** A block type: {@code bounds} is its shape as {@code minX,minY,minZ,maxX,maxY,maxZ} in sixteenths; {@code occludes} means it hides the faces it touches. */
    public record Block(String name,int[] bounds,boolean occludes){}

    public int index(int x,int y,int z){return (y*sizeZ+z)*sizeX+x;}

    public String encode(){
        try{
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(new GZIPOutputStream(bytes))){
                out.writeByte(1);out.writeShort(sizeX);out.writeShort(sizeY);out.writeShort(sizeZ);out.writeInt(blocks);
                out.writeByte(notes.size());for(String note:notes)out.writeUTF(note.length()>200?note.substring(0,200):note);
                out.writeShort(palette.size());
                for(var block:palette){out.writeUTF(block.name());for(int v:block.bounds())out.writeByte(v);out.writeBoolean(block.occludes());}
                // Runs of equal cells: a structure is mostly long stretches of one block or of nothing.
                int i=0;while(i<cells.length){int value=cells[i],run=1;while(i+run<cells.length&&cells[i+run]==value)run++;varint(out,value+2);varint(out,run);i+=run;}
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static void varint(DataOutput out,int value)throws IOException{while((value&~0x7f)!=0){out.writeByte((value&0x7f)|0x80);value>>>=7;}out.writeByte(value);}
    private static int varint(DataInput in)throws IOException{int value=0,shift=0,b;do{b=in.readUnsignedByte();value|=(b&0x7f)<<shift;shift+=7;}while((b&0x80)!=0&&shift<35);return value;}

    public static StructurePreview decode(String text){
        try(var in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text))))){
            if(in.readUnsignedByte()!=1)throw new IllegalArgumentException("Unknown preview version");
            int sx=in.readUnsignedShort(),sy=in.readUnsignedShort(),sz=in.readUnsignedShort(),blocks=in.readInt();long volume=(long)sx*sy*sz;
            if(volume<0||volume>MAX_VOLUME)throw new IllegalArgumentException("Preview too large");
            var notes=new ArrayList<String>();for(int n=in.readUnsignedByte();n>0;n--)notes.add(in.readUTF());
            int count=in.readUnsignedShort();var palette=new ArrayList<Block>(count);
            for(int i=0;i<count;i++){String name=in.readUTF();var bounds=new int[6];for(int b=0;b<6;b++)bounds[b]=Math.clamp(in.readUnsignedByte(),0,16);palette.add(new Block(name,bounds,in.readBoolean()));}
            var cells=new int[(int)volume];int at=0;
            while(at<cells.length){int value=varint(in)-2,run=varint(in);if(run<1||run>cells.length-at||value>=count)throw new IllegalArgumentException("Corrupt preview");Arrays.fill(cells,at,at+run,value);at+=run;}
            return new StructurePreview(sx,sy,sz,List.copyOf(palette),cells,List.copyOf(notes),blocks);
        }catch(IOException|IllegalArgumentException e){throw new IllegalArgumentException("Could not read the structure preview: "+e.getMessage(),e);}
    }
}
