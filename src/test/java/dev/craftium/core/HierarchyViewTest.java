package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HierarchyViewTest {
    @Test void aGuidNeverSeenIsUnknownAndStartsClosed(){
        var view=new HierarchyView();
        assertFalse(view.knowsEye("g"));assertTrue(view.hidden("g"));assertFalse(view.folded("g"));
    }
    @Test void anEyeAndAFoldAreRememberedByGuid(){
        var view=new HierarchyView();
        assertTrue(view.setHidden("g",false));assertFalse(view.setHidden("g",false),"the same again changes nothing");
        assertTrue(view.setFolded("g",true));assertTrue(view.setFolded("g",false));
        assertTrue(view.knowsEye("g"));assertFalse(view.hidden("g"));assertFalse(view.folded("g"));
    }
    @Test void whatWasChosenSurvivesWritingAndReading(){
        var view=new HierarchyView();view.setHidden("open",false);view.setHidden("closed",true);view.setFolded("folded",true);
        var back=new HierarchyView();back.load(view.toJson());
        assertTrue(back.knowsEye("open")&&!back.hidden("open"));assertTrue(back.knowsEye("closed")&&back.hidden("closed"));assertTrue(back.folded("folded"));assertFalse(back.folded("open"));
    }
    @Test void onlyTheNewestChoicesAreKept(){
        var view=new HierarchyView();
        for(int i=0;i<HierarchyView.MAX+50;i++)view.setHidden("g"+i,false);
        assertFalse(view.knowsEye("g0"),"the oldest went");assertTrue(view.knowsEye("g"+(HierarchyView.MAX+49)));
    }
    @Test void aFileThatIsNotWhatWasWrittenIsIgnored(){
        var view=new HierarchyView();
        view.load("not json");view.load("[1,2]");view.load("{\"eyesOpen\":5,\"folded\":[1,{\"a\":1},\"ok\"]}");
        assertTrue(view.folded("ok"));assertFalse(view.knowsEye("5"));
    }
}
