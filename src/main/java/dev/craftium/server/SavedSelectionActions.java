package dev.craftium.server;

import dev.craftium.core.Point;
import dev.craftium.core.SavedSelections;
import dev.craftium.net.Packets;
import java.util.List;

/** The actions of the Hierarchy window, named {@code saved_selection:<verb>[:<id>[:<name>]]}. They only ever touch the saved selections and swap the live one in and out; no block is changed. */
final class SavedSelectionActions {
    private SavedSelectionActions(){}
    static final String PREFIX="saved_selection:";

    /** Runs the action and returns the message to show. Throws IllegalArgumentException for a request that makes no sense. */
    static String run(Session s,String action){
        var part=action.substring(PREFIX.length()).split(":",3);var saved=s.saved;
        switch(part[0]){
            case "save"->{var entry=saved.create(s.selection);return "Created "+entry.name();}
            case "view"->{saved.view(id(part),s.selection);return "Showing "+saved.active().name();}
            case "edit"->{saved.edit(id(part),s.selection);return "Editing "+saved.active().name();}
            case "release"->{saved.release(s.selection);return "Your selection is back";}
            case "delete"->{var entry=saved.delete(id(part),s.selection);return "Deleted "+entry.name();}
            case "place"->{ // place:<id>:<parent id, 0 for the top>:<child number among the parent's others>
                var where=part.length<3?new String[0]:part[2].split(":");
                try{if(where.length!=2)throw new NumberFormatException();saved.place(id(part),Integer.parseInt(where[0]),Integer.parseInt(where[1]));}catch(NumberFormatException e){throw new IllegalArgumentException("Give a parent and a place");}
                return "Moved";
            }
            case "rename"->{if(part.length<3)throw new IllegalArgumentException("Give a name");saved.rename(id(part),part[2]);return "Renamed";}
            default->throw new IllegalArgumentException("Unknown BuildObject action "+part[0]);
        }
    }
    /** Whether the action changes the BuildObjects themselves (and so goes in the history), as against only what is being looked at. */
    static boolean changesHierarchy(String action){return switch(verb(action)){case "save","delete","rename","place"->true;default->false;};}
    /** What the history calls the action: "Undid Create BuildObject". */
    static String historyName(String action){
        return switch(verb(action)){case "save"->"Create BuildObject";case "delete"->"Delete BuildObject";case "rename"->"Rename BuildObject";case "place"->"Move BuildObject";default->"BuildObject";};
    }
    private static String verb(String action){return action.startsWith(PREFIX)?action.substring(PREFIX.length()).split(":",2)[0]:"";}
    private static int id(String[] part){
        if(part.length<2)throw new IllegalArgumentException("Pick a BuildObject");
        try{return Integer.parseInt(part[1]);}catch(NumberFormatException e){throw new IllegalArgumentException("Pick a BuildObject");}
    }

    /** What the client is shown of them: the list in order, with where each one is and which is being looked at. */
    static List<Packets.SavedSelection> list(Session s){return list(s,false);}
    private static List<Packets.SavedSelection> list(Session s,boolean withData){
        var out=new java.util.ArrayList<Packets.SavedSelection>();
        for(var e:s.saved.entries()){
            Point min=e.points().isEmpty()?new Point(0,0,0):new Point(e.points().stream().mapToInt(Point::x).min().getAsInt(),e.points().stream().mapToInt(Point::y).min().getAsInt(),e.points().stream().mapToInt(Point::z).min().getAsInt());
            Point max=e.points().isEmpty()?min:new Point(e.points().stream().mapToInt(Point::x).max().getAsInt(),e.points().stream().mapToInt(Point::y).max().getAsInt(),e.points().stream().mapToInt(Point::z).max().getAsInt());
            out.add(new Packets.SavedSelection(e.id(),e.name(),e.size(),min,max,e==s.saved.active(),e==s.saved.active()&&s.saved.editing(),withData?data(s,e):null,e.guid(),e.parentId()));
        }
        return out;
    }
    /** The list for the status that is going to this player, or null when they already have the current one. Asking marks it as sent. */
    static List<Packets.SavedSelection> forStatus(Session s,boolean supported){
        if(!supported||s.savedSent==s.saved.revision())return null;
        s.savedSent=s.saved.revision();
        s.savedDataSent.keySet().retainAll(s.saved.entries().stream().map(e->e.id()).collect(java.util.stream.Collectors.toSet())); // forget what was sent of deleted ones
        return list(s,true);
    }
    /** The blocks of an entry, once for each time they change; null when the client has them. */
    private static String data(Session s,dev.craftium.core.SavedSelections.Entry e){
        if(Integer.valueOf(e.version()).equals(s.savedDataSent.get(e.id())))return null;
        s.savedDataSent.put(e.id(),e.version());
        return dev.craftium.core.SelectionCodec.encode(e.points());
    }
}
