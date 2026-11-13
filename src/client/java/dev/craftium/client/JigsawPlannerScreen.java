package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/**
 * The jigsaw planner, full screen: the pool to grow, how deep and how far, and the seed on the left, the buttons that make, vary and build a run under
 * them, and on the right what the planner said about the last run (problems in the pools it reached and the connectors left open). The work is done by
 * the server (see JigsawRuns); this screen only sends the player's settings and buttons. It opens from Structure, Jigsaw planner.
 */
final class JigsawPlannerScreen extends CraftiumScreen {
    private static final int SIDE = 12, TOP = 50, FOOT = 12, FORM = 310, LINE = 11;
    private static final int PANEL = 0xe0172330, MUTED = 0xff9db5cb, ERROR = 0xffff8a80, WARN = 0xffffd479, OPEN = 0xff8fd3ff;

    private final Screen parent;
    private float uiScale = 1;
    private int cw, ch;
    private final Tips tips = new Tips();
    private double reportScroll;
    private boolean draggingReport;
    private int reportBarX = -100, reportBarY;
    private int reportContent, reportRoom;
    private String seedText = "";
    private EditBox poolBox;
    /** The pools the server knows, as id and the pack each comes from, and the ones that fit what is typed in the pool box. */
    private final List<String[]> pools = new java.util.ArrayList<>();
    private final List<String[]> matches = new java.util.ArrayList<>();
    private boolean suggesting, accepting, asked, wasFocused;
    private int highlight = -1, seenModel = -1, listScroll, lastMouseX = -1, lastMouseY = -1;
    private boolean draggingList;
    private static final int ROWS = 8, ROW = 15;

    JigsawPlannerScreen(Screen parent) {
        super(Component.literal("Craftium jigsaw planner"));
        this.parent = parent;
    }

    static void open(Screen parent) {
        ClientCompat.setScreen(net.minecraft.client.Minecraft.getInstance(), new JigsawPlannerScreen(parent));
    }

    private static void send(String action) { CraftiumClient.send("craftium:jigsaw_" + action, null, null); }

    // ---- widgets

    private FlatButton button(String label, int x, int y, int w, int h, Runnable action) {
        var b = new FlatButton(x, y, w, h, label, action);
        addRenderableWidget(b);
        return b;
    }

    private EditBox box(int x, int y, int w, String value, int max, java.util.function.Consumer<String> changed) {
        var field = new EditBox(font, x, y, w, 18, Component.empty());
        field.setMaxLength(max);
        field.setValue(value);
        field.setResponder(changed::accept);
        addRenderableWidget(field);
        return field;
    }

