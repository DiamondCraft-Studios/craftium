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
 * The jigsaw pool editor. At the top left a dropdown picks the pool (the craftium pack's, and every other pack's, which are overridden in the craftium pack when edited); on
 * the left the elements of the pool as cards with a picture, a weight and a projection, in the middle the preview of the selected element, on the right its connectors, each
 * with how many elements it can meet, and a graph of how the pools lead to each other. The server does all the editing, by changing the pack's files
 * (so the live preview follows) and answering with a model; every change is one undo step. A text box applies what is typed when Enter is pressed.
 */
final class JigsawEditorScreen extends CraftiumScreen {
    private static final int SIDE = 12, TOP = 50, FOOT = 12, CARD_H = 98, CARD_GAP = 6, ROW_H = 160, FOLDED_H = 22, LIST_ROW = 20, MENU_ROWS = 12, CONN_HEAD = 72, THUMB_W = 60, THUMB_H = 42, DROP = 18, TARGET_ROW = 38;
    /** The preview of the selected element, in the middle column: a label line and then the picture, in canvas pixels. */
    private static final int PREVIEW_LABEL = 14, MIN_RIGHT = 220, MIN_CENTRE = 200, MIN_PREVIEW = 160, GAP = 10;
    /** The widths of the preview column and the connectors column, as the player has dragged them (0: as laid out); kept while the game runs. */
    private static int rightPref, previewPref;
    /** The connectors column shows its connectors as a List (each folded or open), or Single: the picked one in a window on top with the others listed under it. Kept while the game runs. */
    private static boolean singleView;
    /** Which divider is being dragged (2 elements | preview, 3 preview | connectors), where the pointer was and what the size was then. */
    private int resizing, resizeFrom, resizeStart;
    /** The width of the Back and Forward tabs under the header, which the messages start after. */
    private static final int NAV_W = 160, LINK = 26;
    private static final String[] SORTS = {"Pool order", "Name", "Weight", "Connectors", "Size", "Type", "Problems"};
    private static final int PANEL = 0xe0172330, CARD = 0xff223247, MUTED = 0xff9db5cb, TEXT = 0xffdce6ef, WARN = 0xffffd479, ERROR = 0xffff8a80, OK = 0xff8fe3a0;

    private final Screen parent;
    private final OrbitPreview preview = new OrbitPreview();
    private int sortMode;
    private boolean sortDesc;
    private JsonObject model = new JsonObject();
    private int seenVersion = -1, selected, page, jigPage;
    private boolean graph, adding;
    private String filter = "", name = "", query = "", selectionName = "";
    private double poolScroll, graphScroll;
    private boolean draggingGraphBar, draggingPoolBar, poolMenuOpen;
    private EditBox poolBox;
    private int pickW;
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
    /** The add panel's tab (template, selection, feature, empty), what was last searched for among the features, the guide, and the name being asked for. */
    private int addTab;

    // ---- the block suggestions of "Turns into"
    private static final int SUGGEST_ROW = 12, SUGGEST_ROWS = 7;
    private static List<String> allBlocks;
    /** The boxes that suggest, with what each suggests from: blocks, pools or connector names. */
    private final Map<EditBox, List<String>> suggestSources = new HashMap<>();
    private List<String> suggestSource = List.of();
    private final List<String> suggestMatches = new ArrayList<>();
    private int suggestHighlight = -1, suggestScroll, lastMouseX = -1, lastMouseY = -1;
    private boolean suggesting;
    private String suggestTyped = "";
    private EditBox suggestBox;
    private static final String SIDEWAYS = "This connector faces sideways, and a joint only matters for a connector that faces up or down, so there is nothing to set here.";
    private String featureQuery = "";
    private boolean helpOpen;
    private String dialogMode, dialogText = "";
    private int rightW, previewW, centreX, centreW;
    /** The connector the player picked in the preview (its fold key), shown lit until another is picked; and which one the pointer is over in the preview, or -1. */
    private String pickedJig;
    private int previewJig = -1;
    private double pressPreviewX, pressPreviewY;
    /** The pools the target boxes show elements of, as the server sent them (by pool id), and the ones last asked for. */
    private final Map<String, JsonObject> targetPools = new HashMap<>();
    private String askedPools = "";
    /** The open target dropdown: the connector it belongs to (its fold key), where it hangs, which pool it lists, what choosing a row does, and how far it is scrolled. */
    private String targetMenuKey;
    private int targetMenuX, targetMenuY, targetMenuW, targetMenuButtonX, targetMenuButtonY;
    private String targetMenuPool = "", targetMenuCurrent = "";
    private java.util.function.Consumer<String> targetMenuPick;
    private double targetMenuScroll;
    private boolean draggingTargetBar;
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
            case "open", "templates", "features", "names", "undo", "redo", "retarget_check", "used_by", "target_pools", "bedrock_plan", "bedrock_export" -> { }
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
        if (model.has("usedBy")) { usedModel = model.getAsJsonArray("usedBy"); usedFor = poolId(); usedTruncated = model.has("usedByTruncated") && model.get("usedByTruncated").getAsBoolean(); }
        targetPools.clear();
        if (model.has("targetPools")) for (var each : model.getAsJsonArray("targetPools")) { var one = each.getAsJsonObject(); targetPools.put(str(one, "id", ""), one); }
        resolveTarget();
        var elements = elements();
        if (selected >= elements.size()) selected = Math.max(0, elements.size() - 1);
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

    /** A box that applies what is typed: on Enter, when the focus leaves it, and before the screen is rebuilt, so an edit is never lost by clicking elsewhere. */
    private EditBox applyBox(int x, int y, int w, String value, int max, java.util.function.Consumer<String> apply) {
        var field = box(x, y, w, value, max, null, null);
        applied.put(field, apply);
        original.put(field, value);
        return field;
    }

    private final Map<EditBox, java.util.function.Consumer<String>> applied = new HashMap<>();
    private final Map<EditBox, String> original = new HashMap<>();
    private EditBox lastFocusedApply;

    /** Applies the box if it holds something other than what it was made with. */
    private void applyIfChanged(EditBox field) {
        var apply = applied.get(field);
        if (apply == null || field.getValue().equals(original.get(field))) return;
        original.put(field, field.getValue());
        apply.accept(field.getValue());
    }

    private static void selectAll(EditBox box) {
        box.moveCursorToEnd(false);
        box.setHighlightPos(0);
    }

    private void flushEdits() { for (var field : new ArrayList<>(applied.keySet())) applyIfChanged(field); }

    @Override protected void init() {
        flushEdits(); // typed but not yet applied: send it before the widgets are made again
        applied.clear();
        original.clear();
        lastFocusedApply = null;
        parseModel();
        PoolThumbnails.newFrame();
        clearWidgets();
        uiScale = Math.min(1f, Math.min(this.width / 800f, this.height / 440f));
        cw = Math.round(this.width / uiScale);
        ch = Math.round(this.height / uiScale);
        commits.clear();
        suggestSources.clear();
        tips.clear();
        int third = (cw - 2 * SIDE - 2 * GAP) / 3; // by default the elements, the preview and the connectors each take a third
        rightW = rightPref > 0 ? rightPref : third;
        rightW = Math.clamp(rightW, MIN_RIGHT, Math.max(MIN_RIGHT, cw - 2 * SIDE - 2 * GAP - MIN_CENTRE - MIN_PREVIEW));
        previewW = previewPref > 0 ? previewPref : third;
        previewW = Math.clamp(previewW, MIN_PREVIEW, Math.max(MIN_PREVIEW, cw - 2 * SIDE - 2 * GAP - MIN_CENTRE - rightW));
        centreX = SIDE;
        centreW = Math.max(200, cw - SIDE - rightW - GAP - previewW - GAP - centreX);
        int unapplied = unappliedCount();
        int redoCount = localRedo.isEmpty() ? num(model, "redo", 0) : localRedo.size(), undoCount = localUndo.isEmpty() ? num(model, "undo", 0) : localUndo.size();
        button("Close", cw - SIDE - 70, 10, 70, 20, this::onClose);
        button("Redo (" + redoCount + ")", cw - SIDE - 70 - 76, 10, 70, 20, this::redo).active = redoCount > 0;
        button("Undo (" + undoCount + ")", cw - SIDE - 70 - 152, 10, 70, 20, this::undo).active = undoCount > 0;
        button("Discard", cw - SIDE - 70 - 152 - 76, 10, 70, 20, () -> edit("discard")).active = unapplied > 0;
        button("Apply (" + unapplied + ")", cw - SIDE - 70 - 152 - 76 - 102, 10, 96, 20, () -> edit("apply")).lit(unapplied > 0).active = unapplied > 0;
        button("?", cw - SIDE - 70 - 152 - 76 - 102 - 28, 10, 22, 20, () -> { helpOpen = true; init(); });
        button("Bedrock pack…", cw - SIDE - 70 - 152 - 76 - 102 - 28 - 112, 10, 106, 20, this::openBedrock).active = pool() != null;
        if (retargetPrompt) { retargetButtons(); applyTips(); return; }
        if (confirmLeave) { leavePrompt(); applyTips(); return; }
        if (dialogMode != null) { dialogButtons(); applyTips(); return; }
        if (helpOpen) { helpButtons(); applyTips(); return; }
        if (adding) { overlay(); applyTips(); return; }
        button("‹ Back", SIDE, 24, 70, 18, () -> stepHistory(-1)).active = historyAt > 0;
        button("Forward ›", SIDE + 74, 24, 78, 18, () -> stepHistory(1)).active = historyAt >= 0 && historyAt < poolHistory.size() - 1;
        poolPicker();
        if (pool() != null) {
            centreHeader();
            if (!graph && !usedView) {
                cards();
                button("+ Add element", centreX, ch - FOOT - 22, centreW, 20, () -> { adding = true; addTab = 0; query = ""; init(); });
            }
        }
        previewButtons();
        rightPane();
        applyTips();
    }

    /** Gives each button its tip by what it says, so the buttons whose label changes (Undo, Rollable, a template name) are covered where they are made. */
    private void applyTips() {
        for (var child : children()) {
            if (!(child instanceof FlatButton b) || tips.has(b)) continue;
            String label = b.getMessage().getString();
            String key = label.startsWith("Sort: ") ? "Sort" : label.startsWith("Create pool ") ? "Create connector pool" : label.startsWith("Undo") ? "Undo" : label.startsWith("Redo") ? "Redo" : label.startsWith("Apply (") ? "Apply" : label;
            String tip = switch (key) {
                case "Close" -> adding || helpOpen || dialogMode != null ? "Closes this panel." : confirmLeave ? "Keeps editing." : "Back to the planner. If changes are not applied you are asked what to do with them.";
                case "Apply" -> "Writes the changes you have made here into datapacks/craftium, where the game reads them. Until then they wait in a draft: this editor and the preview use the draft, the datapack does not. Undo history ends here.";
                case "Discard" -> "Throws away every change made since the last Apply. Undo cannot bring them back.";
                case "Apply and leave" -> "Applies the waiting changes to datapacks/craftium, then goes back to the planner.";
                case "Discard and leave" -> "Throws the waiting changes away, then goes back to the planner.";
                case "Keep editing" -> "Stays in the editor with the changes still waiting.";
                case "‹ Back" -> "Goes back to the pool you had open before this one.";
                case "Forward ›" -> "Goes forward again to the pool you came back from.";
                case "Used by" -> "Lists the connectors of every pool that draw from this pool: where this pool is used from. Click one to go to its pool and element.";
                case "Auto-align" -> "Puts every pool back in its column and row, as the automatic layout places them. You can undo it.";
                case "Retarget" -> "Points those connectors at the new pool. It is one undo step, and nothing reaches the datapack until you Apply.";
                case "Cancel" -> retargetPrompt ? "Leaves the connectors as they are." : "Closes this without making anything.";
                case "Undo" -> "Takes back your last change here: an edit to a pool or template, or a move of a pool in the graph.";
                case "Redo" -> "Puts back a change you took back, including a move in the graph.";
                case "Bedrock pack…" -> "Makes a file for Minecraft Bedrock Edition (an .mcpack) from the open pool and every pool and piece it leads to. You name it, see what Bedrock cannot hold, then choose where to save it.";
                case "New…" -> "Makes an empty pool. You are asked for its name: without a namespace it goes in craftium.";
                case "?" -> "A short guide: what a pool is, how to make a structure, and what the connector fields mean.";
                case "Template" -> "Adds a template that already exists: one of the game's, another pack's, or one you saved.";
                case "Selection" -> "Saves the blocks you selected as a new template and adds it.";
                case "Feature" -> "Adds a feature, such as a tree or a flower, that the game places by itself.";
                case "Empty" -> "Adds an empty slot, which places nothing.";
                case "Structure" -> "Imports a structure file from your computer as a template.";
                case "Add an empty slot" -> "Adds an element that places nothing, so a connector can end here some of the time.";
                case "Create pool", "Copy pool", "Rename pool" -> "Does it with the name typed above.";
                case "Create connector pool" -> "Makes an empty pool with this name, so the connector has somewhere to point. Add elements to it afterwards.";
                case "Copy…" -> "Copies the open pool under a new name that you are asked for.";
                case "Rename…" -> "Renames the open pool, if it is in the craftium pack. Connectors that point at the old name are not changed.";
                case "Delete" -> "Deletes the open pool from the craftium pack. A pool of another pack that it overrode shows again. You can undo it.";
                case "Preview from here" -> "Runs the planner with this pool as the start pool, using the depth, distance and seed of the planner.";
                case "Reset" -> "Puts the view of the preview back: the angle, the zoom and where it was moved to.";
                case "Top" -> "Looks straight down at the piece, with north at the top.";
                case "Front" -> "Looks at the piece from the south, level.";
                case "Sort" -> "Changes how the elements are listed here: pool order, name, weight, connectors, size, type, or the ones with problems first. It only changes the list: the pool keeps its own order, which you can change with the arrows in Pool order.";
                case "Asc", "Desc" -> "Turns the sort the other way round.";
                case "Elements" -> "Shows the elements of this pool as cards.";
                case "Graph" -> "Shows how this pool leads to other pools through the connectors of its templates. Click a pool to open it.";
                case "+ Add element" -> "Adds an element to this pool: a template (found, or made from your selection), a feature such as a tree, or an empty slot.";
                case "‹" -> "Previous page.";
                case "›" -> "Next page.";
                case "↑" -> "Moves this element up the list. The order does not change how often it is chosen; the weight does.";
                case "↓" -> "Moves this element down the list. The order does not change how often it is chosen; the weight does.";
                case "Remove" -> "Removes this element from the pool. The template itself is kept.";
                case "Rollable" -> b.active ? "Rollable: a piece joined to a vertical connector may be turned any way round it. Click to make it aligned." : SIDEWAYS;
                case "Aligned" -> b.active ? "Aligned: the joined piece is turned to line up with the top of this connector. Click to make it rollable." : SIDEWAYS;
                case "Save selection" -> "Saves the blocks you selected as a new template, and adds it to this pool.";
                default -> adding ? "Adds this to the pool as a new element." : null;
            };
            if (tip != null) tips.on(b, tip);
        }
    }

