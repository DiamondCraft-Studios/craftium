package dev.craftium.server;

import dev.craftium.core.BlueprintInfo;
import dev.craftium.core.Point;
import dev.craftium.net.Packets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * What the blueprint browser's file menu does to a library folder: rename, delete, new folder, cut or copy and paste, and editing a blueprint's name, author and tags.
 * It works on a root folder with relative paths, so the client's own library and the server's share it. A blueprint's {@code .json} sidecar always goes with it.
 * Every path is checked to stay inside the root, and folders stay shallow enough for the listing to reach their files.
 */
public final class BlueprintFiles {
    /** The deepest a file may sit below the root, counting the file itself: what the listing walks. */
    public static final int DEPTH = 8;
    private static final String EXTENSIONS = "(?i).*\\.(nbt|schem|schematic)";
    private BlueprintFiles() {}

    /** One request: {@code op} is rename, delete, mkdir, cut, copy or edit. {@code target} is a folder for cut and copy, or the path for mkdir, rename and edit. */
    public record Op(String op, String target, List<String> paths, String name, String author, String tags) {}

    public static String encode(Op op) { return Packets.JSON.toJson(op); }

    /** A path inside the root; "" is the root itself. */
    public static Path resolve(Path root, String name) {
        if (name == null) throw new IllegalArgumentException("Missing path");
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) return root;
        if (name.length() > 160 || !name.matches("[A-Za-z0-9 _./-]+")) throw new IllegalArgumentException("Use plain folder and file names");
        for (String segment : name.split("/", -1)) if (segment.isBlank() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Invalid folder");
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Path leaves the library");
        return path;
    }

    private static int depth(Path root, Path path) { return path.equals(root) ? 0 : root.relativize(path).getNameCount(); }

    /** Runs a request against a library folder and says what happened. */
    public static String run(Path root, Op op) throws IOException { return run(root, root.toAbsolutePath().normalize().resolveSibling("blueprints-archive"), op); }

