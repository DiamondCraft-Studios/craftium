package dev.craftium.core;

import java.util.*;

/**
 * The selections a player saved by name, kept beside the live selection without ever changing it on their own.
 * Looking at one swaps it in for the live selection and keeps the live one aside; looking away gives it back.
 * Editing is looking with the changes written back to the saved one.
 */
public final class SavedSelections {
    /** The most selections one player may save. */
    public static final int MAX=64;
    /** A saved selection: it keeps its id and name when the others come and go. */
    public static final class Entry {
        private final int id;private String name;private List<Point> points;
        Entry(int id,String name,Collection<Point> points){this.id=id;this.name=name;this.points=List.copyOf(points);}
        public int id(){return id;}
        public String name(){return name;}
        public List<Point> points(){return points;}
        public int size(){return points.size();}
    }
    private final List<Entry> entries=new ArrayList<>();
    private int nextNumber=1,nextId=1;
    /** The saved selection being looked at or edited, or null. */
    private Entry active;private boolean editing;
    /** The live selection as it was before looking began; null when not looking. */
    private List<Point> stash;
    private long revision;
    /** Entries that were deleted, newest last, so Undo can bring the latest back. */
    private record Deleted(Entry entry,int index,long time){}
    private final Deque<Deleted> deleted=new ArrayDeque<>();

    public List<Entry> entries(){return Collections.unmodifiableList(entries);}
    public Entry active(){return active;}
    public boolean editing(){return editing&&active!=null;}
    /** Changes whenever the list, the names or the looking changes, so the client is only sent what is new. */
    public long revision(){return revision;}
    public Entry byId(int id){for(var e:entries)if(e.id==id)return e;return null;}
    /** An entry by its name, ignoring case, so "selection 1" finds "Selection 1"; null when there is none. */
    public Entry byName(String name){if(name==null)return null;var wanted=name.strip();for(var e:entries)if(e.name.equalsIgnoreCase(wanted))return e;return null;}

    /** Saves the live selection as a new entry, "Selection 1", "Selection 2", and so on. Numbers are not given again after a delete, so a name keeps meaning one selection. */
    public Entry save(Selection live){
        if(live.size()==0)throw new IllegalArgumentException("Nothing is selected to save");
        if(entries.size()>=MAX)throw new IllegalArgumentException("Saved selection limit: "+MAX);
        // while looking at one, the live selection is the saved one, so what a player means by saving is what they see: the tweaked copy
        var entry=new Entry(nextId++,"Selection "+nextNumber++,live.points());entries.add(entry);revision++;return entry;
    }
    public void rename(int id,String name){
        var entry=need(id);var clean=name==null?"":name.strip();
        if(clean.isEmpty()||clean.length()>40)throw new IllegalArgumentException("A name is 1 to 40 characters");
        var other=byName(clean);if(other!=null&&other!=entry)throw new IllegalArgumentException("There is already a selection named "+clean);
        entry.name=clean;revision++;
    }
    /** Swaps the entry in for the live selection. Looking at another while looking keeps the first live selection aside, not the one just seen. */
    public void view(int id,Selection live){look(id,live,false);}
    /** As {@link #view}, and changes to the live selection are written back by {@link #sync}. */
    public void edit(int id,Selection live){look(id,live,true);}
    private void look(int id,Selection live,boolean edit){
        var entry=need(id);
        if(active!=null&&editing)sync(live); // leaving an edit for another entry keeps what was done
        if(stash==null)stash=List.copyOf(live.points());
        live.restore(entry.points);active=entry;editing=edit;revision++;
    }
    /** Gives back the live selection from before looking. Whatever was done to the live selection while only viewing is dropped. Does nothing when not looking. */
    public void release(Selection live){
        if(active==null)return;
        if(editing)sync(live);
        live.restore(stash);stash=null;active=null;editing=false;revision++;
    }
    /** While editing, writes the live selection to the entry being edited. Call it after anything changed the live selection. */
    public boolean sync(Selection live){
        if(!editing()||live.size()==0)return false; // an emptied selection (Clear, Delete) never empties the saved one; delete it from the window instead
        if(active.points.size()==live.size()&&new HashSet<>(active.points).equals(live.points()))return false;
        active.points=List.copyOf(live.points());revision++;return true;
    }
    /** Removes the entry; if it was being looked at, the live selection comes back first. Returns the removed entry so a delete can be undone. */
    public Entry delete(int id,Selection live){
        var entry=need(id);if(active==entry)release(live);
        int index=entries.indexOf(entry);entries.remove(entry);revision++;
        deleted.addLast(new Deleted(entry,index,System.currentTimeMillis()));while(deleted.size()>16)deleted.removeFirst();
        return entry;
    }
    /** Brings back the entry deleted last, when that was after {@code time} (epoch milliseconds: when the newest edit in the history was made, so Undo takes the most recent thing). Returns the entry, or null when there is none to bring back. */
    public Entry undoDelete(long time){
        var last=deleted.peekLast();if(last==null||last.time<=time)return null;
        deleted.removeLast();
        if(byId(last.entry.id)!=null||entries.size()>=MAX)return null;
        entries.add(Math.clamp(last.index,0,entries.size()),last.entry);revision++;return last.entry;
    }
    /** Puts a deleted entry back where it was, with its id and name. */
    public void restore(Entry entry,int index){
        if(byId(entry.id)!=null||entries.size()>=MAX)return;
        entries.add(Math.clamp(index,0,entries.size()),entry);revision++;
    }
    public int indexOf(Entry entry){return entries.indexOf(entry);}
    public void clear(Selection live){release(live);entries.clear();revision++;}
    private Entry need(int id){var e=byId(id);if(e==null)throw new IllegalArgumentException("No saved selection with that id");return e;}
}
