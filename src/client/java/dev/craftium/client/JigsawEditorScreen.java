package dev.craftium.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * The jigsaw pool editor. On the left the pools (the craftium pack's, and every other pack's, which are overridden in the craftium pack when edited), in
 * the middle the elements of the pool as cards with a picture, a weight and a projection, on the right the connectors of the selected element, each
 * with how many elements it can meet, and a graph of how the pools lead to each other. The server does all the editing, by changing the pack's files
 * (so the live preview follows) and answering with a model; every change is one undo step. A text box applies what is typed when Enter is pressed.
 */
final class JigsawEditorScreen extends CraftiumScreen {
    private static final int SIDE = 12, TOP = 50, FOOT = 12, CARD_H = 98, CARD_GAP = 6, ROW_H = 112, LIST_ROW = 20;
    private static final int PANEL = 0xe0172330, CARD = 0xff223247, MUTED = 0xff9db5cb, TEXT = 0xffdce6ef, WARN = 0xffffd479, ERROR = 0xffff8a80, OK = 0xff8fe3a0;

    private final Screen parent;
    private JsonObject model = new JsonObject();
    private int seenVersion = -1, selected, page, jigPage;
    private boolean graph, adding;
    private String filter = "", name = "", query = "", selectionName = "";
    private double poolScroll, graphScroll;
    private boolean draggingPoolBar, draggingGraphBar;
    private final Map<EditBox, Runnable> commits = new HashMap<>();
    /** What to say when the pointer rests on a button; drawn inside the scaled canvas, so it sits at the pointer whatever the window size. */
    private final Tips tips = new Tips();
    private static final String RIGID_TIP = "Rigid: the piece keeps exactly the height the structure grew to and ignores the ground, so it never bends. Use it for buildings, rooms and anything solid."
            + "\nClick to make it follow the terrain instead.";
    private static final String FOLLOW_TIP = "Follow terrain (terrain_matching): the piece is bent to the ground, each column of it moving up or down to meet the surface, like village streets and paths."
            + "\nIn the preview this follows the height the world generated, not blocks you have changed."
            + "\nClick to make it rigid instead.";
    private Integer pendingWeightIndex;
    private int pendingWeight;
    private long pendingAt;
    private String localMessage = "";
    /** Asking what to do with unapplied changes before leaving, and whether to leave as soon as the server has said it is done. */
    private boolean confirmLeave, leaveAfter;
    /** Where the player has dragged pools in the graph, in graph coordinates, by pool being edited and pool; kept while the game runs. */
    private static final Map<String, int[]> NODE_POS = new HashMap<>();
    /** What undo and redo take back here: a command the server has its own history of, a move of a pool in the graph, or putting every pool back. */
    private enum Kind { SERVER, MOVE, RESET }
    private record Local(Kind kind, String key, int[] from, int[] to, Map<String, int[]> before) {}
    private final Deque<Local> localUndo = new ArrayDeque<>(), localRedo = new ArrayDeque<>();
    private String pressNode, pressKey;
    private boolean pressMissing, dragging;
    private double pressX, pressY;
    private int[] pressOrigin, pressFrom;
    private static final int NODE_H = 22, GRAPH_ROW = 27;
    private static final String HINT = "Arrows go from a pool to the pools its connectors ask for. Drag an arrow's square onto another pool to retarget its connectors, or into the red box to disconnect them. Drag a pool to arrange it, click one to open it.";
    /** The red box an arrow is dropped in to disconnect it sits just above the hint, whatever the hint's length. */
    private int disconnectY() { return ch - FOOT - 8 - font.split(Component.literal(HINT), centreW).size() * 10 - 24; }
    /** An arrow of the graph, from where it leaves its pool to where it ends, with how many connectors stand behind it. */
    private record Edge(String from, String to, int count, int x1, int y1, int x2, int y2) {}
    private Edge pressEdge;
    private double edgePressX, edgePressY, edgeX, edgeY;
    private boolean edgeDragging, retargetPrompt, alignAfter;
    private int awaitTicks;
    private int leftW, rightW, centreX, centreW;
    /** The editor is laid out on a canvas at least 860 by 460 and scaled down to fit a smaller window, like the Studio inspector. */
    private float uiScale = 1;
    private int cw, ch;

    JigsawEditorScreen(Screen parent) {
        super(Component.literal("Craftium jigsaw pools"));
        this.parent = parent;
    }

    /** Opens the editor on the pool the planner last used, asking the server for the pools first. */
    static void open(Screen parent) {
        var command = cmd("open");
        var pool = ClientState.options.jigsawPool;
        if (pool != null && !pool.isBlank()) command.addProperty("pool", pool);
        send(command);
        ClientCompat.setScreen(net.minecraft.client.Minecraft.getInstance(), new JigsawEditorScreen(parent));
    }

    // ---- talking to the server