    /** The dropdown that picks the pool, and the buttons that make, copy, rename and delete one, along the top of the middle. */
    private void poolPicker() {
        int y = TOP - 4, buttons = buttonsW();
        pickW = Math.clamp(centreW - 24 - 6 - buttons - 8, 90, 320);
        poolBox = box(centreX, y, pickW, filter, 64, v -> { filter = v; poolMenuOpen = true; poolScroll = 0; }, this::pickFirstPool);
        tips.on(poolBox, "Type part of a pool name to narrow the list, then pick a pool. Start a word with @ to narrow by namespace: @minecraft shows only the pools of minecraft, and @craftium town only the town pools of craftium. Enter opens the first one.");
        var arrow = button(poolMenuOpen ? "▴" : "▾", centreX + pickW + 2, y, 22, 18, () -> toggleMenu());
        tips.on(arrow, "Shows the list of pools to pick from.");
        int x = centreX + pickW + 24 + 6;
        int unit = (buttons - 3 * 4) * 100 / 224; // New, Copy, Rename and Delete share the room in the ratio 54 : 54 : 62 : 54
        int newW = unit * 54 / 100, copyW = unit * 54 / 100, renameW = unit * 62 / 100, deleteW = buttons - 3 * 4 - newW - copyW - renameW;
        button("New…", x, y, newW, 18, () -> openDialog("pool_new"));
        button("Copy…", x + newW + 4, y, copyW, 18, () -> { if (!poolId().isEmpty()) openDialog("pool_copy"); });
        button("Rename…", x + newW + copyW + 8, y, renameW, 18, () -> { if (!poolId().isEmpty()) openDialog("pool_rename"); });
        button("Delete", x + newW + copyW + renameW + 12, y, deleteW, 18, () -> { if (!poolId().isEmpty()) edit("pool_delete", "id", poolId()); });
    }

    /** The room the four pool buttons take: all of it in a wide window, less in a narrow one so the dropdown keeps some. */
    private int buttonsW() { return Math.clamp(centreW * 45 / 100, 176, 236); }

    private void toggleMenu() {
        poolMenuOpen = !poolMenuOpen;
        if (poolMenuOpen) scrollMenuToOpenPool();
        else filter = "";
        init();
    }

    /** Scrolls the menu so the open pool shows, as the left list used to when the editor opened. */
    private void scrollMenuToOpenPool() {
        var pools = visiblePools();
        for (int i = 0; i < pools.size(); i++)
            if (pools.get(i).get("id").getAsString().equals(poolId())) { poolScroll = Math.clamp((i - menuRows(pools.size()) / 2) * LIST_ROW, 0, Math.max(0, (pools.size() - menuRows(pools.size())) * LIST_ROW)); return; }
        poolScroll = 0;
    }

    private void pickFirstPool() {
        var pools = visiblePools();
        if (pools.isEmpty()) return;
        closeMenu();
        openPool(pools.get(0).get("id").getAsString(), 0);
    }

    private void closeMenu() { poolMenuOpen = false; filter = ""; }

    private int menuRows(int count) { return Math.max(1, Math.min(Math.min(MENU_ROWS, count), (ch - FOOT - menuY() - 22) / LIST_ROW)); }
    private int menuY() { return TOP + 16; }
    private int menuH(int count) { return menuRows(count) * LIST_ROW + 4 + 14; }

    private void centreHeader() {
        int y = TOP + 16;
        final String pool0 = poolId();
        int fallbackW = Math.min(260, centreW - 54 - 130) - LINK;
        var fallbackLink = button("↗", centreX + 54 + fallbackW + 4, y - 1, LINK - 2, 20, () -> followPool(str(pool(), "fallback", ""))).scale(2f);
        fallbackLink.active = canOpen(str(pool(), "fallback", ""));
        tips.on(fallbackLink, "Goes to the fallback pool.");
        tips.on(applyBox(centreX + 54, y, fallbackW, str(pool(), "fallback", "minecraft:empty"), 120, v -> edit("fallback_set", "pool", pool0, "fallback", v)), "The pool used when no element can be attached, usually minecraft:empty. Enter applies.");
        button("Preview from here", centreX + centreW - 124, y - 1, 124, 20, () -> { ClientState.options.jigsawPool = poolId(); CraftiumClient.send("craftium:jigsaw_generate", null, null); });
        tips.on(applyBox(centreX + 70, y + 24, centreW - 70, str(pool(), "description", ""), 300, v -> edit("description_set", "pool", pool0, "text", v)),
                "A note about this pool, saved in its file as $comment. The game ignores that key, so it is safe, and the editor keeps it. Enter applies; empty removes it.");
        int tabs = y + 50;
        button("Elements", centreX, tabs, 68, 20, () -> { graph = false; usedView = false; init(); }).lit(!graph && !usedView);
        button("Graph", centreX + 72, tabs, 68, 20, () -> { graph = true; usedView = false; init(); }).lit(graph);
        button("Used by", centreX + 144, tabs, 68, 20, () -> { graph = false; usedView = true; usedScroll = 0; usedAsked = poolId(); edit("used_by"); }).lit(usedView);
        if (graph) {
            button("Auto-align", centreX + centreW - 80, tabs, 80, 20, this::resetLayout);
        }
        if (!graph && !usedView) {
            int pages = Math.max(1, (shown().size() + perPage() - 1) / perPage());
            page = Math.clamp(page, 0, pages - 1);
            button("‹", centreX + centreW - 96, tabs, 24, 20, () -> { page = Math.max(0, page - 1); init(); });
            button("›", centreX + centreW - 24, tabs, 24, 20, () -> { page = Math.min(pages - 1, page + 1); init(); });
            int sortW = Math.min(150, centreW - 60);
            button("Sort: " + SORTS[sortMode], centreX, tabs + 24, sortW, 18, () -> stepSort(1)).back(() -> stepSort(-1));
            button(sortDesc ? "Desc" : "Asc", centreX + sortW + 4, tabs + 24, 52, 18, this::flipSort).back(this::flipSort);
            int searchX = centreX + sortW + 4 + 52 + 4, searchW = centreW - (searchX - centreX);
            if (searchW >= 50) {
                searchBox = box(searchX, tabs + 24, searchW, elementFilter, 60, v -> { elementFilter = v; page = 0; searchChanged = true; }, null);
                searchBox.setHint(Component.literal("search"));
                tips.on(searchBox, "Narrows the elements to those with all the words typed in their name, type, template, or the names and pools of their connectors.");
            }
        }
    }

    /** Where the player has been: a pool and which of its elements was selected (-1 for none). Back and Forward walk along it like a browser's. */
    private record Nav(String pool, int element) {}
    private final List<Nav> poolHistory = new ArrayList<>();
    private int historyAt = -1;
    /** A Target link waiting for its pool to arrive: the pool, and the connector name the element to select has. */
    private String pendingPool, pendingTarget;

    /** Keeps which element is selected in the place of the history that is open. */
    private void rememberElement() {
        if (historyAt >= 0 && historyAt < poolHistory.size()) poolHistory.set(historyAt, new Nav(poolHistory.get(historyAt).pool(), selected));
    }

    /** Opens a pool the player chose with this element selected (-1 for none), and remembers it so that Back can return to the one that was open. */
    private void openPool(String id, int element) {
        pendingPool = null; pendingTarget = null;
        if (!id.equals(poolId())) elementFilter = ""; // a search belongs to the pool it was typed in
        rememberElement();
        if (!id.equals(poolId()) || element != selected) {
            while (poolHistory.size() > historyAt + 1) poolHistory.remove(poolHistory.size() - 1); // a new choice drops what Forward would have gone to
            if (historyAt < 0 && !poolId().isEmpty()) { poolHistory.add(new Nav(poolId(), selected)); historyAt = 0; }
            poolHistory.add(new Nav(id, element));
            historyAt = poolHistory.size() - 1;
        }
        selected = element; page = 0; jigPage = 0;
        edit("open", "pool", id);
    }

    private void stepHistory(int by) {
        int to = historyAt + by;
        if (to < 0 || to >= poolHistory.size()) return;
        rememberElement();
        historyAt = to;
        var at = poolHistory.get(to);
        pendingPool = null; pendingTarget = null;
        if (!at.pool().equals(poolId())) elementFilter = "";
        selected = at.element(); page = 0; jigPage = 0;
        edit("open", "pool", at.pool());
    }

    /** A pool name as the editor knows it: with a namespace, craftium when none is typed. */
    private static String fullPool(String name) { return name.contains(":") ? name : "craftium:" + name; }

    /** Whether a pool name is one the editor can open: a pool that is loaded, and not the one that means nothing attaches. */
    private boolean canOpen(String name) {
        if (name.isBlank() || name.strip().equals("minecraft:empty")) return false;
        return poolIds().contains(fullPool(name.strip()));
    }

    /** The pool link of a connector: opens that pool with no element selected. */
    private void followPool(String pool) {
        if (pool.isBlank()) return;
        openPool(fullPool(pool.strip()), -1);
    }

    /** The target link of a connector: opens its pool and selects the element that has a connector of that name. The element is found once the pool has arrived. */
    private void followTarget(String pool, String target) {
        if (pool.isBlank() || target.isBlank()) return;
        openPool(fullPool(pool.strip()), -1);
        pendingPool = fullPool(pool.strip());
        pendingTarget = target.strip();
        elementFilter = pendingTarget; // every element that has the name is listed, and the first is selected
    }

    /** Selects the element of the open pool whose connector has the name the player followed, when the pool is the one asked for. */
    private void resolveTarget() {
        if (pendingTarget == null || !poolId().equals(pendingPool)) return;
        var names = Set.of(pendingTarget, "minecraft:" + pendingTarget, "craftium:" + pendingTarget);
        pendingTarget = null; pendingPool = null;
        int found = -1;
        var list = elements();
        for (int i = 0; i < list.size() && found < 0; i++) {
            var element = list.get(i).getAsJsonObject();
            if (element.has("jigsaws")) for (var j : element.getAsJsonArray("jigsaws")) if (names.contains(str(j.getAsJsonObject(), "name", ""))) { found = i; break; }
        }
        selected = found;
        jigPage = 0;
        rememberElement();
        if (found < 0) localMessage = "No element of " + poolId() + " has a connector named " + names.iterator().next() + ".";
    }

    // ---- the connectors that draw from the open pool

    private static final int USED_ROW = 30;
    private boolean usedView, usedTruncated;
    private double usedScroll;
    private JsonArray usedModel = new JsonArray();
    /** The pool the list is of, and the pool it was last asked for, so a pool opened while the tab is showing asks for its own list once. */
    private String usedFor = "", usedAsked = "";

    private JsonArray usedList() { return poolId().equals(usedFor) ? usedModel : new JsonArray(); }

    private void drawUsed(Gfx g, int mx, int my) {
        int top = cardsTop() - 4, bottom = ch - FOOT;
        var list = usedList();
        boolean ready = poolId().equals(usedFor);
        String head = !ready ? "Looking for the connectors that draw from this pool…" : list.size() == 0 ? "No connector of any pool draws from this pool." : list.size() + (list.size() == 1 ? " connector draws" : " connectors draw") + " from this pool" + (usedTruncated ? " (the search stopped early: there may be more)" : "") + ":";
        g.text(font, font.plainSubstrByWidth(head, centreW), centreX, top - 2, MUTED, false);
        top += 12;
        g.enableScissor(centreX, top, centreX + centreW, bottom);
        for (int i = 0; i < list.size(); i++) {
            int y = top + i * USED_ROW - (int) usedScroll;
            if (y + USED_ROW < top || y > bottom) continue;
            var u = list.get(i).getAsJsonObject();
            boolean hover = mx >= centreX && mx < centreX + centreW && my >= Math.max(y, top) && my < Math.min(y + USED_ROW - 2, bottom);
            g.fill(centreX, y, centreX + centreW, y + USED_ROW - 2, hover ? 0xff2a3d55 : CARD);
            g.text(font, font.plainSubstrByWidth(shortName(str(u, "pool", "")) + "  ·  element " + (num(u, "element", 0) + 1), centreW - 12), centreX + 6, y + 4, 0xffffffff, false);
            String detail = str(u, "template", "").replaceAll("^.*/", "") + "  ·  " + shortName(str(u, "name", "")) + " → " + shortName(str(u, "target", ""));
            g.text(font, font.plainSubstrByWidth(detail, centreW - 12), centreX + 6, y + 15, MUTED, false);
        }
        g.disableScissor();
    }

