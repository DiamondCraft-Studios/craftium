package dev.craftium.client;

import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.IconCatalog;
import dev.craftium.core.IconSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Every icon in the {@link IconCatalog}, one row per category, drawn by id the way a tool or a plugin would ask for it. It is a contact sheet
 * to look at, and a picture to compare: the same icons drawn two ways give the same pixels, and no two icons give the same ones.
 */
final class IconSheetScreen extends CraftiumScreen {
    static final int COLOR = 0xffd0deeb, BACKGROUND = 0xff101923, CELL = 30, FIRST_CELL_X = 120, TOP = 8;

    /** Where an icon is drawn on the sheet, in GUI pixels. */
    record Placed(IconSpec icon, int x, int y) {}

    IconSheetScreen() { super(Component.literal("Icon sheet")); }

    /** Each icon sits in the middle of its cell, so the rows line up whatever size the icon is. */
    static List<Placed> layout() {
        var placed = new ArrayList<Placed>();
        int y = TOP;
        for (String category : IconCatalog.categories()) {
            int x = FIRST_CELL_X;
            for (IconSpec icon : IconCatalog.inCategory(category)) {
                placed.add(new Placed(icon, x + (CELL - icon.width()) / 2, y + (CELL - icon.height()) / 2));
                x += CELL;
            }
            y += CELL;
        }
        return placed;
    }

    @Override protected void drawBackground(Gfx g, int mouseX, int mouseY, float delta) {}

    @Override protected void draw(Gfx g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, BACKGROUND);
        var font = Minecraft.getInstance().font;
        int y = TOP;
        for (String category : IconCatalog.categories()) { g.text(font, category.replace("/", " / "), 6, y + 10, COLOR, false); y += CELL; }
        for (Placed p : layout()) Icons.draw(g, p.icon().id(), p.x(), p.y(), COLOR);
    }
}
