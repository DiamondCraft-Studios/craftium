package dev.craftium.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bedrock's flavour of NBT: little-endian and uncompressed, as in {@code .mcstructure} files. Compounds are
 * {@code Map<String,Object>}, lists are {@code List<Object>} and scalars are {@code Byte}, {@code Short},
 * {@code Integer}, {@code Long}, {@code Float}, {@code Double} and {@code String}. A list takes its element type from its
 * first element, and an empty list is written with the End type, which Bedrock accepts.
 */
public final class BedrockNbt {
    private static final int END=0,BYTE=1,SHORT=2,INT=3,LONG=4,FLOAT=5,DOUBLE=6,BYTE_ARRAY=7,STRING=8,LIST=9,COMPOUND=10,INT_ARRAY=11,LONG_ARRAY=12,MAX_DEPTH=64;
    private BedrockNbt(){}

    /** A list of ints held as an array, for the long index lists of a structure, which would cost far more as boxed numbers. */
    public record IntList(int[] values){}

    public static byte[] write(Map<String,Object> root){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeByte(COMPOUND);string(out,"");compound(out,root,0);return bytes.toByteArray();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static Map<String,Object> read(byte[] data){
        try{
            var in=new Input(data);int type=in.u8();
            if(type!=COMPOUND)throw new IllegalArgumentException("Not a Bedrock NBT compound");
            in.string();return compound(in,0);
        }catch(IOException e){throw new IllegalArgumentException("Truncated Bedrock NBT",e);}
    }

    private static void string(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>65535)throw new IllegalArgumentException("NBT string too long");u16(out,bytes.length);out.write(bytes);}
    private static void u16(DataOutputStream out,int v)throws IOException{out.writeByte(v);out.writeByte(v>>8);}
    private static void int_(DataOutputStream out,int v)throws IOException{out.writeInt(Integer.reverseBytes(v));}
    private static void long_(DataOutputStream out,long v)throws IOException{out.writeLong(Long.reverseBytes(v));}

    private static int tag(Object value){
        if(value instanceof Byte)return BYTE;if(value instanceof Short)return SHORT;if(value instanceof Integer)return INT;if(value instanceof Long)return LONG;
        if(value instanceof Float)return FLOAT;if(value instanceof Double)return DOUBLE;if(value instanceof String)return STRING;if(value instanceof List<?>||value instanceof IntList)return LIST;
        if(value instanceof Map<?,?>)return COMPOUND;if(value instanceof int[])return INT_ARRAY;if(value instanceof byte[])return BYTE_ARRAY;
        throw new IllegalArgumentException("Unsupported NBT value "+(value==null?"null":value.getClass().getSimpleName()));
    }
    @SuppressWarnings("unchecked")
    private static void compound(DataOutputStream out,Map<String,Object> map,int depth)throws IOException{
        if(depth>MAX_DEPTH)throw new IllegalArgumentException("NBT nests too deeply");
        for(var entry:map.entrySet()){out.writeByte(tag(entry.getValue()));string(out,entry.getKey());payload(out,entry.getValue(),depth);}
        out.writeByte(END);
    }
    private static void payload(DataOutputStream out,Object value,int depth)throws IOException{
        switch(tag(value)){
            case BYTE->out.writeByte((Byte)value);
            case SHORT->u16(out,(Short)value);
            case INT->int_(out,(Integer)value);
            case LONG->long_(out,(Long)value);
            case FLOAT->int_(out,Float.floatToIntBits((Float)value));
            case DOUBLE->long_(out,Double.doubleToLongBits((Double)value));
            case STRING->string(out,(String)value);
            case BYTE_ARRAY->{var array=(byte[])value;int_(out,array.length);out.write(array);}
            case INT_ARRAY->{var array=(int[])value;int_(out,array.length);for(int v:array)int_(out,v);}
            case LIST->{
                if(value instanceof IntList ints){out.writeByte(INT);int_(out,ints.values().length);for(int v:ints.values())int_(out,v);break;}
                var list=(List<?>)value;int type=list.isEmpty()?END:tag(list.getFirst());out.writeByte(type);int_(out,list.size());
                for(var item:list){if(tag(item)!=type)throw new IllegalArgumentException("Mixed NBT list");payload(out,item,depth+1);}
            }
            case COMPOUND->{@SuppressWarnings("unchecked") var map=(Map<String,Object>)value;compound(out,map,depth+1);}
            default->throw new IllegalArgumentException("Unsupported NBT value");
        }
    }

    /** Reads with a bound on every length, so a hostile file cannot make a huge allocation. */
    private static final class Input {
        private final byte[] data;private int at;
        Input(byte[] data){this.data=data;}
        int remaining(){return data.length-at;}
        int u8()throws IOException{if(at>=data.length)throw new EOFException();return data[at++]&255;}
        int u16()throws IOException{int a=u8();return a|u8()<<8;}
        int i32()throws IOException{int a=u16();return a|u16()<<16;}
        long i64()throws IOException{long a=i32()&0xffffffffL;return a|(long)i32()<<32;}
        String string()throws IOException{int n=u16();if(n>remaining())throw new EOFException();var value=new String(data,at,n,StandardCharsets.UTF_8);at+=n;return value;}
        int length(int elementBytes)throws IOException{int n=i32();if(n<0||(long)n*elementBytes>remaining())throw new EOFException();return n;}
        byte[] bytes(int n){var copy=Arrays.copyOfRange(data,at,at+n);at+=n;return copy;}
    }
    private static Map<String,Object> compound(Input in,int depth)throws IOException{
        if(depth>MAX_DEPTH)throw new IllegalArgumentException("NBT nests too deeply");
        var map=new LinkedHashMap<String,Object>();
        for(int type=in.u8();type!=END;type=in.u8()){String key=in.string();map.put(key,payload(in,type,depth));}
        return map;
    }
    private static Object payload(Input in,int type,int depth)throws IOException{
        return switch(type){
            case BYTE->(byte)in.u8();
            case SHORT->(short)in.u16();
            case INT->in.i32();
            case LONG->in.i64();
            case FLOAT->Float.intBitsToFloat(in.i32());
            case DOUBLE->Double.longBitsToDouble(in.i64());
            case STRING->in.string();
            case BYTE_ARRAY->in.bytes(in.length(1));
            case INT_ARRAY->{int n=in.length(4);var array=new int[n];for(int i=0;i<n;i++)array[i]=in.i32();yield array;}
            case LONG_ARRAY->{int n=in.length(8);for(int i=0;i<n;i++)in.i64();yield new int[0];}
            case LIST->{
                if(depth>MAX_DEPTH)throw new IllegalArgumentException("NBT nests too deeply");
                int item=in.u8();int n=in.length(item==END?0:1);var list=new ArrayList<Object>(Math.min(n,4096));
                for(int i=0;i<n;i++)list.add(payload(in,item,depth+1));
                yield list;
            }
            case COMPOUND->compound(in,depth+1);
            default->throw new IllegalArgumentException("Unknown NBT tag "+type);
        };
    }
}