    private int cardsTop() { return TOP + 116; }
    private int perPage() { return Math.max(1, (ch - FOOT - 26 - cardsTop() + CARD_GAP) / (CARD_H + CARD_GAP)); } // the last 26 is the add button

    /** The elements in the order they are listed, as indexes into the pool's own list (the order the game reads, which the sort leaves alone). */
    private List<Integer> order() {
        var list = elements();
        var out = new ArrayList<Integer>();
        for (int i = 0; i < list.size(); i++) out.add(i);
        Comparator<Integer> by = switch (sortMode) {
            case 1 -> Comparator.comparing(i -> title(list.get(i).getAsJsonObject()).toLowerCase(Locale.ROOT));
            case 2 -> Comparator.comparingInt(i -> num(list.get(i).getAsJsonObject(), "weight", 1));
            case 3 -> Comparator.comparingInt(i -> { var e = list.get(i).getAsJsonObject(); return e.has("jigsaws") ? e.getAsJsonArray("jigsaws").size() : 0; });
            case 4 -> Comparator.comparingLong(i -> { var e = list.get(i).getAsJsonObject(); if (!e.has("size")) return 0L; var n = e.getAsJsonArray("size"); return (long) n.get(0).getAsInt() * n.get(1).getAsInt() * n.get(2).getAsInt(); });
            case 5 -> Comparator.comparing(i -> str(list.get(i).getAsJsonObject(), "type", ""));
            case 6 -> Comparator.comparingInt(i -> { var e = list.get(i).getAsJsonObject(); return issues(e) + (e.has("missing") ? 1000 : 0); });
            default -> Comparator.naturalOrder();
        };
        out.sort((sortDesc ? by.reversed() : by).thenComparing(Comparator.naturalOrder()));
        return out;
    }

    private String elementFilter = "";
    private EditBox searchBox;
    private boolean searchChanged;

    private void stepSort(int by) {
        sortMode = Math.floorMod(sortMode + by, SORTS.length);
        sortDesc = sortMode == 2 || sortMode == 3 || sortMode == 4 || sortMode == 6;
        page = 0;
        init();
    }

    private void flipSort() { sortDesc = !sortDesc; page = 0; init(); }

    /** The elements in the order they are listed, less those that do not have every word of the search. */
    private List<Integer> shown() {
        var all = order();
        var words = new ArrayList<String>();
        for (var word : elementFilter.toLowerCase(Locale.ROOT).strip().split("\\s+")) if (!word.isEmpty()) words.add(word);
        if (words.isEmpty()) return all;
        var list = elements();
        var out = new ArrayList<Integer>();
        for (int i : all) {
            var e = list.get(i).getAsJsonObject();
            var text = new StringBuilder(title(e)).append(' ').append(str(e, "type", "")).append(' ').append(str(e, "template", "")).append(' ').append(str(e, "feature", "")).append(' ').append(str(e, "projection", ""));
            if (e.has("jigsaws")) for (var j : e.getAsJsonArray("jigsaws")) { var jig = j.getAsJsonObject(); text.append(' ').append(str(jig, "name", "")).append(' ').append(str(jig, "pool", "")); }
            String hay = text.toString().toLowerCase(Locale.ROOT);
            if (words.stream().allMatch(hay::contains)) out.add(i);
        }
        return out;
    }

    private static String title(JsonObject element) {
        return element.has("template") ? str(element, "template", "").replaceAll("^.*/", "") : str(element, "type", "?") + (element.has("feature") ? " " + shortName(str(element, "feature", "")) : "") + (element.has("count") ? " of " + num(element, "count", 0) : "");
    }

    private void cards() {
        final String pool0 = poolId();
        var list = elements();
        var order = shown();
        boolean poolOrder = sortMode == 0 && !sortDesc; // moving an element up or down only means something in the pool's own order
        int first = page * perPage();
        for (int slot = 0; slot < perPage() && first + slot < list.size(); slot++) {
            int index = order.get(first + slot);
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
            button("↑", tx, y + 70, 22, 20, () -> edit("element_move", "pool", pool0, "index", index, "to", index - 1)).active = poolOrder && index > 0;
            button("↓", tx + 26, y + 70, 22, 20, () -> edit("element_move", "pool", pool0, "index", index, "to", index + 1)).active = poolOrder && index < list.size() - 1;
            button("Remove", tx + 52, y + 70, 64, 20, () -> edit("element_remove", "pool", pool0, "index", index));
            boolean rigid = str(element, "projection", "rigid").equals("rigid");
            Runnable flipProjection = () -> edit("element_set", "pool", pool0, "index", index, "projection", rigid ? "terrain_matching" : "rigid");
            var projection = button(rigid ? "Rigid" : "Follow terrain", tx, y + 48, 96, 18, flipProjection).back(flipProjection);
            projection.active = editable || element.has("projection");
            tips.on(projection, rigid ? RIGID_TIP : FOLLOW_TIP);
            if (editable && element.has("processors")) {
                var processors = tips.on(applyBox(tx + 100, y + 48, Math.max(40, w - 100), str(element, "processors", "minecraft:empty"), 120, v -> edit("element_set", "pool", pool0, "index", index, "processors", v)), "The processor list applied to this element when it is placed, such as minecraft:mossify_10_percent. Enter applies.");
                var lists = new ArrayList<String>();
                if (model.has("processorLists")) for (var n : model.getAsJsonArray("processorLists")) lists.add(n.getAsString());
                if (!lists.contains("minecraft:empty")) lists.add("minecraft:empty");
                suggestSources.put(processors, lists);
                if (str(element, "processors", "").equals("(inline)")) processors.setEditable(false); // a processor list written in the pool file is kept as it is
            }
        }
    }

    private int share(int index, int weight) {
        int total = 0;
        var list = elements();
        for (int i = 0; i < list.size(); i++) total += i == index ? weight : num(list.get(i).getAsJsonObject(), "weight", 1);
        return total == 0 ? 0 : (int) Math.round(weight * 100.0 / total);
    }

    /** The connectors whose fields are shown; every other connector is folded away, so a fold survives a redraw. Kept by template and place. */
    private final Set<String> openConnectors = new HashSet<>();
    private boolean folded(String template, JsonObject jig) { return !openConnectors.contains(foldKey(template, jig)); }

    private static String foldKey(String template, JsonObject jig) { return template + "@" + jig.getAsJsonArray("pos"); }

    /** The order the connectors are listed in: the template's own. In Single none is listed, only the window. */
    private List<Integer> orderList(JsonArray jigsaws, String template) {
        var order = new ArrayList<Integer>();
        if (singleView) return order; // the window is all there is: a connector is picked by clicking its jigsaw block in the preview
        for (int i = 0; i < jigsaws.size(); i++) order.add(i);
        return order;
    }

    /** The connector the window of Single shows: the one picked in the preview or by its button, or else the first. */
    private int selectedIndex(JsonArray jigsaws, String template) {
        for (int i = 0; i < jigsaws.size(); i++) if (foldKey(template, jigsaws.get(i).getAsJsonObject()).equals(pickedJig)) return i;
        return jigsaws.isEmpty() ? -1 : 0;
    }

    /** The connectors that fit on each page, as from (included) and to (excluded) in {@link #orderList}: a folded one is short, so more of them fit. In Single the window takes room on every page. */
    private List<int[]> jigPages(JsonArray jigsaws, String template) {
        var order = orderList(jigsaws, template);
        int room = ch - FOOT - (connY() + CONN_HEAD) - (singleView && !jigsaws.isEmpty() ? ROW_H : 0), used = 0, start = 0;
        var pages = new ArrayList<int[]>();
        for (int i = 0; i < order.size(); i++) {
            int height = singleView || folded(template, jigsaws.get(order.get(i)).getAsJsonObject()) ? FOLDED_H : ROW_H;
            if (used + height > room && i > start) { pages.add(new int[]{start, i}); start = i; used = 0; }
            used += height;
        }
        pages.add(new int[]{start, order.size()});
        return pages;
    }

    /** One connector as it is laid out: which one, where its heading is, whether its fields show, and whether it is the window of Single. */
    private record Row(int index, int y, boolean open, boolean window) {}

    /** The rows of the connectors column: the window of Single first, then the connectors of the current page. */
    private List<Row> rows(JsonArray jigsaws, String template) {
        var out = new ArrayList<Row>();
        var order = orderList(jigsaws, template);
        var pages = jigPages(jigsaws, template);
        jigPage = Math.clamp(jigPage, 0, pages.size() - 1);
        int cursor = connY() + CONN_HEAD;
        if (singleView && !jigsaws.isEmpty()) { out.add(new Row(selectedIndex(jigsaws, template), cursor, true, true)); cursor += ROW_H; }
        var range = pages.get(jigPage);
        for (int p = range[0]; p < range[1]; p++) {
            int index = order.get(p);
            boolean open = !singleView && !folded(template, jigsaws.get(index).getAsJsonObject());
            out.add(new Row(index, cursor, open, false));
            cursor += open ? ROW_H : FOLDED_H;
        }
        return out;
    }

    /** The preview fills its column from under its label to the foot; the connectors start at the top of theirs. */
    private int previewH() { return Math.max(40, ch - FOOT - previewY()); }
    private int connY() { return TOP; }
    private int connX() { return cw - SIDE - rightW; }

    // ---- the dividers between the panels

    /** The divider between the elements and the preview, and the one between the preview and the connectors. */
    private boolean overPreviewDivider(double x, double y) { return x >= centreX + centreW && x < previewX() && y >= TOP - 4 && y < ch - FOOT; }
    private boolean overRightDivider(double x, double y) { return x >= previewX() + previewW && x < connX() && y >= TOP - 4 && y < ch - FOOT; }

    /** Starts dragging a divider, if the pointer is on one. */
    private boolean pressDivider(double x, double y) {
        if (overPreviewDivider(x, y)) { resizing = 2; resizeFrom = (int) x; resizeStart = previewW; return true; }
        if (overRightDivider(x, y)) { resizing = 3; resizeFrom = (int) x; resizeStart = rightW; return true; }
        return false;
    }

    private void dragDivider(double x, double y) {
        switch (resizing) {
            case 2 -> previewPref = (int) Math.round(resizeStart - (x - resizeFrom));
            default -> rightPref = (int) Math.round(resizeStart - (x - resizeFrom));
        }
        init(); // lays everything out again at the new sizes; init limits each size to what fits
    }

    /** A grip on each divider, brighter when the pointer is on it or it is being dragged. */
    private void drawDividers(Gfx g, int mx, int my) {
        int midY = (TOP + ch - FOOT) / 2;
        grip(g, centreX + centreW + GAP / 2 - 1, midY - 12, 3, 24, resizing == 2 || (resizing == 0 && overPreviewDivider(mx, my)));
        grip(g, previewX() + previewW + GAP / 2 - 1, midY - 12, 3, 24, resizing == 3 || (resizing == 0 && overRightDivider(mx, my)));
    }

    private void grip(Gfx g, int x, int y, int w, int h, boolean active) { g.fill(x, y, x + w, y + h, active ? ClientState.options.uiAccent : 0xff4a5c70); }
    private int previewX() { return centreX + centreW + GAP; }
    private int previewY() { return TOP + PREVIEW_LABEL; }
    private boolean overPreview(double mx, double my) { return mx >= previewX() && mx < previewX() + previewW && my >= previewY() && my < previewY() + previewH(); }
    private boolean graphDragging() { return pressNode != null || pressEdge != null; }
    private boolean modal() { return adding || helpOpen || dialogMode != null || confirmLeave || retargetPrompt; }

    /** The buttons that put the view of the preview back: where it started, straight down, level. */
    private void previewButtons() {
        if (element(selected) == null) return;
        int bx = previewX() + previewW - 50, by = previewY() + 4;
        button("Reset", bx, by, 46, 16, preview::reset);
        button("Top", bx, by + 20, 46, 16, preview::top);
        button("Front", bx, by + 40, 46, 16, preview::front);
    }

