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
        assertEquals("Saved as Selection 1",SavedSelectionActions.run(s,"saved_selection:save"));
        var list=SavedSelectionActions.forStatus(s,true);
        assertEquals(1,list.size());assertEquals("Selection 1",list.getFirst().name());assertEquals(24,list.getFirst().size());
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
}
