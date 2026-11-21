package dev.craftium.server;

import dev.craftium.core.Point;
import dev.craftium.net.Packets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The actions of the Hierarchy window, named {@code saved_selection:<verb>[:<id>[:<name>]]}. They only ever touch the saved selections and swap the live one in and out; no block is changed. */
final class SavedSelectionActions {
    private SavedSelectionActions() {}

    static final String PREFIX = "saved_selection:";

    /** Runs the action and returns the message to show. Throws IllegalArgumentException for a request that makes no sense. */
    static String run(Session s, String action) {
        var part = action.substring(PREFIX.length()).split(":", 3);
        var saved = s.saved;
        switch (part[0]) {
            case "save" -> {
                var entry = saved.create(s.selection, part.length > 1 && !part[1].isBlank() ? id(part) : 0);
                saved.reveal(entry);
                saved.view(entry.id(), s.selection);
                return "Created " + entry.name();
            }
            case "hiddenghost" -> { // hiddenghost: the hidden blocks of what is shown, with their states, sent with the
                // next list, to preview a drag with the blocks themselves
                var shown = new java.util.ArrayList<Integer>();
                for (var e : saved.viewed()) shown.add(e.id());
                s.hiddenGhostWanted.clear();
                for (int id : saved.withDescendants(shown)) {
                    var e = saved.byId(id);
                    if (e != null && !e.dormant().isEmpty()) s.hiddenGhostWanted.add(id);
                }
                s.savedSent = -1;
                return "";
            }
            case "view" -> {
                saved.view(id(part), s.selection);
                return "Showing " + saved.active().name();
            }
            case "viewset" -> { // viewset:<ids separated by commas>: these BuildObjects, selected together; none lets
                // go
                var ids = ids(part);
                saved.viewSet(ids, s.selection);
                return ids.isEmpty() ? "Your selection is back" : "Showing " + ids.size() + " BuildObjects";
            }
            case "placeset" -> { // placeset:<ids separated by commas>:<parent id, 0 for the top>:<child number among
                // the parent's others>
                var rest = part.length < 3 ? new String[0] : part[2].split(":");
                try {
                    if (rest.length < 2 || rest.length > 3) throw new NumberFormatException();
                    saved.placeIn(
                            ids(part),
                            Integer.parseInt(rest[0]),
                            rest.length == 3 ? Integer.parseInt(rest[2]) : 0,
                            Integer.parseInt(rest[1]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Give BuildObjects, a parent and a place");
                }
                return "Moved";
            }
            case "group" -> {
                var entry = saved.group(ids(part));
                saved.view(entry.id(), s.selection);
                return "Grouped into " + entry.name();
            }
            case "createchild" -> {
                var entry = saved.createChild(id(part));
                saved.reveal(entry);
                saved.view(entry.id(), s.selection);
                return "Created " + entry.name();
            }
            case "deleteset" -> {
                var ids = ids(part);
                saved.deleteMany(ids, s.selection);
                return "Deleted " + ids.size() + " BuildObjects";
            }
            case "colour" -> { // colour:<id>:<argb>
                var where = part.length < 3 ? "" : part[2];
                try {
                    saved.setColor(id(part), Integer.parseInt(where.strip()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Give a colour");
                }
                return "Recoloured";
            }
            case "edit" -> {
                saved.edit(id(part), s.selection);
                return "Editing " + saved.active().name();
            }
            case "release" -> {
                saved.release(s.selection);
                return "Your selection is back";
            }
            case "delete" -> {
                var entry = saved.delete(id(part), s.selection);
                return "Deleted " + entry.name();
            }
            case "place" -> { // place:<id>:<parent id, 0 for the top>:<child number among the parent's others>[:<group
                // id>, which makes the number the place in that group]
                var where = part.length < 3 ? new String[0] : part[2].split(":");
                try {
                    if (where.length < 2 || where.length > 3) throw new NumberFormatException();
                    saved.placeIn(
                            List.of(id(part)),
                            Integer.parseInt(where[0]),
                            where.length == 3 ? Integer.parseInt(where[2]) : 0,
                            Integer.parseInt(where[1]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Give a parent and a place");
                }
                return "Moved";
            }
            case "fold" -> { // fold:<id>:<1 to fold, 0 to unfold>: nothing is said, as it is only how the Hierarchy is
                // shown
                var where = part.length < 3 ? "" : part[2].strip();
                if (!where.equals("0") && !where.equals("1")) throw new IllegalArgumentException("Give 1 or 0");
                saved.setFolded(id(part), where.equals("1"));
                return "";
            }
            case "groupfold" -> {
                var where = part.length < 3 ? "" : part[2].strip();
                if (!where.equals("0") && !where.equals("1")) throw new IllegalArgumentException("Give 1 or 0");
                saved.setGroupFolded(id(part), where.equals("1"));
                return "";
            }
            case "ungroup" -> {
                saved.ungroup(ids(part));
                return "Taken out";
            } // ungroup:<ids separated by commas>: out of the BuildObject each is in, back in the group it had
            case "groupnew" -> {
                var group = saved.createGroup(part.length < 2 ? "" : part[1]);
                return "Created " + group.name();
            } // groupnew[:<name>]
            case "grouprename" -> {
                if (part.length < 3) throw new IllegalArgumentException("Give a name");
                saved.renameGroup(id(part), part[2]);
                return "Renamed";
            } // grouprename:<group id>:<name>
            case "groupmove" -> { // groupmove:<group id>:<place among the other groups>
                var where = part.length < 3 ? "" : part[2];
                try {
                    saved.moveGroup(id(part), Integer.parseInt(where.strip()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Give a place");
                }
                return "Moved";
            }
            case "groupdelete" -> {
                var group = saved.deleteGroup(id(part));
                return "Deleted " + group.name();
            } // groupdelete:<group id>: what is in it goes to the group before
            case "empty" -> {
                var entry = saved.createEmpty(part.length < 2 || part[1].isBlank() ? 0 : id(part));
                saved.reveal(entry);
                saved.view(entry.id(), s.selection);
                return "Created " + entry.name();
            } // empty[:<group id>]: an empty BuildObject
            case "rename" -> {
                if (part.length < 3) throw new IllegalArgumentException("Give a name");
                saved.rename(id(part), part[2]);
                return "Renamed";
            }
            default -> throw new IllegalArgumentException("Unknown BuildObject action " + part[0]);
        }
    }
    /** Whether the action changes the BuildObjects themselves (and so goes in the history), as against only what is being looked at. */
    static boolean changesHierarchy(String action) {
        return switch (verb(action)) {
            case "save",
                    "delete",
                    "deleteset",
                    "rename",
                    "place",
                    "placeset",
                    "group",
                    "createchild",
                    "colour",
                    "ungroup",
                    "groupnew",
                    "grouprename",
                    "groupmove",
                    "groupdelete",
                    "empty" -> true;
            default -> false;
        };
    }
    /** What the action asks permission for (see {@link HierarchyPermissions}), or null for one everybody may do: looking, which changes nothing of the BuildObjects. */
    /** Whether the action can change which BuildObject outranks which: where one is put, grouped or taken out, or a group moved. */
    static boolean changesPriority(String action) {
        return switch (verb(action)) {
            case "place", "placeset", "group", "ungroup", "groupmove" -> true;
            default -> false;
        };
    }

    static String permission(String action) {
        return switch (verb(action)) {
            case "save", "group", "createchild", "groupnew", "empty" -> "create";
            case "rename", "grouprename" -> "rename";
            case "place", "placeset", "groupmove", "ungroup" -> "move";
            case "delete", "deleteset", "groupdelete" -> "delete";
            case "colour" -> "recolour";
            case "edit" -> "edit";
            default -> null;
        };
    }
    /** What the history calls the action: "Undid Create BuildObject". */
    static String historyName(String action) {
        return switch (verb(action)) {
            case "save", "createchild" -> "Create BuildObject";
            case "group" -> "Group BuildObjects";
            case "delete" -> "Delete BuildObject";
            case "deleteset" -> "Delete BuildObjects";
            case "rename" -> "Rename BuildObject";
            case "colour" -> "Recolour BuildObject";
            case "place" -> "Move BuildObject";
            case "placeset" -> "Move BuildObjects";
            case "empty" -> "Create BuildObject";
            case "ungroup" -> "Ungroup BuildObjects";
            case "groupnew" -> "Create group";
            case "grouprename" -> "Rename group";
            case "groupmove" -> "Move group";
            case "groupdelete" -> "Delete group";
            default -> "BuildObject";
        };
    }

    private static String verb(String action) {
        return action.startsWith(PREFIX) ? action.substring(PREFIX.length()).split(":", 2)[0] : "";
    }
    /** The ids of an action like {@code group:1,2,3}. */
    /** The ids in text like {@code 1,2,3}. */
    static List<Integer> idsOf(String text) {
        var out = new java.util.ArrayList<Integer>();
        try {
            if (!text.isBlank()) for (var t : text.split(",")) out.add(Integer.parseInt(t.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Pick BuildObjects");
        }
        return out;
    }

    private static List<Integer> ids(String[] part) {
        var out = new java.util.ArrayList<Integer>();
        try {
            if (part.length > 1 && !part[1].isBlank())
                for (var t : part[1].split(",")) out.add(Integer.parseInt(t.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Pick BuildObjects");
        }
        return out;
    }

    private static int id(String[] part) {
        if (part.length < 2) throw new IllegalArgumentException("Pick a BuildObject");
        try {
            return Integer.parseInt(part[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Pick a BuildObject");
        }
    }

    /** What the client is shown of them: the list in order, with where each one is and which is being looked at. */
    static List<Packets.SavedSelection> list(Session s) {
        return list(s, (Budget) null);
    }
    /**
     * How much block data one list may still carry, in characters. What does not fit is not marked as sent and goes with the next list, a tick later, so a
     * big world reaches a joining player over a few ticks instead of in one status too big to send (which used to leave pieces out for good: they were marked
     * as sent before they were cut).
     */
    static final class Budget {
        int left;
        boolean more, any;
        final Set<Integer> ghostsSent = new HashSet<>();

        Budget(int left) {
            this.left = left;
        }
        /** Whether {@code text} fits; it is taken from what is left when it does. The first piece always fits, so every list moves on. */
        boolean take(String text) {
            if (text.length() > left && any) {
                more = true;
                return false;
            }
            left -= text.length();
            any = true;
            return true;
        }
    }
    /** The most characters of block data one list carries (the status it rides in can carry 1,500,000 in all). */
    static final int LIST_DATA_BUDGET = 600_000;

    static List<Packets.SavedSelection> list(Session s, Budget budget) {
        var out = new java.util.ArrayList<Packets.SavedSelection>();
        boolean withData = budget != null;
        for (var e : s.saved.entries()) {
            var box = e.bounds(); // kept with the entry until its blocks change
            Point min = box == null ? new Point(0, 0, 0) : new Point(box[0], box[1], box[2]);
            Point max = box == null ? min : new Point(box[3], box[4], box[5]);
            var hidden = e.hiddenBounds();
            String ghost = null;
            if (withData && s.hiddenGhostWanted.contains(e.id())) {
                var text = GhostCodec.encodeHidden(e.dormant(), HIDDEN_GHOST_LIMIT);
                if (budget.take(text)) {
                    ghost = text;
                    budget.ghostsSent.add(e.id());
                }
            }
            out.add(new Packets.SavedSelection(
                    e.id(),
                    e.name(),
                    e.size(),
                    min,
                    max,
                    s.saved.isViewed(e),
                    e == s.saved.active() && s.saved.editing(),
                    withData ? data(s, e, budget) : null,
                    e.guid(),
                    e.parentId(),
                    e.color(),
                    e.groupId(),
                    e.folded(),
                    e.dormant().size(),
                    withData ? hiddenData(s, e, budget) : null,
                    hidden == null ? null : new Point(hidden[0], hidden[1], hidden[2]),
                    hidden == null ? null : new Point(hidden[3], hidden[4], hidden[5]),
                    ghost,
                    e.stowed()));
        }
        return out;
    }
    /** The groups, in order, for the status that carries the list. */
    static List<Packets.SavedGroup> groups(Session s) {
        var out = new java.util.ArrayList<Packets.SavedGroup>();
        for (var g : s.saved.groups()) out.add(new Packets.SavedGroup(g.id(), g.guid(), g.name(), g.folded()));
        return out;
    }
    /** The list for the status that is going to this player, or null when they already have the current one. Asking marks it as sent. */
    static List<Packets.SavedSelection> forStatus(Session s, boolean supported) {
        if (!supported || s.savedSent == s.saved.revision()) return null;
        s.savedSent = s.saved.revision();
        var ids = s.saved.entries().stream().map(e -> e.id()).collect(java.util.stream.Collectors.toSet());
        s.savedDataSent.keySet().retainAll(ids);
        s.savedHiddenSent.keySet().retainAll(ids); // forget what was sent of deleted ones
        var budget = new Budget(LIST_DATA_BUDGET);
        var out = list(s, budget);
        if (budget.more) {
            s.savedSent = -1; // the rest goes with the next list, at the next tick
            s.hiddenGhostWanted.removeAll(budget.ghostsSent);
        } else s.hiddenGhostWanted.clear(); // sent once, for the drag that asked
        return out;
    }
    /** The most hidden blocks sent to be drawn for one BuildObject: more are counted, boxed and kept, but not drawn. */
    static final int HIDDEN_DRAW_LIMIT = 262_144;
    /** The most hidden blocks sent with their states, to be drawn as blocks while a BuildObject is dragged. */
    static final int HIDDEN_GHOST_LIMIT = 16_384;
    /** The blocks an entry keeps hidden, once for each time they change; null when the client has them, and empty when there are none or too many to draw. */
    private static String hiddenData(Session s, dev.craftium.core.SavedSelections.Entry e, Budget budget) {
        if (Integer.valueOf(e.heldVersion()).equals(s.savedHiddenSent.get(e.id()))) return null;
        var text = e.dormant().isEmpty() || e.dormant().size() > HIDDEN_DRAW_LIMIT
                ? ""
                : dev.craftium.core.SelectionCodec.encode(e.dormant().points());
        if (!budget.take(text)) return null; // not marked: it goes with the next list
        s.savedHiddenSent.put(e.id(), e.heldVersion());
        return text;
    }
    /** The blocks of an entry, once for each time they change; null when the client has them. */
    private static String data(Session s, dev.craftium.core.SavedSelections.Entry e, Budget budget) {
        if (Integer.valueOf(e.version()).equals(s.savedDataSent.get(e.id()))) return null;
        var text = dev.craftium.core.SelectionCodec.encode(e.points());
        if (!budget.take(text)) return null; // not marked: it goes with the next list
        s.savedDataSent.put(e.id(), e.version());
        return text;
    }
}