    /** As above, with the folder that deleted items are moved to (outside the library, so the browser never lists them). */
    public static String run(Path root, Path archive, Op op) throws IOException {
        root = root.toAbsolutePath().normalize();
        if (op == null || op.op() == null) throw new IllegalArgumentException("Unknown library action");
        List<String> paths = op.paths() == null ? List.of() : op.paths();
        if (paths.size() > 64) throw new IllegalArgumentException("Too many items at once");
        switch (op.op()) {
            case "mkdir" -> {
                Path folder = resolve(root, op.target());
                if (folder.equals(root)) throw new IllegalArgumentException("Give the folder a name");
                if (depth(root, folder) > DEPTH - 1) throw new IllegalArgumentException("Folders go at most " + (DEPTH - 1) + " deep");
                if (Files.exists(folder)) throw new IllegalArgumentException("That already exists");
                Files.createDirectories(folder);
                return "Created folder " + root.relativize(folder).toString().replace('\\', '/');
            }
            case "rename" -> {
                Path from = existing(root, op.target());
                String leaf = op.name() == null ? "" : op.name().trim();
                if (leaf.isEmpty() || leaf.length() > 100 || !leaf.matches("[A-Za-z0-9 _.-]+") || leaf.startsWith(".")) throw new IllegalArgumentException("Use letters, numbers, spaces, - _ and . in a name");
                boolean directory = Files.isDirectory(from);
                if (!directory) { String old = from.getFileName().toString(), extension = old.substring(old.lastIndexOf('.')); if (leaf.matches(EXTENSIONS)) leaf = leaf.substring(0, leaf.lastIndexOf('.')); leaf += extension; }
                Path to = from.resolveSibling(leaf);
                if (to.equals(from)) return "Nothing to rename";
                if (Files.exists(to)) throw new IllegalArgumentException(leaf + " already exists here");
                moveEntry(from, to);
                return "Renamed to " + leaf;
            }
            case "delete" -> {
                var picked = pick(root, paths);
                if (picked.isEmpty()) throw new IllegalArgumentException("Choose a file or folder");
                Path stamped = archive.toAbsolutePath().normalize().resolve(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                for (Path path : picked) archive(root, stamped, path);
                return "Archived " + picked.size() + (picked.size() == 1 ? " item" : " items") + " to " + archive.getFileName() + "/" + stamped.getFileName();
            }
            case "cut", "copy" -> {
                boolean move = op.op().equals("cut");
                Path target = resolve(root, op.target());
                if (!Files.isDirectory(target)) throw new IllegalArgumentException("Open a folder to paste into");
                int done = 0;
                for (Path source : pick(root, paths)) {
                    boolean directory = Files.isDirectory(source);
                    if (directory && target.startsWith(source)) throw new IllegalArgumentException("A folder cannot be pasted into itself");
                    if (move && source.getParent().equals(target)) continue;
                    if (depth(root, target) + (directory ? height(source) : 1) > DEPTH) throw new IllegalArgumentException("That would put files too deep in folders");
                    Path to = free(target, source.getFileName().toString(), directory);
                    if (move) moveEntry(source, to); else copyEntry(source, to);
                    done++;
                }
                return (move ? "Moved " : "Copied ") + done + (done == 1 ? " item" : " items");
            }
            case "edit" -> {
                Path file = existing(root, op.target());
                if (Files.isDirectory(file) || !file.getFileName().toString().matches(EXTENSIONS)) throw new IllegalArgumentException("Only blueprints have properties");
                Path sidecar = sidecar(file);
                BlueprintInfo info = null;
                try { if (Files.exists(sidecar) && Files.size(sidecar) <= 32768) info = Packets.JSON.fromJson(Files.readString(sidecar), BlueprintInfo.class); } catch (Exception ignored) {}
                if (info == null) {
                    var described = BlueprintFileVoxels.read(file);
                    info = described == null ? new BlueprintInfo(root.relativize(file).toString().replace('\\', '/'), file.getFileName().toString(), "", "", -1, new Point(0, 0, 0), List.of())
                        : new BlueprintInfo(root.relativize(file).toString().replace('\\', '/'), file.getFileName().toString(), "", "", described.blocks(), described.size(), List.of()).withVoxels(described.packed());
                }
                String name = clean(op.name(), 64), author = clean(op.author(), 64), tags = clean(op.tags(), 256);
                if (name.isEmpty()) name = info.name();
                write(sidecar, new BlueprintInfo(info.file(), name, author, tags, info.blocks(), info.size(), info.thumbnail(), info.palette(), info.cells(), info.scale()));
                return "Saved the properties of " + file.getFileName();
            }
            default -> throw new IllegalArgumentException("Unknown library action");
        }
    }

    /** Folders with nothing in them, as listing entries ending in "/" (blocks -2), so the browser can show a folder that has no file yet. */
    public static List<BlueprintInfo> emptyFolders(Path root) {
        var found = new ArrayList<BlueprintInfo>();
        if (!Files.isDirectory(root)) return found;
        try (Stream<Path> walk = Files.walk(root, DEPTH - 1)) {
            for (Path folder : walk.filter(Files::isDirectory).sorted().toList()) {
                if (folder.equals(root) || found.size() >= 64) continue;
                try (Stream<Path> inside = Files.list(folder)) { if (inside.findAny().isPresent()) continue; }
                String relative = root.relativize(folder).toString().replace('\\', '/');
                found.add(new BlueprintInfo(relative + "/", folder.getFileName().toString(), "", "", -2, new Point(0, 0, 0), List.of()));
            }
        } catch (IOException ignored) {}
        return found;
    }

    private static String clean(String text, int limit) {
        if (text == null) return "";
        String cleaned = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return cleaned.length() > limit ? cleaned.substring(0, limit) : cleaned;
    }

    private static Path existing(Path root, String name) {
        Path path = resolve(root, name);
        if (path.equals(root)) throw new IllegalArgumentException("Choose a file or folder");
        if (!Files.exists(path)) throw new IllegalArgumentException("That no longer exists");
        return path;
    }

    /** The items a request names, each once, and none that sits inside a folder that is also named (it goes with the folder). */
    private static List<Path> pick(Path root, List<String> names) {
        var all = new ArrayList<Path>();
        for (String name : names) { Path path = existing(root, name); if (!all.contains(path)) all.add(path); }
        return all.stream().filter(a -> all.stream().noneMatch(b -> !b.equals(a) && a.startsWith(b))).toList();
    }

    private static Path sidecar(Path file) { return file.resolveSibling(file.getFileName() + ".json"); }

    /** How many levels a folder takes, itself included: a file is 1. */
    private static int height(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) { return 1 + walk.filter(p -> !p.equals(folder)).mapToInt(p -> folder.relativize(p).getNameCount()).max().orElse(0); }
        catch (IOException e) { return 1; }
    }

