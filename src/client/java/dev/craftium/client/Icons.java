package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import dev.craftium.core.BrushMath;
import dev.craftium.core.IconCatalog;
import dev.craftium.core.Options;
import dev.craftium.core.Tool;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws an icon by its {@link IconCatalog} id, so what asks for an icon names it and does not need to know how it is drawn. The drawing itself is
 * in {@link ToolIcons}; this is the lookup from an id to it. An icon is drawn with its top left corner at {@code x}, {@code y} in the size the
 * catalog gives it, in one colour, apart from block icons, which are the game's own item icons.
 */
final class Icons {
    @FunctionalInterface private interface Painter { void draw(Gfx g, int x, int y, int color); }

    private static final Map<String, Painter> PAINTERS = painters();

    private Icons() {}

    /** Whether {@code id} can be drawn: it is in the catalog and has a painter, or it names the item icon of a block. */
    static boolean has(String id) { return PAINTERS.containsKey(id) || IconCatalog.isBlock(id); }

    /** Draws the icon, or nothing for an id that is not one: an unknown icon must not take the screen down. */
    static void draw(Gfx g, String id, int x, int y, int color) {
        if (IconCatalog.isBlock(id)) { BlockPickerScreen.icon(g, Minecraft.getInstance().font, IconCatalog.blockId(id), x, y); return; }
        var painter = PAINTERS.get(id);
        if (painter != null) painter.draw(g, x, y, color);
    }

    private static Map<String, Painter> painters() {
        var painters = new HashMap<String, Painter>();
        for (Tool tool : Tool.values()) painters.put(IconCatalog.tool(tool), (g, x, y, c) -> ToolIcons.draw(g, tool, x, y, c));
        for (Options.Compare compare : Options.Compare.values()) painters.put(IconCatalog.compare(compare), (g, x, y, c) -> ToolIcons.compare(g, compare, x, y, c));
        for (BrushMath.Shape shape : BrushMath.Shape.values()) painters.put(IconCatalog.shape(shape), (g, x, y, c) -> ToolIcons.shape(g, shape, x, y, c));
        painters.put(IconCatalog.HARD_HAT, ToolIcons::hardHat);
        painters.put(IconCatalog.CHAIN_LINKED, (g, x, y, c) -> ToolIcons.chain(g, x, y, c, true));
        painters.put(IconCatalog.CHAIN_BROKEN, (g, x, y, c) -> ToolIcons.chain(g, x, y, c, false));
        painters.put(IconCatalog.PIPETTE, ToolIcons::pipette);
        painters.put(IconCatalog.CHECK, ToolIcons::check);
        return painters;
    }
}
