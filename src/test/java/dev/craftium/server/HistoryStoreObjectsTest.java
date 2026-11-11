package dev.craftium.server;

import dev.craftium.core.Point;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreObjectsTest {
    private static final Point A=new Point(0,0,0),B=new Point(1,-64,2),C=new Point(-30000000,319,30000000);

    @Test void whatAnEditDidToTheBuildObjectsBlocksSurvivesBeingWrittenAndRead(){
        var change=Session.SelectionChange.restoring(Map.of(),Map.of()).withObjects(Map.of(1,List.of(A,B),7,List.of(C)),Map.of(1,List.of(B),7,List.of()));
        var back=HistoryStore.readObjects(HistoryStore.objectsTag(change));
        assertNotNull(back);assertTrue(back.hasObjects());
        assertEquals(List.of(A,B),back.objectsBefore().get(1));assertEquals(List.of(B),back.objectsAfter().get(1));
        assertEquals(List.of(C),back.objectsBefore().get(7));assertEquals(List.of(),back.objectsAfter().get(7),"emptied by the edit, and Undo brings it back");
        assertFalse(back.hasPoints(),"the player's own selection is not part of it");
    }
    @Test void anEditWithoutAnythingAboutBuildObjectsReadsBackAsNone(){
        assertNull(HistoryStore.readObjects(new ListTag()));
    }
    @Test void aDamagedEntryIsRefusedNotTrusted(){
        var change=Session.SelectionChange.restoring(Map.of(),Map.of()).withObjects(Map.of(1,List.of(A)),Map.of(1,List.of()));
        var tag=HistoryStore.objectsTag(change);
        tag.getCompoundOrEmpty(0).putIntArray("before",new int[]{1,2}); // not a whole number of points
        assertThrows(IllegalArgumentException.class,()->HistoryStore.readObjects(tag));
        var noId=HistoryStore.objectsTag(change);noId.getCompoundOrEmpty(0).putInt("id",0);
        assertThrows(IllegalArgumentException.class,()->HistoryStore.readObjects(noId));
    }
}
