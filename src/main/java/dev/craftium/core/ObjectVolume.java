package dev.craftium.core;

import java.util.*;

/**
 * The blocks a lifted BuildObject owns, by cell local to the object. They are not in the world, so the world is never
 * touched by them. A state is the block state as text, for example {@code minecraft:oak_stairs[facing=north]}.
 */
public final class ObjectVolume{
    private final Map<Point,String> cells=new HashMap<>();

    public String get(Point local){return cells.get(local);}
    public boolean has(Point local){return cells.containsKey(local);}
    public int size(){return cells.size();}
    public boolean isEmpty(){return cells.isEmpty();}
    public Set<Point> points(){return Collections.unmodifiableSet(cells.keySet());}
    public Map<Point,String> cells(){return Collections.unmodifiableMap(cells);}

    /** Sets a cell; a null or blank state removes it. Returns the state it had. */
    public String set(Point local,String state){
        if(state==null||state.isBlank())return cells.remove(local);
        return cells.put(local,state);
    }
    public String remove(Point local){return cells.remove(local);}

    /** The smallest box around the cells as {x0,y0,z0,x1,y1,z1}, or null when empty. */
    public int[] bounds(){
        if(cells.isEmpty())return null;
        int[] b={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(Point p:cells.keySet()){
            b[0]=Math.min(b[0],p.x());b[1]=Math.min(b[1],p.y());b[2]=Math.min(b[2],p.z());
            b[3]=Math.max(b[3],p.x());b[4]=Math.max(b[4],p.y());b[5]=Math.max(b[5],p.z());
        }
        return b;
    }

    /** One line per cell, {@code x y z state}, sorted so the same volume always gives the same text. */
    public String serialize(){
        var out=new StringBuilder();
        cells.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<Point,String> e)->e.getKey().y())
                .thenComparingInt(e->e.getKey().z()).thenComparingInt(e->e.getKey().x()))
            .forEach(e->out.append(e.getKey().x()).append(' ').append(e.getKey().y()).append(' ').append(e.getKey().z())
                .append(' ').append(e.getValue()).append('\n'));
        return out.toString();
    }

    public static ObjectVolume parse(String text){
        var v=new ObjectVolume();
        for(String line:text.split("\n")){
            if(line.isBlank())continue;
            String[] f=line.split(" ",4);
            if(f.length<4)throw new IllegalArgumentException("Bad cell line: "+line);
            v.set(new Point(Integer.parseInt(f[0]),Integer.parseInt(f[1]),Integer.parseInt(f[2])),f[3]);
        }
        return v;
    }
}
