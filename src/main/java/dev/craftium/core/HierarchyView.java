package dev.craftium.core;

import com.google.gson.*;
import java.util.*;

/**
 * What one player has chosen to see of the Hierarchy: which BuildObjects have their eye open and which are folded, kept by GUID so it holds across worlds,
 * servers and rejoins (a GUID is the same everywhere; an id is only the same in one world). It belongs to the player and is never sent to a server.
 * The newest {@link #MAX} choices are kept, so a player who makes thousands of BuildObjects does not grow the file for ever.
 */
public final class HierarchyView {
    public static final int MAX=4000;
    /** GUID to whether its eye is closed. A GUID that is not here has not been seen: it starts closed. */
    private final LinkedHashMap<String,Boolean> eyes=new LinkedHashMap<>();
    private final LinkedHashSet<String> folds=new LinkedHashSet<>();

    public boolean knowsEye(String guid){return eyes.containsKey(guid);}
    public boolean hidden(String guid){return eyes.getOrDefault(guid,true);}
    /** Returns whether this changed anything. */
    public boolean setHidden(String guid,boolean hidden){
        var was=eyes.remove(guid);eyes.put(guid,hidden); // newest last
        trim();return was==null||was!=hidden;
    }
    public boolean folded(String guid){return folds.contains(guid);}
    public boolean setFolded(String guid,boolean folded){
        boolean changed=folded?folds.add(guid):folds.remove(guid);
        trim();return changed;
    }
    private void trim(){
        while(eyes.size()>MAX)eyes.remove(eyes.keySet().iterator().next());
        while(folds.size()>MAX)folds.remove(folds.iterator().next());
    }

    public String toJson(){
        var root=new JsonObject();
        var open=new JsonArray();var closed=new JsonArray();
        for(var e:eyes.entrySet())(e.getValue()?closed:open).add(e.getKey());
        root.add("eyesOpen",open);root.add("eyesClosed",closed);
        var folded=new JsonArray();for(var g:folds)folded.add(g);root.add("folded",folded);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }
    /** Reads what {@link #toJson} wrote; anything that is not what it expects is ignored. */
    public void load(String json){
        JsonObject root;
        try{root=JsonParser.parseString(json).getAsJsonObject();}catch(RuntimeException e){return;}
        read(root,"eyesClosed",g->eyes.put(g,true));
        read(root,"eyesOpen",g->eyes.put(g,false));
        read(root,"folded",folds::add);
        trim();
    }
    private static void read(JsonObject root,String key,java.util.function.Consumer<String> into){
        if(!root.has(key)||!root.get(key).isJsonArray())return;
        for(var e:root.getAsJsonArray(key))if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString()&&e.getAsString().length()<=64)into.accept(e.getAsString());
    }
}
