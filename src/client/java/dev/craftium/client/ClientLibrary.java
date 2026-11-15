package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.net.Packets;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;

final class ClientLibrary {
    private ClientLibrary() {}

    private static Path directory(String type) {
        return FabricLoader.getInstance().getConfigDir().resolve("craftium").resolve(type);
    }

    private static Path file(String type, String name) {
        if (!name.matches("[A-Za-z0-9 _.-]{1,80}") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Use a name containing letters, numbers, spaces, dots or dashes");
        return directory(type).resolve(name + ".json");
    }

    static void savePreset(String name) {
        write(file("presets", name), Packets.JSON.toJson(ClientState.options));
    }

    static void loadPreset(String name) {
        try {
            var options = Packets.JSON.fromJson(Files.readString(file("presets", name)), Options.class);
            options.validate();
            ClientState.options = options;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot load preset: " + name, e);
        }
    }

    private record Palette(List<String> blocks, List<Double> weights, List<String> recent) {}

    static void savePalette(String name) {
        write(
                file("palettes", name),
                Packets.JSON.toJson(new Palette(
                        dev.craftium.core.MaterialList.split(ClientState.options.materials),
                        ClientState.options.materialWeights,
                        ClientState.palette)));
    }

    static void loadPalette(String name) {
        try {
            var json = com.google.gson.JsonParser.parseString(Files.readString(file("palettes", name)));
            Palette value = json.isJsonArray()
                    ? new Palette(List.of(Packets.JSON.fromJson(json, String[].class)), List.of(), List.of())
                    : Packets.JSON.fromJson(json, Palette.class);
            if (value.blocks() == null
                    || value.blocks().isEmpty()
                    || value.blocks().size() > 256
                    || value.blocks().contains(null)) throw new IllegalArgumentException("Invalid palette");
            ClientState.options.materials = String.join(",", value.blocks());
            ClientState.options.materialWeights =
                    new ArrayList<>(value.weights() == null ? List.of() : value.weights());
            ClientState.options.validate();
            ClientState.palette.clear();
            ClientState.palette.addAll(
                    value.recent() == null || value.recent().isEmpty()
                            ? value.blocks()
                                    .subList(0, Math.min(18, value.blocks().size()))
                            : value.recent()
                                    .subList(0, Math.min(18, value.recent().size())));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot load palette: " + name, e);
        }
    }

    static List<String> list(String type) {
        try {
            if (!Files.isDirectory(directory(type))) return List.of();
            try (var paths = Files.list(directory(type))) {
                return paths.filter(p -> p.toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
                        .sorted()
                        .toList();
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, content);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot save library item", e);
        }
    }
}
