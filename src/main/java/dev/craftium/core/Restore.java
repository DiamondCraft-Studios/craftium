package dev.craftium.core;

import java.util.*;

/**
 * What undo and redo hand back to the editor besides blocks: the client-side state an edit changed, such as the builder's box,
 * the handles of a box selection or the shape waiting to be built. It is a set of keys and values, sent as {@code key=value;key=value}.
 * Points are {@code x,y,z} and are joined with {@code /}.
 */
public final class Restore {
    private Restore(){}
    public static String encode(Map<String,String> values){
        var out=new StringJoiner(";");
        for(var e:values.entrySet())out.add(e.getKey()+"="+e.getValue());
        return out.toString();
    }
    public static Map<String,String> parse(String text){
        var values=new LinkedHashMap<String,String>();
        if(text==null||text.isEmpty())return values;
        for(String part:text.split(";")){int at=part.indexOf('=');if(at>0)values.put(part.substring(0,at),part.substring(at+1));}
        return values;
    }
    public static String points(Point... points){
        var out=new StringJoiner("/");
        for(var p:points)out.add(p.x()+","+p.y()+","+p.z());
        return out.toString();
    }
    /** The points in the text, or an empty list when it is not made of points. */
    public static List<Point> parsePoints(String text){
        var points=new ArrayList<Point>();
        try{
            for(String part:text.split("/")){String[] v=part.split(",");if(v.length!=3)return List.of();points.add(new Point(Integer.parseInt(v[0].trim()),Integer.parseInt(v[1].trim()),Integer.parseInt(v[2].trim())));}
        }catch(NumberFormatException e){return List.of();}
        return points;
    }
}
