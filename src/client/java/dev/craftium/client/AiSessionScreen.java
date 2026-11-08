package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Point;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * One AI build session in detail: its picture, status and facts, with the controls the player has over it: teleport to the build, select its
 * region, flash it, hide its ghost, apply, discard, undo or redo its builds, stop the Claude session (after a warning) and resume or forget it.
 */
final class AiSessionScreen extends CraftiumScreen {
    private static final int SIDE = 12, PICTURE_W = AiThumbnails.WIDTH * 2, PICTURE_H = AiThumbnails.HEIGHT * 2, GAP = 6;
    private final Screen parent;
    private final String id;
    private final AiSessionUi.StopDialog stopping = new AiSessionUi.StopDialog();
    private EditBox nameField;
    private FlatButton teleport, select, highlight, copy, apply, discard, undo, redo, stop, resume, hide, forget, rename;
    private long materialsFor = -1;
    private List<String> materials = List.of();

    AiSessionScreen(Screen parent, String id) {
        super(Component.literal("Craftium AI build session"));
        this.parent = parent;
        this.id = id;
    }

    private ClientState.AiSessionView view() { return ClientState.aiSession(id); }

    @Override protected void init() {
        var view = view();
        int x1 = SIDE + PICTURE_W + 16, available = width - x1 - SIDE;
        int cols = available >= 4 * 84 + 3 * GAP ? 4 : 2, bw = (available - (cols - 1) * GAP) / cols, top = 150;
        var buttons = new ArrayList<FlatButton>();
        teleport = new FlatButton(0, 0, bw, 22, "Teleport", () -> CraftiumClient.sendAi("ai_session_teleport", id, null));
        select = new FlatButton(0, 0, bw, 22, "Select region", this::selectRegion);
        highlight = new FlatButton(0, 0, bw, 22, "Highlight", () -> { ClientState.highlightId = id; ClientState.highlightUntil = System.nanoTime() + 4_000_000_000L; });
        copy = new FlatButton(0, 0, bw, 22, "Copy coordinates", this::copyCoordinates);
        apply = new FlatButton(0, 0, bw, 22, "Apply", () -> CraftiumClient.sendAi("ai_apply", id, null));
        discard = new FlatButton(0, 0, bw, 22, "Discard", () -> CraftiumClient.sendAi("ai_cancel", id, null));
        undo = new FlatButton(0, 0, bw, 22, "Undo", () -> CraftiumClient.sendAi("ai_undo", id, null));
        redo = new FlatButton(0, 0, bw, 22, "Redo", () -> CraftiumClient.sendAi("ai_redo", id, null));
        stop = new FlatButton(0, 0, bw, 22, "Stop", () -> stopping.open("\"" + (view() == null ? "" : AiSessionUi.name(view())) + "\"", () -> CraftiumClient.sendAi("ai_session_stop", id, null)));
        resume = new FlatButton(0, 0, bw, 22, "Resume", () -> CraftiumClient.sendAi("ai_session_resume", id, null));
        hide = new FlatButton(0, 0, bw, 22, "Hide ghost", () -> { if (!ClientState.aiHidden.remove(id)) ClientState.aiHidden.add(id); });
        forget = new FlatButton(0, 0, bw, 22, "Forget", () -> CraftiumClient.sendAi("ai_session_forget", id, null));
        // The stop and resume buttons share a place: only the one that applies is shown.
        Collections.addAll(buttons, teleport, select, highlight, copy, apply, discard, undo, redo, stop, hide, forget);
        int i = 0;
        for (var b : buttons) { b.setX(x1 + (i % cols) * (bw + GAP)); b.setY(top + (i / cols) * 26); addRenderableWidget(b); i++; }
        resume.setX(stop.getX()); resume.setY(stop.getY()); addRenderableWidget(resume);
        nameField = new EditBox(font, SIDE, 48 + PICTURE_H + 10, PICTURE_W - 74, 20, Component.literal("Name"));
        nameField.setMaxLength(60);
        nameField.setValue(view == null ? "" : view.info().label());
        addRenderableWidget(nameField);
        rename = new FlatButton(SIDE + PICTURE_W - 68, 48 + PICTURE_H + 10, 68, 20, "Rename", () -> CraftiumClient.sendAi("ai_session_rename", id, nameField.getValue()));
        addRenderableWidget(rename);
        addRenderableWidget(new FlatButton(SIDE, height - 30, 80, 22, "Back", this::onClose));
    }

    private void selectRegion() {
        var info = view().info();
        if (info.min() == null || info.max() == null) return;
        var o = ClientState.options;
        o.position1 = info.min();
        o.position2 = info.max();
        CraftiumClient.send("select_box", null, null);
    }

    private void copyCoordinates() {
        var info = view().info();
        if (info.min() == null || info.max() == null) return;
        minecraft.keyboardHandler.setClipboard(info.min().x() + " " + info.min().y() + " " + info.min().z() + " " + info.max().x() + " " + info.max().y() + " " + info.max().z());
        ClientState.notify("Coordinates copied");
    }

