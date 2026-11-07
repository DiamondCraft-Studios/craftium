package dev.craftium.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Selections (and other sets of block positions) as text for a packet: gzip, then base64.
 * <p>
 * The first int says how the positions follow. A negative one ({@link #RUNS}) is the current format: the positions in their own order, written
 * as runs of consecutive blocks along an axis (a box is one run per row, a few thousand records for a million blocks, where raw coordinates
 * were 12 bytes a block and did not shrink with the shape). A count of zero or more is the older format, raw coordinates, which is still read.
 */
public final class SelectionCodec {
    /** Marks the run format; the older format starts with a count, which is never negative. */
    private static final int RUNS = -2;
    private SelectionCodec(){}

    public static String encode(Collection<Point> points){
        if(points.size()>Selection.LIMIT)throw new IllegalArgumentException("Selection exceeds limit");
        try{
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(new GZIPOutputStream(bytes))){out.writeInt(RUNS);out.writeInt(points.size());writeRuns(out,points);}
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(IOException e){throw new IllegalArgumentException("Cannot encode selection",e);}
    }

    /** Runs of positions that follow each other by one block along x, y or z, as records of axis, length and where the run starts. */
    private static void writeRuns(DataOutputStream out,Collection<Point> points)throws IOException{
        Point previous=new Point(0,0,0),start=null,last=null;int axis=0,length=0;
        for(Point p:points){
            if(start!=null){
                int along=step(last,p);
                if(along!=0&&(axis==0||along==axis)){axis=along;length++;last=p;continue;}
                record(out,previous,start,axis,length);previous=start;
            }
            start=p;last=p;axis=0;length=1;
        }
        if(start!=null)record(out,previous,start,axis,length);
    }

    private static void record(DataOutputStream out,Point previous,Point start,int axis,int length)throws IOException{
        out.writeByte(axis);
        if(axis!=0)varint(out,length);
        varint(out,zigzag((long)start.x()-previous.x()));varint(out,zigzag((long)start.y()-previous.y()));varint(out,zigzag((long)start.z()-previous.z()));
    }

    /** 1, 2 or 3 when {@code next} is one block further along x, y or z than {@code last}, else 0. */
    private static int step(Point last,Point next){
        int dx=next.x()-last.x(),dy=next.y()-last.y(),dz=next.z()-last.z();
        if(dx==1&&dy==0&&dz==0)return 1;
        if(dx==0&&dy==1&&dz==0)return 2;
        if(dx==0&&dy==0&&dz==1)return 3;
        return 0;
    }

    public static Set<Point> decode(String encoded){
        if(encoded==null||encoded.isBlank())return Set.of();if(encoded.length()>16_000_000)throw new IllegalArgumentException("Selection packet too large");
        try(var in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))))){
            int first=in.readInt();
            if(first==RUNS)return readRuns(in);
            int size=first;if(size<0||size>Selection.LIMIT)throw new IllegalArgumentException("Invalid selection count");byte[] raw=in.readNBytes(size*12);if(raw.length!=size*12)throw new EOFException(); // one bulk read: a
            var ints=java.nio.ByteBuffer.wrap(raw).asIntBuffer();var result=new LinkedHashSet<Point>((int)(size/.75f)+1);for(int i=0;i<size;i++)result.add(new Point(ints.get(),ints.get(),ints.get()));return result;
        }catch(IOException e){throw new IllegalArgumentException("Invalid selection data",e);}
    }

    private static Set<Point> readRuns(DataInputStream in)throws IOException{
        int count=in.readInt();if(count<0||count>Selection.LIMIT)throw new IllegalArgumentException("Invalid selection count");
        var result=new LinkedHashSet<Point>((int)(count/.75f)+1);
        long x=0,y=0,z=0;int emitted=0;
        while(emitted<count){
            int axis=in.readUnsignedByte();if(axis>3)throw new IllegalArgumentException("Invalid selection data");
            int length=axis==0?1:(int)varint(in);if(length<1||length>count-emitted)throw new IllegalArgumentException("Invalid selection run");
            x+=unzigzag(varint(in));y+=unzigzag(varint(in));z+=unzigzag(varint(in));
            if(x<Integer.MIN_VALUE||x>Integer.MAX_VALUE||y<Integer.MIN_VALUE||y>Integer.MAX_VALUE||z<Integer.MIN_VALUE||z>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid selection position");
            for(int i=0;i<length;i++)result.add(new Point((int)(x+(axis==1?i:0)),(int)(y+(axis==2?i:0)),(int)(z+(axis==3?i:0))));
            emitted+=length;
        }
        return result;
    }

    private static long zigzag(long v){return (v<<1)^(v>>63);}
    private static long unzigzag(long v){return (v>>>1)^-(v&1);}
    private static void varint(DataOutputStream out,long value)throws IOException{while((value&~0x7FL)!=0){out.writeByte((int)((value&0x7F)|0x80));value>>>=7;}out.writeByte((int)value);}
    private static long varint(DataInputStream in)throws IOException{long result=0;for(int shift=0;shift<70;shift+=7){int b=in.readUnsignedByte();result|=(long)(b&0x7F)<<shift;if((b&0x80)==0)return result;}throw new IllegalArgumentException("Invalid selection data");}
}
