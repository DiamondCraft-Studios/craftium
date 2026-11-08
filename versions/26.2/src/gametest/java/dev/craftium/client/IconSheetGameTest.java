package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.IconCatalog;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Checks that every icon in the {@link IconCatalog} can be drawn, opens the icon sheet and takes its picture
 * ({@code CRAFTIUM_TEST_SCOPE=icons}), then checks the picture: no icon is blank and no two icons are the same drawing, so a tool can be told
 * from another by its icon. The sheet is the same pixels every run, so two pictures can be compared.
 */
public final class IconSheetGameTest {
    private static final int SCALE = 2; // the GUI scale the sheet is taken at

    private IconSheetGameTest() {}

    public static void run(ClientGameTestContext c) {
        for (var icon : IconCatalog.all())
            if (!Icons.has(icon.id())) throw new AssertionError("The catalog lists " + icon.id() + " but nothing draws it");
        if (!Icons.has(IconCatalog.block("minecraft:stone"))) throw new AssertionError("A block icon id was not accepted");
        if (Icons.has("craftium:tool/not_a_tool")) throw new AssertionError("An unknown icon id was accepted");
        c.runOnClient(mc -> { mc.getWindow().setWindowed(1200, 800); mc.options.guiScale().set(SCALE); mc.resizeGui(); });
        c.runOnClient(mc -> ClientCompat.setScreen(mc, new IconSheetScreen()));
        c.waitTicks(10);
        var picture = c.takeScreenshot("icon-sheet");
        System.out.println("CRAFTIUM_ICONS " + picture);
        BufferedImage image;
        try { image = javax.imageio.ImageIO.read(picture.toFile()); } catch (java.io.IOException e) { throw new AssertionError(e); }
        // The sheet is put away before the test ends, so the game shuts down from a normal screen: once it was left open and the window hung after the check had passed.
        c.runOnClient(mc -> ClientCompat.setScreen(mc, null));
        checkDrawings(image);
        System.out.println("CRAFTIUM_ICONS_PASS " + IconCatalog.all().size() + " icons in " + IconCatalog.categories().size() + " categories, each drawn and none alike");
    }

    private static void checkDrawings(BufferedImage image) {
        var byDrawing = new LinkedHashMap<Integer, List<String>>();
        for (var placed : IconSheetScreen.layout()) {
            int x = placed.x() * SCALE, y = placed.y() * SCALE, w = placed.icon().width() * SCALE, h = placed.icon().height() * SCALE;
            int[] pixels = image.getRGB(x, y, w, h, null, 0, w);
            boolean blank = true;
            for (int pixel : pixels) if (pixel != IconSheetScreen.BACKGROUND) { blank = false; break; }
            if (blank) throw new AssertionError(placed.icon().id() + " draws nothing");
            byDrawing.computeIfAbsent(Arrays.hashCode(pixels), k -> new java.util.ArrayList<>()).add(placed.icon().id());
        }
        var alike = byDrawing.values().stream().filter(ids -> ids.size() > 1).toList();
        if (!alike.isEmpty()) throw new AssertionError("These icons are drawn alike: " + alike);
    }
}