    @Override public void tick() {
        super.tick();
        if (view() == null) onClose(); // forgotten, or expired
    }

    @Override protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override protected void draw(Gfx g, int mx, int my, float delta) {
        AiThumbnails.newFrame();
        g.fill(0, 0, width, height, 0xf5101923);
        var view = view();
        if (view == null) { drawWidgets(g, mx, my, delta); return; }
        var info = view.info();
        boolean controls = AiSessionUi.controls(), hasBox = info.min() != null && info.max() != null, stopped = info.stopped();
        g.fill(0, 0, 4, height, AiSessionUi.accent(view));
        g.text(font, font.plainSubstrByWidth(AiSessionUi.name(view), width - 2 * SIDE), SIDE, 12, 0xffffffff, false);
        int dot = AiSessionUi.statusColour(view);
        g.fill(SIDE, 30, SIDE + 6, 36, dot);
        g.text(font, AiSessionUi.status(view) + " · running " + AiSessionUi.clock(view.ageMs()), SIDE + 10, 29, dot, false);
        AiSessionUi.thumbnail(g, view, SIDE, 48, PICTURE_W, PICTURE_H);

        int x1 = SIDE + PICTURE_W + 16, y = 48;
        int away = AiSessionUi.distance(view);
        var lines = new ArrayList<String>();
        lines.add("Last activity: " + (info.lastTool().isEmpty() ? "none yet" : info.lastTool().replace('_', ' ') + " · " + AiSessionUi.ago(view.idleMs()) + " ago"));
        lines.add("Preview: " + (info.blocks() > 0 ? String.format(Locale.ROOT, "%,d blocks", info.blocks()) : "none waiting") + " · " + info.undo() + (info.undo() == 1 ? " build" : " builds") + " to undo" + (info.undoName().isEmpty() ? "" : " (last: " + info.undoName() + ")"));
        if (hasBox) {
            var a = info.min();
            var b = info.max();
            lines.add("Size: " + (b.x() - a.x() + 1) + " × " + (b.y() - a.y() + 1) + " × " + (b.z() - a.z() + 1) + " blocks");
            lines.add("Centre: " + (a.x() + b.x()) / 2 + ", " + (a.y() + b.y()) / 2 + ", " + (a.z() + b.z()) / 2 + (away >= 0 ? " · " + away + " m away" : AiSessionUi.here(view) ? "" : " · in another dimension"));
        } else lines.add("Nothing placed yet");
        refreshMaterials(view);
        if (!materials.isEmpty()) lines.add("Mostly: " + String.join(", ", materials));
        for (var line : lines) { g.text(font, font.plainSubstrByWidth(line, width - x1 - SIDE), x1, y, 0xffc9d6e2, false); y += 13; }
        if (!controls) g.text(font, font.plainSubstrByWidth("This server's Craftium predates AI sessions: stop, rename, forget and teleport need an update.", width - x1 - SIDE), x1, 136, 0xffffc857, false);

        teleport.active = controls && hasBox && AiSessionUi.here(view);
        select.active = hasBox;
        highlight.active = hasBox && AiSessionUi.here(view);
        copy.active = hasBox;
        apply.active = view.ghost().total() > 0 && !stopped;
        discard.active = view.ghost().total() > 0;
        undo.active = info.undo() > 0 && !stopped;
        redo.active = !stopped;
        stop.visible = controls && !stopped;
        resume.visible = controls && stopped;
        hide.setMessage(Component.literal(ClientState.aiHidden.contains(id) ? "Show ghost" : "Hide ghost"));
        hide.active = view.ghost().total() > 0;
        forget.active = controls && (stopped || !info.state().equals("applying") && !AiSessionUi.working(view));
        rename.active = controls;
        nameField.setEditable(controls);
        drawWidgets(g, mx, my, delta);
        if (stop.visible) g.fill(stop.getX() + 10, stop.getY() + 7, stop.getX() + 18, stop.getY() + 15, AiSessionUi.RED); // the stop square
        g.text(font, "Name", SIDE, 48 + PICTURE_H + 1, 0xff7e8fa0, false);
        stopping.draw(g, width, height, mx, my);
    }

    /** The commonest blocks of the preview, worked out when the preview is new. */
    private void refreshMaterials(ClientState.AiSessionView view) {
        long key = view.info().previewId();
        if (key == materialsFor || view.ghost().total() == 0) return;
        materialsFor = key;
        var counts = new HashMap<String, Integer>();
        int[] cells = view.ghost().placedCells();
        var names = view.ghost().stateNames();
        for (int i = 3; i < cells.length; i += 4) {
            String name = names.get(cells[i]);
            int bracket = name.indexOf('[');
            counts.merge((bracket < 0 ? name : name.substring(0, bracket)).replace("minecraft:", ""), 1, Integer::sum);
        }
        materials = counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4).map(e -> e.getKey() + " ×" + e.getValue()).toList();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (stopping.click(event.x(), event.y(), width, height)) return true;
        return super.mouseClicked(event, twice);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && stopping.visible()) { stopping.close(); return true; }
        return super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { ClientCompat.setScreen(minecraft, parent); }
}
