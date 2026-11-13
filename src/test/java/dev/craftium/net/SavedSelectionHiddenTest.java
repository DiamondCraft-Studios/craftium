package dev.craftium.net;

import dev.craftium.core.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The hidden blocks of a BuildObject are an addition to the status: a server or client without them must still read it. */
class SavedSelectionHiddenTest {
    @Test void aStatusFromAServerThatPredatesHiddenBlocksReadsWithNoneHidden(){
        var old="{\"id\":4,\"name\":\"Tower\",\"size\":12,\"min\":{\"x\":0,\"y\":0,\"z\":0},\"max\":{\"x\":1,\"y\":2,\"z\":3},\"active\":true,\"editing\":false,\"data\":\"abc\",\"guid\":\"g\",\"parent\":0,\"color\":0,\"group\":1,\"folded\":false}";
        var row=Packets.JSON.fromJson(old,Packets.SavedSelection.class);
        assertEquals(0,row.hidden());assertNull(row.hiddenData());assertNull(row.hiddenMin());assertNull(row.hiddenMax());
        assertEquals("abc",row.data());assertEquals(12,row.size());
    }
    @Test void hiddenBlocksSurviveTheWireAndTheOlderConstructorMeansNone(){
        var row=new Packets.SavedSelection(1,"A",0,new Point(0,0,0),new Point(0,0,0),true,false,null,"g",0,0,1,false,7,"xyz",new Point(1,2,3),new Point(4,5,6));
        var back=Packets.JSON.fromJson(Packets.JSON.toJson(row),Packets.SavedSelection.class);
        assertEquals(row,back);assertEquals(new Point(4,5,6),back.hiddenMax());
        var none=new Packets.SavedSelection(1,"A",0,new Point(0,0,0),new Point(0,0,0),true,false,null,"g",0,0,1,false);
        assertEquals(0,none.hidden());assertNull(none.hiddenMin());
    }
    @Test void theHiddenBlocksWithTheirStatesAreOnlyThereWhenAsked(){
        var plain=new Packets.SavedSelection(1,"A",0,new Point(0,0,0),new Point(0,0,0),true,false,null,"g",0,0,1,false,7,"xyz",new Point(1,2,3),new Point(4,5,6));
        assertNull(plain.hiddenGhost());
        var asked=new Packets.SavedSelection(1,"A",0,new Point(0,0,0),new Point(0,0,0),true,false,null,"g",0,0,1,false,7,null,new Point(1,2,3),new Point(4,5,6),"{\"total\":7}");
        assertEquals("{\"total\":7}",Packets.JSON.fromJson(Packets.JSON.toJson(asked),Packets.SavedSelection.class).hiddenGhost());
    }
}
