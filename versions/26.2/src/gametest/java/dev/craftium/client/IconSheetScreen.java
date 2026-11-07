package dev.craftium.client;

import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.IconCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Every icon in the {@link IconCatalog}, one row per category, drawn by id the way a tool or a plugin would ask for it. It is a contact sheet
 * to look at, and a picture to compare: the same icons drawn two ways give the same pixels.
 */
final class IconSheetScreen extends CraftiumScreen {
    static final int COLOR = 0xffd0deeb, CELL = 30;

    IconSheetScreen() { super(Component.literal("Icon sheet")); }

    @Override protected void drawBackground(Gfx g, int mouseX, int mouseY, float delta) {}

    @Override protected void draw(Gfx g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xff101923);
        var font = Minecraft.getInstance().font;
        int y = 8;
        for (String category : IconCatalog.categories()) {
            g.text(font, category.replace("/", " / "), 6, y + 10, COLOR, false);
            int x = 120;
            for (var icon : IconCatalog.inCategory(category)) {
                // Each icon sits in the middle of its cell, so the rows line up whatever size the icon is.
                Icons.draw(g, icon.id(), x + (CELL - icon.width()) / 2, y + (CELL - icon.height()) / 2, COLOR);
                x += CELL;
            }
            y += CELL;
        }
    }
}
