package dev.craftium.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The server's library of saved palettes: a set of blocks with weights that every player on the server can see and use, one JSON file each in a folder.
 * Who may change it is the server's decision (operators); the library records only who saved a palette, to show beside it.
 */
public final class SharedPalettes {
    /** The most palettes the library holds, so a server's folder and the list sent to clients stay bounded. */
    public static final int MAX_PALETTES = 100;
    /** The longest a palette's file is, in characters: with {@link #MAX_PALETTES} it keeps the whole list under a status packet's size. */
    public static final int MAX_FILE = 12_000;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 _.-]{1,64}");
    private static final Gson GSON = new Gson();

    /** One palette. {@code owner} is the name of the player who saved it, and {@code ownerId} their UUID text ("" for one put in the folder by hand). */
    public record Palette(
            String name, String owner, String ownerId, List<String> blocks, List<Double> weights, long modified) {}

    private final Path directory;

    public SharedPalettes(Path directory) {
        this.directory = directory;
    }

    /** The name with its spaces trimmed, or an error that says what a name may hold. */
    public static String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        if (!NAME.matcher(clean).matches() || clean.equals(".") || clean.equals(".."))
            throw new IllegalArgumentException(
                    "Use a name of up to 64 letters, numbers, spaces, dots, underscores or dashes");
        return clean;
    }

    /** Every readable palette by name, ignoring case; a file that cannot be read is skipped. */
    public List<Palette> list() {
        var found = new ArrayList<Palette>();
        if (!Files.isDirectory(directory)) return found;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList()) {
                var palette = read(path);
                if (palette != null) found.add(palette);
            }
        } catch (IOException e) {
            return found;
        }
        found.sort(Comparator.comparing((Palette p) -> p.name().toLowerCase(Locale.ROOT))
                .thenComparing(Palette::name));
        return found;
    }

    public Palette get(String name) {
        Path path = existing(cleanName(name));
        return path == null ? null : read(path);
    }

    /**
     * Saves a palette, replacing one of the same name (ignoring case).
     *
     * @throws IllegalArgumentException with what is wrong, for the player to read
     */
    public Palette save(String name, String owner, String ownerId, List<String> blocks, List<Double> weights) {
        String clean = cleanName(name);
        if (blocks == null || blocks.isEmpty()) throw new IllegalArgumentException("The palette needs a block");
        if (blocks.size() > MaterialList.MAX)
            throw new IllegalArgumentException("A palette holds at most " + MaterialList.MAX + " blocks");
        var cleanBlocks = new ArrayList<String>();
        for (String block : blocks) {
            if (block == null || block.isBlank() || block.contains(",") || block.length() > 200)
                throw new IllegalArgumentException("Invalid block in the palette");
            cleanBlocks.add(block.trim());
        }
        var cleanWeights = new ArrayList<Double>();
        for (int i = 0; i < cleanBlocks.size(); i++) {
            Double weight = weights != null && i < weights.size() ? weights.get(i) : null;
            cleanWeights.add(
                    weight == null || !Double.isFinite(weight)
                            ? 1d
                            : Math.clamp(weight, MaterialPalette.MIN_WEIGHT, MaterialPalette.MAX_WEIGHT));
        }
        Path old = existing(clean);
        if (old == null && list().size() >= MAX_PALETTES)
            throw new IllegalArgumentException("The server's palette library is full (" + MAX_PALETTES + ")");
        var palette = new Palette(
                old == null ? clean : stem(old),
                owner == null ? "" : owner,
                ownerId == null ? "" : ownerId,
                cleanBlocks,
                cleanWeights,
                System.currentTimeMillis());
        String json = GSON.toJson(palette);
        if (json.length() > MAX_FILE) throw new IllegalArgumentException("The palette is too big to share");
        write(directory.resolve(palette.name() + ".json"), json);
        return palette;
    }

    public void delete(String name) {
        Path path = existing(cleanName(name));
        if (path == null) throw new IllegalArgumentException("No palette named " + name.trim());
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot delete the palette: " + e.getMessage(), e);
        }
    }
    /** The palettes as JSON text for a status, leaving out those that would not fit in {@code budget} characters. */
    public static String toJson(List<Palette> palettes, int budget) {
        var fit = new ArrayList<Palette>();
        int used = 2;
        for (Palette palette : palettes) {
            int size = GSON.toJson(palette).length() + 1;
            if (used + size > budget) break;
            used += size;
            fit.add(palette);
        }
        return GSON.toJson(fit);
    }
    /** The palettes in the text {@link #toJson} makes: empty when the text is not valid. */
    public static List<Palette> fromJson(String json) {
        try {
            var array = GSON.fromJson(json, Palette[].class);
            if (array == null) return List.of();
            var out = new ArrayList<Palette>();
            for (Palette palette : array) if (valid(palette)) out.add(palette);
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The file of the palette with this name ignoring case, with the spelling it was saved in (a file system that ignores case would otherwise hand back the asked spelling). */
    private Path existing(String clean) {
        if (!Files.isDirectory(directory)) return null;
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".json") && stem(p).equalsIgnoreCase(clean))
                    .min(Comparator.comparing((Path p) -> !stem(p).equals(clean)))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stem(Path path) {
        return path.getFileName().toString().replaceFirst("\\.json$", "");
    }

    private static boolean valid(Palette p) {
        return p != null
                && p.name() != null
                && NAME.matcher(p.name()).matches()
                && p.blocks() != null
                && !p.blocks().isEmpty()
                && p.blocks().size() <= MaterialList.MAX
                && !p.blocks().contains(null);
    }

    private Palette read(Path path) {
        try {
            if (Files.size(path) > MAX_FILE * 2L) return null;
            var palette = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Palette.class);
            if (palette == null || palette.blocks() == null) return null;
            // The file's name is the palette's name, whatever the file says, so one put in by hand works.
            var weights = new ArrayList<Double>();
            for (int i = 0; i < palette.blocks().size(); i++) {
                Double weight =
                        palette.weights() != null && i < palette.weights().size()
                                ? palette.weights().get(i)
                                : null;
                weights.add(
                        weight == null || !Double.isFinite(weight)
                                ? 1d
                                : Math.clamp(weight, MaterialPalette.MIN_WEIGHT, MaterialPalette.MAX_WEIGHT));
            }
            var out = new Palette(
                    stem(path),
                    palette.owner() == null ? "" : palette.owner(),
                    palette.ownerId() == null ? "" : palette.ownerId(),
                    palette.blocks(),
                    weights,
                    palette.modified());
            return valid(out) ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot save the palette: " + e.getMessage(), e);
        }
    }
}
