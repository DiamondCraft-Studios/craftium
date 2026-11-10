package dev.craftium.core;

import java.util.*;

/**
 * The BuildObjects a player made from selections, kept beside the live selection without ever changing it on their own.
 * Looking at one swaps it in for the live selection and keeps the live one aside; looking away gives it back.
 * Editing is looking with the changes written back to the saved one.
 * <p>
 * They form a hierarchy: each has at most one parent, to any depth. The list is always in tree order (a parent, then everything under it, then the next
 * of its level), so a subtree is the run of entries after its root. Looking at a parent shows its blocks and those of everything under it; editing one
 * changes only the blocks it holds itself.
 */
public final class SavedSelections {
    /** The most selections one player may save. */
    public static final int MAX=64;
    /** A BuildObject: it keeps its id, its GUID and its name when the others come and go. Names may repeat; the GUID never does, and is what tells two of the same name apart. */
    public static final class Entry {
        private final int id;private final String guid=java.util.UUID.randomUUID().toString();private String name;private List<Point> points;private int version;private Entry parent;
        Entry(int id,String name,Collection<Point> points){this.id=id;this.name=name;this.points=List.copyOf(points);}
        public int id(){return id;}
        /** The unique, lasting identity of this BuildObject, for anything that has to name one exactly (the AI, a saved world). */
        public String guid(){return guid;}
        public String name(){return name;}
        /** The id of the BuildObject this one is under, or 0 when it is at the top. */
        public int parentId(){return parent==null?0:parent.id;}
        public Entry parent(){return parent;}
        /** The blocks it holds itself; see {@link SavedSelections#effective} for those with everything under it. */
        public List<Point> points(){return points;}
        public int size(){return points.size();}
        /** Goes up whenever the blocks change, so what was sent of them can be told from what is there now. */
        public int version(){return version;}
    }
    private final List<Entry> entries=new ArrayList<>();
    private int nextId=1;
    private static final java.util.regex.Pattern NUMBERED=java.util.regex.Pattern.compile("BuildObject(?: \\((\\d{1,9})\\))?");
    /** The BuildObject being looked at or edited, or null. */
    private Entry active;private boolean editing;
    /** The live selection as it was before looking began; null when not looking. */
    private List<Point> stash;
    private long revision;
    /** Entries that were deleted, newest last, so Undo can bring the latest back. */
    private record Deleted(List<Entry> group,int siblingIndex,long time){}
    private final Deque<Deleted> deleted=new ArrayDeque<>();

    /** Every BuildObject, in tree order. */
    public List<Entry> entries(){return Collections.unmodifiableList(entries);}
    /** The BuildObjects directly under {@code parent} (null for the top level), in order. */
    public List<Entry> children(Entry parent){var out=new ArrayList<Entry>();for(var e:entries)if(e.parent==parent)out.add(e);return out;}
    /** Whether {@code entry} is somewhere under {@code ancestor}, at any depth. */
    public boolean isUnder(Entry entry,Entry ancestor){for(var p=entry.parent;p!=null;p=p.parent)if(p==ancestor)return true;return false;}
    /** How many BuildObjects there are above this one: 0 at the top level. */
    public int depth(Entry entry){int d=0;for(var p=entry.parent;p!=null;p=p.parent)d++;return d;}
    /** The entry and everything under it, in tree order. */
    private List<Entry> subtree(Entry root){
        var out=new ArrayList<Entry>();out.add(root);
        for(int i=entries.indexOf(root)+1;i<entries.size()&&isUnder(entries.get(i),root);i++)out.add(entries.get(i));
        return out;
    }
    /** The blocks every BuildObject holds itself, by id, as they are now: what a transform remaps from, and what undo puts back. */
    public Map<Integer,List<Point>> snapshot(){var out=new LinkedHashMap<Integer,List<Point>>();for(var e:entries)out.put(e.id,e.points);return out;}
    /** Sets the own blocks of the BuildObjects named in {@code points} (an id that is gone is skipped). Returns whether any changed. */
    public boolean setPoints(Map<Integer,List<Point>> points){
        boolean changed=false;
        for(var e:entries){
            var next=points.get(e.id);
            if(next==null||e.points.equals(next))continue;
            e.points=List.copyOf(next);e.version++;changed=true;
        }
        if(changed)revision++;
        return changed;
    }
    /** The blocks of the BuildObject and of everything under it: what looking at it shows. */
    public Set<Point> effective(Entry entry){var out=new LinkedHashSet<Point>();for(var e:subtree(entry))out.addAll(e.points);return out;}
    public Entry active(){return active;}
    public boolean editing(){return editing&&active!=null;}
    /** Changes whenever the list, the names or the looking changes, so the client is only sent what is new. */
    public long revision(){return revision;}
    public Entry byId(int id){for(var e:entries)if(e.id==id)return e;return null;}
    /**
     * The BuildObject a reference means: its GUID, or its name ignoring case ("buildobject (1)" finds "BuildObject (1)"). Null when there is none. Names may repeat, so
     * a name that fits several is an error that lists their GUIDs, and the GUID is what to use then.
     */
    public Entry find(String ref){return find(ref,entries,Entry::guid,Entry::name);}
    /** {@link #find(String)} for any list of things that have a GUID and a name. */
    public static <T> T find(String ref,Collection<T> items,java.util.function.Function<T,String> guid,java.util.function.Function<T,String> name){
        if(ref==null)return null;var wanted=ref.strip();
        for(var t:items)if(guid.apply(t)!=null&&guid.apply(t).equalsIgnoreCase(wanted))return t;
        var fits=items.stream().filter(t->name.apply(t).strip().equalsIgnoreCase(wanted)).toList();
        if(fits.size()>1)throw new IllegalArgumentException("Several BuildObjects are named \""+wanted+"\"; use the GUID of one: "+String.join(", ",fits.stream().map(guid).toList()));
        return fits.isEmpty()?null:fits.getFirst();
    }