    @Override protected void init() {
        uiScale = Math.min(1f, Math.min(this.width / 800f, this.height / 440f));
        cw = Math.round(this.width / uiScale);
        ch = Math.round(this.height / uiScale);
        clearWidgets();
        tips.clear();
        var o = ClientState.options;
        if (!asked) { asked = true; o.jigsawEdit = "{\"op\":\"names\"}"; CraftiumClient.send("craftium:jigsaw_edit", null, null); }
        if (seedText.isEmpty()) seedText = String.valueOf(o.jigsawSeed);
        tips.on(button("Close", cw - SIDE - 70, 10, 70, 20, this::onClose), "Back to the editor. Your settings are kept.");
        tips.on(button("Pool editor…", cw - SIDE - 70 - 6 - 110, 10, 110, 20, () -> JigsawEditorScreen.open(this)), "Opens the pool editor: the pools, their elements and connectors, and how the pools lead to each other.");

        int x = SIDE, y = TOP + 14;
        poolBox = box(x, y, FORM, o.jigsawPool, 200, v -> { o.jigsawPool = v.strip(); if (!accepting) { suggesting = true; refreshMatches(); } });
        tips.on(poolBox, "The template pool to start from, such as minecraft:village/plains/town_centers. Pools that fit what you type are suggested: click one, or use the arrow keys with Enter or Tab. List pools shows them all. Enter generates.");
        tips.on(box(x, y + 40, FORM, o.jigsawStart, 200, v -> o.jigsawStart = v.strip()), "The name of the jigsaw in the start piece to grow from. Leave it empty to let the planner choose.");
        var depth = new FlatSlider(x, y + 80, FORM, 18, Component.empty(), (o.jigsawDepth - 1) / 19.0) {
            private int units() { return 1 + (int) Math.round(value * 19); }
            @Override protected void updateMessage() { setMessage(Component.literal("Depth " + units())); }
            @Override protected void applyValue() { ClientState.options.jigsawDepth = units(); }
        }.range(1, 20, 5);
        depth.setRaw((o.jigsawDepth - 1) / 19.0);
        addRenderableWidget(tips.on(depth, "How many pieces deep the structure grows from the start piece, 1 to 20. Ctrl-click to type a number."));
        var distance = new FlatSlider(x, y + 108, FORM, 18, Component.empty(), (o.jigsawDistance - 1) / 127.0) {
            private int units() { return 1 + (int) Math.round(value * 127); }
            @Override protected void updateMessage() { setMessage(Component.literal("Distance " + units() + " blocks")); }
            @Override protected void applyValue() { ClientState.options.jigsawDistance = units(); }
        }.range(1, 128, 80);
        distance.setRaw((o.jigsawDistance - 1) / 127.0);
        addRenderableWidget(tips.on(distance, "How far from the start, in blocks, pieces may be placed, 1 to 128. Ctrl-click to type a number."));
        tips.on(box(x, y + 148, FORM - 100, seedText, 20, v -> { seedText = v; try { o.jigsawSeed = Long.parseLong(v.strip()); } catch (NumberFormatException ignored) { /* kept as it was until it is a number */ } }), "The same seed always gives the same layout from the same files. Change it, or press Random seed, to try another layout.");
        tips.on(button("Random seed", x + FORM - 94, y + 147, 94, 20, () -> { o.jigsawSeed = (long) (Math.random() * 1_000_000); seedText = String.valueOf(o.jigsawSeed); init(); }), "Puts a random number in the Seed box. The next Generate uses it.");
        addRenderableWidget(tips.on(new FlatCheckbox(x, y + 178, FORM, 20, "Live: run again when pool or template files change", o.jigsawLive, () -> { o.jigsawLive = !o.jigsawLive; send("live"); init(); }), "When on, the preview runs again by itself, with the same seed, as soon as a pool or template file in datapacks/craftium changes, or a structure you saved does. Edit a file and watch the layout change."));

        addRenderableWidget(tips.on(new FlatCheckbox(x, y + 200, FORM / 2, 20, "Show piece boxes", o.jigsawBoxes, () -> { o.jigsawBoxes = !o.jigsawBoxes; ClientState.save(); init(); }), "Draws a coloured box around each piece of the preview. Turn it off when the boxes are in the way, to see only the blocks."));
        addRenderableWidget(tips.on(new FlatCheckbox(x + FORM / 2, y + 200, FORM / 2, 20, "Show piece names", o.jigsawNames, () -> { o.jigsawNames = !o.jigsawNames; ClientState.save(); init(); }), "Draws the name of each piece template above it. Independent of the boxes."));
        int by = y + 232, third = (FORM - 8) / 3;
        tips.on(button("Generate", x, by, FORM, 26, () -> send("generate")).lit(true), "Lays the pool out with the game jigsaw placement and shows it as a preview, at the middle of your selection on its lowest layer, or at your feet. It uses the pool, depth, distance and seed here.");
        tips.on(button("Reroll", x, by + 32, third, 22, () -> send("reroll")), "Runs the same pool again with a new random seed, in the same place.");
        tips.on(button("‹ Back", x + third + 4, by + 32, third, 22, () -> send("back")), "Goes back to the run before this one. The last 20 runs are kept.");
        tips.on(button("Next ›", x + 2 * (third + 4), by + 32, third, 22, () -> send("next")), "Goes forward to the run after this one, after stepping back.");
        tips.on(button("Pin seed", x, by + 58, third, 22, () -> send("pin")), "Keeps the seed of the run on show. The status line lists the pinned seeds; type one in the Seed box to bring its layout back.");
        tips.on(button("View", x + third + 4, by + 58, third, 22, () -> send("view")).back(() -> send("view_back")), "Changes what the preview draws, one step each click: outlines and blocks, then outlines only (cheap for very large structures), then blocks only.");
        tips.on(button("List pools", x + 2 * (third + 4), by + 58, third, 22, () -> send("pools")), "Lists every pool the planner can start from in the report: those of the datapacks the world uses and of datapacks/craftium.");
        tips.on(button("Apply (Enter)", x, by + 90, (FORM - 4) / 2, 24, () -> send("apply")), "Builds the preview in the world as one edit. You can undo it like any other edit.");
        tips.on(button("Cancel preview", x + (FORM - 4) / 2 + 4, by + 90, (FORM - 4) / 2, 24, () -> send("cancel")), "Throws the preview away without building anything. Escape does not: it keeps the preview, so you can leave the editor and walk around it.");
    }

