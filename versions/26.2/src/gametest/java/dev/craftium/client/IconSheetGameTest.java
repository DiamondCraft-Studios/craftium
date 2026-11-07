package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.IconCatalog;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Checks that every icon in the {@link IconCatalog} can be drawn, then opens the icon sheet and takes its picture
 * ({@code CRAFTIUM_TEST_SCOPE=icons}). The sheet is the same pixels every run, so two pictures can be compared.
 */
public final class IconSheetGameTest {
    private IconSheetGameTest() {}

    public static void run(ClientGameTestContext c) {
        for (var icon : IconCatalog.all())
            if (!Icons.has(icon.id())) throw new AssertionError("The catalog lists " + icon.id() + " but nothing draws it");
        if (!Icons.has(IconCatalog.block("minecraft:stone"))) throw new AssertionError("A block icon id was not accepted");
        if (Icons.has("craftium:tool/not_a_tool")) throw new AssertionError("An unknown icon id was accepted");
        c.runOnClient(mc -> { mc.getWindow().setWindowed(1200, 800); mc.options.guiScale().set(2); mc.resizeGui(); });
        c.runOnClient(mc -> ClientCompat.setScreen(mc, new IconSheetScreen()));
        c.waitTicks(10);
        System.out.println("CRAFTIUM_ICONS_PASS " + IconCatalog.all().size() + " icons in " + IconCatalog.categories().size() + " categories");
        System.out.println("CRAFTIUM_ICONS " + c.takeScreenshot("icon-sheet"));
    }
}
