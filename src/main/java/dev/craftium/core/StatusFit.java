package dev.craftium.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.*;

/**
 * Keeps a status packet inside what a packet can carry. The packet is one string and a string that is too long cannot be written, which
 * throws while the server is sending and drops the player's connection. So the server checks the size first, and if a status is too big it
 * leaves out the largest of the pieces that are only there to be drawn (the selection, the clipboard, the waiting previews) and says so,
 * instead: the editor still works, it just does not draw them. Pure, so it needs no game.
 */
public final class StatusFit {
    /** Fields that carry data to draw, biggest first when dropped. The rest of a status is small. */
    static final List<String> DRAWN = List.of("selectionData", "clipboardData", "clipboardGhost", "pendingData", "aiPendingData", "sharedAi", "pulse", "restore");

    /** The previews other players are holding: left out before anything of the player's own. */
    static final String OTHERS = "sharedAi";

    private StatusFit() {}

    /**
     * @param json  the status as JSON
     * @param limit the most characters the packet may hold
     * @param note  added to the status message when something had to be left out
     * @return {@code json} when it fits, else the status without enough of the drawn data to fit
     */
    public static String fit(String json, int limit, String note) {
        if (json.length() <= limit) return json;
        JsonObject status = JsonParser.parseString(json).getAsJsonObject();
        var dropped = new ArrayList<String>();
        while (status.toString().length() > limit) {
            String biggest = null;
            int size = 0;
            // What other players are doing goes first: the player's own selection, clipboard and previews matter more to them than somebody else's.
            var others = status.get(OTHERS);
            if (others != null && others.isJsonArray() && !others.getAsJsonArray().isEmpty()) biggest = OTHERS;
            else for (String name : DRAWN) {
                var value = status.get(name);
                int length = value == null ? 0 : value.isJsonPrimitive() ? value.getAsString().length() : value.isJsonArray() ? value.toString().length() : 0;
                if (length > size) { size = length; biggest = name; }
            }
            if (biggest == null) break; // nothing left to leave out: the rest is not ours to shrink
            if (status.get(biggest).isJsonArray()) status.add(biggest, new JsonArray()); // a list of previews is left empty: none to show
            else status.addProperty(biggest, "");
            dropped.add(biggest);
        }
        if (!dropped.isEmpty()) {
            var message = status.get("message");
            String text = message != null && message.isJsonPrimitive() ? message.getAsString() : "";
            status.addProperty("message", text.isEmpty() ? note : text + " · " + note);
        }
        return status.toString();
    }

    /** The names of what {@link #fit} left out, for a message or a log. */
    public static List<String> left(String original, String fitted) {
        var before = JsonParser.parseString(original).getAsJsonObject();
        var after = JsonParser.parseString(fitted).getAsJsonObject();
        var out = new ArrayList<String>();
        for (String name : DRAWN) {
            var a = before.get(name);
            var b = after.get(name);
            boolean had = a != null && (a.isJsonPrimitive() ? !a.getAsString().isEmpty() : a.isJsonArray() && !a.getAsJsonArray().isEmpty());
            boolean has = b != null && (b.isJsonPrimitive() ? !b.getAsString().isEmpty() : b.isJsonArray() && !b.getAsJsonArray().isEmpty());
            if (had && !has) out.add(name);
        }
        return out;
    }
}
