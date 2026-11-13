package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The AI build sessions the player has open, as a gallery of cards: a picture of what each previewed, its name, status, size, distance and how
 * long it has been going. A click opens the session's own screen. The grid scrolls with the wheel, the bar or the page keys, and the clock on
 * every card counts on each second.
 */
final class AiSessionsScreen extends CraftiumScreen {
    private static final int CARD_W = AiThumbnails.WIDTH + 16, CARD_H = 152, GAP = 8, TOP = 56, SIDE = 12, BAR = 6, FOOT = 38;
    private final Screen parent;
    private final AiSessionUi.StopDialog stopping = new AiSessionUi.StopDialog();
    private double scroll;
    private int maxScroll;
    private boolean draggingBar;

    AiSessionsScreen(Screen parent) {
        super(Component.literal("Craftium AI build sessions"));
        this.parent = parent;
    }

    @Override protected void init() {
        addRenderableWidget(new FlatButton(SIDE, height - 30, 80, 22, "Back", this::onClose));
        int x = width - SIDE - 3 * 96 - 2 * 6;
        boolean controls = AiSessionUi.controls();
        addRenderableWidget(new FlatButton(x, 10, 96, 22, "Apply all", AiSessionActions::applyAll));
        addRenderableWidget(new FlatButton(x + 102, 10, 96, 22, "Discard all", AiSessionActions::discardAll));
        var stop = new FlatButton(x + 204, 10, 96, 22, "Stop all", () -> stopping.open("every AI session", AiSessionActions::stopAll));
        stop.active = controls;
        addRenderableWidget(stop);
    }

    private int columns() { return Math.max(1, (width - 2 * SIDE - BAR - 6) / (CARD_W + GAP)); }
    private int viewBottom() { return height - FOOT; }
    private int gridLeft() { int cols = Math.min(columns(), Math.max(1, ClientState.aiSessions.size())); return (width - (cols * (CARD_W + GAP) - GAP)) / 2; }

    @Override protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override protected void draw(Gfx g, int mx, int my, float delta) {
        AiThumbnails.newFrame();
        g.fill(0, 0, width, height, 0xf5101923);
        var sessions = ClientState.aiSessions;
        g.text(font, "AI build sessions", SIDE, 12, 0xffb5dcfc, false);
        long busy = sessions.stream().filter(v -> AiSessionUi.working(v) || v.info().state().equals("applying")).count();
        g.text(font, sessions.size() + (sessions.size() == 1 ? " session" : " sessions") + " · " + busy + " working", SIDE, 28, 0xff9db5cb, false);
        g.text(font, font.plainSubstrByWidth(ClientState.status.message(), width - 2 * SIDE), SIDE, 40, 0xffffdca8, false);

        int cols = columns(), rows = (sessions.size() + cols - 1) / cols, viewTop = TOP, viewBottom = viewBottom();
        int content = Math.max(0, rows * (CARD_H + GAP) - GAP);
        maxScroll = Math.max(0, content - (viewBottom - viewTop));
        scroll = Math.clamp(scroll, 0, maxScroll);
        if (sessions.isEmpty()) {
            g.centeredText(font, "No AI build sessions yet", width / 2, height / 2 - 14, 0xffdce6ef);
            g.centeredText(font, "Ask Claude for a build, and its session shows up here.", width / 2, height / 2, 0xff9db5cb);
            if (!AiBridge.running()) g.centeredText(font, "AI building is off: turn it on in the AI menu.", width / 2, height / 2 + 14, 0xffffc857);
        }
        boolean over = !stopping.visible() && my >= viewTop && my < viewBottom;
        g.enableScissor(0, viewTop, width, viewBottom);
        int left = gridLeft();
        for (int i = 0; i < sessions.size(); i++) {
            int x = left + (i % cols) * (CARD_W + GAP), y = viewTop + (i / cols) * (CARD_H + GAP) - (int) scroll;
            if (y + CARD_H < viewTop || y > viewBottom) continue;
            card(g, sessions.get(i), x, y, over && AiSessionUi.inside(mx, my, x, y, CARD_W, CARD_H));
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int track = viewBottom - viewTop, thumb = Math.max(20, track * track / Math.max(track, content));
            int thumbY = viewTop + (int) ((track - thumb) * (scroll / maxScroll));
            g.fill(width - SIDE - BAR + 2, viewTop, width - SIDE + 2, viewBottom, 0xff1b2838);
            g.fill(width - SIDE - BAR + 2, thumbY, width - SIDE + 2, thumbY + thumb, draggingBar ? 0xff7cc4ff : 0xff4a6585);
        }
        drawWidgets(g, mx, my, delta);
        stopping.draw(g, width, height, mx, my);
    }

