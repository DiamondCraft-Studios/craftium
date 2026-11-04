package dev.craftium.client;

import dev.craftium.core.*;
import java.lang.reflect.Field;
import java.util.*;

/** Reads, shows and changes one {@link Options} field by name, for the Studio inspector and the editor's Pinned tab alike. */
final class OptionFields {
    enum Kind { BOOLEAN,ENUM,VECTOR,COLOUR,NUMBER,TEXT,LIST }
    /** Vector properties shown as one space-delimited input instead of an input for each axis. */
    static final Set<String> SINGLE_LINE=new HashSet<>();
    private OptionFields(){}
    static boolean isColour(String name){return name.startsWith("ui")||name.equals("annotationColor");}
    private static Field field(String name){try{return Options.class.getField(name);}catch(NoSuchFieldException e){throw new IllegalArgumentException("Unknown property "+name,e);}}
    static boolean exists(String name){try{Options.class.getField(name);return true;}catch(NoSuchFieldException e){return false;}}
    static Kind kind(String name){
        Class<?> type=field(name).getType();
        if(type==boolean.class)return Kind.BOOLEAN;
        if(type.isEnum())return Kind.ENUM;
        if(type==Point.class)return Kind.VECTOR;
        if(List.class.isAssignableFrom(type))return Kind.LIST;
        if(isColour(name))return Kind.COLOUR;
        if(type==int.class||type==long.class||type==double.class||type==float.class)return Kind.NUMBER;
        return Kind.TEXT;
    }
    private static Object get(String name){try{return field(name).get(ClientState.options);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    private static void set(String name,Object value){try{field(name).set(ClientState.options,value);if(name.equals("flightMomentum"))ClientState.options.flightMomentumCustom=true;}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    static boolean bool(String name){return (Boolean)get(name);}
    static void toggle(String name){set(name,!bool(name));}
    /** The enum's current value as text, as the inspector shows it on its button. */
    static String choice(String name){return human(get(name).toString());}
    /** Moves an enum to its next value, or with a negative step to the one before, wrapping round at either end. */
    static void cycle(String name,int step){
        Object[] values=field(name).getType().getEnumConstants();
        set(name,values[Math.floorMod(((Enum<?>)get(name)).ordinal()+step,values.length)]);
    }
    static Point vector(String name){return (Point)get(name);}
    /** The three axes of a vector as separate inputs. */
    static String[] axes(String name){Point p=vector(name);return new String[]{Integer.toString(p.x()),Integer.toString(p.y()),Integer.toString(p.z())};}
    /** Changes one axis of a vector (0 for X, 1 for Y, 2 for Z) to the integer typed. */
    static void setAxis(String name,int axis,String input){
        Point p=vector(name);int value=Integer.parseInt(input.trim());
        set(name,new Point(axis==0?value:p.x(),axis==1?value:p.y(),axis==2?value:p.z()));
    }
    /** The value as the one text input of a property shows it. */
    static String text(String name){
        Object value=get(name);
        if(isColour(name))return String.format(Locale.ROOT,"#%08X",(Integer)value);
        if(value instanceof Point p)return p.x()+" "+p.y()+" "+p.z();
        if(value instanceof List<?> list)return String.join("; ",list.stream().map(v->((Point)v).coordinates()).toList());
        return String.valueOf(value).replace("\n","\\n");
    }
    /** Sets the property from what was typed; a value that does not parse throws and leaves the property as it was. */
    static void parse(String name,String input){
        Class<?> type=field(name).getType();Object parsed;
        if(isColour(name))parsed=(int)Long.parseUnsignedLong(input.replaceFirst("^(#|0x)",""),16);
        else if(type==int.class)parsed=Integer.parseInt(input);
        else if(type==long.class)parsed=Long.parseLong(input);
        else if(type==double.class)parsed=Double.parseDouble(input);
        else if(type==float.class)parsed=Float.parseFloat(input);
        else if(type==Point.class)parsed=point(input);
        else if(List.class.isAssignableFrom(type)){var points=new ArrayList<Point>();if(!input.isBlank())for(String token:input.split(";"))points.add(point(token));parsed=points;}
        else parsed=input.replace("\\n","\n");
        set(name,parsed);
    }
    private static Point point(String input){String[] parts=input.trim().split("[,\\s]+");if(parts.length!=3)throw new IllegalArgumentException();return new Point(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));}
    /** Names that read better than the field's own: the four ways the Magic Wand's search can spread share a "Search" prefix. */
    private static final Map<String,String> LABELS=Map.of("selectUp","Search up","selectDown","Search down","selectHorizontal","Search horizontal","selectCorners","Search corners");
    static String human(String name){String label=LABELS.get(name);if(label!=null)return label;String s=name.replaceAll("([a-z])([A-Z])","$1 $2").replace('_',' ').toLowerCase(Locale.ROOT);return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);}
    static boolean pinned(String name){return ClientState.options.pinned.contains(name);}
    static void togglePin(String name){var pins=ClientState.options.pinned;if(!pins.remove(name))pins.add(name);}
    /** The pinned properties in the order the full inspector lays them out, each once however many pages list it. */
    static List<String> pinnedInOrder(){
        var seen=new LinkedHashSet<String>();
        for(var names:StudioScreen.GROUPS.values())for(String name:names)if(pinned(name)&&exists(name))seen.add(name);
        return new ArrayList<>(seen);
    }
}
