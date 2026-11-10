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
    private double reportScroll;
    private int reportContent, reportRoom;
    private String seedText = "";

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
        uiScale = Math.min(1f, Math.min(this.width / 860f, this.height / 460f));
        cw = Math.round(this.width / uiScale);
        ch = Math.round(this.height / uiScale);
        clearWidgets();
        var o = ClientState.options;
        if (seedText.isEmpty()) seedText = String.valueOf(o.jigsawSeed);
        button("Close", cw - SIDE - 70, 10, 70, 20, this::onClose);
        button("Pool editor…", cw - SIDE - 70 - 6 - 110, 10, 110, 20, () -> JigsawEditorScreen.open(this));

        int x = SIDE, y = TOP + 14;
        box(x, y, FORM, o.jigsawPool, 200, v -> o.jigsawPool = v.strip());
        box(x, y + 40, FORM, o.jigsawStart, 200, v -> o.jigsawStart = v.strip());
        var depth = new FlatSlider(x, y + 80, FORM, 18, Component.empty(), (o.jigsawDepth - 1) / 19.0) {
            private int units() { return 1 + (int) Math.round(value * 19); }
            @Override protected void updateMessage() { setMessage(Component.literal("Depth " + units())); }
            @Override protected void applyValue() { ClientState.options.jigsawDepth = units(); }
        }.range(1, 20, 5);
        depth.setRaw((o.jigsawDepth - 1) / 19.0);
        addRenderableWidget(depth);
        var distance = new FlatSlider(x, y + 108, FORM, 18, Component.empty(), (o.jigsawDistance - 1) / 127.0) {
            private int units() { return 1 + (int) Math.round(value * 127); }
            @Override protected void updateMessage() { setMessage(Component.literal("Distance " + units() + " blocks")); }
            @Override protected void applyValue() { ClientState.options.jigsawDistance = units(); }
        }.range(1, 128, 80);
        distance.setRaw((o.jigsawDistance - 1) / 127.0);
        addRenderableWidget(distance);
        box(x, y + 148, FORM - 100, seedText, 20, v -> { seedText = v; try { o.jigsawSeed = Long.parseLong(v.strip()); } catch (NumberFormatException ignored) { /* kept as it was until it is a number */ } });
        button("Random seed", x + FORM - 94, y + 147, 94, 20, () -> { o.jigsawSeed = (long) (Math.random() * 1_000_000); seedText = String.valueOf(o.jigsawSeed); init(); });
        addRenderableWidget(new FlatCheckbox(x, y + 178, FORM, 20, "Live: run again when pool or template files change", o.jigsawLive, () -> { o.jigsawLive = !o.jigsawLive; send("live"); init(); }));

        int by = y + 210, third = (FORM - 8) / 3;
        button("Generate", x, by, FORM, 26, () -> send("generate")).lit(true);
        button("Reroll", x, by + 32, third, 22, () -> send("reroll"));
        button("‹ Back", x + third + 4, by + 32, third, 22, () -> send("back"));
        button("Next ›", x + 2 * (third + 4), by + 32, third, 22, () -> send("next"));
        button("Pin seed", x, by + 58, third, 22, () -> send("pin"));
        button("View", x + third + 4, by + 58, third, 22, () -> send("view"));
        button("List pools", x + 2 * (third + 4), by + 58, third, 22, () -> send("pools"));
        button("Apply (Enter)", x, by + 90, (FORM - 4) / 2, 24, () -> send("apply"));
        button("Cancel (Esc)", x + (FORM - 4) / 2 + 4, by + 90, (FORM - 4) / 2, 24, () -> send("cancel"));
    }

    // ---- drawing

    @Override protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override protected void draw(Gfx g, int mx, int my, float delta) {
        g.fill(0, 0, this.width, this.height, 0xf2101923);
        g.pose().pushMatrix();
        g.pose().scale(uiScale, uiScale);
        g.text(font, "JIGSAW PLANNER", SIDE, 12, 0xffb5dcfc, false);
        g.text(font, font.plainSubstrByWidth(ClientState.status.message(), cw - 2 * SIDE - 240), SIDE, 28, 0xffffdca8, false);

        int x = SIDE, y = TOP + 14;
        g.fill(x - 4, TOP - 4, x + FORM + 4, ch - FOOT + 2, PANEL);
        g.text(font, "Pool to grow", x, y - 11, MUTED, false);
        g.text(font, "Start jigsaw (empty for any)", x, y + 29, MUTED, false);
        g.text(font, "Depth and distance", x, y + 69, MUTED, false);
        g.text(font, "Seed", x, y + 137, MUTED, false);
        drawReport(g);
        drawWidgets(g, (int) (mx / uiScale), (int) (my / uiScale), delta);
        g.pose().popMatrix();
    }

    private void drawReport(Gfx g) {
        int x = SIDE + FORM + 14, w = cw - SIDE - x, top = TOP - 4, bottom = ch - FOOT + 2;
        g.fill(x - 4, top, x + w + 4, bottom, PANEL);
        g.text(font, "REPORT", x, TOP, 0xffb5dcfc, false);
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
        if (reportContent > reportRoom) {
            int thumb = Math.max(16, reportRoom * reportRoom / reportContent), thumbY = y0 + (int) ((reportRoom - thumb) * (reportScroll / (reportContent - reportRoom)));
            g.fill(x + w, y0, x + w + 3, y0 + reportRoom, 0xff1b2838);
            g.fill(x + w, thumbY, x + w + 3, thumbY + thumb, 0xff4a6585);
        }
    }

    // ---- input

    private MouseButtonEvent scaled(MouseButtonEvent event) { return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo()); }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) { return super.mouseClicked(scaled(event), twice); }

    @Override public boolean mouseReleased(MouseButtonEvent event) { return super.mouseReleased(scaled(event)); }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return super.mouseDragged(scaled(event), dx / uiScale, dy / uiScale); }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x / uiScale < SIDE + FORM + 10) return false;
        reportScroll = Math.clamp(reportScroll - vertical * 33, 0, Math.max(0, reportContent - reportRoom));
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && getFocused() instanceof EditBox) { send("generate"); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { ClientState.save(); ClientCompat.setScreen(minecraft, parent); }
}
