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
    private final Map<EditBox, Runnable> commits = new HashMap<>();
    private Integer pendingWeightIndex;
    private int pendingWeight;
    private long pendingAt;
    private String localMessage = "";
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

    private void edit(String op, Object... pairs) { localMessage = ""; send(cmd(op, pairs)); }

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
        leftW = Math.clamp(cw / 5, 150, 210);
        rightW = Math.clamp(cw * 3 / 10, 230, 300);
        centreX = SIDE + leftW + 10;
        centreW = Math.max(200, cw - SIDE - rightW - 10 - centreX);
        button("Close", cw - SIDE - 70, 10, 70, 20, this::onClose);
        button("Redo (" + num(model, "redo", 0) + ")", cw - SIDE - 70 - 76, 10, 70, 20, () -> edit("redo")).active = num(model, "redo", 0) > 0;
        button("Undo (" + num(model, "undo", 0) + ")", cw - SIDE - 70 - 152, 10, 70, 20, () -> edit("undo")).active = num(model, "undo", 0) > 0;
        if (adding) { overlay(); return; }
        leftPane();
        if (pool() != null) { centreHeader(); if (!graph) cards(); }
        rightPane();
    }

    private void leftPane() {
        box(SIDE, TOP, leftW, filter, 64, v -> { filter = v; poolScroll = 0; }, null);
        int y = ch - FOOT - 66;
        box(SIDE, y, leftW, name, 120, v -> name = v, () -> action("pool_new"));
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
        box(centreX + 54, y, Math.min(260, centreW - 54 - 130), str(pool(), "fallback", "minecraft:empty"), 120, null, () -> edit("fallback_set", "pool", poolId(), "fallback", focusedText()));
        button("Preview from here", centreX + centreW - 124, y - 1, 124, 20, () -> { ClientState.options.jigsawPool = poolId(); CraftiumClient.send("craftium:jigsaw_generate", null, null); });
        int tabs = y + 28;
        button("Elements", centreX, tabs, 78, 20, () -> { graph = false; init(); }).lit(!graph);
        button("Graph", centreX + 82, tabs, 78, 20, () -> { graph = true; init(); }).lit(graph);
        button("+ Add template", centreX + 166, tabs, 110, 20, () -> { adding = true; query = ""; init(); });
        if (!graph) {
            int pages = Math.max(1, (elements().size() + perPage() - 1) / perPage());
            page = Math.clamp(page, 0, pages - 1);
            button("‹", centreX + centreW - 96, tabs, 24, 20, () -> { page = Math.max(0, page - 1); init(); });
            button("›", centreX + centreW - 24, tabs, 24, 20, () -> { page = Math.min(pages - 1, page + 1); init(); });
        }
    }

    private String focusedText() { return getFocused() instanceof EditBox e ? e.getValue() : ""; }
    private int cardsTop() { return TOP + 70; }
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
            addRenderableWidget(slider);
            boolean rigid = str(element, "projection", "rigid").equals("rigid");
            var projection = button(rigid ? "Rigid" : "Follow terrain", tx, y + 48, 96, 18, () -> edit("element_set", "pool", poolId(), "index", index, "projection", rigid ? "terrain_matching" : "rigid"));
            projection.active = editable || element.has("projection");
            if (editable && element.has("processors")) {
                var processors = box(tx + 100, y + 48, Math.max(40, w - 100), str(element, "processors", "minecraft:empty"), 120, null, () -> edit("element_set", "pool", poolId(), "index", index, "processors", focusedText()));
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
            box(x + 38, y + 14, rightW - 38, str(jig, "name", ""), 80, null, () -> { var c = base.deepCopy(); c.addProperty("name", focusedText()); send(c); });
            box(x + 38, y + 38, half - 38, str(jig, "pool", ""), 100, null, () -> { var c = base.deepCopy(); c.addProperty("pool", focusedText()); send(c); });
            box(x + half + 4 + 40, y + 38, rightW - (half + 4) - 40, str(jig, "target", ""), 100, null, () -> { var c = base.deepCopy(); c.addProperty("target", focusedText()); send(c); });
            boolean rollable = str(jig, "joint", "rollable").equals("rollable");
            button(rollable ? "Rollable" : "Aligned", x, y + 62, 76, 18, () -> { var c = base.deepCopy(); c.addProperty("joint", rollable ? "aligned" : "rollable"); send(c); });
            box(x + 114, y + 62, 40, String.valueOf(num(jig, "placement", 0)), 4, null, () -> { var c = base.deepCopy(); setInt(c, "placement", focusedText()); send(c); });
            box(x + 204, y + 62, 40, String.valueOf(num(jig, "selection", 0)), 4, null, () -> { var c = base.deepCopy(); setInt(c, "selection", focusedText()); send(c); });
        }
    }

    private void setInt(JsonObject json, String key, String text) {
        try { json.addProperty(key, Integer.parseInt(text.strip())); }
        catch (NumberFormatException e) { json.addProperty(key, 0); }
    }

    /** The panel that adds a template to the pool: a search of the templates the server knows, or the player's selection saved as a new one. */
    private void overlay() {
        int w = Math.min(460, cw - 40), x = (cw - w) / 2, y = 40;
        box(x + 10, y + 34, w - 20, query, 80, v -> query = v, () -> edit("templates", "query", query));
        var found = model.has("templates") ? model.getAsJsonArray("templates") : new JsonArray();
        int rows = Math.max(1, (ch - 40 - 150 - 40) / 20);
        for (int i = 0; i < Math.min(found.size(), rows); i++) {
            String id = found.get(i).getAsString();
            button(id, x + 10, y + 58 + i * 20, w - 20, 18, () -> { edit("element_add", "pool", poolId(), "template", id); adding = false; });
        }
        int bottom = Math.min(ch - 40 - 80, y + 58 + rows * 20 + 8);
        box(x + 10, bottom + 18, w - 130, selectionName, 80, v -> selectionName = v, null);
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
        if (ClientState.jigsawModelVersion != seenVersion) init();
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
        g.text(font, "JIGSAW POOLS", SIDE, 12, 0xffb5dcfc, false);
        String message = !localMessage.isEmpty() ? localMessage : str(model, "message", "").isEmpty() ? ClientState.status.message() : str(model, "message", "");
        g.text(font, font.plainSubstrByWidth(message, cw - 2 * SIDE - 240), SIDE, 28, 0xffffdca8, false);
        if (model.has("registryError")) g.text(font, font.plainSubstrByWidth("A pool file has a problem: " + str(model, "registryError", ""), cw - 2 * SIDE), SIDE, 38, ERROR, false);
        if (adding) { drawOverlay(g); drawWidgets(g, mx, my, delta); g.pose().popMatrix(); return; }
        drawPools(g, mx, my);
        if (pool() == null) g.text(font, "Pick a pool on the left, or make a new one.", centreX, TOP + 20, MUTED, false);
        else { drawCentre(g); if (graph) drawGraph(g, mx, my); else drawCards(g, mx, my); }
        drawConnections(g);
        drawWidgets(g, mx, my, delta);
        if (filter.isEmpty()) g.text(font, "filter " + (model.has("pools") ? model.getAsJsonArray("pools").size() : 0) + " pools", SIDE + 5, TOP + 5, 0xff5f7186, false);
        if (name.isEmpty()) g.text(font, "name for New, Copy or Rename", SIDE + 5, ch - FOOT - 66 + 5, 0xff5f7186, false);
        g.pose().popMatrix();
    }

    private void drawPools(Gfx g, int mx, int my) {
        g.fill(SIDE - 4, TOP - 4, SIDE + leftW + 4, ch - FOOT + 2, PANEL);
        g.enableScissor(SIDE, listTop(), SIDE + leftW, listBottom());
        var pools = visiblePools();
        for (int i = 0; i < pools.size(); i++) {
            int y = listTop() + i * LIST_ROW - (int) poolScroll;
            if (y + LIST_ROW < listTop() || y > listBottom()) continue;
            var p = pools.get(i);
            boolean current = p.get("id").getAsString().equals(poolId()), hover = mx >= SIDE && mx < SIDE + leftW && my >= Math.max(y, listTop()) && my < Math.min(y + LIST_ROW, listBottom());
            g.fill(SIDE, y, SIDE + leftW, y + LIST_ROW - 2, current ? 0xff315473 : hover ? 0xff2a3d55 : CARD);
            String source = str(p, "source", "");
            g.fill(SIDE, y, SIDE + 3, y + LIST_ROW - 2, source.equals("craftium") ? OK : source.equals("pack") ? 0xff8fd3ff : 0xff5a6b7d);
            int errors = num(p, "errors", 0), warnings = num(p, "warnings", 0);
            String badge = errors > 0 ? errors + "!" : warnings > 0 ? warnings + "?" : "";
            int room = leftW - 12 - (badge.isEmpty() ? 0 : font.width(badge) + 6);
            g.text(font, font.plainSubstrByWidth(shortName(p.get("id").getAsString()), room), SIDE + 7, y + 5, TEXT, false);
            if (!badge.isEmpty()) g.text(font, badge, SIDE + leftW - font.width(badge) - 4, y + 5, errors > 0 ? ERROR : WARN, false);
        }
        g.disableScissor();
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
        if (!graph) {
            int pages = Math.max(1, (elements().size() + perPage() - 1) / perPage());
            String label = (page + 1) + "/" + pages;
            g.text(font, label, centreX + centreW - 60 - font.width(label) / 2, TOP + 16 + 28 + 6, MUTED, false);
            String count = elements().size() + (elements().size() == 1 ? " element" : " elements");
            g.text(font, count, centreX + centreW - 150 - font.width(count), TOP + 16 + 28 + 6, MUTED, false);
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
            else g.centeredText(font, element.has("missing") ? "missing" : "no picture", x + 6 + PoolThumbnails.WIDTH / 2, y + 6 + PoolThumbnails.HEIGHT / 2 - 4, element.has("missing") ? ERROR : 0xff7e8fa0);
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
        g.text(font, "CONNECTIONS", x, TOP + 4, 0xffb5dcfc, false);
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
        g.text(font, "ADD A TEMPLATE TO " + shortName(poolId()).toUpperCase(Locale.ROOT), x + 10, y + 10, 0xffb5dcfc, false);
        g.text(font, "Search (Enter): templates of every pack and the structures you saved", x + 10, y + 24, MUTED, false);
        int rows = Math.max(1, (ch - 40 - 150 - 40) / 20);
        int found = model.has("templates") ? model.getAsJsonArray("templates").size() : 0;
        if (found == 0) g.text(font, "No templates match.", x + 10, y + 62, MUTED, false);
        else if (found > rows) g.text(font, "...and " + (found - rows) + " more: narrow the search", x + 10, y + 58 + rows * 20 + 0, MUTED, false);
        int bottom = Math.min(ch - 40 - 80, y + 58 + rows * 20 + 8);
        int blocks = num(model, "selection", 0);
        g.text(font, blocks > 0 ? "Or save your selection (" + blocks + " blocks) as a new template:" : "Or select blocks in the world to save them as a new template.", x + 10, bottom + 4, MUTED, false);
    }

    // ---- the graph

    private record Node(String id, boolean missing, int elements, int depth, int row) {}

    private void drawGraph(Gfx g, int mx, int my) {
        var graphModel = model.has("graph") ? model.getAsJsonObject("graph") : null;
        int top = cardsTop() - 4;
        if (graphModel == null || graphModel.getAsJsonArray("nodes").size() == 0) { g.text(font, "Nothing to draw: the pools are not loaded (see the message above).", centreX, top + 6, MUTED, false); return; }
        var nodes = layout(graphModel);
        int maxDepth = nodes.stream().mapToInt(Node::depth).max().orElse(0);
        int nodeW = Math.clamp((centreW - 10) / (maxDepth + 1) - 22, 60, 170), nodeH = 18, gap = 20, rowH = nodeH + 5;
        g.enableScissor(centreX, top, centreX + centreW, ch - FOOT);
        var place = new HashMap<String, int[]>();
        for (var n : nodes) place.put(n.id(), new int[]{centreX + 4 + n.depth() * (nodeW + gap), top + 4 + n.row() * rowH - (int) graphScroll});
        for (var e : graphModel.getAsJsonArray("edges")) {
            var edge = e.getAsJsonObject();
            var a = place.get(str(edge, "from", "")); var b = place.get(str(edge, "to", ""));
            if (a == null || b == null || a == b) continue;
            int x1 = a[0] + nodeW, y1 = a[1] + nodeH / 2, x2 = b[0], y2 = b[1] + nodeH / 2;
            int mid = x2 > x1 ? (x1 + x2) / 2 : x1 + gap / 2;
            int colour = 0xff4a6585;
            g.fill(x1, y1, mid + 1, y1 + 1, colour);
            g.fill(mid, Math.min(y1, y2), mid + 1, Math.max(y1, y2) + 1, colour);
            if (x2 > mid) g.fill(mid, y2, x2 - 2, y2 + 1, colour);
            g.fill(x2 - 3, y2 - 2, x2 - 2, y2 + 3, 0xff8fd3ff);
            g.fill(x2 - 2, y2 - 1, x2, y2 + 2, 0xff8fd3ff);
        }
        for (var n : nodes) {
            var at = place.get(n.id());
            boolean current = n.id().equals(poolId()), hover = mx >= at[0] && mx < at[0] + nodeW && my >= at[1] && my < at[1] + nodeH && my >= top;
            g.fill(at[0], at[1], at[0] + nodeW, at[1] + nodeH, n.missing() ? 0xff5a2a2a : current ? 0xff315473 : hover ? 0xff2f4663 : CARD);
            g.outline(at[0], at[1], nodeW, nodeH, current ? ClientState.options.uiAccent : ClientState.options.uiBorder);
            String label = shortName(n.id()).replaceAll("^.*/", "") + (n.missing() ? " (missing)" : " · " + n.elements());
            g.text(font, font.plainSubstrByWidth(label, nodeW - 6), at[0] + 3, at[1] + 5, n.missing() ? ERROR : TEXT, false);
        }
        g.disableScissor();
        g.text(font, "Arrows go from a pool to the pools its connectors ask for. Click a pool to open it.", centreX, ch - FOOT - 10, 0xff6f8296, false);
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

    @Override public boolean mouseReleased(MouseButtonEvent event) { return super.mouseReleased(scaled(event)); }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return super.mouseDragged(scaled(event), dx / uiScale, dy / uiScale); }

    @Override public boolean mouseClicked(MouseButtonEvent raw, boolean twice) {
        var event = scaled(raw);
        if (super.mouseClicked(event, twice)) return true;
        if (event.button() != 0 || adding) return false;
        double x = event.x(), y = event.y();
        if (x >= SIDE && x < SIDE + leftW && y >= listTop() && y < listBottom()) {
            int i = (int) ((y - listTop() + poolScroll) / LIST_ROW);
            var pools = visiblePools();
            if (i >= 0 && i < pools.size()) { selected = 0; page = 0; jigPage = 0; edit("open", "pool", pools.get(i).get("id").getAsString()); return true; }
        }
        if (pool() != null && !graph && x >= centreX && x < centreX + centreW && y >= cardsTop()) {
            int slot = (int) ((y - cardsTop()) / (CARD_H + CARD_GAP)), index = page * perPage() + slot;
            if (index < elements().size()) { selected = index; jigPage = 0; init(); return true; }
        }
        if (pool() != null && graph && model.has("graph")) {
            var graphModel = model.getAsJsonObject("graph");
            var nodes = layout(graphModel);
            int maxDepth = nodes.stream().mapToInt(Node::depth).max().orElse(0), nodeW = Math.clamp((centreW - 10) / (maxDepth + 1) - 22, 60, 170), top = cardsTop() - 4;
            for (var n : nodes) {
                int nx = centreX + 4 + n.depth() * (nodeW + 20), ny = top + 4 + n.row() * 23 - (int) graphScroll;
                if (x >= nx && x < nx + nodeW && y >= Math.max(ny, top) && y < ny + 18) { if (!n.missing()) { selected = 0; page = 0; edit("open", "pool", n.id()); } return true; }
            }
        }
        return false;
    }

    @Override public boolean mouseScrolled(double rawX, double rawY, double horizontal, double vertical) {
        double x = rawX / uiScale;
        if (adding) return false;
        if (x < SIDE + leftW + 6) { poolScroll = Math.clamp(poolScroll - vertical * 30, 0, Math.max(0, visiblePools().size() * LIST_ROW - (listBottom() - listTop()))); return true; }
        if (graph && x < cw - SIDE - rightW) { graphScroll = Math.max(0, graphScroll - vertical * 30); return true; }
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
    @Override public void onClose() { ClientCompat.setScreen(minecraft, parent); }
}