    // ---- drawing

    @Override protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override protected void draw(Gfx g, int mx, int my, float delta) {
        g.fill(0, 0, this.width, this.height, 0xf2101923);
        g.pose().pushMatrix();
        g.pose().scale(uiScale, uiScale);
        g.text(font, "Jigsaw planner", SIDE, 12, 0xffb5dcfc, false);
        g.text(font, font.plainSubstrByWidth(ClientState.status.message(), cw - 2 * SIDE - 240), SIDE, 28, 0xffffdca8, false);

        int x = SIDE, y = TOP + 14;
        g.fill(x - 4, TOP - 4, x + FORM + 4, ch - FOOT + 2, PANEL);
        g.text(font, "Pool to grow", x, y - 11, MUTED, false);
        g.text(font, "Start jigsaw (empty for any)", x, y + 29, MUTED, false);
        g.text(font, "Depth and distance", x, y + 69, MUTED, false);
        g.text(font, "Seed", x, y + 137, MUTED, false);
        drawReport(g);
        drawWidgets(g, (int) (mx / uiScale), (int) (my / uiScale), delta);
        drawSuggestions(g, (int) (mx / uiScale), (int) (my / uiScale));
        if (!showing()) tips.draw(g, font, (int) (mx / uiScale), (int) (my / uiScale), cw, ch);
        g.pose().popMatrix();
    }

    private void drawReport(Gfx g) {
        int x = SIDE + FORM + 14, w = cw - SIDE - x, top = TOP - 4, bottom = ch - FOOT + 2;
        g.fill(x - 4, top, x + w + 4, bottom, PANEL);
        g.text(font, "Report", x, TOP, 0xffb5dcfc, false);
        var report = ClientState.jigsawReport;
        int y0 = TOP + 18;
        reportRoom = bottom - 6 - y0;
        if (report == null || report.isBlank()) {
            g.text(font, "Generate a structure to see problems in its pools and the connectors left open.", x, y0, MUTED, false);
            g.text(font, "Pools are read from the world's datapacks and from datapacks/craftium.", x, y0 + LINE + 4, 0xff6f8296, false);
            reportContent = 0;
            return;
        }
        var wrapped = new java.util.ArrayList<Object[]>();
        for (var line : report.split("\n")) {
            int colour = line.startsWith("ERROR") ? ERROR : line.startsWith("WARN") ? WARN : line.startsWith("OPEN") ? OPEN : line.startsWith("Pool check") || line.contains("connectors were left open") || line.startsWith("No connectors") ? 0xffdce6ef : MUTED;
            for (var part : font.split(Component.literal(line), w - 6)) wrapped.add(new Object[]{part, colour});
        }
        reportContent = wrapped.size() * LINE;
        reportScroll = Math.clamp(reportScroll, 0, Math.max(0, reportContent - reportRoom));
        g.enableScissor(x, y0, x + w, y0 + reportRoom);
        for (int i = 0; i < wrapped.size(); i++) {
            int y = y0 + i * LINE - (int) reportScroll;
            if (y + LINE < y0 || y > y0 + reportRoom) continue;
            g.text(font, (net.minecraft.util.FormattedCharSequence) wrapped.get(i)[0], x, y, (Integer) wrapped.get(i)[1], false);
        }
        g.disableScissor();
        reportBarX = x + w - 2;
        reportBarY = y0;
        Scrollbars.draw(g, reportBarX, y0, reportRoom, reportScroll, reportContent, draggingReport);
    }

    // ---- input

