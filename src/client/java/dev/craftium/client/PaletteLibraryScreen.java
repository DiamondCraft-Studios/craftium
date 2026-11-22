package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Handshake;
import dev.craftium.core.SharedPalettes.Palette;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The palette library: the server's palettes, which everyone on the server sees and only operators change, and the player's own, kept on this computer. A palette
 * is a list of blocks with weights, the Materials window's contents; it is loaded into that window, or saved from it.
 */
final class PaletteLibraryScreen extends CraftiumScreen {
    private static final int PITCH = 34, TOP = 66, ICONS = 8, BUTTON = 76;
    private final Screen parent;
    private boolean server = true;
    private int scroll;
    private List<Palette> local = List.of();
    /** What the last thing the player did here said, until the server says something newer. */
    private String message = "";
    /** The palette whose Delete was clicked once: a second click within three seconds deletes it. */
    private String armed = "";

    private long armedAt;

    PaletteLibraryScreen(Screen parent) {
        super(Component.literal("Craftium Palette Library"));
        this.parent = parent;
    }

    private boolean supported() {
        return ClientHandshake.serverHas(Handshake.SHARED_PALETTES);
    }

    @Override
    protected void init() {
        local = ClientLibrary.localPalettes();
        clearWidgets();
        addRenderableWidget(new FlatButton(12, 34, 120, 22, "Server palettes", () -> tab(true)).lit(server));
        addRenderableWidget(new FlatButton(138, 34, 120, 22, "My palettes", () -> tab(false)).lit(!server));
        addRenderableWidget(new FlatButton(
                        12,
                        height - 34,
                        200,
                        22,
                        "Save Materials palette…",
                        () -> ClientCompat.setScreen(minecraft, new SavePaletteScreen(this, server, "")))
                .lit(true));
        addRenderableWidget(new FlatButton(width - 112, height - 34, 100, 22, "Return", this::onClose));
        if (server && supported()) ClientLibrary.requestShared("palette_list", null, null, null, null, null);
    }

    private void tab(boolean toServer) {
        if (server == toServer) return;
        server = toServer;
        scroll = 0;
        message = "";
        armed = ""; // a Delete clicked once on one list must not count for a palette of the same name on the other
        rebuildWidgets();
    }

    private List<Palette> rows() {
        return server ? (supported() ? ClientState.sharedPalettes : List.of()) : local;
    }

    /** Saves a copy of a palette under its own name in the other list (the player's own when this is the server's, and the other way round). */
    private void copy(Palette from) {
        if (server) {
            try {
                ClientLibrary.savePalette(from.name(), from.blocks(), from.weights(), from.owner(), from.tags());
                message = "Copied " + from.name() + " to my palettes";
            } catch (IllegalArgumentException e) {
                message = e.getMessage();
            }
            local = ClientLibrary.localPalettes();
        } else if (!supported()) message = "This server does not have shared palettes";
        else {
            message = "";
            ClientLibrary.requestShared(
                    "palette_save", from.name(), from.owner(), from.tags(), from.blocks(), from.weights());
        }
    }

    private void act(int button, Palette p) {
        switch (button) {
            case 0 -> {
                try {
                    ClientLibrary.apply(p.blocks(), p.weights());
                    message = "Loaded " + p.name() + " into the Materials window";
                } catch (IllegalArgumentException e) {
                    message = "Cannot load " + p.name() + ": " + e.getMessage();
                }
            }
            case 1 -> copy(p);
            default -> {
                long now = System.currentTimeMillis();
                if (!p.name().equals(armed) || now - armedAt > 3000) {
                    armed = p.name();
                    armedAt = now;
                    return;
                }
                armed = "";
                if (server) {
                    message = "";
                    ClientLibrary.requestShared("palette_delete", p.name(), "", "", null, null);
                } else {
                    try {
                        ClientLibrary.deletePalette(p.name());
                        message = "Deleted " + p.name() + " from my palettes";
                    } catch (IllegalArgumentException e) {
                        message = e.getMessage();
                    }
                    local = ClientLibrary.localPalettes();
                }
            }
        }
    }

    private String label(int button) {
        return button == 0 ? "Load" : button == 1 ? (server ? "Copy to mine" : "Share") : "Delete";
    }

    private int buttonX(int button) {
        return width - 12 - 8 - (3 - button) * BUTTON - (2 - button) * 4;
    }

