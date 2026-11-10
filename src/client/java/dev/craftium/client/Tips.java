package dev.craftium.client;

import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tooltips for the widgets of a screen that is laid out on a scaled canvas (the jigsaw planner and its pool editor). The vanilla tooltip of a widget is
 * placed by the pointer's position on the real screen, which is wrong once the canvas is scaled, so this draws inside the canvas, beside the pointer,
 * whatever the window size. A widget that is greyed out still explains itself.
 */
final class Tips {
    private static final int WIDTH = 240;
    private final Map<AbstractWidget, String> tips = new LinkedHashMap<>();

    void clear() { tips.clear(); }

    boolean has(AbstractWidget widget) { return tips.containsKey(widget); }

    /** Gives the widget its tip and returns it, so a widget can be made and tipped in one go. */
    <T extends AbstractWidget> T on(T widget, String text) {
        tips.put(widget, text);
        return widget;
    }

    /** Draws the tip of the widget the pointer is on, at canvas coordinates. */
    void draw(Gfx g, Font font, int mx, int my, int canvasWidth, int canvasHeight) {
        for (var entry : tips.entrySet()) {
            var w = entry.getKey();
            if (!w.visible || mx < w.getX() || mx >= w.getX() + w.getWidth() || my < w.getY() || my >= w.getY() + w.getHeight()) continue;
            var rows = font.split(Component.literal(entry.getValue()), WIDTH);
            int widest = 0;
            for (var row : rows) widest = Math.max(widest, font.width(row));
            int h = rows.size() * 10 + 8;
            int x = Math.max(4, Math.min(mx + 12, canvasWidth - widest - 18)), y = Math.max(4, Math.min(my + 14, canvasHeight - h - 4));
            g.fill(x, y, x + widest + 12, y + h, 0xf0101923);
            g.outline(x, y, widest + 12, h, ClientState.options.uiAccent);
            for (int i = 0; i < rows.size(); i++) g.text(font, rows.get(i), x + 6, y + 5 + i * 10, 0xffdce6ef, false);
            return;
        }
    }
}
