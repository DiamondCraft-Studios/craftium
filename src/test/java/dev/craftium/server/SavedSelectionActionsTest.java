package dev.craftium.server;

import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SavedSelectionActionsTest {
    private static Session session(){var s=new Session();s.selection.combine(Selection.box(new Point(0,0,0),new Point(2,3,1)),Selection.Mode.REPLACE);return s;}

    @Test void savingListsTheEntryWithItsBoundsAndSendsTheListOnlyWhenItChanged(){
        var s=session();
        assertEquals("Created BuildObject",SavedSelectionActions.run(s,"saved_selection:save"));
        var list=SavedSelectionActions.forStatus(s,true);
        assertEquals(1,list.size());assertEquals("BuildObject",list.getFirst().name());assertEquals(24,list.getFirst().size());
        assertEquals(new Point(2,3,1),list.getFirst().max());assertFalse(list.getFirst().active());
        assertNull(SavedSelectionActions.forStatus(s,true),"nothing new to say");
        assertNull(SavedSelectionActions.forStatus(new Session(),false),"a client that does not know them is sent nothing");
    }
    @Test void viewingAndReleasingSwapTheLiveSelection(){
        var s=session();SavedSelectionActions.run(s,"saved_selection:save");
        s.selection.combine(List.of(new Point(9,9,9)),Selection.Mode.REPLACE);
        int id=s.saved.entries().getFirst().id();
        SavedSelectionActions.run(s,"saved_selection:view:"+id);
        assertEquals(24,s.selection.size());assertTrue(SavedSelectionActions.list(s).getFirst().active());
        SavedSelectionActions.run(s,"saved_selection:release");
        assertEquals(1,s.selection.size());assertTrue(s.selection.contains(new Point(9,9,9)));
    }
    @Test void nonsenseIsRefused(){
        var s=session();
        assertThrows(IllegalArgumentException.class,()->SavedSelectionActions.run(s,"saved_selection:bogus"));
        assertThrows(IllegalArgumentException.class,()->SavedSelectionActions.run(s,"saved_selection:view"));
        assertThrows(IllegalArgumentException.class,()->SavedSelectionActions.run(s,"saved_selection:view:x"));
        assertThrows(IllegalArgumentException.class,()->SavedSelectionActions.run(s,"saved_selection:delete:99"));
    }
    @Test void renameTakesTheRestOfTheActionAsTheName(){
        var s=session();SavedSelectionActions.run(s,"saved_selection:save");int id=s.saved.entries().getFirst().id();
        SavedSelectionActions.run(s,"saved_selection:rename:"+id+":Roof: east side");
        assertEquals("Roof: east side",s.saved.entries().getFirst().name());
    }
    /** "saved_selection:save" has the shape of a plugin's action, so the server must route it before asking the plugin registry; this pins the shape that made that matter. */
    @Test void theShortActionsLookLikePluginActions(){
        assertTrue(ActionRegistry.isNamespaced("saved_selection:save"));assertTrue(ActionRegistry.isNamespaced("saved_selection:release"));
        assertTrue(SavedSelectionActions.PREFIX.equals("saved_selection:"));
    }
    @Test void theBlocksAreSentOnceAndAgainOnlyWhenTheyChange(){
        var s=session();SavedSelectionActions.run(s,"saved_selection:save");int id=s.saved.entries().getFirst().id();
        var first=SavedSelectionActions.forStatus(s,true).getFirst();
        assertEquals(dev.craftium.core.SelectionCodec.encode(s.saved.entries().getFirst().points()),first.data());
        SavedSelectionActions.run(s,"saved_selection:rename:"+id+":Roof");
        assertNull(SavedSelectionActions.forStatus(s,true).getFirst().data(),"a rename does not resend the blocks");
        SavedSelectionActions.run(s,"saved_selection:edit:"+id);s.selection.combine(List.of(new Point(9,9,9)),Selection.Mode.ADD);s.saved.sync(s.selection);
        assertNotNull(SavedSelectionActions.forStatus(s,true).getFirst().data(),"changed blocks are sent again");
    }

    @Test void groupActionsMakeRenameMoveAndDeleteGroupsAndSendThemWithTheList(){
        var s=session();
        assertEquals("Created New Group (1)",SavedSelectionActions.run(s,"saved_selection:groupnew"));
        int first=s.saved.groups().get(0).id(),second=s.saved.groups().get(1).id();
        SavedSelectionActions.run(s,"saved_selection:grouprename:"+second+":Roofs: east");
        assertEquals("Roofs: east",s.saved.groups().get(1).name(),"a name may hold a colon");
        SavedSelectionActions.run(s,"saved_selection:groupmove:"+second+":0");
        assertEquals(List.of(second,first),s.saved.groups().stream().map(g->g.id()).toList());
        var sent=SavedSelectionActions.groups(s);
        assertEquals(List.of("Roofs: east","New Group"),sent.stream().map(g->g.name()).toList());
        assertEquals("Deleted Roofs: east",SavedSelectionActions.run(s,"saved_selection:groupdelete:"+second));
        assertThrows(IllegalArgumentException.class,()->SavedSelectionActions.run(s,"saved_selection:groupdelete:"+first),"the last group stays");
    }
    @Test void anEmptyBuildObjectAndAPlaceInAGroupGoWhereTheyAreAskedTo(){
        var s=session();SavedSelectionActions.run(s,"saved_selection:save");SavedSelectionActions.run(s,"saved_selection:groupnew");
        int id=s.saved.entries().getFirst().id(),second=s.saved.groups().get(1).id();
        SavedSelectionActions.run(s,"saved_selection:empty:"+second);
        assertEquals(second,s.saved.entries().getLast().groupId());assertEquals(0,s.saved.entries().getLast().size());
        SavedSelectionActions.run(s,"saved_selection:place:"+id+":0:0:"+second);
        assertEquals(second,s.saved.byId(id).groupId());assertEquals(id,s.saved.entries().getFirst().id(),"first of the second group, and the first group is empty");
        SavedSelectionActions.run(s,"saved_selection:placeset:"+id+":0:5:"+s.saved.groups().get(0).id());
        assertEquals(s.saved.groups().get(0).id(),s.saved.byId(id).groupId());
        assertEquals(s.saved.groups().get(0).id(),SavedSelectionActions.list(s).stream().filter(e->e.id()==id).findFirst().get().group(),"the client is told which group each is in");
    }
    @Test void groupActionsAreInTheHistoryAndAskForTheMatchingPermissions(){
        for(var verb:List.of("groupnew","grouprename:1:x","groupmove:1:0","groupdelete:1","empty"))assertTrue(SavedSelectionActions.changesHierarchy("saved_selection:"+verb),verb);
        assertEquals("create",SavedSelectionActions.permission("saved_selection:groupnew"));
        assertEquals("create",SavedSelectionActions.permission("saved_selection:empty"));
        assertEquals("rename",SavedSelectionActions.permission("saved_selection:grouprename:1:x"));
        assertEquals("move",SavedSelectionActions.permission("saved_selection:groupmove:1:0"));
        assertEquals("delete",SavedSelectionActions.permission("saved_selection:groupdelete:1"));
        assertEquals("Create group",SavedSelectionActions.historyName("saved_selection:groupnew"));
    }

    @Test void ungroupTakesBuildObjectsOutAndBackToTheirOwnGroup(){
        var s=session();SavedSelectionActions.run(s,"saved_selection:save");SavedSelectionActions.run(s,"saved_selection:save");SavedSelectionActions.run(s,"saved_selection:groupnew");
        int a=s.saved.entries().get(0).id(),b=s.saved.entries().get(1).id(),second=s.saved.groups().get(1).id();
        SavedSelectionActions.run(s,"saved_selection:place:"+b+":0:0:"+second);
        SavedSelectionActions.run(s,"saved_selection:group:"+a+","+b);
        assertEquals(second,s.saved.byId(b).ownGroupId());
        assertEquals("Taken out",SavedSelectionActions.run(s,"saved_selection:ungroup:"+b));
        assertEquals(second,s.saved.byId(b).groupId());assertEquals(0,s.saved.byId(b).parentId());
        assertThrows(IllegalArgumentException.class,()->SavedSelectionActions.run(s,"saved_selection:ungroup:"+b),"it is not inside another any more");
        assertEquals("move",SavedSelectionActions.permission("saved_selection:ungroup:1"));assertTrue(SavedSelectionActions.changesHierarchy("saved_selection:ungroup:1"));
    }
}
