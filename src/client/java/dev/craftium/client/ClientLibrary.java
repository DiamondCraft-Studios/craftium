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

    private record Palette(
            List<String> blocks, List<Double> weights, List<String> recent, String author, String tags) {}

    private static Palette readPaletteFile(String name) throws java.io.IOException {
        var json = com.google.gson.JsonParser.parseString(Files.readString(file("palettes", name)));
        Palette value = json.isJsonArray()
                ? new Palette(List.of(Packets.JSON.fromJson(json, String[].class)), List.of(), List.of(), "", "")
                : Packets.JSON.fromJson(json, Palette.class);
        if (value.blocks() == null
                || value.blocks().isEmpty()
                || value.blocks().size() > 256
                || value.blocks().contains(null)) throw new IllegalArgumentException("Invalid palette");
        return value;
    }

    /** Makes the blocks and weights the Materials palette. */
    static void apply(List<String> blocks, List<Double> weights) {
        var o = ClientState.options;
        String oldBlocks = o.materials;
        var oldWeights = o.materialWeights;
        o.materials = String.join(",", blocks);
        o.materialWeights = new ArrayList<>(weights == null ? List.of() : weights);
        try {
            o.validate();
        } catch (RuntimeException bad) {
            o.materials = oldBlocks; // a palette that is refused leaves the Materials window as it was
            o.materialWeights = oldWeights;
            throw bad;
        }
        ClientState.save();
    }
    /** The client's own saved palettes, in the shape the shared ones have (no owner); one that cannot be read is left out. */
    static List<dev.craftium.core.SharedPalettes.Palette> localPalettes() {
        var out = new ArrayList<dev.craftium.core.SharedPalettes.Palette>();
        for (String name : list("palettes"))
            try {
                var value = readPaletteFile(name);
                var weights = new ArrayList<Double>();
                for (int i = 0; i < value.blocks().size(); i++)
                    weights.add(dev.craftium.core.MaterialPalette.clamp(
                            value.weights() != null && i < value.weights().size()
                                    ? value.weights().get(i)
                                    : null));
                out.add(new dev.craftium.core.SharedPalettes.Palette(
                        name,
                        value.author() == null ? "" : value.author(),
                        "",
                        List.copyOf(value.blocks()),
                        weights,
                        0,
                        value.tags() == null ? "" : value.tags()));
            } catch (Exception skipped) {
                // a file that is not a palette
            }
        return out;
    }

    static void deletePalette(String name) {
        try {
            Files.deleteIfExists(file("palettes", name));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot delete palette: " + name, e);
        }
    }
    /** Saves blocks and weights as one of the client's palettes, replacing one of the same name. */
    static void savePalette(String name, List<String> blocks, List<Double> weights, String author, String tags) {
        write(file("palettes", name), Packets.JSON.toJson(new Palette(blocks, weights, List.of(), author, tags)));
    }
    /** Asks the server to do something with the shared palettes, putting what it needs in the request's data for the one request. A null name sends none (a list). */
    static void requestShared(
            String action, String name, String author, String tags, List<String> blocks, List<Double> weights) {
        var o = ClientState.options;
        String prior = o.assetData;
        o.assetData = name == null
                ? ""
                : Packets.JSON.toJson(java.util.Map.of(
                        "name",
                        name,
                        "author",
                        author == null ? "" : author,
                        "tags",
                        tags == null ? "" : tags,
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
