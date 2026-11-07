package dev.craftium.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatusFitTest {
    private static String status(int selection, int clipboard, int pending) {
        var o = new JsonObject();
        o.addProperty("message", "Ready");
        o.addProperty("selectionSize", 12);
        o.addProperty("selectionData", "s".repeat(selection));
        o.addProperty("clipboardData", "c".repeat(clipboard));
        o.addProperty("pendingData", "p".repeat(pending));
        return o.toString();
    }

    @Test void aStatusThatFitsIsReturnedUntouched() {
        String json = status(1000, 1000, 1000);
        assertSame(json, StatusFit.fit(json, 100_000, "note"));
    }

    @Test void theLargestDrawnPieceIsLeftOutFirstAndNothingMoreThanNeeded() {
        var fitted = JsonParser.parseString(StatusFit.fit(status(3_000_000, 4_000, 2_000), 1_500_000, "too big")).getAsJsonObject();
        assertEquals("", fitted.get("selectionData").getAsString(), "the 3 MB selection is what does not fit");
        assertEquals(4_000, fitted.get("clipboardData").getAsString().length(), "the rest is kept");
        assertEquals(2_000, fitted.get("pendingData").getAsString().length());
        assertEquals(12, fitted.get("selectionSize").getAsInt(), "the size is still said, so the editor knows a selection exists");
        assertEquals("Ready · too big", fitted.get("message").getAsString());
    }

    @Test void severalPiecesAreLeftOutWhenOneIsNotEnough() {
        String json = status(900_000, 900_000, 900_000);
        String fitted = StatusFit.fit(json, 1_500_000, "too big");
        assertTrue(fitted.length() <= 1_500_000);
        assertEquals(2, StatusFit.left(json, fitted).size(), "two of three 900 KB pieces had to go");
    }

    @Test void whatCannotBeShrunkIsLeftAloneInsteadOfLoopingForever() {
        var o = new JsonObject();
        o.addProperty("message", "m".repeat(2_000_000)); // not a drawn field
        String json = o.toString();
        assertDoesNotThrow(() -> StatusFit.fit(json, 1_000, "note"));
    }

    @Test void anEmptyMessageGetsTheNoteAlone() {
        var o = new JsonObject();
        o.addProperty("message", "");
        o.addProperty("selectionData", "s".repeat(50_000));
        assertEquals("note", JsonParser.parseString(StatusFit.fit(o.toString(), 1_000, "note")).getAsJsonObject().get("message").getAsString());
    }
}