    private static JsonObject cmd(String op, Object... pairs) {
        var json = new JsonObject();
        json.addProperty("op", op);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v instanceof Number n) json.addProperty((String) pairs[i], n);
            else if (v instanceof Boolean b) json.addProperty((String) pairs[i], b);
            else json.addProperty((String) pairs[i], String.valueOf(v));
        }
        return json;
    }

    private static void send(JsonObject command) {
        ClientState.options.jigsawEdit = command.toString();
        CraftiumClient.send("craftium:jigsaw_edit", null, null);
    }

    private void edit(String op, Object... pairs) { localMessage = ""; note(op); send(cmd(op, pairs)); }

    /** Sends a command that was built by hand (a connector edit). */
    private void change(JsonObject command) { localMessage = ""; note(command.get("op").getAsString()); send(command); }

    /** Notes in this screen's own history that a command changed something on the server, so undo can tell it from a move in the graph. */
    private void note(String op) {
        switch (op) {
            case "open", "templates", "names", "undo", "redo", "retarget_check" -> { }
            case "apply", "discard" -> { localUndo.clear(); localRedo.clear(); }
            default -> { localUndo.push(new Local(Kind.SERVER, null, null, null, null)); localRedo.clear(); }
        }
    }

    private void undo() {
        if (localUndo.isEmpty()) { send(cmd("undo")); return; }
        var step = localUndo.pop();
        localRedo.push(step);
        switch (step.kind()) {
            case SERVER -> send(cmd("undo"));
            case MOVE -> { if (step.from() == null) NODE_POS.remove(step.key()); else NODE_POS.put(step.key(), step.from().clone()); init(); }
            case RESET -> { NODE_POS.putAll(step.before()); init(); }
        }
    }

    private void redo() {
        if (localRedo.isEmpty()) { send(cmd("redo")); return; }
        var step = localRedo.pop();
        localUndo.push(step);
        switch (step.kind()) {
            case SERVER -> send(cmd("redo"));
            case MOVE -> { NODE_POS.put(step.key(), step.to().clone()); init(); }
            case RESET -> { for (var key : step.before().keySet()) NODE_POS.remove(key); init(); }
        }
    }

    private JsonObject unapplied() { return model.has("unapplied") ? model.getAsJsonObject("unapplied") : new JsonObject(); }
    private int unappliedCount() { return num(unapplied(), "count", 0); }
    private String nodeKey(String id) { return poolId() + "|" + id; }
    private void finishClose() { ClientCompat.setScreen(minecraft, parent); }


    // ---- the model

    private void parseModel() {
        seenVersion = ClientState.jigsawModelVersion;
        try { model = ClientState.jigsawModel.isBlank() ? new JsonObject() : JsonParser.parseString(ClientState.jigsawModel).getAsJsonObject(); }
        catch (RuntimeException e) { model = new JsonObject(); }
        var elements = elements();
        if (selected >= elements.size()) selected = Math.max(0, elements.size() - 1);
        var pools = visiblePools();
        for (int i = 0; i < pools.size(); i++)
            if (pools.get(i).get("id").getAsString().equals(poolId())) {
                int y = i * LIST_ROW, room = listBottom() - listTop();
                if (y < poolScroll || y + LIST_ROW > poolScroll + room) poolScroll = Math.max(0, y - room / 2);
            }
    }
    private JsonObject pool() { return model.has("pool") ? model.getAsJsonObject("pool") : null; }
    private String poolId() { var p = pool(); return p == null ? "" : p.get("id").getAsString(); }
    private JsonArray elements() { var p = pool(); return p == null ? new JsonArray() : p.getAsJsonArray("elements"); }
    private JsonObject element(int i) { var list = elements(); return i >= 0 && i < list.size() ? list.get(i).getAsJsonObject() : null; }
    private static String str(JsonObject o, String key, String fallback) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback; }
    private static int num(JsonObject o, String key, int fallback) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback; }
    private static String shortName(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    // ---- widgets

    private FlatButton button(String label, int x, int y, int w, int h, Runnable action) {
        var b = new FlatButton(x, y, w, h, label, action);
        addRenderableWidget(b);
        return b;
    }

    private EditBox box(int x, int y, int w, String value, int max, java.util.function.Consumer<String> changed, Runnable commit) {
        var field = new EditBox(font, x, y, w, 18, Component.empty());
        field.setMaxLength(max);
        field.setValue(value);
        if (changed != null) field.setResponder(changed::accept);
        if (commit != null) commits.put(field, commit);
        addRenderableWidget(field);
        return field;
    }

    @Override protected void init() {
        parseModel();
        PoolThumbnails.newFrame();
        clearWidgets();
        uiScale = Math.min(1f, Math.min(this.width / 860f, this.height / 460f));
        cw = Math.round(this.width / uiScale);
        ch = Math.round(this.height / uiScale);
        commits.clear();
        tips.clear();
        leftW = Math.clamp(cw / 5, 150, 210);
        rightW = Math.clamp(cw * 3 / 10, 230, 300);
        centreX = SIDE + leftW + 10;
        centreW = Math.max(200, cw - SIDE - rightW - 10 - centreX);
        int unapplied = unappliedCount();
        int redoCount = localRedo.isEmpty() ? num(model, "redo", 0) : localRedo.size(), undoCount = localUndo.isEmpty() ? num(model, "undo", 0) : localUndo.size();
        button("Close", cw - SIDE - 70, 10, 70, 20, this::onClose);
        button("Redo (" + redoCount + ")", cw - SIDE - 70 - 76, 10, 70, 20, this::redo).active = redoCount > 0;
        button("Undo (" + undoCount + ")", cw - SIDE - 70 - 152, 10, 70, 20, this::undo).active = undoCount > 0;
        button("Discard", cw - SIDE - 70 - 152 - 76, 10, 70, 20, () -> edit("discard")).active = unapplied > 0;
        button("Apply (" + unapplied + ")", cw - SIDE - 70 - 152 - 76 - 102, 10, 96, 20, () -> edit("apply")).lit(unapplied > 0).active = unapplied > 0;
        if (retargetPrompt) { retargetButtons(); applyTips(); return; }
        if (confirmLeave) { leavePrompt(); applyTips(); return; }
        if (adding) { overlay(); applyTips(); return; }
        leftPane();
        if (pool() != null) { centreHeader(); if (!graph) cards(); }
        rightPane();
        applyTips();
    }

    /** Gives each button its tip by what it says, so the buttons whose label changes (Undo, Rollable, a template name) are covered where they are made. */
    private void applyTips() {
        for (var child : children()) {
            if (!(child instanceof FlatButton b) || tips.has(b)) continue;
            String label = b.getMessage().getString();
            String key = label.startsWith("Undo") ? "Undo" : label.startsWith("Redo") ? "Redo" : label.startsWith("Apply (") ? "Apply" : label;
            String tip = switch (key) {
                case "Close" -> adding ? "Closes this panel." : confirmLeave ? "Keeps editing." : "Back to the planner. If changes are not applied you are asked what to do with them.";
                case "Apply" -> "Writes the changes you have made here into datapacks/craftium, where the game reads them. Until then they wait in a draft: this editor and the preview use the draft, the datapack does not. Undo history ends here.";
                case "Discard" -> "Throws away every change made since the last Apply. Undo cannot bring them back.";
                case "Apply and leave" -> "Applies the waiting changes to datapacks/craftium, then goes back to the planner.";
                case "Discard and leave" -> "Throws the waiting changes away, then goes back to the planner.";
                case "Keep editing" -> "Stays in the editor with the changes still waiting.";
                case "Auto-align" -> "Puts every pool back in its column and row, as the automatic layout places them. You can undo it.";
                case "Retarget" -> "Points those connectors at the new pool. It is one undo step, and nothing reaches the datapack until you Apply.";
                case "Cancel" -> "Leaves the connectors as they are.";
                case "Undo" -> "Takes back your last change here: an edit to a pool or template, or a move of a pool in the graph.";
                case "Redo" -> "Puts back a change you took back, including a move in the graph.";
                case "New" -> "Makes an empty pool with the name typed above.";
                case "Copy" -> "Copies the open pool under the name typed above.";
                case "Rename" -> "Renames the open pool, if it is in the craftium pack. Connectors that point at the old name are not changed.";
                case "Delete" -> "Deletes the open pool from the craftium pack. A pool of another pack that it overrode shows again. You can undo it.";
                case "Preview from here" -> "Runs the planner with this pool as the start pool, using the depth, distance and seed of the planner.";
                case "Elements" -> "Shows the elements of this pool as cards.";
                case "Graph" -> "Shows how this pool leads to other pools through the connectors of its templates. Click a pool to open it.";
                case "+ Add template" -> "Adds a template to this pool: search the templates of every pack, or save your selection as a new one.";
                case "‹" -> "Previous page.";
                case "›" -> "Next page.";
                case "↑" -> "Moves this element up the list. The order does not change how often it is chosen; the weight does.";
                case "↓" -> "Moves this element down the list. The order does not change how often it is chosen; the weight does.";
                case "Remove" -> "Removes this element from the pool. The template itself is kept.";
                case "Rollable" -> "Rollable: a piece joined to a vertical connector may be turned any way round it. Click to make it aligned.";
                case "Aligned" -> "Aligned: the joined piece is turned to line up with the top of this connector. Click to make it rollable.";
                case "Save selection" -> "Saves the blocks you selected as a new template, and adds it to this pool.";
                default -> adding ? "Adds this template to the pool." : null;
            };
            if (tip != null) tips.on(b, tip);
        }
    }

    private void leftPane() {
        tips.on(box(SIDE, TOP, leftW, filter, 64, v -> { filter = v; poolScroll = 0; }, null), "Type part of a pool name to narrow the list.");
        int y = ch - FOOT - 66;
        tips.on(box(SIDE, y, leftW, name, 120, v -> name = v, () -> action("pool_new")), "The name for New, Copy or Rename. Without a namespace it goes in craftium, so my_pool means craftium:my_pool. Enter makes a new pool.");
        int half = leftW / 2 - 2;
        button("New", SIDE, y + 22, half, 18, () -> action("pool_new"));
        button("Copy", SIDE + half + 4, y + 22, half, 18, () -> action("pool_copy"));
        button("Rename", SIDE, y + 44, half, 18, () -> action("pool_rename"));
        button("Delete", SIDE + half + 4, y + 44, half, 18, () -> { if (!poolId().isEmpty()) edit("pool_delete", "id", poolId()); });
    }

    /** New, copy and rename take the name typed under the list; a name with no namespace goes in craftium. */
    private void action(String op) {
        if (name.isBlank()) { localMessage = "Type a name for the pool first"; return; }
        switch (op) {
            case "pool_new" -> edit("pool_new", "id", name.strip());
            case "pool_copy" -> { if (!poolId().isEmpty()) edit("pool_copy", "from", poolId(), "to", name.strip()); }
            default -> { if (!poolId().isEmpty()) edit("pool_rename", "from", poolId(), "to", name.strip()); }
        }
    }

    private void centreHeader() {
        int y = TOP + 16;
        tips.on(box(centreX + 54, y, Math.min(260, centreW - 54 - 130), str(pool(), "fallback", "minecraft:empty"), 120, null, () -> edit("fallback_set", "pool", poolId(), "fallback", focusedText())), "The pool used when no element can be attached, usually minecraft:empty. Enter applies.");
        button("Preview from here", centreX + centreW - 124, y - 1, 124, 20, () -> { ClientState.options.jigsawPool = poolId(); CraftiumClient.send("craftium:jigsaw_generate", null, null); });
        tips.on(box(centreX + 70, y + 24, centreW - 70, str(pool(), "description", ""), 300, null, () -> edit("description_set", "pool", poolId(), "text", focusedText())),
                "A note about this pool, saved in its file as $comment. The game ignores that key, so it is safe, and the editor keeps it. Enter applies; empty removes it.");
        int tabs = y + 50;
        button("Elements", centreX, tabs, 78, 20, () -> { graph = false; init(); }).lit(!graph);
        button("Graph", centreX + 82, tabs, 78, 20, () -> { graph = true; init(); }).lit(graph);
        button("+ Add template", centreX + 166, tabs, 110, 20, () -> { adding = true; query = ""; init(); });
        if (graph) button("Auto-align", centreX + centreW - 96, tabs, 96, 20, this::resetLayout);
        if (!graph) {
            int pages = Math.max(1, (elements().size() + perPage() - 1) / perPage());
            page = Math.clamp(page, 0, pages - 1);
            button("‹", centreX + centreW - 96, tabs, 24, 20, () -> { page = Math.max(0, page - 1); init(); });
            button("›", centreX + centreW - 24, tabs, 24, 20, () -> { page = Math.min(pages - 1, page + 1); init(); });
        }
    }

    private String focusedText() { return getFocused() instanceof EditBox e ? e.getValue() : ""; }
    private int cardsTop() { return TOP + 92; }
    private int perPage() { return Math.max(1, (ch - FOOT - cardsTop() + CARD_GAP) / (CARD_H + CARD_GAP)); }

    private void cards() {
        var list = elements();
        int first = page * perPage();
        for (int slot = 0; slot < perPage() && first + slot < list.size(); slot++) {
            int index = first + slot;
            var element = list.get(index).getAsJsonObject();
            int x = centreX, y = cardsTop() + slot * (CARD_H + CARD_GAP), tx = x + 6 + PoolThumbnails.WIDTH + 8, w = centreW - (tx - x) - 8;
            boolean editable = element.has("editable") && element.get("editable").getAsBoolean();
            int weight = num(element, "weight", 1);
            var slider = new FlatSlider(tx, y + 28, w, 16, Component.empty(), (weight - 1) / 149.0) {
                private int units() { return 1 + (int) Math.round(value * 149); }
                @Override protected void updateMessage() { setMessage(Component.literal("weight " + units() + " · " + share(index, units()) + "%")); }
                @Override protected void applyValue() { pendingWeightIndex = index; pendingWeight = units(); pendingAt = System.currentTimeMillis(); }
            }.range(1, 150, weight);
            slider.setRaw((weight - 1) / 149.0);
            addRenderableWidget(tips.on(slider, "How often this element is chosen compared with the others in the pool, 1 to 150. The percentage is its share. Ctrl-click to type a number."));
            boolean rigid = str(element, "projection", "rigid").equals("rigid");
            var projection = button(rigid ? "Rigid" : "Follow terrain", tx, y + 48, 96, 18, () -> edit("element_set", "pool", poolId(), "index", index, "projection", rigid ? "terrain_matching" : "rigid"));
            projection.active = editable || element.has("projection");
            tips.on(projection, rigid ? RIGID_TIP : FOLLOW_TIP);
            if (editable && element.has("processors")) {
                var processors = tips.on(box(tx + 100, y + 48, Math.max(40, w - 100), str(element, "processors", "minecraft:empty"), 120, null, () -> edit("element_set", "pool", poolId(), "index", index, "processors", focusedText())), "The processor list applied to this element when it is placed, such as minecraft:mossify_10_percent. Enter applies.");
                if (str(element, "processors", "").equals("(inline)")) processors.setEditable(false); // a processor list written in the pool file is kept as it is
            }
            button("↑", tx, y + 70, 22, 20, () -> edit("element_move", "pool", poolId(), "index", index, "to", index - 1)).active = index > 0;
            button("↓", tx + 26, y + 70, 22, 20, () -> edit("element_move", "pool", poolId(), "index", index, "to", index + 1)).active = index < list.size() - 1;
            button("Remove", tx + 52, y + 70, 64, 20, () -> edit("element_remove", "pool", poolId(), "index", index));
        }
    }

    private int share(int index, int weight) {
        int total = 0;
        var list = elements();
        for (int i = 0; i < list.size(); i++) total += i == index ? weight : num(list.get(i).getAsJsonObject(), "weight", 1);
        return total == 0 ? 0 : (int) Math.round(weight * 100.0 / total);
    }

    private int jigsawsPerPage() { return Math.max(1, (ch - FOOT - (TOP + 52)) / ROW_H); }

    private void rightPane() {
        int x = cw - SIDE - rightW;
        var element = element(selected);
        if (element == null || !element.has("jigsaws")) return;
        var jigsaws = element.getAsJsonArray("jigsaws");
        int per = jigsawsPerPage(), pages = Math.max(1, (jigsaws.size() + per - 1) / per);
        jigPage = Math.clamp(jigPage, 0, pages - 1);
        button("‹", x + rightW - 56, TOP, 24, 18, () -> { jigPage = Math.max(0, jigPage - 1); init(); });
        button("›", x + rightW - 28, TOP, 24, 18, () -> { jigPage = Math.min(pages - 1, jigPage + 1); init(); });
        String template = str(element, "template", "");
        for (int slot = 0; slot < per && jigPage * per + slot < jigsaws.size(); slot++) {
            var jig = jigsaws.get(jigPage * per + slot).getAsJsonObject();
            int y = TOP + 52 + slot * ROW_H;
            var at = jig.getAsJsonArray("pos");
            JsonObject base = cmd("jigsaw_set", "template", template);
            base.add("pos", at);
            int half = rightW / 2 - 3;
            tips.on(box(x + 38, y + 14, rightW - 38, str(jig, "name", ""), 80, null, () -> { var c = base.deepCopy(); c.addProperty("name", focusedText()); change(c); }), "The name of this connector. A connector of another piece joins it when its target is this name. Enter applies.");
            tips.on(box(x + 38, y + 38, half - 38, str(jig, "pool", ""), 100, null, () -> { var c = base.deepCopy(); c.addProperty("pool", focusedText()); change(c); }), "The pool the next piece is picked from. minecraft:empty ends the branch here. Enter applies.");
            tips.on(box(x + half + 4 + 40, y + 38, rightW - (half + 4) - 40, str(jig, "target", ""), 100, null, () -> { var c = base.deepCopy(); c.addProperty("target", focusedText()); change(c); }), "The name a connector of the next piece must have to join this one. Enter applies.");
            boolean rollable = str(jig, "joint", "rollable").equals("rollable");
            button(rollable ? "Rollable" : "Aligned", x, y + 62, 76, 18, () -> { var c = base.deepCopy(); c.addProperty("joint", rollable ? "aligned" : "rollable"); change(c); });
            tips.on(box(x + 114, y + 62, 40, String.valueOf(num(jig, "placement", 0)), 4, null, () -> { var c = base.deepCopy(); setInt(c, "placement", focusedText()); change(c); }), "Placement priority: when several pieces wait to be attached, those from connectors with a higher number are placed first. Enter applies.");
            tips.on(box(x + 204, y + 62, 40, String.valueOf(num(jig, "selection", 0)), 4, null, () -> { var c = base.deepCopy(); setInt(c, "selection", focusedText()); change(c); }), "Selection priority: among the connectors of a piece, those with a higher number are tried first. Enter applies.");
        }
    }

    private void setInt(JsonObject json, String key, String text) {
        try { json.addProperty(key, Integer.parseInt(text.strip())); }
        catch (NumberFormatException e) { json.addProperty(key, 0); }
    }

    /** The panel that adds a template to the pool: a search of the templates the server knows, or the player's selection saved as a new one. */
    private void overlay() {
        int w = Math.min(460, cw - 40), x = (cw - w) / 2, y = 40;
        tips.on(box(x + 10, y + 34, w - 20, query, 80, v -> query = v, () -> edit("templates", "query", query)), "Part of a template name. Press Enter to search the templates of every pack and the structures you saved.");
        var found = model.has("templates") ? model.getAsJsonArray("templates") : new JsonArray();
        int rows = Math.max(1, (ch - 40 - 150 - 40) / 20);
        for (int i = 0; i < Math.min(found.size(), rows); i++) {
            String id = found.get(i).getAsString();
            button(id, x + 10, y + 58 + i * 20, w - 20, 18, () -> { edit("element_add", "pool", poolId(), "template", id); adding = false; });
        }
        int bottom = Math.min(ch - 40 - 80, y + 58 + rows * 20 + 8);
        tips.on(box(x + 10, bottom + 18, w - 130, selectionName, 80, v -> selectionName = v, null), "A name for the new template, for example craftium:my_room.");
        var save = button("Save selection", x + w - 114, bottom + 17, 104, 20, () -> {
            if (selectionName.isBlank()) { localMessage = "Name the template first"; return; }
            edit("element_from_selection", "pool", poolId(), "name", selectionName.strip());
            adding = false;
        });
        save.active = num(model, "selection", 0) > 0;
        button("Close", x + w - 80, 40 + 6, 70, 20, () -> { adding = false; init(); });
    }

    // ---- drawing

    @Override protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override public void tick() {
        if (awaitTicks > 0) awaitTicks--;
        if (ClientState.jigsawModelVersion != seenVersion) {
            init();
            if (awaitTicks > 0 && model.has("retarget")) { awaitTicks = 0; retargetPrompt = true; init(); }
            if (alignAfter) { alignAfter = false; clearLayout(); init(); }
            if (leaveAfter) { leaveAfter = false; if (unappliedCount() == 0) { confirmLeave = false; finishClose(); return; } }
        }
        if (pendingWeightIndex != null && System.currentTimeMillis() - pendingAt > 350
                && GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            int index = pendingWeightIndex;
            pendingWeightIndex = null;
            edit("element_set", "pool", poolId(), "index", index, "weight", pendingWeight);
        }
    }

    private List<JsonObject> visiblePools() {
        var out = new ArrayList<JsonObject>();
        if (!model.has("pools")) return out;
        String needle = filter.toLowerCase(Locale.ROOT);
        for (var e : model.getAsJsonArray("pools")) { var p = e.getAsJsonObject(); if (needle.isEmpty() || p.get("id").getAsString().contains(needle)) out.add(p); }
        out.sort(Comparator.comparingInt(p -> str(p, "source", "").equals("craftium") ? 0 : 1));
        return out;
    }

    private int listTop() { return TOP + 24; }
    private int listBottom() { return ch - FOOT - 96; }

    @Override protected void draw(Gfx g, int mx, int my, float delta) {
        PoolThumbnails.newFrame();
        g.fill(0, 0, this.width, this.height, 0xf2101923);
        g.pose().pushMatrix();
        g.pose().scale(uiScale, uiScale);
        int smx = (int) (mx / uiScale), smy = (int) (my / uiScale);
        mx = smx;
        my = smy;
        g.text(font, "Jigsaw pools", SIDE, 12, 0xffb5dcfc, false);
        String message = !localMessage.isEmpty() ? localMessage : str(model, "message", "").isEmpty() ? ClientState.status.message() : str(model, "message", "");
        g.text(font, font.plainSubstrByWidth(message, cw - 2 * SIDE - 440), SIDE, 28, 0xffffdca8, false);
        int waiting = unappliedCount();
        if (waiting > 0) { String note = waiting + (waiting == 1 ? " unapplied change" : " unapplied changes"); g.text(font, note, cw - SIDE - font.width(note), 34, WARN, false); }
        if (model.has("registryError")) g.text(font, font.plainSubstrByWidth("A pool file has a problem: " + str(model, "registryError", ""), cw - 2 * SIDE - 200), SIDE, 38, ERROR, false);
        if (retargetPrompt) { drawRetargetPrompt(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        if (confirmLeave) { drawLeavePrompt(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        if (adding) { drawOverlay(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        drawPools(g, mx, my);
        if (pool() == null) g.text(font, "Pick a pool on the left, or make a new one.", centreX, TOP + 20, MUTED, false);
        else { drawCentre(g); if (graph) drawGraph(g, mx, my); else drawCards(g, mx, my); }
        drawConnections(g);
        drawWidgets(g, mx, my, delta);
        tips.draw(g, font, mx, my, cw, ch);
        if (filter.isEmpty()) g.text(font, "filter " + (model.has("pools") ? model.getAsJsonArray("pools").size() : 0) + " pools", SIDE + 5, TOP + 5, 0xff5f7186, false);
        if (name.isEmpty()) g.text(font, "name for New, Copy or Rename", SIDE + 5, ch - FOOT - 66 + 5, 0xff5f7186, false);
        g.pose().popMatrix();
    }

    private void drawPools(Gfx g, int mx, int my) {
        g.fill(SIDE - 4, TOP - 4, SIDE + leftW + 4, ch - FOOT + 2, PANEL);
        g.enableScissor(SIDE, listTop(), SIDE + leftW, listBottom());
        var pools = visiblePools();
        int rowW = leftW - Scrollbars.WIDTH - 3; // the bar has its own strip
        for (int i = 0; i < pools.size(); i++) {
            int y = listTop() + i * LIST_ROW - (int) poolScroll;
            if (y + LIST_ROW < listTop() || y > listBottom()) continue;
            var p = pools.get(i);
            boolean current = p.get("id").getAsString().equals(poolId()), hover = mx >= SIDE && mx < SIDE + rowW && my >= Math.max(y, listTop()) && my < Math.min(y + LIST_ROW, listBottom());
            g.fill(SIDE, y, SIDE + rowW, y + LIST_ROW - 2, current ? 0xff315473 : hover ? 0xff2a3d55 : CARD);
            String source = str(p, "source", "");
            g.fill(SIDE, y, SIDE + 3, y + LIST_ROW - 2, source.equals("craftium") ? OK : source.equals("pack") ? 0xff8fd3ff : 0xff5a6b7d);
            int errors = num(p, "errors", 0), warnings = num(p, "warnings", 0);
            String badge = errors > 0 ? errors + "!" : warnings > 0 ? warnings + "?" : "";
            int room = rowW - 12 - (badge.isEmpty() ? 0 : font.width(badge) + 6);
            g.text(font, font.plainSubstrByWidth(shortName(p.get("id").getAsString()), room), SIDE + 7, y + 5, TEXT, false);
            if (!badge.isEmpty()) g.text(font, badge, SIDE + rowW - font.width(badge) - 4, y + 5, errors > 0 ? ERROR : WARN, false);
        }
        g.disableScissor();
        Scrollbars.draw(g, SIDE + leftW - Scrollbars.WIDTH, listTop(), listBottom() - listTop(), poolScroll, pools.size() * LIST_ROW, draggingPoolBar);
        int ly = ch - FOOT - 90;
        int lx = SIDE;
        for (var key : new String[][]{{"craftium", "" + OK}, {"pack", "" + 0xff8fd3ff}, {"vanilla", "" + 0xff5a6b7d}}) {
            g.fill(lx, ly + 1, lx + 6, ly + 8, Integer.parseInt(key[1]));
            g.text(font, key[0], lx + 9, ly, 0xff6f8296, false);
            lx += font.width(key[0]) + 15;
        }
    }

    private void drawCentre(Gfx g) {
        var p = pool();
        g.fill(centreX - 4, TOP - 4, centreX + centreW + 4, ch - FOOT + 2, PANEL);
        boolean ours = p.has("overridden") && p.get("overridden").getAsBoolean();
        String tag = ours ? "in the craftium pack" : "another pack: an edit overrides it here";
        g.text(font, tag, centreX + centreW - font.width(tag), TOP, ours ? OK : MUTED, false);
        g.text(font, font.plainSubstrByWidth(p.get("id").getAsString(), centreW - font.width(tag) - 12), centreX, TOP, 0xffffffff, false);
        g.text(font, "Fallback", centreX + 2, TOP + 21, MUTED, false);
        g.text(font, "Description", centreX + 2, TOP + 45, MUTED, false);
        if (!graph) {
            int pages = Math.max(1, (elements().size() + perPage() - 1) / perPage());
            String label = (page + 1) + "/" + pages;
            g.text(font, label, centreX + centreW - 60 - font.width(label) / 2, TOP + 16 + 50 + 6, MUTED, false);
            String count = elements().size() + (elements().size() == 1 ? " element" : " elements");
            g.text(font, count, centreX + centreW - 150 - font.width(count), TOP + 16 + 50 + 6, MUTED, false);
        }
    }

    private void drawCards(Gfx g, int mx, int my) {
        var list = elements();
        int first = page * perPage();
        if (list.size() == 0) g.text(font, "No elements yet. Add a template, or save your selection as one.", centreX, cardsTop() + 6, MUTED, false);
        for (int slot = 0; slot < perPage() && first + slot < list.size(); slot++) {
            int index = first + slot, x = centreX, y = cardsTop() + slot * (CARD_H + CARD_GAP);
            var element = list.get(index).getAsJsonObject();
            boolean current = index == selected;
            g.fill(x, y, x + centreW, y + CARD_H, current ? 0xff2b4766 : CARD);
            g.outline(x, y, centreW, CARD_H, current ? ClientState.options.uiAccent : ClientState.options.uiBorder);
            g.fill(x + 6, y + 6, x + 6 + PoolThumbnails.WIDTH, y + 6 + PoolThumbnails.HEIGHT, 0xff2a3441);
            var tex = PoolThumbnails.of(str(element, "template", "?"), element.has("thumb") ? element.getAsJsonObject("thumb") : null);
            if (tex != null) g.blit(tex, x + 6, y + 6, 0, 0, PoolThumbnails.WIDTH, PoolThumbnails.HEIGHT, PoolThumbnails.WIDTH, PoolThumbnails.HEIGHT, PoolThumbnails.WIDTH, PoolThumbnails.HEIGHT, 0xffffffff);
            else g.centeredText(font, noPicture(element), x + 6 + PoolThumbnails.WIDTH / 2, y + 6 + PoolThumbnails.HEIGHT / 2 - 4, element.has("missing") ? ERROR : 0xff7e8fa0);
            int tx = x + 6 + PoolThumbnails.WIDTH + 8, w = centreW - (tx - x) - 8;
            int jigsaws = element.has("jigsaws") ? element.getAsJsonArray("jigsaws").size() : 0, issues = issues(element);
            String verdict = issues > 0 ? issues + (issues == 1 ? " problem" : " problems") : jigsaws == 0 ? (element.has("template") ? "no connectors" : "") : jigsaws + (jigsaws == 1 ? " connector, ok" : " connectors, ok");
            int verdictW = font.width(verdict) + 6;
            if (!verdict.isEmpty()) g.text(font, verdict, x + centreW - 8 - font.width(verdict), y + 6, issues > 0 ? ERROR : jigsaws == 0 ? MUTED : OK, false);
            String title = element.has("template") ? str(element, "template", "").replaceAll("^.*/", "") : str(element, "type", "?") + (element.has("feature") ? " " + shortName(str(element, "feature", "")) : "") + (element.has("count") ? " of " + num(element, "count", 0) : "");
            g.text(font, font.plainSubstrByWidth((index + 1) + ". " + title, w - verdictW), tx, y + 6, 0xffffffff, false);
            g.text(font, font.plainSubstrByWidth(str(element, "type", "") + (element.has("template") ? " · " + shortName(str(element, "template", "")) : ""), w), tx, y + 16, MUTED, false);
            var size = element.has("size") ? element.getAsJsonArray("size") : null;
            if (size != null) g.text(font, size.get(0) + " × " + size.get(1) + " × " + size.get(2) + " blocks", x + 6, y + 6 + PoolThumbnails.HEIGHT + 4, MUTED, false);
        }
    }

    /** What stands in for the picture of an element that has none, and why: a feature, an empty element and a list have no template of their own. */
    private static String noPicture(JsonObject element) {
        if (element.has("missing")) return "missing";
        String type = str(element, "type", "");
        if (!element.has("template")) return switch (type) { case "feature" -> "feature, no blocks"; case "empty" -> "empty space"; case "list" -> "a list of pieces"; default -> "no picture"; };
        if (!element.has("thumb")) return element.has("size") ? "too big to picture" : "no picture yet";
        return "no picture";
    }

    private static int issues(JsonObject element) {
        if (!element.has("jigsaws")) return 0;
        int n = 0;
        for (var j : element.getAsJsonArray("jigsaws")) if (!str(j.getAsJsonObject(), "issue", "").isEmpty()) n++;
        return n;
    }

    private void drawConnections(Gfx g) {
        int x = cw - SIDE - rightW;
        g.fill(x - 4, TOP - 4, x + rightW + 4, ch - FOOT + 2, PANEL);
        var element = element(selected);
        g.text(font, "Connections", x, TOP + 4, 0xffb5dcfc, false);
        if (element == null || !element.has("jigsaws")) { g.text(font, "Select an element to see its connectors.", x, TOP + 24, MUTED, false); return; }
        var jigsaws = element.getAsJsonArray("jigsaws");
        g.text(font, font.plainSubstrByWidth(str(element, "template", ""), rightW), x, TOP + 18, TEXT, false);
        g.text(font, font.plainSubstrByWidth("Enter applies what you typed.", rightW), x, TOP + 30, 0xff6f8296, false);
        if (jigsaws.size() == 0) { g.text(font, "This template has no connectors, so nothing grows from it.", x, TOP + 52, MUTED, false); return; }
        int per = jigsawsPerPage();
        for (int slot = 0; slot < per && jigPage * per + slot < jigsaws.size(); slot++) {
            var jig = jigsaws.get(jigPage * per + slot).getAsJsonObject();
            int y = TOP + 52 + slot * ROW_H;
            var at = jig.getAsJsonArray("pos");
            String issue = str(jig, "issue", "");
            int fits = num(jig, "fits", -1);
            g.fill(x - 2, y - 2, x + rightW + 2, y + ROW_H - 8, issue.isEmpty() ? 0x40223247 : 0x40ff5a4d);
            g.text(font, "#" + (jigPage * per + slot + 1) + " at " + at.get(0) + " " + at.get(1) + " " + at.get(2), x, y + 2, MUTED, false);
            String verdict = !issue.isEmpty() ? issue : fits < 0 ? "ends here (empty pool)" : "meets " + fits + (fits == 1 ? " element" : " elements");
            g.text(font, font.plainSubstrByWidth(verdict, rightW - 100), x + rightW - 4 - Math.min(font.width(verdict), rightW - 100), y + 2, !issue.isEmpty() ? ERROR : fits < 0 ? MUTED : OK, false);
            g.text(font, "name", x, y + 18, MUTED, false);
            g.text(font, "pool", x, y + 42, MUTED, false);
            g.text(font, "target", x + (rightW / 2 - 3) + 4, y + 42, MUTED, false);
            g.text(font, "place", x + 82, y + 66, MUTED, false);
            g.text(font, "select", x + 160, y + 66, MUTED, false);
        }
        int pages = Math.max(1, (jigsaws.size() + per - 1) / per);
        g.text(font, (jigPage + 1) + "/" + pages, x + rightW - 62 - font.width((jigPage + 1) + "/" + pages), TOP + 4, MUTED, false);
    }

    private void drawOverlay(Gfx g) {
        int w = Math.min(460, cw - 40), x = (cw - w) / 2, y = 40;
        g.fill(0, 0, cw, ch, 0xaa000000);
        g.fill(x, y, x + w, ch - 40, 0xff172330);
        g.outline(x, y, w, ch - 80, ClientState.options.uiAccent);
        g.text(font, "Add a template to " + shortName(poolId()), x + 10, y + 10, 0xffb5dcfc, false);
        g.text(font, "Search (Enter): templates of every pack and the structures you saved", x + 10, y + 24, MUTED, false);
        int rows = Math.max(1, (ch - 40 - 150 - 40) / 20);
        int found = model.has("templates") ? model.getAsJsonArray("templates").size() : 0;
        if (found == 0) g.text(font, "No templates match.", x + 10, y + 62, MUTED, false);
        else if (found > rows) g.text(font, "...and " + (found - rows) + " more: narrow the search", x + 10, y + 58 + rows * 20 + 0, MUTED, false);
        int bottom = Math.min(ch - 40 - 80, y + 58 + rows * 20 + 8);
        int blocks = num(model, "selection", 0);
        g.text(font, blocks > 0 ? "Or save your selection (" + blocks + " blocks) as a new template:" : "Or select blocks in the world to save them as a new template.", x + 10, bottom + 4, MUTED, false);
    }

    // ---- pointing the connectors behind an arrow at another pool

    private JsonObject retarget() { return model.has("retarget") ? model.getAsJsonObject("retarget") : new JsonObject(); }

    private void retargetButtons() {
        int w = Math.min(470, cw - 40), x = (cw - w) / 2, y = Math.max(50, ch / 2 - 120);
        var info = retarget();
        boolean usable = info.has("pool") && !(info.has("toMissing") && info.get("toMissing").getAsBoolean());
        button("Retarget", x + 10, y + 196, 110, 22, () -> {
            retargetPrompt = false;
            alignAfter = true;
            edit("retarget", "pool", str(info, "pool", ""), "from", str(info, "from", ""), "to", str(info, "to", ""));
        }).active = usable;
        button("Cancel", x + 126, y + 196, 100, 22, () -> { retargetPrompt = false; init(); });
    }

    private void drawRetargetPrompt(Gfx g) {
        int w = Math.min(470, cw - 40), x = (cw - w) / 2, y = Math.max(50, ch / 2 - 120);
        var info = retarget();
        g.fill(0, 0, cw, ch, 0xaa000000);
        g.fill(x, y, x + w, y + 228, 0xff172330);
        g.outline(x, y, w, 228, ClientState.options.uiAccent);
        g.text(font, "Retarget connectors", x + 10, y + 10, 0xffb5dcfc, false);
        int line = y + 26;
        int connectors = num(info, "connectors", 0), templates = info.has("templates") ? info.getAsJsonArray("templates").size() : 0;
        String to = str(info, "to", "");
        var summary = connectors + (connectors == 1 ? " connector" : " connectors") + " in " + templates + (templates == 1 ? " template" : " templates") + " of " + shortName(str(info, "pool", "")) + " lead to "
                + shortName(str(info, "from", "")) + ". " + (to.equals("minecraft:empty") ? "They would lead nowhere: nothing would grow from them." : "They would lead to " + shortName(to) + " instead.");
        for (var row : font.split(Component.literal(summary), w - 20)) { g.text(font, row, x + 10, line, TEXT, false); line += 10; }
        line += 4;
        if (info.has("templates")) {
            int shown = 0;
            for (var id : info.getAsJsonArray("templates")) {
                if (shown++ >= 4) { g.text(font, "...and " + (templates - 4) + " more templates", x + 14, line, MUTED, false); line += 10; break; }
                g.text(font, font.plainSubstrByWidth(shortName(id.getAsString()), w - 28), x + 14, line, MUTED, false);
                line += 10;
            }
        }
        line += 4;
        if (info.has("toMissing") && info.get("toMissing").getAsBoolean()) { g.text(font, shortName(to) + " does not exist, so this cannot be done.", x + 10, line, ERROR, false); line += 12; }
        if (info.has("shared") && info.getAsJsonArray("shared").size() > 0) {
            var shared = info.getAsJsonArray("shared");
            for (var row : font.split(Component.literal("Shared templates: other pools use them too, so those pools change as well."), w - 20)) { g.text(font, row, x + 10, line, WARN, false); line += 10; }
            int shown = 0;
            for (var item : shared) {
                if (shown++ >= 3) break;
                var o = item.getAsJsonObject();
                var pools = new ArrayList<String>();
                for (var p : o.getAsJsonArray("pools")) pools.add(shortName(p.getAsString()));
                int more = num(o, "more", 0);
                g.text(font, font.plainSubstrByWidth(shortName(str(o, "template", "")).replaceAll("^.*/", "") + " is in " + String.join(", ", pools) + (more > 0 ? " and " + more + " more" : ""), w - 28), x + 14, line, WARN, false);
                line += 10;
            }
        }
        int unmatched = num(info, "unmatched", 0);
        if (unmatched > 0) { line += 2; for (var row : font.split(Component.literal(unmatched + " of them ask for a name that no element of " + shortName(to) + " offers, so they would be dead ends."), w - 20)) { g.text(font, row, x + 10, line, WARN, false); line += 10; } }
    }

    // ---- leaving with changes that are not applied

    private void leavePrompt() {
        int w = Math.min(440, cw - 40), x = (cw - w) / 2, y = Math.max(60, ch / 2 - 90);
        button("Apply and leave", x + 10, y + 128, 130, 22, () -> { leaveAfter = true; edit("apply"); });
        button("Discard and leave", x + 146, y + 128, 140, 22, () -> { leaveAfter = true; edit("discard"); });
        button("Keep editing", x + 292, y + 128, w - 302, 22, () -> { confirmLeave = false; init(); });
    }

    private void drawLeavePrompt(Gfx g) {
        int w = Math.min(440, cw - 40), x = (cw - w) / 2, y = Math.max(60, ch / 2 - 90);
        g.fill(0, 0, cw, ch, 0xaa000000);
        g.fill(x, y, x + w, y + 162, 0xff172330);
        g.outline(x, y, w, 162, WARN);
        g.text(font, "Unapplied changes", x + 10, y + 10, WARN, false);
        int count = unappliedCount(), line = y + 26;
        for (var row : font.split(Component.literal(count + (count == 1 ? " change is" : " changes are") + " waiting in a draft. The preview uses the draft; the datapack gets it only when you apply."), w - 20)) { g.text(font, row, x + 10, line, MUTED, false); line += 10; }
        var summary = unapplied();
        int shown = 0;
        for (var group : new String[][]{{"pools", "pool"}, {"templates", "template"}, {"deleted", "deleted"}}) {
            if (!summary.has(group[0])) continue;
            for (var id : summary.getAsJsonArray(group[0])) {
                if (shown++ >= 5) break;
                g.text(font, font.plainSubstrByWidth(group[1] + " " + id.getAsString(), w - 24), x + 14, line + 4, TEXT, false);
                line += 10;
            }
        }
        if (count > 5) g.text(font, "...and " + (count - 5) + " more", x + 14, line + 4, MUTED, false);
    }

    // ---- the graph

    private record Node(String id, boolean missing, int elements, int depth, int row) {}

    private void drawGraph(Gfx g, int mx, int my) {
        var graphModel = model.has("graph") ? model.getAsJsonObject("graph") : null;
        int top = cardsTop() - 4;
        if (graphModel == null || graphModel.getAsJsonArray("nodes").size() == 0) { g.text(font, "Nothing to draw: the pools are not loaded (see the message above).", centreX, top + 6, MUTED, false); return; }
        var nodes = layout(graphModel);
        int nodeW = nodeWidth(nodes), nodeH = NODE_H, gap = 20;
        graphScroll = Math.clamp(graphScroll, 0, Math.max(0, graphContent(nodes) - (graphBottom() - top)));
        g.enableScissor(centreX, top, centreX + centreW, graphBottom());
        var place = new HashMap<String, int[]>();
        for (var n : nodes) place.put(n.id(), placeOf(n, nodeW, top));
        Edge hovered = null;
        for (var edge : edgeShapes(nodes, graphModel.getAsJsonArray("edges"), nodeW, top)) {
            boolean lifted = edgeDragging && pressEdge != null && edge.from().equals(pressEdge.from()) && edge.to().equals(pressEdge.to());
            int x1 = edge.x1(), y1 = edge.y1(), x2 = edge.x2(), y2 = edge.y2();
            int mid = x2 > x1 ? (x1 + x2) / 2 : x1 + gap / 2;
            int colour = lifted ? 0xff2f4358 : 0xff4a6585;
            g.fill(x1, y1, mid + 1, y1 + 1, colour);
            g.fill(mid, Math.min(y1, y2), mid + 1, Math.max(y1, y2) + 1, colour);
            if (x2 > mid) g.fill(mid, y2, x2 - 9, y2 + 1, colour);
            boolean over = !edgeDragging && mx >= x2 - 11 && mx < x2 + 1 && my >= y2 - 5 && my < y2 + 5 && my >= top;
            if (over) hovered = edge;
            int handle = over ? 0xffffdca8 : lifted ? 0xff5a6f85 : 0xff8fd3ff;
            g.fill(x2 - 9, y2 - 3, x2 - 2, y2 + 4, handle); // the square to take hold of
            g.fill(x2 - 2, y2 - 1, x2, y2 + 2, handle);
        }
        for (var n : nodes) {
            var at = place.get(n.id());
            boolean current = n.id().equals(poolId()), hover = mx >= at[0] && mx < at[0] + nodeW && my >= at[1] && my < at[1] + nodeH && my >= top;
            boolean target = edgeDragging && pressEdge != null && hover && !n.missing() && !n.id().equals(pressEdge.to());
            g.fill(at[0], at[1], at[0] + nodeW, at[1] + nodeH, n.missing() ? 0xff5a2a2a : current ? 0xff315473 : hover ? 0xff2f4663 : CARD);
            g.outline(at[0], at[1], nodeW, nodeH, target ? OK : current ? ClientState.options.uiAccent : ClientState.options.uiBorder);
            String label = shortName(n.id()).replaceAll("^.*/", "") + (n.missing() ? " (missing)" : " · " + n.elements());
            g.text(font, font.plainSubstrByWidth(label, nodeW - 6), at[0] + 3, at[1] + 5, n.missing() ? ERROR : TEXT, false);
        }
        if (hovered != null) {
            String tip = hovered.count() + (hovered.count() == 1 ? " connector" : " connectors") + " · drag the square onto a pool to retarget";
            int lx = Math.max(centreX + 4, hovered.x2() - font.width(tip) - 12), ly = hovered.y2() - 20;
            g.fill(lx - 3, ly - 2, lx + font.width(tip) + 3, ly + 10, 0xf0101923);
            g.text(font, tip, lx, ly, TEXT, false);
        }
        if (edgeDragging && pressEdge != null) { // an arrow being dragged: a line to the pointer, and a box that disconnects it
            int x1 = pressEdge.x1(), y1 = pressEdge.y1(), x2 = (int) edgeX, y2 = (int) edgeY, mid = (x1 + x2) / 2;
            g.fill(x1, y1, mid + 1, y1 + 1, 0xffffdca8);
            g.fill(mid, Math.min(y1, y2), mid + 1, Math.max(y1, y2) + 1, 0xffffdca8);
            g.fill(mid, y2, x2 + 1, y2 + 1, 0xffffdca8);
            g.fill(x2 - 4, y2 - 4, x2 + 3, y2 + 3, 0xffffdca8);
        }
        g.disableScissor();
        if (edgeDragging && pressEdge != null) { // the box that disconnects an arrow stays visible however the graph is scrolled
            int zx = centreX + 4, zy = disconnectY();
            boolean over = edgeX >= zx && edgeX < zx + 180 && edgeY >= zy && edgeY < zy + 22;
            g.fill(zx, zy, zx + 180, zy + 22, over ? 0xff6a3a2a : 0xff3a2a28);
            g.outline(zx, zy, 180, 22, over ? ERROR : 0xff8a5a4a);
            g.centeredText(font, "Drop here to disconnect", zx + 90, zy + 7, TEXT);
        }
        var bar = graphBar();
        if (bar != null) Scrollbars.draw(g, bar[0], bar[1], bar[2], graphScroll, bar[3], draggingGraphBar);
        var hint = font.split(Component.literal(HINT), centreW);
        for (int i = 0; i < hint.size(); i++) g.text(font, hint.get(i), centreX, ch - FOOT - 4 - (hint.size() - i) * 10, 0xff6f8296, false);
    }

    /** Where the graph shows, down to just above the hint, and how tall the pools in it stand together. */
    private int graphBottom() { return disconnectY() - 4; }

    private int graphContent(List<Node> nodes) {
        int bottom = 0;
        for (var n : nodes) { var moved = NODE_POS.get(nodeKey(n.id())); bottom = Math.max(bottom, (moved != null ? moved[1] : n.row() * GRAPH_ROW) + NODE_H + 8); }
        return bottom + 4;
    }

    /** The graph's scrollbar as x, y, room and content, or null when the graph is not shown or all of it fits. */
    private int[] graphBar() {
        if (!graph || pool() == null || !model.has("graph")) return null;
        int top = cardsTop() - 4, room = graphBottom() - top, content = graphContent(layout(model.getAsJsonObject("graph")));
        return content > room ? new int[]{centreX + centreW - Scrollbars.WIDTH, top, room, content} : null;
    }

    private int nodeWidth(List<Node> nodes) {
        int maxDepth = nodes.stream().mapToInt(Node::depth).max().orElse(0);
        return Math.clamp((centreW - 10) / (maxDepth + 1) - 22, 60, 170);
    }

    /** Where a pool is drawn: where the player dragged it, or in its column by the layout. */
    private int[] placeOf(Node n, int nodeW, int top) {
        var moved = NODE_POS.get(nodeKey(n.id()));
        if (moved != null) return new int[]{centreX + 4 + moved[0], top + 4 + moved[1] - (int) graphScroll};
        return new int[]{centreX + 4 + n.depth() * (nodeW + 20), top + 4 + n.row() * GRAPH_ROW - (int) graphScroll};
    }

    /** The arrows of the graph with where each leaves and ends: the ends of the arrows that meet at a pool are spread down its side, so each can be taken hold of. */
    private List<Edge> edgeShapes(List<Node> nodes, JsonArray edges, int nodeW, int top) {
        var place = new HashMap<String, int[]>();
        for (var n : nodes) place.put(n.id(), placeOf(n, nodeW, top));
        var incoming = new HashMap<String, List<JsonObject>>();
        for (var e : edges) {
            var edge = e.getAsJsonObject();
            String from = str(edge, "from", ""), to = str(edge, "to", "");
            if (from.equals(to) || !place.containsKey(from) || !place.containsKey(to)) continue;
            incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(edge);
        }
        var out = new ArrayList<Edge>();
        for (var entry : incoming.entrySet()) {
            var list = entry.getValue();
            list.sort(Comparator.<JsonObject>comparingInt(edge -> place.get(str(edge, "from", ""))[1]));
            var b = place.get(entry.getKey());
            for (int i = 0; i < list.size(); i++) {
                var edge = list.get(i);
                var a = place.get(str(edge, "from", ""));
                int y2 = list.size() == 1 ? b[1] + NODE_H / 2 : b[1] + 5 + i * (NODE_H - 10) / (list.size() - 1);
                out.add(new Edge(str(edge, "from", ""), entry.getKey(), num(edge, "count", 0), a[0] + nodeW, a[1] + NODE_H / 2, b[0], y2));
            }
        }
        return out;
    }

    /** Where a moved pool lands: on the grid the layout uses, in a place no other pool has. */
    private int[] snap(String id, int[] at) {
        if (!model.has("graph")) return at;
        var nodes = layout(model.getAsJsonObject("graph"));
        int cellW = nodeWidth(nodes) + 20, cellH = GRAPH_ROW;
        int x = Math.max(0, Math.round((float) at[0] / cellW) * cellW), y = Math.max(0, Math.round((float) at[1] / cellH) * cellH);
        boolean free;
        do {
            free = true;
            for (var n : nodes) {
                if (n.id().equals(id)) continue;
                var moved = NODE_POS.get(nodeKey(n.id()));
                int nx = moved != null ? moved[0] : n.depth() * cellW, ny = moved != null ? moved[1] : n.row() * cellH;
                if (nx == x && ny == y) { y += cellH; free = false; break; }
            }
        } while (!free);
        return new int[]{x, y};
    }

    /** Forgets where the player put the pools of this graph, so the layout places them: after a retarget the columns have changed. */
    private void clearLayout() {
        String prefix = poolId() + "|";
        NODE_POS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** An arrow was dropped: asks the server what pointing its connectors at the pool under it would change, which is then put to the player. */
    private void dropEdge(Edge edge, double x, double y) {
        var nodes = layout(model.getAsJsonObject("graph"));
        int nodeW = nodeWidth(nodes), top = cardsTop() - 4;
        String to = null;
        if (x >= centreX + 4 && x < centreX + 4 + 180 && y >= disconnectY() && y < disconnectY() + 22) to = "minecraft:empty";
        else for (var n : nodes) {
            var at = placeOf(n, nodeW, top);
            if (!n.missing() && x >= at[0] && x < at[0] + nodeW && y >= Math.max(at[1], top) && y < at[1] + NODE_H) to = n.id();
        }
        if (to == null) { localMessage = "Drop the arrow on a pool, or in the red box"; init(); return; }
        if (to.equals(edge.to())) { localMessage = "That arrow already ends there"; init(); return; }
        awaitTicks = 40;
        edit("retarget_check", "pool", edge.from(), "from", edge.to(), "to", to);
    }

    /** Puts every pool of this graph back where the layout places it, as one step that can be undone. */
    private void resetLayout() {
        var before = new HashMap<String, int[]>();
        String prefix = poolId() + "|";
        for (var e : NODE_POS.entrySet()) if (e.getKey().startsWith(prefix)) before.put(e.getKey(), e.getValue().clone());
        if (before.isEmpty()) { localMessage = "The pools are already where the layout puts them"; return; }
        for (var key : before.keySet()) NODE_POS.remove(key);
        localUndo.push(new Local(Kind.RESET, null, null, null, before));
        localRedo.clear();
        localMessage = "";
        init();
    }

    /** The graph's pools in columns by how many steps they are from the pool being edited. */
    private List<Node> layout(JsonObject graphModel) {
        var depth = new LinkedHashMap<String, Integer>();
        var edges = graphModel.getAsJsonArray("edges");
        var queue = new ArrayDeque<String>();
        depth.put(poolId(), 0);
        queue.add(poolId());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            for (var e : edges) { var edge = e.getAsJsonObject(); if (str(edge, "from", "").equals(id) && !depth.containsKey(str(edge, "to", ""))) { depth.put(str(edge, "to", ""), depth.get(id) + 1); queue.add(str(edge, "to", "")); } }
        }
        var rows = new HashMap<Integer, Integer>();
        var out = new ArrayList<Node>();
        for (var n : graphModel.getAsJsonArray("nodes")) {
            var node = n.getAsJsonObject();
            String id = str(node, "id", "");
            int d = depth.getOrDefault(id, 0), row = rows.merge(d, 1, Integer::sum) - 1;
            out.add(new Node(id, node.has("missing") && node.get("missing").getAsBoolean(), num(node, "elements", 0), d, row));
        }
        return out;
    }

    // ---- input

    private MouseButtonEvent scaled(MouseButtonEvent event) { return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo()); }

    @Override public boolean mouseReleased(MouseButtonEvent raw) {
        var event = scaled(raw);
        if (draggingPoolBar || draggingGraphBar) { draggingPoolBar = false; draggingGraphBar = false; return true; }
        if (pressEdge != null && event.button() == 0) {
            var edge = pressEdge;
            boolean carried = edgeDragging;
            pressEdge = null;
            edgeDragging = false;
            if (carried) dropEdge(edge, event.x(), event.y());
            return true;
        }
        if (pressNode != null && event.button() == 0) {
            var id = pressNode;
            boolean moved = dragging;
            pressNode = null;
            dragging = false;
            if (moved) {
                var to = NODE_POS.get(pressKey);
                if (to != null) {
                    to = snap(id, to);
                    NODE_POS.put(pressKey, to);
                    localUndo.push(new Local(Kind.MOVE, pressKey, pressFrom, to.clone(), null));
                    localRedo.clear();
                }
                init();
            } else if (!pressMissing) { selected = 0; page = 0; edit("open", "pool", id); }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override public boolean mouseDragged(MouseButtonEvent raw, double dx, double dy) {
        var event = scaled(raw);
        if (draggingPoolBar) { poolScroll = Scrollbars.scrollAt(event.y(), listTop(), listBottom() - listTop(), visiblePools().size() * LIST_ROW); return true; }
        if (draggingGraphBar) { var bar = graphBar(); if (bar != null) graphScroll = Scrollbars.scrollAt(event.y(), bar[1], bar[2], bar[3]); return true; }
        if (pressEdge != null) {
            if (!edgeDragging && Math.hypot(event.x() - edgePressX, event.y() - edgePressY) > 3) edgeDragging = true;
            edgeX = event.x();
            edgeY = event.y();
            return true;
        }
        if (pressNode != null) {
            double moveX = event.x() - pressX, moveY = event.y() - pressY;
            if (!dragging && Math.hypot(moveX, moveY) > 3) dragging = true;
            if (dragging) NODE_POS.put(pressKey, new int[]{Math.max(0, (int) Math.round(pressOrigin[0] + moveX)), Math.max(0, (int) Math.round(pressOrigin[1] + moveY))});
            return true;
        }
        return super.mouseDragged(event, dx / uiScale, dy / uiScale);
    }

    @Override public boolean mouseClicked(MouseButtonEvent raw, boolean twice) {
        var event = scaled(raw);
        if (super.mouseClicked(event, twice)) return true;
        if (event.button() != 0 || adding) return false;
        double x = event.x(), y = event.y();
        int poolRoom = listBottom() - listTop(), poolContent = visiblePools().size() * LIST_ROW;
        if (poolContent > poolRoom && Scrollbars.hit(x, y, SIDE + leftW - Scrollbars.WIDTH, listTop(), poolRoom)) { draggingPoolBar = true; poolScroll = Scrollbars.scrollAt(y, listTop(), poolRoom, poolContent); return true; }
        var graphBar = graphBar();
        if (graphBar != null && Scrollbars.hit(x, y, graphBar[0], graphBar[1], graphBar[2])) { draggingGraphBar = true; graphScroll = Scrollbars.scrollAt(y, graphBar[1], graphBar[2], graphBar[3]); return true; }
        if (x >= SIDE && x < SIDE + leftW - Scrollbars.WIDTH - 3 && y >= listTop() && y < listBottom()) {
            int i = (int) ((y - listTop() + poolScroll) / LIST_ROW);
            var pools = visiblePools();
            if (i >= 0 && i < pools.size()) { selected = 0; page = 0; jigPage = 0; edit("open", "pool", pools.get(i).get("id").getAsString()); return true; }
        }
        if (pool() != null && !graph && x >= centreX && x < centreX + centreW && y >= cardsTop()) {
            int slot = (int) ((y - cardsTop()) / (CARD_H + CARD_GAP)), index = page * perPage() + slot;
            if (index < elements().size()) { selected = index; jigPage = 0; init(); return true; }
        }
        if (pool() != null && graph && model.has("graph")) {
            var nodes = layout(model.getAsJsonObject("graph"));
            int nodeW = nodeWidth(nodes), top = cardsTop() - 4;
            for (var edge : edgeShapes(nodes, model.getAsJsonObject("graph").getAsJsonArray("edges"), nodeW, top)) {
                if (x >= edge.x2() - 11 && x < edge.x2() + 1 && y >= edge.y2() - 5 && y < edge.y2() + 5 && y >= top && y < graphBottom()) {
                    pressEdge = edge; edgePressX = x; edgePressY = y; edgeX = x; edgeY = y; edgeDragging = false;
                    return true;
                }
            }
            for (var n : nodes) {
                var at = placeOf(n, nodeW, top);
                if (x >= at[0] && x < at[0] + nodeW && y >= Math.max(at[1], top) && y < Math.min(at[1] + NODE_H, graphBottom())) {
                    // A press on a pool: it opens when let go in place, and moves when dragged.
                    pressNode = n.id(); pressKey = nodeKey(n.id()); pressMissing = n.missing(); dragging = false;
                    pressX = x; pressY = y;
                    var moved = NODE_POS.get(pressKey);
                    pressFrom = moved == null ? null : moved.clone();
                    pressOrigin = new int[]{at[0] - (centreX + 4), at[1] - (top + 4) + (int) graphScroll};
                    return true;
                }
            }
        }
        return false;
    }

    @Override public boolean mouseScrolled(double rawX, double rawY, double horizontal, double vertical) {
        double x = rawX / uiScale;
        if (adding) return false;
        if (x < SIDE + leftW + 6) { poolScroll = Math.clamp(poolScroll - vertical * 30, 0, Math.max(0, visiblePools().size() * LIST_ROW - (listBottom() - listTop()))); return true; }
        if (graph && x < cw - SIDE - rightW) { var bar = graphBar(); graphScroll = Math.clamp(graphScroll - vertical * 30, 0, bar == null ? 0 : bar[3] - bar[2]); return true; }
        return false;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (getFocused() instanceof EditBox box && commits.containsKey(box)) { commits.get(box).run(); return true; }
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && adding) { adding = false; init(); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { PoolThumbnails.releaseAll(); }
    @Override public void onClose() {
        if (retargetPrompt) { retargetPrompt = false; init(); return; }
        if (confirmLeave) { confirmLeave = false; init(); return; } // Escape on the question means keep editing
        if (unappliedCount() > 0) { confirmLeave = true; init(); return; }
        finishClose();
    }
}
