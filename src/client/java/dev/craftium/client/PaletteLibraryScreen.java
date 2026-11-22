package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Handshake;
import dev.craftium.core.MaterialPalette;
import dev.craftium.core.SharedPalettes.Palette;
import dev.craftium.net.Packets;
import java.util.List;
import net.minecraft.client.gui.components.EditBox;
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
    private EditBox name;
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
        name = new EditBox(font, 12, height - 34, 230, 22, Component.literal("Palette name"));
        name.setHint(Component.literal("Name for the Materials palette"));
        name.setMaxLength(64);
        addRenderableWidget(name);
        addRenderableWidget(new FlatButton(
                        248, height - 34, 150, 22, server ? "Save to server" : "Save to my palettes", this::saveCurrent)
                .lit(true));
        addRenderableWidget(new FlatButton(width - 112, height - 34, 100, 22, "Return", this::onClose));
        if (server && supported()) request("palette_list", null, null, null);
    }

    private void tab(boolean toServer) {
        if (server == toServer) return;
        server = toServer;
        scroll = 0;
        message = "";
        rebuildWidgets();
    }

    private List<Palette> rows() {
        return server ? (supported() ? ClientState.sharedPalettes : List.of()) : local;
    }

    /** Asks the server to do something with the library, putting what it needs in the request's data for the one request. */
    private void request(String action, String paletteName, List<String> blocks, List<Double> weights) {
        var o = ClientState.options;
        String prior = o.assetData;
        o.assetData = paletteName == null
                ? ""
                : Packets.JSON.toJson(java.util.Map.of(
                        "name",
                        paletteName,
                        "blocks",
                        blocks == null ? List.of() : blocks,
                        "weights",
                        weights == null ? List.of() : weights));
        try {
            CraftiumClient.send(action, null, null);
        } finally {
            o.assetData = prior;
        }
    }

    private void saveCurrent() {
        String chosen = name.getValue().trim();
        var o = ClientState.options;
        var blocks = MaterialPalette.blocks(o);
        if (blocks.isEmpty()) {
            message = "The Materials palette has no blocks to save";
            return;
        }
        var weights = MaterialPalette.weights(o, blocks.size());
        save(chosen, blocks, weights, server);
    }

    private void save(String chosen, List<String> blocks, List<Double> weights, boolean toServer) {
        try {
            dev.craftium.core.SharedPalettes.cleanName(chosen);
        } catch (IllegalArgumentException e) {
            message = chosen.isEmpty() ? "Type a name for the palette first" : e.getMessage();
            return;
        }
        if (toServer) {
            if (!supported()) {
                message = "This server does not have shared palettes";
                return;
            }
            message = "";
            request("palette_save", chosen, blocks, weights);
        } else {
            try {
                ClientLibrary.savePalette(chosen, blocks, weights);
                message = "Saved " + chosen + " to my palettes";
            } catch (IllegalArgumentException e) {
                message = e.getMessage();
            }
            local = ClientLibrary.localPalettes();
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
            case 1 -> {
                if (server) {
                    save(p.name(), p.blocks(), p.weights(), false);
                    if (message.startsWith("Saved ")) message = "Copied " + p.name() + " to my palettes";
                } else save(p.name(), p.blocks(), p.weights(), true);
            }
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
                    request("palette_delete", p.name(), null, null);
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
                    + (p.owner().isBlank() ? "" : " · by " + p.owner());
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
