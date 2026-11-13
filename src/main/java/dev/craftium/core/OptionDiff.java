package dev.craftium.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * What changed in the {@link Options} between two moments, and the way to put it back: every setting is a public field, so a snapshot is the field values (lists, sets,
 * maps and arrays copied so a later change to them does not show in it), and a change is the fields whose values differ. The client's option history is made of these.
 */
public final class OptionDiff {
    private OptionDiff(){}
    private static final List<Field> FIELDS=new ArrayList<>();
    static{
        for(Field f:Options.class.getFields()){
            int m=f.getModifiers();
            if(Modifier.isStatic(m)||Modifier.isFinal(m)||Modifier.isTransient(m))continue;
            FIELDS.add(f);
        }
    }
    /** The names of the settings the snapshot takes. */
    public static List<String> names(){return FIELDS.stream().map(Field::getName).toList();}

    /** The settings as they are now. */
    public static Map<String,Object> snapshot(Options options){
        var out=new LinkedHashMap<String,Object>();
        for(Field f:FIELDS){try{out.put(f.getName(),copy(f.get(options)));}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
        return out;
    }
    /** The settings that differ between two snapshots, as {@code before} and {@code after} values of just those. Both empty when nothing changed. */
    public record Change(Map<String,Object> before,Map<String,Object> after){
        public boolean isEmpty(){return after.isEmpty();}
    }
    public static Change between(Map<String,Object> before,Map<String,Object> after){
        var b=new LinkedHashMap<String,Object>();var a=new LinkedHashMap<String,Object>();
        for(var e:after.entrySet()){
            Object was=before.get(e.getKey());
            if(!same(was,e.getValue())){b.put(e.getKey(),was);a.put(e.getKey(),e.getValue());}
        }
        return new Change(b,a);
    }
    /** Puts these values into the options, then has them validated as any loaded settings are. A name that is no setting is skipped. */
    public static void apply(Options options,Map<String,Object> values){
        for(Field f:FIELDS){
            if(!values.containsKey(f.getName()))continue;
            try{f.set(options,copy(values.get(f.getName())));}catch(IllegalAccessException|IllegalArgumentException e){/* a value of another kind is left out */}
        }
        options.validate();
    }

    private static boolean same(Object a,Object b){
        if(a instanceof Object[] x&&b instanceof Object[] y)return Arrays.deepEquals(x,y);
        if(a instanceof int[] x&&b instanceof int[] y)return Arrays.equals(x,y);
        if(a instanceof double[] x&&b instanceof double[] y)return Arrays.equals(x,y);
        return Objects.equals(a,b);
    }
    private static Object copy(Object v){
        if(v instanceof List<?> l)return new ArrayList<>(l);
        if(v instanceof Set<?> s)return new LinkedHashSet<>(s);
        if(v instanceof Map<?,?> m)return new LinkedHashMap<>(m);
        if(v instanceof int[] a)return a.clone();
        if(v instanceof double[] a)return a.clone();
        if(v instanceof Object[] a)return a.clone();
        return v;
    }
}