    private void rightPane() {
        int x = cw - SIDE - rightW;
        var element = element(selected);
        if (element == null || !element.has("jigsaws")) return;
        var jigsaws = element.getAsJsonArray("jigsaws");
        String template = str(element, "template", "");
        var rowList = rows(jigsaws, template);
        int pages = jigPages(jigsaws, template).size();
        tips.on(button("List", x, connY() + 18, 60, 18, () -> setView(false)).lit(!singleView), "Every connector of the element in a list: open it to edit its fields, or fold it to leave its heading.");
        tips.on(button("Single", x + 64, connY() + 18, 60, 18, () -> setView(true)).lit(singleView), "One connector at a time, in a window that stays where it is: click a jigsaw block in the preview to show its connector there. What you change shows there as you go.");
        if (!singleView) {
            button("‹", x + rightW - 56, connY(), 24, 18, () -> { jigPage = Math.max(0, jigPage - 1); init(); });
            button("›", x + rightW - 28, connY(), 24, 18, () -> { jigPage = Math.min(pages - 1, jigPage + 1); init(); });
        }
        askForTargetPools(jigsaws, rowList);
        for (var row : rowList) {
            var jig = jigsaws.get(row.index()).getAsJsonObject();
            String key = foldKey(template, jig);
            boolean isFolded = !row.open();
            int y = row.y();
            if (!singleView) tips.on(button(isFolded ? "+" : "-", x, y - 1, 16, 14, () -> { if (!openConnectors.remove(key)) openConnectors.add(key); init(); }), isFolded ? "Show the fields of this connector." : "Fold this connector away, leaving only its heading.");
            if (isFolded) continue;
            var at = jig.getAsJsonArray("pos");
            JsonObject base = cmd("jigsaw_set", "template", template);
            base.add("pos", at);
            int half = rightW / 2 - 3;
            tips.on(applyBox(x + 52, y + 14, rightW - 52, str(jig, "name", ""), 80, v -> { var c = base.deepCopy(); c.addProperty("name", v); change(c); }), "Name: the name of this connector. When another piece asks for a connector with this name, this is the one it joins. Enter applies.");
            int linkX = x + rightW - LINK + 2 - THUMB_W - 4;
            var poolBox = applyBox(x + 52, y + 38, rightW - 52 - LINK - THUMB_W - 4, str(jig, "pool", ""), 100, v -> { var c = base.deepCopy(); c.addProperty("pool", v); change(c); });
            tips.on(poolBox, "Pool: the pool the next piece is chosen from, by chance. minecraft:empty means nothing attaches here. Type to see the loaded pools; a pool that another mod adds can be typed in even though it is not loaded here. Enter applies.");
            suggestSources.put(poolBox, poolIds());
            var poolLink = button("↗", linkX, y + 38, LINK - 2, 18, () -> followPool(poolBox.getValue())).scale(2f);
            poolLink.active = canOpen(poolBox.getValue()); // not minecraft:empty, and a pool that is loaded
            tips.on(poolLink, "Goes to this pool: it is chosen in the pool list, with no element selected.");
            var offered = new ArrayList<String>();
            if (jig.has("offered")) for (var n : jig.getAsJsonArray("offered")) offered.add(n.getAsString());
            if (!offered.contains("minecraft:empty")) offered.add("minecraft:empty");
            int targetW = rightW - 52 - LINK - THUMB_W - 4 - DROP - 2;
            var targetBox = applyBox(x + 52, y + 62, targetW, str(jig, "target", ""), 100, v -> { var c = base.deepCopy(); c.addProperty("target", v); change(c); });
            tips.on(targetBox, "Target: the name the connector of the next piece must have to join this one. Suggestions are the names the pieces of the pool offer. Enter applies.");
            suggestSources.put(targetBox, offered);
            var dropdown = button(key.equals(targetMenuKey) ? "▴" : "▾", x + 52 + targetW + 2, y + 62, DROP, 18, () -> { targetMenuKey = key.equals(targetMenuKey) ? null : key; targetMenuScroll = 0; init(); });
            tips.on(dropdown, "Lists the elements of the pool above, each with its picture, name and path. Choosing one writes the name of its connector in the box.");
            if (key.equals(targetMenuKey)) {
                targetMenuX = x; targetMenuY = y + 62 + 20; targetMenuW = rightW; targetMenuButtonX = x + 52 + targetW + 2; targetMenuButtonY = y + 62;
                targetMenuPool = poolBox.getValue(); targetMenuCurrent = str(jig, "target", "");
                targetMenuPick = name -> { var c = base.deepCopy(); c.addProperty("target", name); change(c); };
            }
            var targetLink = button("↗", linkX, y + 62, LINK - 2, 18, () -> followTarget(poolBox.getValue(), targetBox.getValue())).scale(2f);
            targetLink.active = canOpen(poolBox.getValue()) && !targetBox.getValue().isBlank();
            tips.on(targetLink, "Goes to the element of the pool above that has a connector with this name: its pool is chosen in the pool list and the element is selected, so its preview and connectors show.");
            boolean vertical = !jig.has("vertical") || jig.get("vertical").getAsBoolean(); // only a connector that faces up or down has a joint that matters
            boolean rollable = str(jig, "joint", "rollable").equals("rollable");
            Runnable flipJoint = () -> { var c = base.deepCopy(); c.addProperty("joint", rollable ? "aligned" : "rollable"); change(c); };
            button(rollable ? "Rollable" : "Aligned", x, y + 86, 76, 18, flipJoint).back(flipJoint).active = vertical; // greyed out for a connector that faces sideways
            tips.on(applyBox(x + 114, y + 86, 40, String.valueOf(num(jig, "placement", 0)), 4, v -> { var c = base.deepCopy(); setInt(c, "placement", v); change(c); }), "Placement priority: when several pieces wait to be attached, those from connectors with a higher number are placed first. Enter applies.");
            tips.on(applyBox(x + 204, y + 86, 40, String.valueOf(num(jig, "selection", 0)), 4, v -> { var c = base.deepCopy(); setInt(c, "selection", v); change(c); }), "Selection priority: among the connectors of a piece, those with a higher number are tried first. Enter applies.");
            var turns = applyBox(x + 58, y + 110, rightW - 58, str(jig, "final", "minecraft:air"), 120, v -> { var c = base.deepCopy(); c.addProperty("final", v); change(c); });
            suggestSources.put(turns, blocks());
            tips.on(turns, "Turns into: the block this connector is replaced with once the structure has grown, such as minecraft:stone_bricks, or minecraft:air to leave a gap. Type to see matching blocks; Enter picks the highlighted one, Enter again applies. A block state may follow in brackets.");
            if (str(jig, "issue", "").endsWith("is not loaded")) {
                String missing = str(jig, "pool", "");
                button("Create pool " + shortName(missing), x, y + 134, rightW, 18, () -> edit("pool_new", "id", missing));
            }
        }
    }

    /** The index of the connector of the element whose jigsaw block is at this cell, or -1. */
    private static int jigAt(JsonObject element, int[] cell) {
        if (cell == null || !element.has("jigsaws")) return -1;
        var jigsaws = element.getAsJsonArray("jigsaws");
        for (int i = 0; i < jigsaws.size(); i++) {
            var pos = jigsaws.get(i).getAsJsonObject().getAsJsonArray("pos");
            if (pos.get(0).getAsInt() == cell[0] && pos.get(1).getAsInt() == cell[1] && pos.get(2).getAsInt() == cell[2]) return i;
        }
        return -1;
    }

    /** Asks the server for the pools the open connectors point at, so their elements' pictures can be shown; only when the set has changed. */
    private void askForTargetPools(JsonArray jigsaws, List<Row> rowList) {
        var wanted = new TreeSet<String>();
        for (var row : rowList) {
            if (!row.open()) continue;
            String pool = str(jigsaws.get(row.index()).getAsJsonObject(), "pool", "").strip();
            if (!pool.isEmpty() && !pool.equals("minecraft:empty")) wanted.add(pool);
            if (wanted.size() >= 4) break;
        }
        String joined = String.join(",", wanted);
        if (joined.equals(askedPools)) return;
        askedPools = joined;
        edit("target_pools", "pools", joined);
    }

    /** What the elements of a pool offer for a connector: the names its jigsaws have, as the element lists them. */
    private record Offer(String template, String name, String path, JsonObject thumb, String connector, boolean matches) {}

    private List<Offer> offers(String pool, String target) {
        var out = new ArrayList<Offer>();
        var found = targetPools.get(pool.strip());
        if (found == null || !found.has("elements")) return out;
        var wanted = target.isBlank() ? Set.<String>of() : Set.of(target.strip(), "minecraft:" + target.strip(), "craftium:" + target.strip());
        for (var each : found.getAsJsonArray("elements")) {
            var element = each.getAsJsonObject();
            if (!element.has("template") || !element.has("jigsaws")) continue;
            String connector = "";
            boolean matches = false;
            for (var jig : element.getAsJsonArray("jigsaws")) {
                String name = str(jig.getAsJsonObject(), "name", "");
                if (connector.isEmpty()) connector = name;
                if (wanted.contains(name)) { connector = name; matches = true; break; }
            }
            if (connector.isEmpty()) continue; // no connector, so nothing could attach to it
            String path = str(element, "template", "");
            out.add(new Offer(path, path.substring(path.indexOf(':') + 1).replaceAll(".*/", ""), path, element.has("thumb") ? element.getAsJsonObject("thumb") : null, connector, matches));
        }
        return out;
    }

    /** The picture of the first element of the connector's pool that has a connector with the target's name, in a frame at (x, y). */
    private void drawTargetThumbnail(Gfx g, int x, int y, String pool, String target) {
        g.fill(x, y, x + THUMB_W, y + THUMB_H, 0xff2a3441);
        g.outline(x, y, THUMB_W, THUMB_H, ClientState.options.uiBorder);
        Offer shown = null;
        for (var offer : offers(pool, target)) if (offer.matches()) { shown = offer; break; }
        if (shown == null) { g.centeredText(font, targetPools.containsKey(pool.strip()) ? "no match" : "…", x + THUMB_W / 2, y + THUMB_H / 2 - 4, 0xff7e8fa0); return; }
        var tex = PoolThumbnails.of(shown.template(), shown.thumb());
        if (tex != null) g.blit(tex, x + 1, y + 1, 0, 0, THUMB_W - 2, THUMB_H - 2, PoolThumbnails.WIDTH * 2, PoolThumbnails.HEIGHT * 2, PoolThumbnails.WIDTH * 2, PoolThumbnails.HEIGHT * 2, 0xffffffff);
        else g.centeredText(font, shown.thumb() == null ? "no picture" : "…", x + THUMB_W / 2, y + THUMB_H / 2 - 4, 0xff7e8fa0);
    }

    private static final int TARGET_MENU_ROW = 38, TARGET_MENU_ROWS = 6;

    private int targetMenuRows(int count) { return Math.max(1, Math.min(Math.min(TARGET_MENU_ROWS, count), (ch - FOOT - targetMenuY - 6) / TARGET_MENU_ROW)); }

    private boolean overTargetMenu(double x, double y) {
        if (targetMenuKey == null) return false;
        int count = Math.max(1, offers(targetMenuPool, targetMenuCurrent).size());
        return x >= targetMenuX && x < targetMenuX + targetMenuW && y >= targetMenuY && y < targetMenuY + targetMenuRows(count) * TARGET_MENU_ROW + 4;
    }

    /** The open target dropdown: the elements of the pool with their picture, name and path, over everything else. */
    private void drawTargetMenu(Gfx g, int mx, int my) {
        if (targetMenuKey == null || modal()) return;
        var list = offers(targetMenuPool, targetMenuCurrent);
        int rows = targetMenuRows(Math.max(1, list.size())), h = rows * TARGET_MENU_ROW + 4, x = targetMenuX, y = targetMenuY, w = targetMenuW;
        boolean bar = list.size() > rows;
        targetMenuScroll = Math.clamp(targetMenuScroll, 0, Math.max(0, (list.size() - rows) * TARGET_MENU_ROW));
        g.fill(x, y, x + w, y + h, 0xf0101923);
        g.outline(x, y, w, h, ClientState.options.uiAccent);
        int rowW = w - 4 - (bar ? Scrollbars.WIDTH + 2 : 0);
        g.enableScissor(x + 2, y + 2, x + w - 2, y + 2 + rows * TARGET_MENU_ROW);
        if (list.isEmpty()) g.text(font, targetPools.containsKey(targetMenuPool.strip()) ? "No element of this pool has a connector." : "Working out the elements…", x + 8, y + 8, MUTED, false);
        for (int i = 0; i < list.size(); i++) {
            int ry = y + 2 + i * TARGET_MENU_ROW - (int) targetMenuScroll;
            if (ry + TARGET_MENU_ROW < y + 2 || ry > y + 2 + rows * TARGET_MENU_ROW) continue;
            var offer = list.get(i);
            boolean hover = mx >= x + 2 && mx < x + 2 + rowW && my >= Math.max(ry, y + 2) && my < Math.min(ry + TARGET_MENU_ROW, y + 2 + rows * TARGET_MENU_ROW);
            g.fill(x + 2, ry, x + 2 + rowW, ry + TARGET_MENU_ROW - 2, offer.matches() ? 0xff315473 : hover ? 0xff2a3d55 : CARD);
            int th = TARGET_MENU_ROW - 8, tw = th * PoolThumbnails.WIDTH / PoolThumbnails.HEIGHT; // the picture's own proportions
            g.fill(x + 6, ry + 3, x + 6 + tw, ry + 3 + th, 0xff2a3441);
            var tex = PoolThumbnails.of(offer.template(), offer.thumb());
            if (tex != null) g.blit(tex, x + 6, ry + 3, 0, 0, tw, th, PoolThumbnails.WIDTH * 2, PoolThumbnails.HEIGHT * 2, PoolThumbnails.WIDTH * 2, PoolThumbnails.HEIGHT * 2, 0xffffffff);
            int room = rowW - tw - 18;
            g.text(font, font.plainSubstrByWidth(offer.name(), room), x + 6 + tw + 6, ry + 7, TEXT, false);
            g.text(font, font.plainSubstrByWidth(offer.path(), room), x + 6 + tw + 6, ry + 19, MUTED, false);
        }
        g.disableScissor();
        if (bar) Scrollbars.draw(g, x + w - Scrollbars.WIDTH - 2, y + 2, rows * TARGET_MENU_ROW, targetMenuScroll, list.size() * TARGET_MENU_ROW, draggingTargetBar);
    }

    /** A click while the target dropdown is open: a row writes that element's connector name as the target; a click elsewhere (not on its own button) closes it. */
    private boolean clickTargetMenu(double x, double y) {
        var list = offers(targetMenuPool, targetMenuCurrent);
        int rows = targetMenuRows(Math.max(1, list.size()));
        if (overTargetMenu(x, y)) {
            int top = targetMenuY + 2;
            if (list.size() > rows && Scrollbars.hit(x, y, targetMenuX + targetMenuW - Scrollbars.WIDTH - 2, top, rows * TARGET_MENU_ROW)) { draggingTargetBar = true; targetMenuScroll = Scrollbars.scrollAt(y, top, rows * TARGET_MENU_ROW, list.size() * TARGET_MENU_ROW); return true; }
            int i = (int) ((y - top + targetMenuScroll) / TARGET_MENU_ROW);
            if (y >= top && y < top + rows * TARGET_MENU_ROW && i >= 0 && i < list.size()) {
                var pick = targetMenuPick;
                String name = list.get(i).connector();
                targetMenuKey = null;
                if (pick != null) pick.accept(name);
            }
            return true;
        }
        boolean onButton = x >= targetMenuButtonX && x < targetMenuButtonX + DROP && y >= targetMenuButtonY && y < targetMenuButtonY + 18;
        if (!onButton) { targetMenuKey = null; init(); }
        return false;
    }