    private MouseButtonEvent scaled(MouseButtonEvent event) { return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo()); }

    @Override public boolean mouseClicked(MouseButtonEvent raw, boolean twice) {
        var event = scaled(raw);
        if (event.button() == 1) for (var child : children()) if (child instanceof FlatButton b && b.rightClick(event.x(), event.y())) return true; // a cycling button steps back
        if (showing() && event.button() == 0) {
            int x = SIDE, y = listTop(), rows = visibleRows();
            if (matches.size() > ROWS && Scrollbars.hit(event.x(), event.y(), x + FORM - Scrollbars.WIDTH - 2, y + 2, rows * ROW)) {
                draggingList = true;
                listScroll = (int) Math.round(Scrollbars.scrollAt(event.y(), y + 2, rows * ROW, matches.size() * ROW) / ROW);
                return true;
            }
            if (event.x() >= x && event.x() < x + FORM && event.y() >= y && event.y() < y + rows * ROW + 4) {
                int i = listScroll + (int) ((event.y() - y - 2) / ROW);
                if (i >= listScroll && i < matches.size()) accept(matches.get(i)[0]);
                return true;
            }
        }
        if (event.button() == 0 && reportContent > reportRoom && Scrollbars.hit(event.x(), event.y(), reportBarX, reportBarY, reportRoom)) {
            draggingReport = true;
            reportScroll = Scrollbars.scrollAt(event.y(), reportBarY, reportRoom, reportContent);
            return true;
        }
        return super.mouseClicked(event, twice);
    }

    @Override public boolean mouseReleased(MouseButtonEvent raw) {
        if (draggingList) { draggingList = false; return true; }
        if (draggingReport) { draggingReport = false; return true; }
        return super.mouseReleased(scaled(raw));
    }

    @Override public boolean mouseDragged(MouseButtonEvent raw, double dx, double dy) {
        var event = scaled(raw);
        if (draggingList) {
            listScroll = (int) Math.round(Scrollbars.scrollAt(event.y(), listTop() + 2, visibleRows() * ROW, matches.size() * ROW) / ROW);
            if (highlight >= 0) highlight = Math.clamp(highlight, listScroll, listScroll + ROWS - 1);
            return true;
        }
        if (draggingReport) { reportScroll = Scrollbars.scrollAt(event.y(), reportBarY, reportRoom, reportContent); return true; }
        return super.mouseDragged(event, dx / uiScale, dy / uiScale);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (showing() && x / uiScale >= SIDE && x / uiScale < SIDE + FORM && y / uiScale >= listTop() && y / uiScale < listTop() + visibleRows() * ROW + 4) {
            listScroll = Math.clamp(listScroll - (int) Math.signum(vertical) * 2, 0, Math.max(0, matches.size() - ROWS));
            if (highlight >= 0) highlight = Math.clamp(highlight, listScroll, listScroll + ROWS - 1); // the highlight stays in the window, and moves with it
            return true;
        }
        if (x / uiScale < SIDE + FORM + 10) return false;
        reportScroll = Math.clamp(reportScroll - vertical * 33, 0, Math.max(0, reportContent - reportRoom));
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (showing()) {
            switch (event.key()) {
                case GLFW.GLFW_KEY_DOWN -> { highlight = Math.min(matches.size() - 1, highlight + 1); keepHighlightVisible(); return true; }
                case GLFW.GLFW_KEY_UP -> { highlight = highlight <= 0 ? matches.size() - 1 : highlight - 1; keepHighlightVisible(); return true; }
                case GLFW.GLFW_KEY_PAGE_DOWN -> { highlight = Math.min(matches.size() - 1, Math.max(0, highlight) + ROWS); keepHighlightVisible(); return true; }
                case GLFW.GLFW_KEY_PAGE_UP -> { highlight = Math.max(0, Math.max(0, highlight) - ROWS); keepHighlightVisible(); return true; }
                case GLFW.GLFW_KEY_TAB -> { accept(matches.get(Math.max(0, highlight))[0]); return true; }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { if (highlight >= 0) { accept(matches.get(highlight)[0]); return true; } }
                case GLFW.GLFW_KEY_ESCAPE -> { suggesting = false; return true; }
                default -> { }
            }
        }
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && getFocused() instanceof EditBox) { send("generate"); return true; }
        return super.keyPressed(event);
    }

    // ---- the pool box's suggestions

    @Override public void tick() {
        if (ClientState.jigsawModelVersion != seenModel) { seenModel = ClientState.jigsawModelVersion; readPools(); refreshMatches(); }
        boolean focused = poolBox != null && poolBox.isFocused();
        if (focused && !wasFocused) { suggesting = true; refreshMatches(); }
        wasFocused = focused;
    }

    private void readPools() {
        pools.clear();
        try {
            var model = com.google.gson.JsonParser.parseString(ClientState.jigsawModel).getAsJsonObject();
            if (model.has("pools")) for (var e : model.getAsJsonArray("pools")) { var p = e.getAsJsonObject(); pools.add(new String[]{p.get("id").getAsString(), p.has("source") ? p.get("source").getAsString() : ""}); }
        } catch (RuntimeException ignored) { /* no list, no suggestions */ }
    }

    /** The pools that contain what is typed: those that start with it first, then the craftium pack's, then other packs', then vanilla's. */
    private void refreshMatches() {
        matches.clear();
        highlight = -1;
        if (poolBox == null) return;
        String text = poolBox.getValue().strip().toLowerCase(java.util.Locale.ROOT);
        var found = new java.util.ArrayList<String[]>();
        for (var p : pools) if (text.isEmpty() || p[0].contains(text)) found.add(p);
        found.sort(java.util.Comparator.<String[]>comparingInt(p -> rank(p, text)).thenComparing(p -> p[0]));
        matches.addAll(found); // all of them: the list scrolls
        listScroll = 0;
    }

    private static int rank(String[] pool, String text) {
        String path = pool[0].substring(pool[0].indexOf(':') + 1);
        int fit = pool[0].startsWith(text) ? 0 : path.startsWith(text) ? 1 : 2;
        return fit * 3 + (pool[1].equals("craftium") ? 0 : pool[1].equals("pack") ? 1 : 2);
    }

    /** Whether the list is open: the box has the focus, something fits, and what is typed is not already the only fit. */
    private boolean showing() {
        if (!suggesting || poolBox == null || !poolBox.isFocused() || matches.isEmpty()) return false;
        return !(matches.size() == 1 && matches.get(0)[0].equals(poolBox.getValue().strip()));
    }

    private int listTop() { return TOP + 14 + 20; }

    private void accept(String id) {
        accepting = true;
        poolBox.setValue(id);
        accepting = false;
        ClientState.options.jigsawPool = id;
        suggesting = false;
        matches.clear();
        listScroll = 0;
    }

    private int visibleRows() { return Math.min(ROWS, matches.size()); }

    /** Brings the highlighted row into the window. */
    private void keepHighlightVisible() {
        if (highlight < listScroll) listScroll = highlight;
        else if (highlight >= listScroll + ROWS) listScroll = highlight - ROWS + 1;
        listScroll = Math.clamp(listScroll, 0, Math.max(0, matches.size() - ROWS));
    }

    private void drawSuggestions(Gfx g, int mx, int my) {
        if (!showing()) return;
        int x = SIDE, y = listTop(), rows = visibleRows(), h = rows * ROW + 4;
        boolean bar = matches.size() > ROWS;
        // The highlight goes where the pointer goes, once the pointer moves; the arrow keys move it too.
        if ((mx != lastMouseX || my != lastMouseY)) {
            lastMouseX = mx;
            lastMouseY = my;
            if (mx >= x && mx < x + FORM && my >= y + 2 && my < y + 2 + rows * ROW) highlight = listScroll + (my - y - 2) / ROW;
        }
        g.fill(x, y, x + FORM, y + h, 0xf0101923);
        g.outline(x, y, FORM, h, ClientState.options.uiAccent);
        String typed = poolBox.getValue().strip().toLowerCase(java.util.Locale.ROOT);
        int room = FORM - 24 - (bar ? Scrollbars.WIDTH + 2 : 0);
        for (int row = 0; row < rows; row++) {
            int i = listScroll + row, ry = y + 2 + row * ROW;
            if (i >= matches.size()) break;
            if (i == highlight) g.fill(x + 1, ry, x + FORM - 1 - (bar ? Scrollbars.WIDTH + 2 : 0), ry + ROW, 0xff315473);
            var pool = matches.get(i);
            g.fill(x + 5, ry + 4, x + 10, ry + 9, pool[1].equals("craftium") ? 0xff8fe3a0 : pool[1].equals("pack") ? 0xff8fd3ff : 0xff5a6b7d);
            String shown = font.plainSubstrByWidth(pool[0], room);
            int at = typed.isEmpty() ? -1 : shown.toLowerCase(java.util.Locale.ROOT).indexOf(typed);
            if (at >= 0) { // the part that was typed is picked out
                int px = x + 14;
                g.text(font, shown.substring(0, at), px, ry + 4, 0xffdce6ef, false);
                px += font.width(shown.substring(0, at));
                g.text(font, shown.substring(at, at + typed.length()), px, ry + 4, 0xffffdca8, false);
                px += font.width(shown.substring(at, at + typed.length()));
                g.text(font, shown.substring(at + typed.length()), px, ry + 4, 0xffdce6ef, false);
            } else g.text(font, shown, x + 14, ry + 4, 0xffdce6ef, false);
        }
        if (bar) Scrollbars.draw(g, x + FORM - Scrollbars.WIDTH - 2, y + 2, rows * ROW, listScroll * ROW, matches.size() * ROW, draggingList);
    }

    /** The size this screen is laid out for: at Auto the interface takes the largest whole scale at which it fits, instead of shrinking it to fit. */
    @Override protected int[] canvas() { return new int[]{800, 440}; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { ClientState.save(); ClientCompat.setScreen(minecraft, parent); }
}
