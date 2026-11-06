package dev.craftium.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class SelectionCodec {
    private SelectionCodec(){}
    public static String encode(Collection<Point> points){
        if(points.size()>Selection.LIMIT)throw new IllegalArgumentException("Selection exceeds limit");
        try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(new GZIPOutputStream(bytes))){out.writeInt(points.size());for(Point p:points){out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}}return Base64.getEncoder().encodeToString(bytes.toByteArray());}
        catch(IOException e){throw new IllegalArgumentException("Cannot encode selection",e);}
    }
    public static Set<Point> decode(String encoded){
        if(encoded==null||encoded.isBlank())return Set.of();if(encoded.length()>3_000_000)throw new IllegalArgumentException("Selection packet too large");
        try(var in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))))){
            int size=in.readInt();if(size<0||size>Selection.LIMIT)throw new IllegalArgumentException("Invalid selection count");byte[] raw=in.readNBytes(size*12);if(raw.length!=size*12)throw new EOFException(); // one bulk read: an int at a time through the stream chain was most of the cost of a large selection
            var ints=java.nio.ByteBuffer.wrap(raw).asIntBuffer();var result=new LinkedHashSet<Point>((int)(size/.75f)+1);for(int i=0;i<size;i++)result.add(new Point(ints.get(),ints.get(),ints.get()));return result;
        }catch(IOException e){throw new IllegalArgumentException("Invalid selection data",e);}
    }
}