    private void setView(boolean single) {
        if (singleView == single) return;
        singleView = single;
        jigPage = 0;
        init();
    }

    /** A click on a jigsaw block in the preview: its connector is picked and, in List, unfolded and its page opened; in Single it fills the window. */
    private void pickJigsawInPreview(double x, double y) {
        var element = element(selected);
        if (element == null || !element.has("jigsaws") || !preview.hasScene()) return;
        int index = jigAt(element, preview.cellAt(x, y, previewX(), previewY(), previewW, previewH()));
        if (index < 0) return;
        var jigsaws = element.getAsJsonArray("jigsaws");
        String template = str(element, "template", "");
        var jig = jigsaws.get(index).getAsJsonObject();
        pickedJig = foldKey(template, jig);
        if (!singleView) {
            openConnectors.add(pickedJig); // opened, so its fields show
            var pages = jigPages(jigsaws, template);
            for (int page = 0; page < pages.size(); page++) if (index >= pages.get(page)[0] && index < pages.get(page)[1]) jigPage = page;
        }
        init();
    }

    /** For the game test: a point of the preview where the jigsaw block of connector {@code index} can be seen, as {x, y}, or null. */
    double[] jigsawPoint(int index) {
        var element = element(selected);
        if (element == null || !preview.hasScene()) return null;
        for (int y = previewY(); y < previewY() + previewH(); y += 2)
            for (int x = previewX(); x < previewX() + previewW; x += 2)
                if (jigAt(element, preview.cellAt(x, y, previewX(), previewY(), previewW, previewH())) == index) return new double[]{x, y};
        return null;
    }

    private void setInt(JsonObject json, String key, String text) {
        try { json.addProperty(key, Integer.parseInt(text.strip())); }
        catch (NumberFormatException e) { json.addProperty(key, 0); }
    }

