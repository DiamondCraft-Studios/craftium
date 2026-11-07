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

    @Test void aListOfSharedPreviewsCanBeLeftOutToo() {
        var o = JsonParser.parseString(status(1000, 1000, 1000)).getAsJsonObject();
        var list = new com.google.gson.JsonArray();
        for (int n = 0; n < 4; n++) { var p = new JsonObject(); p.addProperty("owner", "p" + n); p.addProperty("data", "d".repeat(500_000)); list.add(p); }
        o.add("sharedAi", list); // 2 MB of other players' previews
        String fitted = StatusFit.fit(o.toString(), 1_500_000, "too big");
        var out = JsonParser.parseString(fitted).getAsJsonObject();
        assertTrue(out.get("sharedAi").isJsonArray() && out.getAsJsonArray("sharedAi").isEmpty(), "the list is emptied, not turned into text");
        assertEquals(1000, out.get("selectionData").getAsString().length(), "the small pieces are kept");
        assertEquals(java.util.List.of("sharedAi"), StatusFit.left(o.toString(), fitted));
    }

    @Test void otherPlayersPreviewsGoBeforeThePlayersOwnEvenWhenTheirsAreSmaller() {
        var o = JsonParser.parseString(status(1000, 1000, 1000)).getAsJsonObject();
        o.addProperty("aiPendingData", "a".repeat(1_400_000)); // the player's own big AI preview
        var list = new com.google.gson.JsonArray();
        var p = new JsonObject(); p.addProperty("owner", "Alex"); p.addProperty("data", "d".repeat(300_000)); list.add(p);
        o.add("sharedAi", list); // somebody else's, smaller
        var out = JsonParser.parseString(StatusFit.fit(o.toString(), 1_500_000, "too big")).getAsJsonObject();
        assertTrue(out.getAsJsonArray("sharedAi").isEmpty(), "the other player's preview is what is left out");
        assertEquals(1_400_000, out.get("aiPendingData").getAsString().length(), "the player's own preview is kept");
    }
}