    /**
     * The next default name: "BuildObject" when there is none, then "BuildObject (1)", "BuildObject (2)" and so on, one more than the highest there is. The count starts
     * again when none are left, and a number freed at the end is used again. A gap in the middle is not filled, and a renamed entry does not count.
     */
    private String freeName(){
        int highest=0;boolean any=false;
        for(var e:entries){var m=NUMBERED.matcher(e.name);if(m.matches()){any=true;if(m.group(1)!=null)highest=Math.max(highest,Integer.parseInt(m.group(1)));}}
        return any?"BuildObject ("+(highest+1)+")":"BuildObject";
    }
    /** Saves the live selection as a new entry, "BuildObject", "BuildObject (1)", and so on (see {@link #freeName}). */
    public Entry save(Selection live){
        if(live.size()==0)throw new IllegalArgumentException("Nothing is selected to make a BuildObject from");
        if(entries.size()>=MAX)throw new IllegalArgumentException("BuildObject limit: "+MAX);
        // while looking at one, the live selection is the saved one, so what a player means by saving is what they see: the tweaked copy
        var entry=new Entry(nextId++,freeName(),live.points());entries.add(entry);revision++;return entry;
    }
    public void rename(int id,String name){
        var entry=need(id);var clean=name==null?"":name.strip();
        if(clean.isEmpty()||clean.length()>40)throw new IllegalArgumentException("A name is 1 to 40 characters");
        entry.name=clean;revision++; // names may repeat: the GUID tells them apart
    }
    /**
     * Puts the entry, with everything under it, under the BuildObject {@code parentId} (0 for the top level) as its child number {@code index}, counted
     * among the children without the entry (0 is the first). An index past the end is the end. A BuildObject cannot go under itself or under anything
     * that is under it.
     */
    public void place(int id,int parentId,int index){
        var entry=need(id);var parent=parentId==0?null:need(parentId);
        if(parent!=null&&(parent==entry||isUnder(parent,entry)))throw new IllegalArgumentException("A BuildObject cannot go inside itself");
        var moving=subtree(entry);
        entries.removeAll(moving);
        var siblings=children(parent);int k=Math.clamp(index,0,siblings.size()),at;
        if(k<siblings.size())at=entries.indexOf(siblings.get(k));
        else if(parent==null)at=entries.size();
        else{at=entries.indexOf(parent)+1;while(at<entries.size()&&isUnder(entries.get(at),parent))at++;}
        entry.parent=parent;entries.addAll(at,moving);revision++;
    }
    /** Swaps the entry in for the live selection. Looking at another while looking keeps the first live selection aside, not the one just seen. */
    public void view(int id,Selection live){look(id,live,false);}
    /** As {@link #view}, and changes to the live selection are written back by {@link #sync}. */
    public void edit(int id,Selection live){look(id,live,true);}
    private void look(int id,Selection live,boolean edit){
        var entry=need(id);
        if(active!=null&&editing)sync(live); // leaving an edit for another entry keeps what was done
        if(stash==null)stash=List.copyOf(live.points());
        live.restore(edit?entry.points:effective(entry));active=entry;editing=edit;revision++;
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
        active.points=List.copyOf(live.points());active.version++;revision++;return true;
    }
    /** Removes the entry and everything under it; if one of them was being looked at, the live selection comes back first. Returns the removed entry so a delete can be undone. */
    public Entry delete(int id,Selection live){
        var entry=need(id);if(active!=null&&(active==entry||isUnder(active,entry)))release(live);
        var group=subtree(entry);int sibling=children(entry.parent).indexOf(entry);
        entries.removeAll(group);revision++;
        deleted.addLast(new Deleted(group,sibling,System.currentTimeMillis()));while(deleted.size()>16)deleted.removeFirst();
        return entry;
    }
    /** Brings back the entry deleted last, when that was after {@code time} (epoch milliseconds: when the newest edit in the history was made, so Undo takes the most recent thing). Returns the entry, or null when there is none to bring back. */
    public Entry undoDelete(long time){
        var last=deleted.peekLast();if(last==null||last.time<=time)return null;
        deleted.removeLast();
        var root=last.group.getFirst();
        if(last.group.stream().anyMatch(e->byId(e.id)!=null)||entries.size()+last.group.size()>MAX)return null;
        if(root.parent!=null&&!entries.contains(root.parent))root.parent=null; // what it was under is gone
        var parent=root.parent;entries.addAll(last.group);place(root.id,parent==null?0:parent.id,last.siblingIndex);return root;
    }
    /** Puts a deleted entry back at {@code index} of the list, with its id and name. It goes to the top level when what it was under is gone. */
    public void restore(Entry entry,int index){
        if(byId(entry.id)!=null||entries.size()>=MAX)return;
        if(entry.parent!=null&&!entries.contains(entry.parent))entry.parent=null;
        entries.add(Math.clamp(index,0,entries.size()),entry);revision++;
    }
    public int indexOf(Entry entry){return entries.indexOf(entry);}
    public void clear(Selection live){release(live);entries.clear();revision++;}
    private Entry need(int id){var e=byId(id);if(e==null)throw new IllegalArgumentException("No BuildObject with that id");return e;}
}