    private void card(Gfx g, ClientState.AiSessionView view, int x, int y, boolean hovered) {
        var info = view.info();
        g.fill(x, y, x + CARD_W, y + CARD_H, hovered ? 0xff315473 : 0xff223247);
        g.fill(x, y, x + 4, y + CARD_H, AiSessionUi.accent(view));
        g.outline(x, y, CARD_W, CARD_H, hovered ? ClientState.options.uiAccent : ClientState.options.uiBorder);
        AiSessionUi.thumbnail(g, view, x + 8, y + 8, AiThumbnails.WIDTH, AiThumbnails.HEIGHT);
        int textWidth = CARD_W - 16, textY = y + 8 + AiThumbnails.HEIGHT + 6;
        g.text(font, font.plainSubstrByWidth(AiSessionUi.name(view), textWidth), x + 8, textY, 0xffffffff, false);
        int dot = AiSessionUi.statusColour(view);
        g.fill(x + 8, textY + 14, x + 14, textY + 20, dot);
        g.text(font, AiSessionUi.status(view), x + 18, textY + 13, dot, false);
        String clock = AiSessionUi.clock(view.ageMs());
        g.text(font, clock, x + CARD_W - 8 - font.width(clock), textY + 13, 0xffdce6ef, false);
        int away = AiSessionUi.distance(view);
        String size = info.blocks() > 0 ? String.format(java.util.Locale.ROOT, "%,d blocks", info.blocks()) : info.undo() > 0 ? info.undo() + (info.undo() == 1 ? " build" : " builds") : "nothing yet";
        String where = away >= 0 ? " · " + away + " m" : info.min() != null && !AiSessionUi.here(view) ? " · other dimension" : "";
        g.text(font, font.plainSubstrByWidth(size + where, textWidth), x + 8, textY + 26, 0xff9db5cb, false);
    }

    private int cardAt(double mx, double my) {
        if (my < TOP || my >= viewBottom()) return -1;
        int cols = columns(), left = gridLeft();
        double lx = mx - left, ly = my - TOP + scroll;
        if (lx < 0 || ly < 0) return -1;
        int col = (int) (lx / (CARD_W + GAP)), row = (int) (ly / (CARD_H + GAP));
        if (col >= cols || lx - col * (CARD_W + GAP) >= CARD_W || ly - row * (CARD_H + GAP) >= CARD_H) return -1;
        int index = row * cols + col;
        return index < ClientState.aiSessions.size() ? index : -1;
    }

    private void dragBar(double my) {
        int track = viewBottom() - TOP, content = Math.max(1, (ClientState.aiSessions.size() + columns() - 1) / columns() * (CARD_H + GAP) - GAP);
        int thumb = Math.max(20, track * track / Math.max(track, content)), travel = track - thumb;
        if (travel > 0) scroll = Math.clamp((my - TOP - thumb / 2.0) / travel * maxScroll, 0, maxScroll);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (stopping.click(event.x(), event.y(), width, height)) return true;
        if (event.button() == 0 && maxScroll > 0 && event.x() >= width - SIDE - BAR && event.x() < width - SIDE + 4 && event.y() >= TOP && event.y() < viewBottom()) { draggingBar = true; dragBar(event.y()); return true; }
        if (super.mouseClicked(event, twice)) return true;
        int index = event.button() == 0 ? cardAt(event.x(), event.y()) : -1;
        if (index >= 0) { ClientCompat.setScreen(minecraft, new AiSessionScreen(this, ClientState.aiSessions.get(index).id())); return true; }
        return false;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingBar) { dragBar(event.y()); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingBar) { draggingBar = false; return true; }
        return super.mouseReleased(event);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - vertical * 36, 0, maxScroll);
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (interfaceKey(event)) return true;
        int page = viewBottom() - TOP - CARD_H / 2;
        switch (event.key()) {
            case GLFW.GLFW_KEY_PAGE_DOWN -> scroll = Math.clamp(scroll + page, 0, maxScroll);
            case GLFW.GLFW_KEY_PAGE_UP -> scroll = Math.clamp(scroll - page, 0, maxScroll);
            case GLFW.GLFW_KEY_HOME -> scroll = 0;
            case GLFW.GLFW_KEY_END -> scroll = maxScroll;
            case GLFW.GLFW_KEY_ESCAPE -> { if (stopping.visible()) { stopping.close(); return true; } return super.keyPressed(event); }
            default -> { return super.keyPressed(event); }
        }
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { ClientCompat.setScreen(minecraft, parent); }
}
