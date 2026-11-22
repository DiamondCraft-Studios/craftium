package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.Handshake;
import dev.craftium.core.MaterialPalette;
import dev.craftium.core.SharedPalettes;
import java.util.List;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A pop-up that saves the Materials palette (its blocks and weights) with a name, an author and tags, like the Save blueprint window does for a blueprint. It goes
 * to the player's own palettes or to the server's, where everyone sees it and only operators can save.
 */
final class SavePaletteScreen extends CraftiumScreen {
    private static final int W = 340, H = 272, ICONS = 12;
    private final Screen parent;
    private final List<String> blocks;
    private final List<Double> weights;
    private int left, top;
    private boolean toServer;
    private String name, author, tags, problem = "";
    /** The names (lower case) of the player's own palettes, read once: the window asks whether a name is taken on every frame. */
    private java.util.Set<String> ownNames = java.util.Set.of();

    SavePaletteScreen(Screen parent, boolean toServer, String name) {
        super(Component.literal("Save palette"));
        this.parent = parent;
        this.toServer = toServer && supported();
        this.name = name == null ? "" : name;
        var o = ClientState.options;
        this.author = o.author == null ? "" : o.author;
        this.tags = o.tags == null ? "" : o.tags;
        this.blocks = MaterialPalette.blocks(o);
        this.weights = MaterialPalette.weights(o, blocks.size());
    }

    private static boolean supported() {
        return ClientHandshake.serverHas(Handshake.SHARED_PALETTES);
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        ownNames = new java.util.HashSet<>();
        for (var p : ClientLibrary.localPalettes()) ownNames.add(p.name().toLowerCase(java.util.Locale.ROOT));
        // The server's list may be out of date (or never asked for), and the window warns about a name that is taken.
        if (toServer && supported()) ClientLibrary.requestShared("palette_list", null, null, null, null, null);
        field(66, name, 64, "my_palette", v -> name = v);
        field(104, author, SharedPalettes.MAX_AUTHOR, "Your name", v -> author = v);
        field(142, tags, SharedPalettes.MAX_TAGS, "walls, stone, castle", v -> tags = v);
        int half = (W - 28 - 6) / 2;
        var mine = new FlatButton(left + 14, top + 191, half, 22, "My palettes", () -> choose(false));
        mine.lit(!toServer);
        addRenderableWidget(mine);
        var server = new FlatButton(left + 20 + half, top + 191, half, 22, "Server", () -> choose(true));
        server.lit(toServer);
        server.active = supported();
        addRenderableWidget(server);
        addRenderableWidget(new FlatButton(left + 14, top + H - 34, half, 22, "Save", this::save).lit(true));
        addRenderableWidget(new FlatButton(left + 20 + half, top + H - 34, half, 22, "Cancel", this::onClose));
    }

    private void field(int y, String value, int max, String hint, java.util.function.Consumer<String> changed) {
        var box = new EditBox(font, left + 14, top + y + 11, W - 28, 20, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setMaxLength(max);
        box.setValue(value);
        box.setResponder(v -> {
            changed.accept(v);
            problem = "";
        });
        addRenderableWidget(box);
    }

    private void choose(boolean server) {
        if (toServer == server) return;
        toServer = server;
        problem = "";
        rebuildWidgets(); // asks the server for its list again when that is where it goes
    }

    private void save() {
        String chosen;
        try {
            chosen = SharedPalettes.cleanName(name);
        } catch (IllegalArgumentException e) {
            problem = name.isBlank() ? "Type a name for the palette first" : e.getMessage();
            return;
        }
        if (blocks.isEmpty()) {
            problem = "The Materials palette has no blocks to save";
            return;
        }
        var o = ClientState.options;
        o.author = author;
        o.tags = tags; // the same two fields as a blueprint's, so one is filled in once
        ClientState.save();
        if (toServer) {
            ClientLibrary.requestShared(
                    "palette_save", chosen, author, tags, blocks, weights); // the server's reply says how it went
        } else {
            try {
                ClientLibrary.savePalette(chosen, blocks, weights, author.trim(), tags.trim());
                ClientState.notify("Saved " + chosen + " to my palettes");
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
                return;
            }
        }
        onClose();
    }
    /** Whether a palette of this name is already where the player is saving, which saving replaces. */
    private boolean exists() {
        String clean = name.trim();
        if (clean.isEmpty()) return false;
        if (!toServer) return ownNames.contains(clean.toLowerCase(java.util.Locale.ROOT));
        for (var p : ClientState.sharedPalettes) if (p.name().equalsIgnoreCase(clean)) return true;
        return false;
    }

    @Override
    protected void drawBackground(Gfx g, int x, int y, float delta) {}

    @Override
    protected void draw(Gfx g, int mouseX, int mouseY, float delta) {
        var o = ClientState.options;
        if (parent instanceof EditorScreen editor) editor.suspended = true;
        drawUnder(parent, g, -1, -1, delta);
        g.fill(left - 8, top - 8, left + W + 8, top + H + 8, 0xff172330);
        g.outline(left - 8, top - 8, W + 16, H + 16, o.uiBorder);
        g.text(font, "Save palette", left + 14, top + 12, 0xffb5dcfc, false);
        for (int i = 0; i < Math.min(ICONS, blocks.size()); i++)
            BlockPickerScreen.icon(g, font, blocks.get(i), left + 14 + i * 20, top + 34);
        String count = blocks.size()
                + (blocks.size() == 1 ? " block" : " blocks")
                + (blocks.size() > ICONS ? " · +" + (blocks.size() - ICONS) + " more" : "");
        g.text(font, count, left + 14, top + 54, o.uiMuted, false);
        g.text(font, "Name", left + 14, top + 66, o.uiMuted, false);
        g.text(font, "Author", left + 14, top + 104, o.uiMuted, false);
        g.text(font, "Tags", left + 14, top + 142, o.uiMuted, false);
        g.text(font, "Save to", left + 14, top + 180, o.uiMuted, false);
        String note = !problem.isEmpty()
                ? problem
                : toServer && !supported()
                        ? "This server does not have shared palettes"
                        : exists()
                                ? "A palette with this name is already there: saving replaces it"
                                : toServer
                                        ? "Everyone on the server can load it · only operators can save"
                                        : "Kept on this computer, in every world";
        g.text(
                font,
                font.plainSubstrByWidth(note, W - 28),
                left + 14,
                top + 220,
                !problem.isEmpty() ? 0xffe08a7a : exists() ? 0xffffdca8 : o.uiMuted,
                false);
        drawWidgets(g, mouseX, mouseY, delta);
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