    /** A name for a new item in a folder: the wanted one, or with " (2)", " (3)" and so on before the extension. */
    private static Path free(Path folder, String name, boolean directory) {
        Path path = folder.resolve(name);
        if (!Files.exists(path)) return path;
        int dot = directory ? -1 : name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot), extension = dot < 0 ? "" : name.substring(dot);
        for (int n = 2; n < 1000; n++) { path = folder.resolve(stem + " (" + n + ")" + extension); if (!Files.exists(path)) return path; }
        throw new IllegalArgumentException("Too many copies of " + name);
    }

    private static void moveEntry(Path from, Path to) throws IOException {
        Files.move(from, to);
        if (!Files.isDirectory(to)) { Path side = sidecar(from); if (Files.exists(side)) Files.move(side, sidecar(to), StandardCopyOption.REPLACE_EXISTING); else Files.deleteIfExists(sidecar(to)); retitle(to, from); } // an orphan sidecar already at the target does not describe this file
    }

    private static void copyEntry(Path from, Path to) throws IOException {
        if (Files.isDirectory(from)) {
            try (Stream<Path> walk = Files.walk(from)) { for (Path p : walk.toList()) { Path target = to.resolve(from.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target); } }
            return;
        }
        Files.copy(from, to);
        Path side = sidecar(from);
        if (Files.exists(side)) Files.copy(side, sidecar(to), StandardCopyOption.REPLACE_EXISTING); else Files.deleteIfExists(sidecar(to)); // an orphan sidecar already at the target does not describe this file
        retitle(to, from);
    }

    /** After a rename or a copy under a new name, a sidecar that still carries the old file's title takes the new one. */
    private static void retitle(Path now, Path before) {
        if (now.getFileName().equals(before.getFileName())) return;
        Path side = sidecar(now);
        try {
            if (!Files.exists(side) || Files.size(side) > 32768) return;
            var info = Packets.JSON.fromJson(Files.readString(side), BlueprintInfo.class);
            String old = before.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            if (info == null || !old.equals(info.name())) return;
            write(side, new BlueprintInfo(info.file(), now.getFileName().toString().replaceFirst("\\.[^.]+$", ""), info.author(), info.tags(), info.blocks(), info.size(), info.thumbnail(), info.palette(), info.cells(), info.scale()));
        } catch (Exception ignored) {}
    }

    /** Moves an item, and its sidecar, to the same relative place under the archive folder. */
    private static void archive(Path root, Path stamped, Path path) throws IOException {
        Path to = stamped.resolve(root.relativize(path).toString());
        Files.createDirectories(to.getParent());
        if (Files.exists(to)) to = free(to.getParent(), to.getFileName().toString(), Files.isDirectory(path));
        try { Files.move(path, to); }
        catch (IOException elsewhere) { copyEntry(path, to); delete(path); return; } // another drive: copy, then remove
        if (!Files.isDirectory(to)) { Path side = sidecar(path); if (Files.exists(side)) Files.move(side, sidecar(to), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void delete(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        } else { Files.delete(path); Files.deleteIfExists(sidecar(path)); }
    }

    private static void write(Path sidecar, BlueprintInfo info) throws IOException {
        Path temp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
        Files.write(temp, Packets.JSON.toJson(info).getBytes(StandardCharsets.UTF_8));
        Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING);
    }
}
