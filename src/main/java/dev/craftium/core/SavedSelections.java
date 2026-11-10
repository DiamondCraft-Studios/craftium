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
        private final int id;private final String guid;private String name;private List<Point> points;private int version;private Entry parent;private int color;
        Entry(int id,String name,Collection<Point> points){this(id,java.util.UUID.randomUUID().toString(),name,points);}
        Entry(int id,String guid,String name,Collection<Point> points){this.id=id;this.guid=guid;this.name=name;this.points=List.copyOf(points);}
        public int id(){return id;}
        /** The unique, lasting identity of this BuildObject, for anything that has to name one exactly (the AI, a saved world). */
        public String guid(){return guid;}
        public String name(){return name;}
        /** The id of the BuildObject this one is under, or 0 when it is at the top. */
        public int parentId(){return parent==null?0:parent.id;}
        public Entry parent(){return parent;}
        /** The outline colour everyone sees it in (ARGB), or 0 when none was chosen and each client takes the next one from the wheel. */
        public int color(){return color;}
        /** The blocks it holds itself; see {@link SavedSelections#effective} for those with everything under it. */
        public List<Point> points(){return points;}
        public int size(){return points.size();}
        /** Goes up whenever the blocks change, so what was sent of them can be told from what is there now. */
        public int version(){return version;}
    }
    /**
     * The BuildObjects of a world's dimension, which everyone who builds there shares: the list in tree order, the next id, and two counters.
     * {@code revision} goes up for a change to the BuildObjects, so each player's client is sent what is new (what a player looks at is counted in their own view);
     * {@code mutations} only for a change to the BuildObjects themselves, so they are written to disk only when they changed.
     */
    public static final class Tree {
        private final List<Entry> entries=new ArrayList<>();private int nextId=1;private long revision,mutations;
        public Tree(){}
        public long mutations(){return mutations;}
        public int nextId(){return nextId;}
        /** Everything to write to disk, in tree order. */
        public List<Saved> saved(){var out=new ArrayList<Saved>();for(var e:entries)out.add(new Saved(e.id,e.guid,e.name,e.parentId(),e.color,e.points));return out;}
        /**
         * A tree as it was saved. A BuildObject whose parent is not before it in the list (a file that was edited by hand, or damaged) goes to the top level, so the list is
         * always a tree and in tree order; ids and GUIDs that repeat are dropped.
         */
        public static Tree load(int nextId,List<Saved> rows){
            var tree=new Tree();var byId=new HashMap<Integer,Entry>();var guids=new HashSet<String>();
            for(var r:rows){
                if(tree.entries.size()>=MAX||r.id()<=0||byId.containsKey(r.id())||!guids.add(r.guid()))continue;
                var e=new Entry(r.id(),r.guid(),r.name().isBlank()?"BuildObject":r.name(),r.points());e.color=r.color();
                e.parent=byId.get(r.parentId());byId.put(e.id,e);tree.entries.add(e);
            }
            tree.nextId=Math.max(nextId,tree.entries.stream().mapToInt(e->e.id).max().orElse(0)+1);
            return tree;
        }
    }
    /** One BuildObject as it is saved. */
    public record Saved(int id,String guid,String name,int parentId,int color,List<Point> points){}
    private final Tree tree;
    private final List<Entry> entries;
    /** Counts what this player has looked at, which no one else is sent anything about. */
    private long viewRevision;
    /** A player's own set of BuildObjects, not shared with anyone (what tests and anything without a world use). */
    public SavedSelections(){this(new Tree());}
    /** One player's view of the BuildObjects in {@code tree}: the tree is shared, what is being looked at is this player's own. */
    public SavedSelections(Tree tree){this.tree=tree;this.entries=tree.entries;}
    public Tree tree(){return tree;}
    /** Something about the BuildObjects themselves changed: every client is sent the list, and the tree is written to disk. */
    private void changed(){tree.revision++;tree.mutations++;}
    /** Only what is being looked at changed. */
    private void lookChanged(){viewRevision++;}
    private static final java.util.regex.Pattern NUMBERED=java.util.regex.Pattern.compile("BuildObject(?: \\((\\d{1,9})\\))?");
    /** The BuildObject being looked at or edited, or null. */
    /** The BuildObjects being looked at (several when selected together), and {@code active}, the last of them: the one editing is about. */
    private Entry active;private final List<Entry> viewed=new ArrayList<>();private boolean editing;
    /** The live selection as it was before looking began; null when not looking. */
    private List<Point> stash;

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
        if(changed)changed();
        return changed;
    }
    /** The blocks of the BuildObject and of everything under it: what looking at it shows. */
    public Set<Point> effective(Entry entry){var out=new LinkedHashSet<Point>();for(var e:subtree(entry))out.addAll(e.points);return out;}
    public Entry active(){return active;}
    public boolean editing(){return editing&&active!=null&&viewed.size()==1;}
    /** Every BuildObject being looked at: one, or several selected together. */
    public List<Entry> viewed(){return Collections.unmodifiableList(viewed);}
    public boolean isViewed(Entry entry){return viewed.contains(entry);}
    /** Changes whenever the list, the names or the looking changes, so the client is only sent what is new. */
    public long revision(){return tree.revision+viewRevision;}
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
        var entry=new Entry(tree.nextId++,freeName(),live.points());entries.add(entry);changed();return entry;
    }
    /**
     * Makes a BuildObject from the live selection, as the + button does. It works like selecting several BuildObjects at once and putting them in one: any
     * BuildObject that has a block in the selection is taken in whole, as a child of the new one, wherever the selection reaches only part of it. A
     * BuildObject under another that is also touched comes in with it and not by itself. The new one holds the blocks of the selection that no taken
     * BuildObject holds, so no block is held twice by what it creates, and it takes the place of the first one it took (under the same parent). With
     * nothing touched it is a new BuildObject at the top level, last.
     */
    public Entry create(Selection live){
        if(live.size()==0)throw new IllegalArgumentException("Nothing is selected to make a BuildObject from");
        if(entries.size()>=MAX)throw new IllegalArgumentException("BuildObject limit: "+MAX);
        var cells=live.points();
        var touched=new ArrayList<Entry>();
        for(var e:entries)if(e.points.stream().anyMatch(cells::contains))touched.add(e);
        var taken=touched.stream().filter(e->touched.stream().noneMatch(o->o!=e&&isUnder(e,o))).toList(); // tree order, the outermost only
        var held=new HashSet<Point>();for(var t:taken)held.addAll(effective(t));
        var own=new ArrayList<Point>();for(var p:cells)if(!held.contains(p))own.add(p);
        var entry=new Entry(tree.nextId++,freeName(),own);entries.add(entry);
        adopt(entry,taken);
        changed();return entry;
    }
    /** Puts {@code taken} (in tree order, none under another) inside the new top-level {@code entry}, which takes the place of the first of them. */
    private void adopt(Entry entry,List<Entry> taken){
        if(taken.isEmpty())return;
        var first=taken.getFirst();var parent=first.parent;int index=children(parent).indexOf(first);
        place(entry.id,parent==null?0:parent.id,index);
        int k=0;for(var t:taken)place(t.id,entry.id,k++);
    }
    /** One BuildObject of a copy that is waiting to be pasted: its name, which one of the copy it is under (-1 at the top), its colour, and its own blocks relative to where the copy was taken from. */
    public record Copy(String name,int parentIndex,int color,List<Point> relative){}
    /**
     * What a duplicate has to remember of the BuildObjects shown: each outermost one with everything under it, in tree order, with each one's blocks relative to
     * {@code origin} (the corner the clipboard was copied from), so they can be made again wherever the copy is put down.
     */
    public List<Copy> captureCopy(Point origin){
        var roots=entries.stream().filter(e->viewed.contains(e)&&viewed.stream().noneMatch(o->o!=e&&isUnder(e,o))).toList();
        var out=new ArrayList<Copy>();var index=new HashMap<Entry,Integer>();
        for(var r:roots)for(var e:subtree(r)){
            var relative=new ArrayList<Point>(e.points.size());for(var p:e.points)relative.add(p.subtract(origin));
            index.put(e,out.size());out.add(new Copy(e.name,e==r?-1:index.get(e.parent),e.color,List.copyOf(relative)));
        }
        return out;
    }
    /**
     * Makes the BuildObjects of {@code nodes} again with the same structure: the same names (the top ones with " (copy)" added), parents, order and colours, each holding
     * the cells of {@code placed} that its relative blocks land on at {@code origin}. The copies of the top ones go right after the BuildObject {@code afterId}, in
     * order (the end of the top level when 0 or gone). Returns the new ones, in tree order.
     */
    public List<Entry> createCopies(List<Copy> nodes,Point origin,Set<Point> placed,int afterId){
        if(nodes.isEmpty())throw new IllegalArgumentException("Nothing to copy");
        if(entries.size()+nodes.size()>MAX)throw new IllegalArgumentException("BuildObject limit: "+MAX);
        var made=new ArrayList<Entry>();
        for(var n:nodes){
            var cells=new ArrayList<Point>();
            for(var r:n.relative()){var at=origin.add(r.x(),r.y(),r.z());if(placed.contains(at))cells.add(at);}
            String name=n.name();if(n.parentIndex()<0){name=name+" (copy)";if(name.length()>40)name=name.substring(0,40);}
            var e=new Entry(tree.nextId++,name,cells);e.color=n.color();entries.add(e);made.add(e);
        }
        for(int i=0;i<nodes.size();i++)if(nodes.get(i).parentIndex()>=0){var parent=made.get(nodes.get(i).parentIndex());place(made.get(i).id,parent.id,children(parent).size());}
        var after=afterId==0?null:byId(afterId);
        if(after!=null){int j=0;for(int i=0;i<nodes.size();i++)if(nodes.get(i).parentIndex()<0)place(made.get(i).id,after.parentId(),children(after.parent).indexOf(after)+1+j++);}
        changed();return made;
    }
    /** Makes a BuildObject holding exactly these blocks, right after the BuildObject {@code afterId} on its level (the end of the top level when 0 or gone). Used when something was pasted as one. */
    public Entry createFrom(String name,Collection<Point> cells,int afterId){
        if(cells.isEmpty())throw new IllegalArgumentException("Nothing was pasted to make a BuildObject from");
        if(entries.size()>=MAX)throw new IllegalArgumentException("BuildObject limit: "+MAX);
        var clean=name==null?"":name.strip();if(clean.length()>40)clean=clean.substring(0,40);
        var entry=new Entry(tree.nextId++,clean.isEmpty()?freeName():clean,cells);entries.add(entry);
        var after=afterId==0?null:byId(afterId);
        if(after!=null)place(entry.id,after.parentId(),children(after.parent).indexOf(after)+1);
        changed();return entry;
    }
    /** Makes an empty BuildObject around these ones (each with everything under it), in the place of the first of them: grouping them. One that is under another listed one stays under it. */
    public Entry group(List<Integer> ids){
        var picked=new LinkedHashSet<Entry>();for(int id:ids)picked.add(need(id));
        if(picked.isEmpty())throw new IllegalArgumentException("Pick the BuildObjects to group");
        if(entries.size()>=MAX)throw new IllegalArgumentException("BuildObject limit: "+MAX);
        var taken=entries.stream().filter(e->picked.contains(e)&&picked.stream().noneMatch(o->o!=e&&isUnder(e,o))).toList(); // tree order, the outermost only
        var entry=new Entry(tree.nextId++,freeName(),List.of());entries.add(entry);
        adopt(entry,taken);changed();return entry;
    }
    /** Makes a BuildObject from the live selection as the last child of {@code parentId}. Unlike {@link #create}, it takes nothing in. */
    public Entry createChild(int parentId,Selection live){
        var parent=need(parentId);
        if(live.size()==0)throw new IllegalArgumentException("Nothing is selected to make a BuildObject from");
        if(entries.size()>=MAX)throw new IllegalArgumentException("BuildObject limit: "+MAX);
        var entry=new Entry(tree.nextId++,freeName(),live.points());entries.add(entry);
        place(entry.id,parent.id,children(parent).size());changed();return entry;
    }
    /** Deletes these BuildObjects, each with everything under it (one already gone with another is skipped). */
    public void deleteMany(List<Integer> ids,Selection live){
        for(int id:ids)if(byId(id)!=null)delete(id,live);
    }
    public void rename(int id,String name){
        var entry=need(id);var clean=name==null?"":name.strip();
        if(clean.isEmpty()||clean.length()>40)throw new IllegalArgumentException("A name is 1 to 40 characters");
        entry.name=clean;changed(); // names may repeat: the GUID tells them apart
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
        entry.parent=parent;entries.addAll(at,moving);changed();
    }
    /**
     * As {@link #place} for several at once, in the order they are in the list: each with everything under it goes under {@code parentId} as the children from number
     * {@code index} on, counted among the children without any of them. One that is under another listed one comes along with it and is not placed by itself.
     */
    public void placeMany(List<Integer> ids,int parentId,int index){
        var picked=new LinkedHashSet<Entry>();for(int id:ids)picked.add(need(id));
        var parent=parentId==0?null:need(parentId);
        var roots=entries.stream().filter(e->picked.contains(e)&&picked.stream().noneMatch(o->o!=e&&isUnder(e,o))).toList(); // list order, the outermost only
        if(roots.isEmpty())throw new IllegalArgumentException("Pick the BuildObjects to move");
        for(var r:roots)if(parent==r||parent!=null&&isUnder(parent,r))throw new IllegalArgumentException("A BuildObject cannot go inside itself");
        var moving=new ArrayList<Entry>();for(var r:roots)moving.addAll(subtree(r));
        entries.removeAll(moving);
        var siblings=children(parent);int k=Math.clamp(index,0,siblings.size()),at;
        if(k<siblings.size())at=entries.indexOf(siblings.get(k));
        else if(parent==null)at=entries.size();
        else{at=entries.indexOf(parent)+1;while(at<entries.size()&&isUnder(entries.get(at),parent))at++;}
        for(var r:roots)r.parent=parent;
        entries.addAll(at,moving);changed();
    }
    /** Swaps the entry in for the live selection. Looking at another while looking keeps the first live selection aside, not the one just seen. */
    public void view(int id,Selection live){look(id,live,false);}
    /** As {@link #view}, and changes to the live selection are written back by {@link #sync}. */
    public void edit(int id,Selection live){look(id,live,true);}
    private void look(int id,Selection live,boolean edit){
        var entry=need(id);
        if(active!=null&&editing)sync(live); // leaving an edit for another entry keeps what was done
        if(stash==null)stash=List.copyOf(live.points());
        live.restore(edit?entry.points:effective(entry));active=entry;viewed.clear();viewed.add(entry);editing=edit;lookChanged();
    }
    /**
     * Swaps several BuildObjects in for the live selection at once, as selecting them together: the selection is all of their blocks, each with everything under it.
     * An empty list lets go. Editing is only ever of one, so this is looking.
     */
    public void viewSet(List<Integer> ids,Selection live){
        var picked=new LinkedHashSet<Entry>();for(int id:ids)picked.add(need(id));
        if(picked.isEmpty()){release(live);return;}
        if(active!=null&&editing)sync(live);
        if(stash==null)stash=List.copyOf(live.points());
        var union=new LinkedHashSet<Point>();for(var e:picked)union.addAll(effective(e));
        live.restore(union);viewed.clear();viewed.addAll(picked);active=viewed.getLast();editing=false;lookChanged();
    }
    /** Gives back the live selection from before looking. Whatever was done to the live selection while only viewing is dropped. Does nothing when not looking. */
    public void release(Selection live){
        if(active==null)return;
        if(editing)sync(live);
        live.restore(stash);stash=null;active=null;viewed.clear();editing=false;lookChanged();
    }
    /** While editing, writes the live selection to the entry being edited. Call it after anything changed the live selection. */
    public boolean sync(Selection live){
        if(!editing()||live.size()==0)return false; // an emptied selection (Clear, Delete) never empties the saved one; delete it from the window instead
        if(active.points.size()==live.size()&&new HashSet<>(active.points).equals(live.points()))return false;
        active.points=List.copyOf(live.points());active.version++;changed();return true;
    }
    /** Removes the entry and everything under it; if one of them was being looked at, the live selection comes back first. Returns the removed entry so a delete can be undone. */
    public Entry delete(int id,Selection live){
        var entry=need(id);if(viewed.stream().anyMatch(v->v==entry||isUnder(v,entry)))release(live);
        var group=subtree(entry);
        entries.removeAll(group);changed();
        return entry;
    }
    /** Everything about the BuildObjects that history has to give back: which there are, in what order, under what, called what, holding which blocks. */
    public record State(List<Row> rows){}
    /** One BuildObject as it was: the entry itself (it keeps its id and GUID) with the parts of it that change. */
    public record Row(Entry entry,String name,int parentId,List<Point> points,int color){}
    public State state(){var rows=new ArrayList<Row>();for(var e:entries)rows.add(new Row(e,e.name,e.parentId(),e.points,e.color));return new State(List.copyOf(rows));}
    /** Whether the BuildObjects are, apart from the blocks they hold, as they were in {@code state}: the same ones in the same order, under the same parents, named and coloured the same. */
    public boolean structureMatches(State state){
        var now=state().rows();var then=state.rows();
        if(now.size()!=then.size())return false;
        for(int i=0;i<now.size();i++){var a=now.get(i);var b=then.get(i);if(a.entry()!=b.entry()||!a.name().equals(b.name())||a.parentId()!=b.parentId()||a.color()!=b.color())return false;}
        return true;
    }
    /** Sets the colour of a BuildObject (ARGB; 0 puts it back to the next one from the wheel). */
    public void setColor(int id,int color){need(id).color=color;changed();}
    /** Lets go of what is being looked at when one of it is gone, deleted by someone else. */
    public void reconcileView(Selection live){if(viewed.stream().anyMatch(v->!entries.contains(v)))release(live);}
    /**
     * Puts the BuildObjects back as they were in {@code state}: used by Undo and Redo of anything done in the Hierarchy. A BuildObject that is gone in it is
     * let go of first if it was being looked at. The entries are the same objects, so ids and GUIDs come back as they were; their blocks are left as they are
     * now, except for one that comes back after being deleted.
     */
    public void restore(State state,Selection live){
        var present=new HashSet<Entry>();for(var r:state.rows())present.add(r.entry());
        if(viewed.stream().anyMatch(v->!present.contains(v)))release(live);
        var there=new HashSet<>(entries);
        entries.clear();var byId=new HashMap<Integer,Entry>();
        for(var r:state.rows()){
            var e=r.entry();e.name=r.name();e.color=r.color();
            // The blocks of one that is still here are what they are now: they follow the blocks in the world (and have their own history), and this step is not about them.
            // Only one that comes back after being deleted takes the blocks it had.
            if(!there.contains(e)&&!e.points.equals(r.points())){e.points=r.points();e.version++;}
            entries.add(e);byId.put(e.id,e);
        }
        for(var r:state.rows())r.entry().parent=r.parentId()==0?null:byId.get(r.parentId());
        changed();
    }
    public int indexOf(Entry entry){return entries.indexOf(entry);}
    public void clear(Selection live){release(live);entries.clear();changed();}
    private Entry need(int id){var e=byId(id);if(e==null)throw new IllegalArgumentException("No BuildObject with that id");return e;}
}
