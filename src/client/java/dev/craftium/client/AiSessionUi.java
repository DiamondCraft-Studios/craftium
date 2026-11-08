package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import dev.craftium.core.AiSessions;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;

/** What the Build sessions screens share: how a session is named, how its status reads and is coloured, and the time and distance texts. */
final class AiSessionUi {
    static final int VIOLET = 0xffc9a0ff, BLUE = 0xff6cb6ff, GREEN = 0xff6fe08f, GREY = 0xff7a8794, RED = 0xffff6b6b, AMBER = 0xffffc857;
    /** A session with no request for this long is shown as inactive. */
    private static final long INACTIVE_MS = 10 * 60 * 1000L;
    private AiSessionUi() {}

    static int accent(ClientState.AiSessionView view) { return AiSessions.shade(VIOLET, view.slot()); }

    /** The name the player sees: the build's label, else a short form of the session id. */
    static String name(ClientState.AiSessionView view) {
        String label = view.info().label();
        if (!label.isEmpty()) return label;
        String id = view.id();
        // A session with no id comes from a plugin that predates sessions: every such app shares this one, which is worth knowing when two builds trample each other.
        return id.isEmpty() ? "AI session (older plugin)" : "AI session " + id.substring(0, Math.min(4, id.length()));
    }

    static boolean working(ClientState.AiSessionView view) { return AiBridge.workingOn(view.id()); }

    static String status(ClientState.AiSessionView view) {
        if (view.info().stopped()) return "Stopped";
        if (working(view)) return "Working…";
        return switch (view.info().state()) {
            case "applying" -> "Applying…";
            case "preview" -> "Preview waiting";
            case "applied" -> view.idleMs() > INACTIVE_MS ? "Inactive" : "Applied";
            default -> view.idleMs() > INACTIVE_MS ? "Inactive" : "Idle";
        };
    }

    static int statusColour(ClientState.AiSessionView view) {
        if (view.info().stopped()) return RED;
        if (working(view)) return BLUE;
        return switch (view.info().state()) {
            case "applying" -> BLUE;
            case "preview" -> VIOLET;
            case "applied" -> view.idleMs() > INACTIVE_MS ? GREY : GREEN;
            default -> GREY;
        };
    }

    /** m:ss, or h:mm:ss from an hour on. */
    static String clock(long millis) {
        long s = Math.max(0, millis / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** "8s", "3 min" or "2 h": a rough time since something. */
    static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        return s < 60 ? s + "s" : s < 3600 ? s / 60 + " min" : s / 3600 + " h";
    }

    /** Whether the session's latest build is in the dimension the player is in. */
    static boolean here(ClientState.AiSessionView view) {
        var mc = Minecraft.getInstance();
        return mc.level != null && (view.info().dimension().isEmpty() || view.info().dimension().equals(mc.level.dimension().toString()));
    }

    /** The distance from the player to the middle of the session's build in blocks, or -1 when it has none here. */
    static int distance(ClientState.AiSessionView view) {
        var mc = Minecraft.getInstance();
        var info = view.info();
        if (mc.player == null || info.min() == null || info.max() == null || !here(view)) return -1;
        double dx = (info.min().x() + info.max().x() + 1) / 2.0 - mc.player.getX(), dy = (info.min().y() + info.max().y() + 1) / 2.0 - mc.player.getY(), dz = (info.min().z() + info.max().z() + 1) / 2.0 - mc.player.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /** The picture of the session's preview in a box, or an empty frame while it has none. */
    static void thumbnail(Gfx g, ClientState.AiSessionView view, int x, int y, int width, int height) {
        g.fill(x, y, x + width, y + height, 0xff2a3441);
        var tex = AiThumbnails.of(view);
        if (tex != null) g.blit(tex, x, y, 0, 0, width, height, AiThumbnails.WIDTH, AiThumbnails.HEIGHT, AiThumbnails.WIDTH, AiThumbnails.HEIGHT, 0xffffffff);
        else {
            var font = Minecraft.getInstance().font;
            String text = view.ghost().total() > 0 ? "Too big to picture" : "No preview yet";
            g.text(font, text, x + (width - font.width(text)) / 2, y + height / 2 - 4, 0xff7e8fa0, false);
        }
        g.outline(x, y, width, height, 0xff3a4a5c);
    }

    /** Whether the server understands the session controls (stop, rename, teleport, forget). */
    static boolean controls() { return ClientHandshake.serverHas(dev.craftium.core.Handshake.AI_SESSIONS); }

    // ---- the warning shown before a session is stopped -----------------------------------------------------------------

    /** The pop-up that asks before a session is stopped. One at a time; the screen that opened it forwards its drawing and clicks. */
    static final class StopDialog {
        private String what = "";
        private Runnable confirm;
        boolean visible() { return confirm != null; }
        void open(String what, Runnable confirm) { this.what = what; this.confirm = confirm; }
        void close() { confirm = null; }

        private static final int W = 300, H = 150;
        void draw(Gfx g, int width, int height, int mx, int my) {
            if (confirm == null) return;
            var font = Minecraft.getInstance().font;
            int x = (width - W) / 2, y = (height - H) / 2;
            g.fill(0, 0, width, height, 0xa0000000);
            g.fill(x, y, x + W, y + H, 0xff1d2b3b);
            g.outline(x, y, W, H, RED);
            g.text(font, "Stop " + what + "?", x + 12, y + 10, 0xffffffff, false);
            int line = y + 28;
            for (var part : font.split(Component.literal("This discards its preview and blocks all further building from this Claude session until you press Resume. The Claude session keeps running and will be told to stop, but the game cannot end it. To fully stop it, also interrupt it in its own window."), W - 24)) {
                g.text(font, part, x + 12, line, 0xffc9d6e2, false);
                line += 11;
            }
            FlatButton.face(g, x + W - 200, y + H - 30, 92, 20, "Stop session", false, true, inside(mx, my, x + W - 200, y + H - 30, 92, 20));
            FlatButton.face(g, x + W - 100, y + H - 30, 88, 20, "Cancel", false, true, inside(mx, my, x + W - 100, y + H - 30, 88, 20));
        }
        /** Handles a click while the pop-up is open (it takes every click); returns true. */
        boolean click(double mx, double my, int width, int height) {
            if (confirm == null) return false;
            int x = (width - W) / 2, y = (height - H) / 2;
            if (inside(mx, my, x + W - 200, y + H - 30, 92, 20)) { var run = confirm; confirm = null; run.run(); }
            else if (inside(mx, my, x + W - 100, y + H - 30, 88, 20) || !inside(mx, my, x, y, W, H)) confirm = null;
            return true;
        }
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
}
