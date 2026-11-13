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
    @Test void dormantBlocksOfAnEditSurviveBeingWrittenAndReadWithTheirData(){
        var before=new dev.craftium.core.PackedCells();var after=new dev.craftium.core.PackedCells();
        after.put(B,"minecraft:chest[facing=north]","{Items:[{id:\"minecraft:diamond\",count:3}]}");after.put(C,"minecraft:stone");
        var replaced=new dev.craftium.core.PackedCells();replaced.put(A,"minecraft:water[level=0]");
        var change=Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(
            Map.of(4,dev.craftium.core.SavedSelections.Held.NONE),Map.of(4,new dev.craftium.core.SavedSelections.Held(after,replaced)));
        var back=HistoryStore.readHeld(HistoryStore.heldTag(change),null);
        assertNotNull(back);assertTrue(back.hasHeld());assertTrue(back.heldBefore().get(4).isEmpty());
        var h=back.heldAfter().get(4);assertEquals(2,h.dormant().size());assertEquals("{Items:[{id:\"minecraft:diamond\",count:3}]}",h.dormant().data(B));
        assertEquals("minecraft:water[level=0]",h.replaced().get(A));
        assertFalse(back.hasPoints());
    }
    @Test void heldBlocksJoinWhatTheObjectsAlreadyHadAndNothingStoredReadsBackAsTheBase(){
        var objects=Session.SelectionChange.restoring(Map.of(),Map.of()).withObjects(Map.of(1,List.of(A)),Map.of(1,List.of(B)));
        assertSame(objects,HistoryStore.readHeld(new ListTag(),objects));assertNull(HistoryStore.readHeld(new ListTag(),null));
        var d=new dev.craftium.core.PackedCells();d.put(C,"minecraft:stone");
        var held=Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(Map.of(1,dev.craftium.core.SavedSelections.Held.NONE),Map.of(1,new dev.craftium.core.SavedSelections.Held(d,dev.craftium.core.PackedCells.EMPTY)));
        var both=HistoryStore.readHeld(HistoryStore.heldTag(held),objects);
        assertTrue(both.hasObjects());assertTrue(both.hasHeld());
    }
    @Test void aDamagedHeldEntryIsRefused(){
        var d=new dev.craftium.core.PackedCells();d.put(C,"minecraft:stone");
        var change=Session.SelectionChange.restoring(Map.of(),Map.of()).withHeld(Map.of(1,dev.craftium.core.SavedSelections.Held.NONE),Map.of(1,new dev.craftium.core.SavedSelections.Held(d,dev.craftium.core.PackedCells.EMPTY)));
        var tag=HistoryStore.heldTag(change);tag.getCompoundOrEmpty(0).putInt("id",0);
        assertThrows(IllegalArgumentException.class,()->HistoryStore.readHeld(tag,null));
    }
}