    /** The panel that adds an element to the pool: a template (found, or made from the selection), a feature such as a tree, or an empty slot. */
    private void overlay() {
        int w = Math.min(460, cw - 40), x = (cw - w) / 2, y = 40;
        String[] tabs = {"Template", "Selection", "Structure", "Feature", "Empty"};
        int tabW = (w - 20 - 16) / 5;
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            button(tabs[i], x + 10 + i * (tabW + 4), y + 32, tabW, 20, () -> { addTab = index; if (index == 3) edit("features", "query", featureQuery); else init(); }).lit(addTab == i);
        }
        int top = y + 60, boxY = top + 12, listY = top + 38, rows = Math.max(1, (ch - 40 - listY - 12) / 20);
        switch (addTab) {
            case 0 -> {
                tips.on(box(x + 10, boxY, w - 20, query, 80, v -> query = v, () -> edit("templates", "query", query)), "Part of a template name. Press Enter to search the templates of every pack and the structures you saved.");
                var found = model.has("templates") ? model.getAsJsonArray("templates") : new JsonArray();
                for (int i = 0; i < Math.min(found.size(), rows); i++) {
                    String id = found.get(i).getAsString();
                    button(id, x + 10, listY + i * 20, w - 20, 18, () -> { edit("element_add", "pool", poolId(), "template", id); adding = false; });
                }
            }
            case 1 -> {
                tips.on(box(x + 10, boxY + 40, w - 130, selectionName, 80, v -> selectionName = v, this::saveSelection), "A name for the new template, such as my_room. Without a namespace it goes in the namespace of this pool. Enter saves.");
                button("Save selection", x + w - 114, boxY + 39, 104, 20, this::saveSelection).active = num(model, "selection", 0) > 0;
            }
            case 2 -> {
                tips.on(button("Import structure", x + 10, top + 44, 150, 22, this::importStructure), "Choose a Java structure file (.nbt) on your computer. It is saved as a template, with its jigsaw blocks as they are, and added to the pool.");
                tips.on(button("From blueprint library", x + 10, top + 72, 150, 22, this::pickBlueprint), "Open the blueprint library (the server's, or your own) and choose a blueprint. It is saved as a template, with its jigsaw blocks as they are, and added to the pool.");
            }
            case 3 -> {
                tips.on(box(x + 10, boxY, w - 20, featureQuery, 80, v -> featureQuery = v, () -> edit("features", "query", featureQuery)), "Part of a feature name, such as oak or flower. Press Enter to search.");
                var found = model.has("features") ? model.getAsJsonArray("features") : new JsonArray();
                for (int i = 0; i < Math.min(found.size(), rows); i++) {
                    String id = found.get(i).getAsString();
                    button(id, x + 10, listY + i * 20, w - 20, 18, () -> { edit("element_add_feature", "pool", poolId(), "feature", id); adding = false; });
                }
            }
            default -> button("Add an empty slot", x + 10, top + 64, 150, 22, () -> { edit("element_add_empty", "pool", poolId()); adding = false; });
        }
        button("Close", x + w - 80, y + 6, 70, 20, () -> { adding = false; init(); });
    }

    /** The ids of the loaded pools, those of the craftium pack first, with minecraft:empty (which always means nothing attaches). */
    private List<String> poolIds() {
        var pools = new ArrayList<JsonObject>();
        if (model.has("pools")) for (var e : model.getAsJsonArray("pools")) pools.add(e.getAsJsonObject());
        pools.sort(Comparator.<JsonObject>comparingInt(p -> str(p, "source", "").equals("craftium") ? 0 : 1).thenComparing(p -> p.get("id").getAsString()));
        var ids = new ArrayList<String>();
        for (var p : pools) ids.add(p.get("id").getAsString());
        if (!ids.contains("minecraft:empty")) ids.add("minecraft:empty");
        return ids;
    }

    private static List<String> blocks() {
        if (allBlocks == null) {
            var list = new ArrayList<String>();
            for (var id : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) list.add(id.toString());
            list.sort(Comparator.<String>comparingInt(id -> id.startsWith("minecraft:") ? 0 : 1).thenComparing(id -> id));
            allBlocks = list;
        }
        return allBlocks;
    }

    /** Watches which "Turns into" box has the focus and what it holds, and works out the blocks that fit. */
    private void followSuggestBox() {
        EditBox focused = null;
        for (var b : suggestSources.keySet()) if (b.isFocused()) focused = b;
        if (focused != suggestBox) { suggestBox = focused; suggestTyped = null; suggesting = focused != null; suggestSource = focused == null ? List.of() : suggestSources.get(focused); }
        if (focused == null) { suggestMatches.clear(); return; }
        String value = focused.getValue();
        if (!value.equals(suggestTyped)) {
            if (suggestTyped != null) suggesting = true; // typing opens the list again
            suggestTyped = value;
            refreshSuggestions(value);
        }
    }

    /** The blocks that contain what is typed: those that start with it first, then those whose name (without the namespace) does, then the rest; minecraft's before others'. */
    private void refreshSuggestions(String value) {
        suggestMatches.clear();
        suggestHighlight = -1;
        suggestScroll = 0;
        String text = value.strip().toLowerCase(Locale.ROOT);
        if (text.contains("[") && suggestSource == allBlocks) return; // a block state is being written: nothing to suggest
        // The source is in the order it should be shown in; what starts with the text comes before what only contains it.
        var found = new ArrayList<Integer>();
        for (int i = 0; i < suggestSource.size(); i++) if (text.isEmpty() || suggestSource.get(i).contains(text)) found.add(i);
        found.sort(Comparator.<Integer>comparingInt(i -> { var id = suggestSource.get(i); return id.startsWith(text) ? 0 : id.substring(id.indexOf(':') + 1).startsWith(text) ? 1 : 2; }).thenComparingInt(i -> i));
        for (int i : found) suggestMatches.add(suggestSource.get(i));
    }

    private boolean suggestionsShowing() {
        if (!suggesting || suggestBox == null || !suggestBox.isFocused() || suggestMatches.isEmpty() || adding || helpOpen || dialogMode != null || confirmLeave || retargetPrompt) return false;
        return !(suggestMatches.size() == 1 && suggestMatches.get(0).equals(suggestBox.getValue().strip()));
    }

    /** Where the list is: below its box, or above it when there is no room below. */
    private int[] suggestionBox() {
        int rows = Math.min(SUGGEST_ROWS, suggestMatches.size()), h = rows * SUGGEST_ROW + 4;
        int x = suggestBox.getX(), y = suggestBox.getY() + suggestBox.getHeight() + 1;
        if (y + h > ch - 4) y = suggestBox.getY() - h - 1;
        return new int[]{x, y, suggestBox.getWidth(), h};
    }

    private boolean overSuggestions(double mx, double my) {
        var r = suggestionBox();
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private boolean clickSuggestion(double mx, double my) {
        if (!suggestionsShowing() || !overSuggestions(mx, my)) return false;
        var r = suggestionBox();
        int row = (int) ((my - r[1] - 2) / SUGGEST_ROW), i = suggestScroll + row;
        if (row >= 0 && i < suggestMatches.size()) acceptSuggestion(suggestMatches.get(i));
        return true;
    }

    private void acceptSuggestion(String id) {
        suggestBox.setValue(id);
        suggestTyped = id;
        suggesting = false;
        suggestMatches.clear();
    }

    private void keepSuggestionVisible() {
        if (suggestHighlight < suggestScroll) suggestScroll = suggestHighlight;
        else if (suggestHighlight >= suggestScroll + SUGGEST_ROWS) suggestScroll = suggestHighlight - SUGGEST_ROWS + 1;
        suggestScroll = Math.clamp(suggestScroll, 0, Math.max(0, suggestMatches.size() - SUGGEST_ROWS));
    }

    private void drawSuggestions(Gfx g, int mx, int my) {
        if (!suggestionsShowing()) return;
        var r = suggestionBox();
        int rows = Math.min(SUGGEST_ROWS, suggestMatches.size());
        boolean bar = suggestMatches.size() > SUGGEST_ROWS;
        if (mx != lastMouseX || my != lastMouseY) { // the highlight goes where the pointer goes, once it moves
            lastMouseX = mx;
            lastMouseY = my;
            if (overSuggestions(mx, my)) suggestHighlight = Math.min(suggestMatches.size() - 1, suggestScroll + (my - r[1] - 2) / SUGGEST_ROW);
        }
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xf0101923);
        g.outline(r[0], r[1], r[2], r[3], ClientState.options.uiAccent);
        String typed = suggestBox.getValue().strip().toLowerCase(Locale.ROOT);
        int room = r[2] - 10 - (bar ? Scrollbars.WIDTH + 2 : 0);
        for (int row = 0; row < rows; row++) {
            int i = suggestScroll + row, ry = r[1] + 2 + row * SUGGEST_ROW;
            if (i >= suggestMatches.size()) break;
            if (i == suggestHighlight) g.fill(r[0] + 1, ry, r[0] + r[2] - 1 - (bar ? Scrollbars.WIDTH + 2 : 0), ry + SUGGEST_ROW, 0xff315473);
            String shown = font.plainSubstrByWidth(suggestMatches.get(i), room);
            int at = typed.isEmpty() ? -1 : shown.toLowerCase(Locale.ROOT).indexOf(typed);
            int px = r[0] + 5;
            if (at >= 0) { // the part that was typed is picked out
                g.text(font, shown.substring(0, at), px, ry + 2, TEXT, false);
                px += font.width(shown.substring(0, at));
                g.text(font, shown.substring(at, at + typed.length()), px, ry + 2, 0xffffdca8, false);
                px += font.width(shown.substring(at, at + typed.length()));
                g.text(font, shown.substring(at + typed.length()), px, ry + 2, TEXT, false);
            } else g.text(font, shown, px, ry + 2, TEXT, false);
        }
        if (bar) Scrollbars.draw(g, r[0] + r[2] - Scrollbars.WIDTH - 2, r[1] + 2, rows * SUGGEST_ROW, suggestScroll * SUGGEST_ROW, suggestMatches.size() * SUGGEST_ROW, false);
    }

    private void pickBlueprint() {
        ClientCompat.setScreen(minecraft, new BlueprintScreen(this, (client, info) -> {
            String file = info.file();
            String base = file.substring(file.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            String name = (dot > 0 ? base.substring(0, dot) : base).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
            if (name.isEmpty()) name = "blueprint";
            var o = ClientState.options;
            String prior = o.assetData;
            try {
                if (client) o.assetData = java.util.Base64.getEncoder().encodeToString(ClientAssets.bytes(file)); // the player's own file travels with the request
                edit("element_from_blueprint", "pool", poolId(), "name", name, "file", file);
            } catch (IllegalArgumentException e) {
                localMessage = e.getMessage();
            } finally { o.assetData = prior; }
            adding = false;
            init();
        }));
    }

    private void importStructure() {
        ClientStructures.chooseJavaStructure((name, data) -> {
            var o = ClientState.options;
            String prior = o.assetData;
            o.assetData = java.util.Base64.getEncoder().encodeToString(data);
            try { edit("element_from_file", "pool", poolId(), "name", name); }
            finally { o.assetData = prior; }
            adding = false;
        });
    }

    private void saveSelection() {
        if (selectionName.isBlank()) { localMessage = "Name the template first"; return; }
        edit("element_from_selection", "pool", poolId(), "name", selectionName.strip());
        adding = false;
    }

    // ---- drawing

    @Override protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override public void tick() {
        if (awaitTicks > 0) awaitTicks--;
        followSuggestBox();
        if (usedView && pool() != null && !poolId().equals(usedFor) && !poolId().equals(usedAsked)) { usedAsked = poolId(); edit("used_by"); }
        if (searchChanged) { // the cards are widgets: they are made again for the new list, and the search box keeps the keyboard
            searchChanged = false;
            init();
            if (searchBox != null) { setFocused(searchBox); searchBox.setFocused(true); searchBox.moveCursorToEnd(false); }
        }
        var nowFocused = getFocused() instanceof EditBox e && applied.containsKey(e) ? e : null;
        if (lastFocusedApply != null && lastFocusedApply != nowFocused) applyIfChanged(lastFocusedApply);
        lastFocusedApply = nowFocused;
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
        // Words narrow by name (all of them have to be in it); a word that starts with @ narrows by namespace (any of those named), so "@minecraft town" is the town pools of minecraft.
        var namespaces = new ArrayList<String>();
        var words = new ArrayList<String>();
        for (var word : filter.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (word.startsWith("@")) { if (word.length() > 1) namespaces.add(word.substring(1)); }
            else if (!word.isEmpty()) words.add(word);
        }
        for (var e : model.getAsJsonArray("pools")) {
            var p = e.getAsJsonObject();
            String id = p.get("id").getAsString(), namespace = id.substring(0, Math.max(0, id.indexOf(':')));
            if (!namespaces.isEmpty() && namespaces.stream().noneMatch(namespace::startsWith)) continue;
            if (words.stream().allMatch(id::contains)) out.add(p);
        }
        out.sort(Comparator.comparingInt(p -> str(p, "source", "").equals("craftium") ? 0 : 1));
        return out;
    }

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
        g.text(font, font.plainSubstrByWidth(message, cw - 2 * SIDE - 470 - NAV_W), SIDE + NAV_W, 28, 0xffffdca8, false);
        int waiting = unappliedCount();
        if (waiting > 0) { String note = waiting + (waiting == 1 ? " unapplied change" : " unapplied changes"); g.text(font, note, cw - SIDE - font.width(note), 34, WARN, false); }
        if (model.has("registryError")) g.text(font, font.plainSubstrByWidth("A pool file has a problem: " + str(model, "registryError", ""), cw - 2 * SIDE - 200 - NAV_W), SIDE + NAV_W, 38, ERROR, false);
        if (retargetPrompt) { drawRetargetPrompt(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        if (confirmLeave) { drawLeavePrompt(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        if (dialogMode != null) { drawDialog(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        if (helpOpen) { drawHelp(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        if (adding) { drawOverlay(g); drawWidgets(g, mx, my, delta); tips.draw(g, font, mx, my, cw, ch); g.pose().popMatrix(); return; }
        if (pool() == null) g.text(font, "Pick a pool from the list above, or make a new one.", centreX, TOP + 20, MUTED, false);
        else { drawCentre(g); if (graph) drawGraph(g, mx, my); else if (usedView) drawUsed(g, mx, my); else drawCards(g, mx, my); }
        drawConnections(g, mx, my);
        drawDividers(g, mx, my);
        drawWidgets(g, mx, my, delta);
        drawSuggestions(g, mx, my);
        if (poolBox != null && poolBox.getValue().isEmpty() && !poolBox.isFocused()) g.text(font, font.plainSubstrByWidth(pool() == null ? "Pick a pool · " + (model.has("pools") ? model.getAsJsonArray("pools").size() : 0) + " pools" : poolId(), pickW - 10), centreX + 5, TOP, pool() == null ? 0xff5f7186 : TEXT, false);
        drawPoolMenu(g, mx, my);
        drawTargetMenu(g, mx, my);
        tips.draw(g, font, mx, my, cw, ch);
        g.pose().popMatrix();
    }

    /** The open dropdown: the pools that fit what is typed, over everything else, with what each pool is (craftium's, another pack's, the game's) and its problems. */
    private void drawPoolMenu(Gfx g, int mx, int my) {
        if (!poolMenuOpen || modal()) return;
        var pools = visiblePools();
        int x = centreX, y = menuY(), w = pickW + 24, rows = menuRows(pools.size()), h = menuH(pools.size());
        boolean bar = pools.size() > rows;
        poolScroll = Math.clamp(poolScroll, 0, Math.max(0, (pools.size() - rows) * LIST_ROW));
        g.fill(x, y, x + w, y + h, 0xf0101923);
        g.outline(x, y, w, h, ClientState.options.uiAccent);
        int rowW = w - 4 - (bar ? Scrollbars.WIDTH + 2 : 0);
        g.enableScissor(x + 2, y + 2, x + w - 2, y + 2 + rows * LIST_ROW);
        if (pools.isEmpty()) g.text(font, "No pool matches.", x + 8, y + 7, MUTED, false);
        for (int i = 0; i < pools.size(); i++) {
            int ry = y + 2 + i * LIST_ROW - (int) poolScroll;
            if (ry + LIST_ROW < y + 2 || ry > y + 2 + rows * LIST_ROW) continue;
            var p = pools.get(i);
            boolean current = p.get("id").getAsString().equals(poolId()), hover = mx >= x + 2 && mx < x + 2 + rowW && my >= Math.max(ry, y + 2) && my < Math.min(ry + LIST_ROW, y + 2 + rows * LIST_ROW);
            g.fill(x + 2, ry, x + 2 + rowW, ry + LIST_ROW - 2, current ? 0xff315473 : hover ? 0xff2a3d55 : CARD);
            String source = str(p, "source", "");
            g.fill(x + 2, ry, x + 5, ry + LIST_ROW - 2, source.equals("craftium") ? OK : source.equals("pack") ? 0xff8fd3ff : 0xff5a6b7d);
            int errors = num(p, "errors", 0), warnings = num(p, "warnings", 0);
            String badge = errors > 0 ? errors + "!" : warnings > 0 ? warnings + "?" : "";
            int room = rowW - 14 - (badge.isEmpty() ? 0 : font.width(badge) + 6);
            g.text(font, font.plainSubstrByWidth(shortName(p.get("id").getAsString()), room), x + 9, ry + 5, TEXT, false);
            if (!badge.isEmpty()) g.text(font, badge, x + 2 + rowW - font.width(badge) - 4, ry + 5, errors > 0 ? ERROR : WARN, false);
        }
        g.disableScissor();
        if (bar) Scrollbars.draw(g, x + w - Scrollbars.WIDTH - 2, y + 2, rows * LIST_ROW, poolScroll, pools.size() * LIST_ROW, draggingPoolBar);
        int lx = x + 6, ly = y + h - 12;
        for (var key : new String[][]{{"craftium", "" + OK}, {"pack", "" + 0xff8fd3ff}, {"vanilla", "" + 0xff5a6b7d}}) {
            g.fill(lx, ly + 1, lx + 6, ly + 8, Integer.parseInt(key[1]));
            g.text(font, key[0], lx + 9, ly, 0xff6f8296, false);
            lx += font.width(key[0]) + 15;
        }
    }

    private boolean overPoolMenu(double x, double y) {
        if (!poolMenuOpen) return false;
        int count = visiblePools().size();
        return x >= centreX && x < centreX + pickW + 24 && y >= menuY() && y < menuY() + menuH(count);
    }

    private void drawCentre(Gfx g) {
        var p = pool();
        g.fill(centreX - 4, TOP - 4, centreX + centreW + 4, ch - FOOT + 2, PANEL);
        boolean ours = p.has("overridden") && p.get("overridden").getAsBoolean();
        String tag = ours ? "in the craftium pack" : "another pack: an edit overrides it here";
        int tagX = centreX + pickW + 24 + 6 + buttonsW() + 12;
        if (tagX + font.width(tag) <= centreX + centreW) g.text(font, tag, centreX + centreW - font.width(tag), TOP + 1, ours ? OK : MUTED, false);
        g.text(font, "Fallback", centreX + 2, TOP + 21, MUTED, false);
        g.text(font, "Description", centreX + 2, TOP + 45, MUTED, false);
        if (!graph && !usedView) {
            int pages = Math.max(1, (shown().size() + perPage() - 1) / perPage());
            String label = (page + 1) + "/" + pages;
            g.text(font, label, centreX + centreW - 48 - font.width(label) / 2, TOP + 16 + 50 + 6, MUTED, false);
            int total = elements().size(), matching = shown().size();
            String count = (matching == total ? "" + total : matching + " of " + total) + (total == 1 ? " element" : " elements");
            g.text(font, count, centreX + centreW - 150 - font.width(count), TOP + 16 + 50 + 6, MUTED, false);
        }
    }

    private void drawCards(Gfx g, int mx, int my) {
        var list = elements();
        var order = shown();
        int first = page * perPage();
        if (list.size() == 0) {
            var rows = font.split(Component.literal("This pool has no elements yet. A pool is a list of pieces that a connector can pick from. Press + Add element below to add a template (or turn your selection into one), a feature such as a tree, or an empty slot. Press ? at the top for a short guide."), centreW - 8);
            for (int i = 0; i < rows.size(); i++) g.text(font, rows.get(i), centreX, cardsTop() + 6 + i * 10, MUTED, false);
        }
        for (int slot = 0; slot < perPage() && first + slot < list.size(); slot++) {
            int index = order.get(first + slot), x = centreX, y = cardsTop() + slot * (CARD_H + CARD_GAP);
            var element = list.get(index).getAsJsonObject();
            boolean current = index == selected;
            g.fill(x, y, x + centreW, y + CARD_H, current ? 0xff2b4766 : CARD);
            g.outline(x, y, centreW, CARD_H, current ? ClientState.options.uiAccent : ClientState.options.uiBorder);
            g.fill(x + 6, y + 6, x + 6 + PoolThumbnails.WIDTH, y + 6 + PoolThumbnails.HEIGHT, 0xff2a3441);
            var tex = PoolThumbnails.of(str(element, "template", "?"), element.has("thumb") ? element.getAsJsonObject("thumb") : null);
            if (tex != null) g.blit(tex, x + 6, y + 6, 0, 0, PoolThumbnails.WIDTH, PoolThumbnails.HEIGHT, PoolThumbnails.WIDTH * 2, PoolThumbnails.HEIGHT * 2, PoolThumbnails.WIDTH * 2, PoolThumbnails.HEIGHT * 2, 0xffffffff);
            else g.centeredText(font, noPicture(element), x + 6 + PoolThumbnails.WIDTH / 2, y + 6 + PoolThumbnails.HEIGHT / 2 - 4, element.has("missing") ? ERROR : 0xff7e8fa0);
            int tx = x + 6 + PoolThumbnails.WIDTH + 8, w = centreW - (tx - x) - 8;
            int jigsaws = element.has("jigsaws") ? element.getAsJsonArray("jigsaws").size() : 0, issues = issues(element);
            String verdict = element.has("missing") ? "template missing" : issues > 0 ? issues + (issues == 1 ? " problem" : " problems") : jigsaws == 0 ? (element.has("template") ? "no connectors" : "") : jigsaws + (jigsaws == 1 ? " connector, ok" : " connectors, ok");
            int verdictW = font.width(verdict) + 6;
            if (!verdict.isEmpty()) g.text(font, verdict, x + centreW - 8 - font.width(verdict), y + 6, element.has("missing") || issues > 0 ? ERROR : jigsaws == 0 ? MUTED : OK, false);
            g.text(font, font.plainSubstrByWidth((index + 1) + ". " + title(element), w - verdictW), tx, y + 6, 0xffffffff, false);
            var size = element.has("size") ? element.getAsJsonArray("size") : null;
            String sub = str(element, "type", "") + (element.has("template") ? " · " + shortName(str(element, "template", "")) : "") + "";
            if (size != null) g.text(font, size.get(0) + " × " + size.get(1) + " × " + size.get(2) + " blocks", x + 6, y + 6 + PoolThumbnails.HEIGHT + 4, MUTED, false);
            g.text(font, font.plainSubstrByWidth(sub, w), tx, y + 16, MUTED, false);
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
        for (var j : element.getAsJsonArray("jigsaws")) { var issue = str(j.getAsJsonObject(), "issue", ""); if (!issue.isEmpty() && !issue.endsWith("is not loaded")) n++; }
        return n;
    }

    /** The middle and right columns: the preview of the selected element and its connectors. Pointing at a connector lights up its jigsaw block in the preview, and pointing at a jigsaw block there lights up its connector; a click on the block picks it. */
    private void drawConnections(Gfx g, int mx, int my) {
        int x = connX(), px = previewX();
        g.fill(px - 4, TOP - 4, px + previewW + 4, ch - FOOT + 2, PANEL);
        g.fill(x - 4, TOP - 4, x + rightW + 4, ch - FOOT + 2, PANEL);
        var element = element(selected);
        // the jigsaw block under the pointer in the preview lights its entry in the list, as an entry lights its block in the preview
        previewJig = -1;
        if (element != null && element.has("jigsaws") && !modal() && !preview.dragging() && preview.hasScene() && overPreview(mx, my))
            previewJig = jigAt(element, preview.cellAt(mx, my, px, previewY(), previewW, previewH()));
        int[] hover = drawConnectorList(g, mx, my, x, element);
        drawPreview(g, px, element, hover);
    }

    private void drawPreview(Gfx g, int x, JsonObject element, int[] hover) {
        int y = previewY();
        g.text(font, "Preview", x, TOP + 4, 0xffb5dcfc, false);
        if (element == null) { preview.show(null, null); g.fill(x, y, x + previewW, y + previewH(), 0xff1b2531); g.centeredText(font, "Select an element.", x + previewW / 2, y + previewH() / 2 - 4, MUTED); return; }
        var thumb = element.has("thumb") ? element.getAsJsonObject("thumb") : null;
        preview.show(str(element, "template", "?"), thumb);
        preview.highlight(hover);
        g.enableScissor(x, y, x + previewW, y + previewH());
        if (!preview.draw(g, x, y, previewW, previewH())) {
            g.fill(x, y, x + previewW, y + previewH(), 0xff1b2531);
            g.centeredText(font, noPicture(element), x + previewW / 2, y + previewH() / 2 - 4, element.has("missing") ? ERROR : 0xff7e8fa0);
        } else g.text(font, "drag: turn · middle drag: pan · wheel: zoom", x + 4, y + previewH() - 11, 0xff7e8fa0, false);
        g.disableScissor();
        g.outline(x, y, previewW, previewH(), ClientState.options.uiBorder);
        var size = element.has("size") ? element.getAsJsonArray("size") : null;
        if (size != null) { String text = size.get(0) + " × " + size.get(1) + " × " + size.get(2) + " blocks"; g.text(font, text, x + previewW - font.width(text), TOP + 4, MUTED, false); }
    }

    /** Draws the connectors and answers the place of the jigsaw block of the one under the pointer, or null. */
    private int[] drawConnectorList(Gfx g, int mx, int my, int x, JsonObject element) {
        int top = connY();
        int[] hover = null;
        g.text(font, "Connections", x, top + 4, 0xffb5dcfc, false);
        if (element == null || !element.has("jigsaws")) { g.text(font, "Select an element to see its connectors.", x, top + 24, MUTED, false); return null; }
        var jigsaws = element.getAsJsonArray("jigsaws");
        g.text(font, font.plainSubstrByWidth(str(element, "template", ""), rightW), x, top + 42, TEXT, false);
        g.text(font, font.plainSubstrByWidth("Enter or leaving a box applies what you typed.", rightW), x, top + 54, 0xff6f8296, false);
        if (jigsaws.size() == 0) { g.text(font, "This template has no connectors, so nothing grows from it.", x, top + CONN_HEAD, MUTED, false); return null; }
        String template = str(element, "template", "");
        int pages = jigPages(jigsaws, template).size();
        var rowList = rows(jigsaws, template);
        // lit in the preview when nothing else is: the entry the pointer is over in the preview, or else the one picked there
        for (int i = 0; i < jigsaws.size(); i++) {
            var other = jigsaws.get(i).getAsJsonObject();
            if (i == previewJig || (previewJig < 0 && foldKey(template, other).equals(pickedJig))) { var pos = other.getAsJsonArray("pos"); hover = new int[]{pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()}; break; }
        }
        for (var row : rowList) {
            int index = row.index();
            var jig = jigsaws.get(index).getAsJsonObject();
            boolean isFolded = !row.open();
            int y = row.y();
            var at = jig.getAsJsonArray("pos");
            String issue = str(jig, "issue", "");
            int fits = num(jig, "fits", -1);
            boolean notLoaded = issue.endsWith("is not loaded"); // fine when another mod adds the pool
            int rowBottom = y + (isFolded ? FOLDED_H - 6 : ROW_H - 8);
            int headingBottom = y + FOLDED_H - 6; // the heading, open or folded
            boolean pointed = (mx >= x - 2 && mx < x + rightW + 2 && my >= y - 2 && my < headingBottom) || index == previewJig;
            // the whole entry lights its block up in the preview, but only its heading takes the hover look
            if (mx >= x - 2 && mx < x + rightW + 2 && my >= y - 2 && my < rowBottom) hover = new int[]{at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()};
            if (row.window()) g.outline(x - 3, y - 3, rightW + 6, rowBottom - y + 6, ClientState.options.uiAccent);
            else if (foldKey(template, jig).equals(pickedJig)) g.outline(x - 2, y - 2, rightW + 4, headingBottom - y + 2, ClientState.options.uiAccent);
            g.fill(x - 2, y - 2, x + rightW + 2, rowBottom, issue.isEmpty() ? 0x40223247 : notLoaded ? 0x40ffc857 : 0x40ff5a4d);
            if (pointed) { g.fill(x - 2, y - 2, x + rightW + 2, headingBottom, ClientState.options.hoverColor & 0xffffff | 0x58000000); g.outline(x - 2, y - 2, rightW + 4, headingBottom - y + 2, ClientState.options.hoverColor); }
            g.text(font, "#" + (index + 1) + " at " + at.get(0) + " " + at.get(1) + " " + at.get(2), x + 22, y + 2, MUTED, false);
            String verdict = notLoaded ? issue + " (fine if a mod adds it)" : !issue.isEmpty() ? issue : fits < 0 ? "ends here (empty pool)" : "meets " + fits + (fits == 1 ? " element" : " elements");
            g.text(font, font.plainSubstrByWidth(verdict, rightW - 130), x + rightW - 4 - Math.min(font.width(verdict), rightW - 130), y + 2, notLoaded ? WARN : !issue.isEmpty() ? ERROR : fits < 0 ? MUTED : OK, false);
            if (isFolded) continue;
            g.text(font, "Name", x, y + 18, MUTED, false);
            g.text(font, "Pool", x, y + 42, MUTED, false);
            g.text(font, "Target", x, y + 66, MUTED, false);
            drawTargetThumbnail(g, x + rightW - THUMB_W, y + 38, str(jig, "pool", ""), str(jig, "target", ""));
            g.text(font, "Turns into", x, y + 114, MUTED, false);
            g.text(font, "place", x + 82, y + 90, MUTED, false);
            g.text(font, "select", x + 160, y + 90, MUTED, false);
        }
        if (singleView) g.text(font, font.plainSubstrByWidth("Click a jigsaw block in the preview to change this.", rightW), x, top + CONN_HEAD + ROW_H + 2, 0xff6f8296, false);
        if (!singleView) g.text(font, (jigPage + 1) + "/" + pages, x + rightW - 62 - font.width((jigPage + 1) + "/" + pages), top + 4, MUTED, false);
        return hover;
    }

    private void drawOverlay(Gfx g) {
        int w = Math.min(460, cw - 40), x = (cw - w) / 2, y = 40;
        g.fill(0, 0, cw, ch, 0xaa000000);
        g.fill(x, y, x + w, ch - 40, 0xff172330);
        g.outline(x, y, w, ch - 80, ClientState.options.uiAccent);
        g.text(font, "Add an element to " + shortName(poolId()), x + 10, y + 10, 0xffb5dcfc, false);
        g.text(font, "An element is one entry of a pool.", x + 10, y + 21, 0xff6f8296, false);
        int top = y + 60, listY = top + 38, rows = Math.max(1, (ch - 40 - listY - 12) / 20);
        switch (addTab) {
            case 0 -> {
                g.text(font, "Templates of every pack, and the structures you saved:", x + 10, top, MUTED, false);
                int found = model.has("templates") ? model.getAsJsonArray("templates").size() : 0;
                if (found == 0) g.text(font, "No templates match.", x + 10, listY + 4, MUTED, false);
                else if (found > rows) g.text(font, "...and " + (found - rows) + " more: narrow the search", x + 10, listY + rows * 20 + 2, MUTED, false);
            }
            case 1 -> {
                int blocks = num(model, "selection", 0), line = top;
                String text = "Saves the blocks you have selected as a new template and adds it to the pool. Put jigsaw blocks in the selection first if other pieces should attach to it.";
                for (var row : font.split(Component.literal(text), w - 20)) { g.text(font, row, x + 10, line, MUTED, false); line += 10; }
                g.text(font, blocks > 0 ? blocks + " blocks are selected." : "Select blocks in the world first.", x + 10, line + 4, blocks > 0 ? OK : WARN, false);
            }
            case 2 -> {
                int line = top;
                String text = "Imports a Java structure file (.nbt) from your computer, or takes a blueprint from the blueprint library, as a new template and adds it to the pool. The jigsaw blocks inside it are kept.";
                for (var row : font.split(Component.literal(text), w - 20)) { g.text(font, row, x + 10, line, MUTED, false); line += 10; }
            }
            case 3 -> {
                g.text(font, "Trees, flowers and other things the game places by itself:", x + 10, top, MUTED, false);
                int found = model.has("features") ? model.getAsJsonArray("features").size() : 0;
                if (found == 0) g.text(font, "No features match.", x + 10, listY + 4, MUTED, false);
                else if (found > rows) g.text(font, "...and " + (found - rows) + " more: narrow the search", x + 10, listY + rows * 20 + 2, MUTED, false);
            }
            default -> {
                int line = top;
                String text = "An empty slot places nothing. With a chance of its own it lets a connector end here, so that not every connector grows another piece.";
                for (var row : font.split(Component.literal(text), w - 20)) { g.text(font, row, x + 10, line, MUTED, false); line += 10; }
            }
        }
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

    // ---- naming a pool, and the guide

    private void openDialog(String mode) {
        dialogMode = mode;
        dialogText = switch (mode) { case "pool_new" -> ""; case "pool_copy" -> shortName(poolId()) + "_copy"; default -> shortName(poolId()); };
        localMessage = "";
        init();
    }

    /** Opens the window that names a Bedrock pack made from the open pool, and asks the server what the pack would hold and lose. */
    private void openBedrock() {
        if (poolId().isEmpty()) return;
        dialogMode = "bedrock_pack";
        dialogText = dev.craftium.core.BedrockPack.defaultName(poolId());
        localMessage = "";
        edit("bedrock_plan", "pool", poolId(), "name", dialogText);
        init();
    }

    private int bedrockHeight() { return Math.min(ch - 40, 330); }

    private void dialogButtons() {
        if ("bedrock_pack".equals(dialogMode)) {
            int w = Math.min(520, cw - 40), x = (cw - w) / 2, h = bedrockHeight(), y = Math.max(20, (ch - h) / 2);
            var field = box(x + 10, y + 36, w - 20, dialogText, 80, v -> dialogText = v, this::confirmDialog);
            setFocused(field);
            field.setFocused(true);
            var bedrock = model.has("bedrock") ? model.getAsJsonObject("bedrock") : null;
            var export = button("Export…", x + 10, y + h - 32, 120, 22, this::confirmDialog);
            export.active = bedrock != null && !(bedrock.has("tooBig") && bedrock.get("tooBig").getAsBoolean());
            button("Cancel", x + 136, y + h - 32, 90, 22, () -> { dialogMode = null; init(); });
            return;
        }
        int w = Math.min(420, cw - 40), x = (cw - w) / 2, y = Math.max(60, ch / 2 - 70);
        var field = box(x + 10, y + 56, w - 20, dialogText, 120, v -> dialogText = v, this::confirmDialog);
        setFocused(field);
        field.setFocused(true);
        String ok = switch (dialogMode) { case "pool_new" -> "Create pool"; case "pool_copy" -> "Copy pool"; default -> "Rename pool"; };
        button(ok, x + 10, y + 100, 120, 22, this::confirmDialog);
        button("Cancel", x + 136, y + 100, 90, 22, () -> { dialogMode = null; init(); });
    }

    private void confirmDialog() {
        String text = dialogText.strip();
        if (text.isEmpty()) { localMessage = "Type a name first"; return; }
        String mode = dialogMode;
        dialogMode = null;
        switch (mode) {
            case "bedrock_pack" -> {
                String pool = poolId();
                // the save dialog first (it starts in Documents), then the server builds the pack and sends it to the path that was chosen
                ClientBedrockPack.choose(dev.craftium.core.BedrockPack.fileName(text), path -> { ClientBedrockPack.expect(path); edit("bedrock_export", "pool", pool, "name", text); });
                init();
            }
            case "pool_new" -> edit("pool_new", "id", text);
            case "pool_copy" -> edit("pool_copy", "from", poolId(), "to", text);
            default -> edit("pool_rename", "from", poolId(), "to", text);
        }
    }

    private void drawBedrockDialog(Gfx g) {
        int w = Math.min(520, cw - 40), x = (cw - w) / 2, h = bedrockHeight(), y = Math.max(20, (ch - h) / 2);
        g.fill(0, 0, cw, ch, 0xaa000000);
        g.fill(x, y, x + w, y + h, 0xff172330);
        g.outline(x, y, w, h, ClientState.options.uiAccent);
        g.text(font, "Export Bedrock pack", x + 10, y + 10, 0xffb5dcfc, false);
        g.text(font, "Pack name", x + 10, y + 25, MUTED, false);
        int line = y + 62, bottom = y + h - 40;
        var bedrock = model.has("bedrock") ? model.getAsJsonObject("bedrock") : null;
        if (bedrock == null) {
            String say = localMessage.isEmpty() ? "Working out what the pack holds…" : localMessage;
            g.text(font, say, x + 10, line, localMessage.isEmpty() ? MUTED : ERROR, false);
        } else {
            String structures = num(bedrock, "structures", 0) > 0 ? ", " + num(bedrock, "structures", 0) + (num(bedrock, "structures", 0) == 1 ? " structure" : " structures") : "";
            String head = shortName(str(bedrock, "pool", "")) + " and everything it leads to: " + num(bedrock, "pools", 0) + (num(bedrock, "pools", 0) == 1 ? " pool, " : " pools, ")
                    + num(bedrock, "pieces", 0) + (num(bedrock, "pieces", 0) == 1 ? " piece" : " pieces") + structures;
            for (var row : font.split(Component.literal(head), w - 20)) { g.text(font, row, x + 10, line, TEXT, false); line += 10; }
            boolean tooBig = bedrock.has("tooBig") && bedrock.get("tooBig").getAsBoolean();
            int kib = (num(bedrock, "bytes", 0) + 1023) / 1024;
            g.text(font, kib + " KiB" + (tooBig ? " is over the 1,024 KiB the game can send you: export a pool that leads to fewer or smaller pieces" : ""), x + 10, line, tooBig ? ERROR : MUTED, false);
            line += 14;
            int lost = num(bedrock, "lost", 0), changed = num(bedrock, "changed", 0), check = num(bedrock, "check", 0);
            g.text(font, (lost + changed + check == 0 ? "Nothing is lost, changed or left unchecked." : "Left out: " + lost + "   Changed: " + changed + "   To check in Bedrock: " + check), x + 10, line, lost > 0 ? WARN : TEXT, false);
            line += 14;
            if (bedrock.has("notes")) for (var item : bedrock.getAsJsonArray("notes")) {
                if (line > bottom - 10) break;
                var note = item.getAsJsonObject();
                String kind = str(note, "kind", "");
                int colour = kind.equals("LOST") ? WARN : kind.equals("CHANGED") ? TEXT : MUTED;
                String text = (kind.equals("LOST") ? "Left out: " : kind.equals("CHANGED") ? "Changed: " : "Check: ") + str(note, "text", "");
                for (var row : font.split(Component.literal(text), w - 20)) {
                    if (line > bottom - 10) break;
                    g.text(font, row, x + 10, line, colour, false);
                    line += 10;
                }
                line += 2;
            }
            int more = Math.max(0, num(bedrock, "moreNotes", 0));
            if (more > 0 || line > bottom - 10) { g.text(font, "The whole list is in craftium-export-report.txt inside the pack.", x + 10, bottom - 2, MUTED, false); }
        }
        g.text(font, "Next you choose where to save it (it starts in your Documents).", x + 236, y + h - 26, MUTED, false);
        if (!localMessage.isEmpty() && bedrock != null) g.text(font, localMessage, x + 10, y + h - 44, ERROR, false);
    }

    private void drawDialog(Gfx g) {
        if ("bedrock_pack".equals(dialogMode)) { drawBedrockDialog(g); return; }
        int w = Math.min(420, cw - 40), x = (cw - w) / 2, y = Math.max(60, ch / 2 - 70);
        g.fill(0, 0, cw, ch, 0xaa000000);
        g.fill(x, y, x + w, y + 136, 0xff172330);
        g.outline(x, y, w, 136, ClientState.options.uiAccent);
        String title = switch (dialogMode) { case "pool_new" -> "New pool"; case "pool_copy" -> "Copy " + shortName(poolId()); default -> "Rename " + shortName(poolId()); };
        g.text(font, title, x + 10, y + 10, 0xffb5dcfc, false);
        int line = y + 24;
        for (var row : font.split(Component.literal("A pool is a list of pieces that a connector can pick from. Give it a name: without a namespace it goes in craftium, so my_house means craftium:my_house."), w - 20)) { g.text(font, row, x + 10, line, MUTED, false); line += 10; }
        if (!localMessage.isEmpty()) g.text(font, localMessage, x + 10, y + 82, ERROR, false);
    }

    private void helpButtons() {
        int w = Math.min(520, cw - 40), x = (cw - w) / 2;
        button("Close", x + w - 80, 46, 70, 20, () -> { helpOpen = false; init(); });
    }

    private void drawHelp(Gfx g) {
        int w = Math.min(520, cw - 40), x = (cw - w) / 2, y = 40;
        g.fill(0, 0, cw, ch, 0xaa000000);
        g.fill(x, y, x + w, ch - 30, 0xff172330);
        g.outline(x, y, w, ch - 70, ClientState.options.uiAccent);
        String[][] text = {
            {"h", "What is a pool?"},
            {"t", "A pool is a list of pieces that a connector can pick from, each with a chance. The generator starts with one piece from the start pool. For every connector (jigsaw block) in a piece it picks a piece from the pool that connector names, one that has a connector with the name it asks for, places it, and carries on from there until the depth runs out."},
            {"t", "An element is one entry of a pool: a template, a feature such as a tree, or an empty slot. The chance says how often it is picked next to the others. The fallback pool is used when nothing fits."},
            {"h", "Making a structure"},
            {"t", "1. Build a piece in the world. Put a jigsaw block wherever another piece may attach, and give it a name, the pool to pick from and the name the next piece must have."},
            {"t", "2. Here, make a pool with New pool…, then + Add element and save your selection as a template."},
            {"t", "3. Make the pools your connectors point at, and add pieces to those in the same way. A connector that points at a pool that does not exist has a button to make it."},
            {"t", "4. The Connectors panel shows how many pieces can attach to each connector. Preview from here shows the result in the world, and Apply keeps your changes in the datapack."},
            {"h", "A connector's fields"},
            {"t", "Name is this connector's own name. Pool is the pool the next piece comes from. Target is the name the next piece's connector must have. Rollable or Aligned is how the next piece may turn on a connector that faces up or down."},
        };
        int line = y + 12;
        for (var part : text) {
            boolean heading = part[0].equals("h");
            if (heading) line += 4;
            for (var row : font.split(Component.literal(part[1]), w - 24)) { g.text(font, row, x + 12, line, heading ? 0xffb5dcfc : MUTED, false); line += 10; }
            line += heading ? 2 : 5;
        }
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
        if (resizing != 0) { resizing = 0; return true; }
        if (preview.dragging()) {
            boolean turn = event.button() == 0 && Math.hypot(event.x() - pressPreviewX, event.y() - pressPreviewY) <= 3; // let go in place: a click, not a turn
            preview.letGo();
            if (turn) pickJigsawInPreview(event.x(), event.y());
            return true;
        }
        if (draggingPoolBar || draggingGraphBar || draggingTargetBar) { draggingPoolBar = false; draggingGraphBar = false; draggingTargetBar = false; return true; }
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
            } else if (!pressMissing) openPool(id, 0);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override public boolean mouseDragged(MouseButtonEvent raw, double dx, double dy) {
        var event = scaled(raw);
        if (resizing != 0) { dragDivider(event.x(), event.y()); return true; }
        if (preview.dragging()) { preview.drag(dx / uiScale, dy / uiScale); return true; }
        if (draggingTargetBar) { var list = offers(targetMenuPool, targetMenuCurrent); targetMenuScroll = Scrollbars.scrollAt(event.y(), targetMenuY + 2, targetMenuRows(Math.max(1, list.size())) * TARGET_MENU_ROW, list.size() * TARGET_MENU_ROW); return true; }
        if (draggingPoolBar) { int rows = menuRows(visiblePools().size()); poolScroll = Scrollbars.scrollAt(event.y(), menuY() + 2, rows * LIST_ROW, visiblePools().size() * LIST_ROW); return true; }
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

    /** A click while the dropdown is open: a row opens that pool, the bar scrolls, and a click anywhere but the box and its arrow closes the menu (and goes on to whatever was clicked). */
    private boolean clickPoolMenu(double x, double y) {
        var pools = visiblePools();
        int rows = menuRows(pools.size());
        if (overPoolMenu(x, y)) {
            int top = menuY() + 2;
            boolean bar = pools.size() > rows;
            if (bar && Scrollbars.hit(x, y, centreX + pickW + 24 - Scrollbars.WIDTH - 2, top, rows * LIST_ROW)) { draggingPoolBar = true; poolScroll = Scrollbars.scrollAt(y, top, rows * LIST_ROW, pools.size() * LIST_ROW); return true; }
            if (y >= top && y < top + rows * LIST_ROW) {
                int i = (int) ((y - top + poolScroll) / LIST_ROW);
                if (i >= 0 && i < pools.size()) { closeMenu(); openPool(pools.get(i).get("id").getAsString(), 0); }
            }
            return true;
        }
        boolean onBox = x >= centreX && x < centreX + pickW + 24 && y >= TOP - 4 && y < TOP + 14;
        if (!onBox) { closeMenu(); init(); }
        return false;
    }

    @Override public boolean mouseClicked(MouseButtonEvent raw, boolean twice) {
        var event = scaled(raw);
        if (event.button() == 1) for (var child : children()) if (child instanceof FlatButton b && b.rightClick(event.x(), event.y())) return true; // a cycling button steps back
        if ((event.button() == 3 || event.button() == 4) && !modal()) { stepHistory(event.button() == 3 ? -1 : 1); return true; } // the mouse's back and forward buttons
        if (event.button() == 0 && clickSuggestion(event.x(), event.y())) return true;
        if (event.button() == 0 && !modal() && targetMenuKey != null && clickTargetMenu(event.x(), event.y())) return true;
        if (event.button() == 0 && !modal() && poolMenuOpen && clickPoolMenu(event.x(), event.y())) return true;
        flushEdits(); // leaving a box applies it, before this click changes anything
        if (event.button() == 0 && !modal() && !graphDragging() && pressDivider(event.x(), event.y())) return true;
        EditBox focusedBefore = getFocused() instanceof EditBox before && before.isFocused() ? before : null;
        if (super.mouseClicked(event, twice)) {
            // a click that puts the focus in a box selects all of its text, so typing replaces it; a click in the box that already has the focus moves the cursor as usual
            if (event.button() == 0 && getFocused() instanceof EditBox now && now.isFocused() && now != focusedBefore) selectAll(now);
            if (event.button() == 0 && !modal() && poolBox != null && poolBox.isFocused() && !poolMenuOpen) { poolMenuOpen = true; scrollMenuToOpenPool(); }
            return true;
        }
        if (!modal() && pool() != null && overPreview(event.x(), event.y()) && preview.hasScene() && preview.press(event.button())) { pressPreviewX = event.x(); pressPreviewY = event.y(); return true; }
        if (event.button() != 0 || adding) return false;
        double x = event.x(), y = event.y();
        var graphBar = graphBar();
        if (graphBar != null && Scrollbars.hit(x, y, graphBar[0], graphBar[1], graphBar[2])) { draggingGraphBar = true; graphScroll = Scrollbars.scrollAt(y, graphBar[1], graphBar[2], graphBar[3]); return true; }
        if (pool() != null && usedView && x >= centreX && x < centreX + centreW && y >= cardsTop() - 4 && y < ch - FOOT) {
            int row = (int) ((y - (cardsTop() - 4) + usedScroll) / USED_ROW);
            var list = usedList();
            if (row >= 0 && row < list.size()) { var u = list.get(row).getAsJsonObject(); openPool(str(u, "pool", ""), num(u, "element", 0)); return true; }
        }
        if (pool() != null && !graph && !usedView && x >= centreX && x < centreX + centreW && y >= cardsTop()) {
            int slot = (int) ((y - cardsTop()) / (CARD_H + CARD_GAP)), position = page * perPage() + slot;
            if (slot < perPage() && position < shown().size()) { selected = shown().get(position); jigPage = 0; init(); return true; }
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
        double y = rawY / uiScale;
        if (!modal() && pool() != null && overPreview(x, y) && preview.hasScene()) { preview.zoom(vertical, x - (previewX() + previewW / 2.0), y - (previewY() + previewH() / 2.0)); return true; }
        if (suggestionsShowing() && overSuggestions(x, y)) { suggestScroll = Math.clamp(suggestScroll - (int) Math.signum(vertical) * 3, 0, Math.max(0, suggestMatches.size() - SUGGEST_ROWS)); return true; }
        if (targetMenuKey != null && overTargetMenu(x, y)) { int count = offers(targetMenuPool, targetMenuCurrent).size(); targetMenuScroll = Math.clamp(targetMenuScroll - vertical * 30, 0, Math.max(0, (count - targetMenuRows(Math.max(1, count))) * TARGET_MENU_ROW)); return true; }
        if (poolMenuOpen && overPoolMenu(x, y)) { int count = visiblePools().size(); poolScroll = Math.clamp(poolScroll - vertical * 30, 0, Math.max(0, (count - menuRows(count)) * LIST_ROW)); return true; }
        if (usedView && x < centreX + centreW + GAP && x >= centreX) { usedScroll = Math.clamp(usedScroll - vertical * 30, 0, Math.max(0, usedList().size() * USED_ROW - (ch - FOOT - (cardsTop() - 4)))); return true; }
        if (graph && x < centreX + centreW + GAP) { var bar = graphBar(); graphScroll = Math.clamp(graphScroll - vertical * 30, 0, bar == null ? 0 : bar[3] - bar[2]); return true; }
        return false;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (suggestionsShowing()) {
            switch (event.key()) {
                case GLFW.GLFW_KEY_DOWN -> { suggestHighlight = Math.min(suggestMatches.size() - 1, suggestHighlight + 1); keepSuggestionVisible(); return true; }
                case GLFW.GLFW_KEY_UP -> { suggestHighlight = suggestHighlight <= 0 ? suggestMatches.size() - 1 : suggestHighlight - 1; keepSuggestionVisible(); return true; }
                case GLFW.GLFW_KEY_PAGE_DOWN -> { suggestHighlight = Math.min(suggestMatches.size() - 1, Math.max(0, suggestHighlight) + SUGGEST_ROWS); keepSuggestionVisible(); return true; }
                case GLFW.GLFW_KEY_PAGE_UP -> { suggestHighlight = Math.max(0, Math.max(0, suggestHighlight) - SUGGEST_ROWS); keepSuggestionVisible(); return true; }
                case GLFW.GLFW_KEY_TAB -> { acceptSuggestion(suggestMatches.get(Math.max(0, suggestHighlight))); return true; }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { if (suggestHighlight >= 0) { acceptSuggestion(suggestMatches.get(suggestHighlight)); return true; } }
                case GLFW.GLFW_KEY_ESCAPE -> { suggesting = false; return true; }
                default -> { }
            }
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (getFocused() instanceof EditBox box && applied.containsKey(box)) { applyIfChanged(box); return true; }
            if (getFocused() instanceof EditBox box && commits.containsKey(box)) { commits.get(box).run(); return true; }
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && targetMenuKey != null && !modal()) { targetMenuKey = null; init(); return true; }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && poolMenuOpen && !modal()) { closeMenu(); init(); return true; }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && adding) { adding = false; init(); return true; }
        return super.keyPressed(event);
    }

    /** The size this screen is laid out for: at Auto the interface takes the largest whole scale at which it fits, instead of shrinking it to fit. */
    @Override protected int[] canvas() { return new int[]{800, 440}; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { preview.releaseAll(); PoolThumbnails.releaseAll(); }
    @Override public void onClose() {
        if (dialogMode != null) { dialogMode = null; init(); return; }
        if (helpOpen) { helpOpen = false; init(); return; }
        if (retargetPrompt) { retargetPrompt = false; init(); return; }
        if (confirmLeave) { confirmLeave = false; init(); return; } // Escape on the question means keep editing
        if (unappliedCount() > 0) { confirmLeave = true; init(); return; }
        finishClose();
    }
}
