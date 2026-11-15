package dev.craftium.client;

import java.nio.file.*;
import java.util.Base64;
import net.minecraft.client.Minecraft;

final class AssetPicker {
    private AssetPicker() {}

    static void local(Runnable refresh) {
        Thread picker = new Thread(
                () -> {
                    try {
                        String selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                                "Import local Craftium blueprint", "", null, null, false);
                        if (selected == null) return;
                        Path path = Path.of(selected);
                        Minecraft.getInstance().execute(() -> {
                            try {
                                ClientAssets.importFile(path);
                                refresh.run();
                            } catch (Exception e) {
                                ClientState.notify(e.getMessage());
                            }
                        });
                    } catch (Exception e) {
                        Minecraft.getInstance().execute(() -> ClientState.notify(e.getMessage()));
                    }
                },
                "Craftium local blueprint chooser");
        picker.setDaemon(true);
        picker.start();
    }

    static void open(Runnable refresh) {
        Thread picker = new Thread(
                () -> {
                    try {
                        String selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                                "Import Craftium schematic, heightmap or font", "", null, null, false);
                        if (selected == null) return;
                        Path path = Path.of(selected);
                        if (Files.size(path) > 1_048_576) throw new IllegalArgumentException("Asset limit: 1 MiB");
                        byte[] data = Files.readAllBytes(path);
                        Minecraft.getInstance().execute(() -> {
                            var o = ClientState.options;
                            o.name = path.getFileName().toString();
                            o.assetData = Base64.getEncoder().encodeToString(data);
                            CraftiumClient.send("asset_upload", null, null);
                            o.assetData = "";
                            if (o.name.endsWith(".png")) o.heightmap = o.name;
                            else if (o.name.endsWith(".ttf") || o.name.endsWith(".otf")) {
                                o.fontFile = o.name;
                                o.useBuiltinFont = false;
                            }
                            refresh.run();
                        });
                    } catch (Exception e) {
                        Minecraft.getInstance().execute(() -> ClientState.notify(e.getMessage()));
                    }
                },
                "Craftium asset chooser");
        picker.setDaemon(true);
        picker.start();
    }
}
