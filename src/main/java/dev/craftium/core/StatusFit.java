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
    static final List<String> DRAWN = List.of("selectionData", "clipboardData", "clipboardGhost", "pendingData", "pluginPreviewData", "aiPendingData", "sharedAi", "pulse", "restore");

    /** The previews other players are holding: left out before anything of the player's own. */
    static final String OTHERS = "sharedAi";

    /** The player's own AI sessions, each of which may carry the data of a waiting preview. */
    static final String SESSIONS = "aiSessions";

    /** The player's saved selections, each of which carries its blocks to draw in {@code data}. */
    static final String SAVED = "savedSelections";

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
            int session = -1;
            // What other players are doing goes first: the player's own selection, clipboard and previews matter more to them than somebody else's.
            var others = status.get(OTHERS);
            if (others != null && others.isJsonArray() && !others.getAsJsonArray().isEmpty()) biggest = OTHERS;
            else for (String name : DRAWN) {
                var value = status.get(name);
                int length = value == null ? 0 : value.isJsonPrimitive() ? value.getAsString().length() : value.isJsonArray() ? value.toString().length() : 0;
                if (length > size) { size = length; biggest = name; }
            }
            // The player's own AI sessions and saved selections carry their data inside a list: the largest piece there competes with the rest.
            if (!OTHERS.equals(biggest)) {
                for (String list : List.of(SESSIONS, SAVED)) {
                    int at = largestSessionData(status, list);
                    if (at >= 0 && sessionDataLength(status, list, at) > size) { biggest = list; session = at; size = sessionDataLength(status, list, at); }
                }
            }
            if (biggest == null) break; // nothing left to leave out: the rest is not ours to shrink
            if (session >= 0) { // that entry's longest piece of data is left out, the entry stays listed
                var entry = status.getAsJsonArray(biggest).get(session).getAsJsonObject();
                String longest = "data";
                for (String key : List.of("hiddenData", "hiddenGhost")) if (length(entry.get(key)) > length(entry.get(longest))) longest = key;
                entry.addProperty(longest, "");
            }
            else if (status.get(biggest).isJsonArray()) status.add(biggest, new JsonArray()); // a list of previews is left empty: none to show
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

    private static int sessionDataLength(JsonObject status, String list, int index) {
        var entry = status.getAsJsonArray(list).get(index).getAsJsonObject();
        return Math.max(length(entry.get("data")), Math.max(length(entry.get("hiddenData")), length(entry.get("hiddenGhost"))));
    }

    private static int length(com.google.gson.JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString().length() : 0;
    }

    /** The index in {@code aiSessions} of the entry with the most ghost data, or -1 when none carries any. */
    private static int largestSessionData(JsonObject status, String name) {
        var list = status.get(name);
        if (list == null || !list.isJsonArray()) return -1;
        int best = -1, size = 0;
        for (int i = 0; i < list.getAsJsonArray().size(); i++) {
            int length = sessionDataLength(status, name, i);
            if (length > size) { size = length; best = i; }
        }
        return best;
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