    @Override
    protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override
    protected void draw(Gfx g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xf5101923);
        g.text(font, "Palette library", 12, 12, 0xffb5dcfc, false);
        String hint = server
                ? "Everyone on this server can load these · only operators save or delete here"
                : "Kept on this computer, in every world";
        g.text(font, font.plainSubstrByWidth(hint, width - 280), 270, 40, 0xff9db5cb, false);
        var rows = rows();
        int fit = Math.max(1, (height - TOP - 48) / PITCH);
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - fit));
        if (armedAt != 0 && System.currentTimeMillis() - armedAt > 3000) armed = "";
        for (int i = 0; i < fit && i + scroll < rows.size(); i++) {
            var p = rows.get(i + scroll);
            int top = TOP + i * PITCH;
            g.fill(12, top, width - 12, top + PITCH - 3, 0xff1b2838);
            for (int b = 0; b < Math.min(ICONS, p.blocks().size()); b++)
                BlockPickerScreen.icon(g, font, p.blocks().get(b), 20 + b * 18, top + 7);
            int textX = 20 + ICONS * 18 + 12, textW = buttonX(0) - textX - 8;
            g.text(font, font.plainSubstrByWidth(p.name(), textW), textX, top + 6, 0xffdce6ef, false);
            String detail = p.blocks().size()
                    + (p.blocks().size() == 1 ? " block" : " blocks")
                    + (p.blocks().size() > ICONS ? " · +" + (p.blocks().size() - ICONS) + " more" : "")
                    + (p.owner().isBlank() ? "" : " · by " + p.owner())
                    + (p.tags().isBlank() ? "" : " · " + p.tags());
            g.text(font, font.plainSubstrByWidth(detail, textW), textX, top + 18, 0xff9db5cb, false);
            for (int button = 0; button < 3; button++) {
                int bx = buttonX(button);
                boolean hover = mouseX >= bx && mouseX < bx + BUTTON && mouseY >= top + 5 && mouseY < top + 25;
                boolean sure = button == 2 && p.name().equals(armed);
                g.fill(bx, top + 5, bx + BUTTON, top + 25, sure ? 0xff8a3b3b : hover ? 0xff315473 : 0xff223247);
                String text = sure ? "Sure?" : label(button);
                g.text(
                        font,
                        text,
                        bx + (BUTTON - font.width(text)) / 2,
                        top + 11,
                        sure ? 0xffffe0e0 : 0xffdce6ef,
                        false);
            }
        }
        if (server && !supported())
            g.text(
                    font,
                    "This server does not have shared palettes: update Craftium on the server",
                    20,
                    TOP + 8,
                    0xffffdca8,
                    false);
        else if (rows.isEmpty())
            g.text(
                    font,
                    server
                            ? "No palettes on the server yet · an operator can save the Materials palette here"
                            : "No palettes of your own yet · type a name and save the Materials palette",
                    20,
                    TOP + 8,
                    0xff9db5cb,
                    false);
        else if (rows.size() > fit)
            g.text(font, "Scroll for more · " + rows.size() + " palettes", 20, height - 48, 0xff9db5cb, false);
        // What the server said about the last request shows until the player does something here.
        var status = ClientState.status;
        boolean fromServer = message.isEmpty()
                && server
                && (status.error() || status.message().startsWith("Palettes"));
        String shown = fromServer ? status.message() : message;
        if (!shown.isEmpty())
            g.text(
                    font,
                    font.plainSubstrByWidth(shown, width - 24),
                    12,
                    height - 60,
                    fromServer && status.error() ? 0xffffa8a8 : 0xffffdca8,
                    false);
        drawWidgets(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean twice) {
        var rows = rows();
        int fit = Math.max(1, (height - TOP - 48) / PITCH);
        int row = ((int) event.y() - TOP) / PITCH, index = row + scroll;
        if (event.button() == 0 && event.y() >= TOP && row < fit && index < rows.size()) {
            int top = TOP + row * PITCH;
            if (event.y() >= top + 5 && event.y() < top + 25)
                for (int button = 0; button < 3; button++)
                    if (event.x() >= buttonX(button) && event.x() < buttonX(button) + BUTTON) {
                        act(button, rows.get(index));
                        return true;
                    }
        }
        return super.mouseClicked(event, twice);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        scroll -= (int) Math.signum(v) * 2;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ClientCompat.setScreen(minecraft, parent);
    }
}
