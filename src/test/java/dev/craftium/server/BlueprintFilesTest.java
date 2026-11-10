package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;
import dev.craftium.core.BlueprintInfo;
import dev.craftium.core.Point;
import dev.craftium.net.Packets;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintFilesTest {
    @TempDir Path root;
    @TempDir Path archive;

    private void blueprint(String path, String title) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "data");
        var info = new BlueprintInfo(path, title, "me", "tag", 3, new Point(1, 1, 1), List.of());
        Files.writeString(file.resolveSibling(file.getFileName() + ".json"), Packets.JSON.toJson(info));
    }
    private String run(String op, String target, List<String> paths, String name) throws IOException { return BlueprintFiles.run(root, archive, new BlueprintFiles.Op(op, target, paths, name, null, null)); }

    @Test void pathsCannotLeaveTheLibrary() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintFiles.resolve(root, "../x.nbt"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintFiles.resolve(root, "a/../../x"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintFiles.resolve(root, "c:/x"));
        assertEquals(root.toAbsolutePath().normalize(), BlueprintFiles.resolve(root.toAbsolutePath().normalize(), ""));
    }

    @Test void renameKeepsTheExtensionAndMovesTheSidecar() throws IOException {
        blueprint("keep.nbt", "keep");
        run("rename", "keep.nbt", null, "castle.schem");
        assertTrue(Files.exists(root.resolve("castle.nbt")));
        assertFalse(Files.exists(root.resolve("keep.nbt")));
        assertTrue(Files.exists(root.resolve("castle.nbt.json")));
        assertFalse(Files.exists(root.resolve("keep.nbt.json")));
        assertEquals("castle", Packets.JSON.fromJson(Files.readString(root.resolve("castle.nbt.json")), BlueprintInfo.class).name());
    }

    @Test void renameRefusesAnExistingNameAndBadNames() throws IOException {
        blueprint("a.nbt", "a"); blueprint("b.nbt", "b");
        assertThrows(IllegalArgumentException.class, () -> run("rename", "a.nbt", null, "b"));
        assertThrows(IllegalArgumentException.class, () -> run("rename", "a.nbt", null, "../b"));
        assertThrows(IllegalArgumentException.class, () -> run("rename", "a.nbt", null, ".hidden"));
    }

    @Test void deleteArchivesAFileWithItsSidecarAndAWholeFolder() throws IOException {
        blueprint("one.nbt", "one"); blueprint("dir/two.nbt", "two"); blueprint("dir/deep/three.nbt", "three");
        run("delete", null, List.of("one.nbt", "dir/", "dir/two.nbt"), null); // a file inside a deleted folder is not archived twice
        assertFalse(Files.exists(root.resolve("one.nbt"))); assertFalse(Files.exists(root.resolve("one.nbt.json")));
        assertFalse(Files.exists(root.resolve("dir")));
        try (var found = Files.walk(archive)) {
            var names = found.filter(Files::isRegularFile).map(p -> { var relative = archive.relativize(p); return relative.subpath(1, relative.getNameCount()).toString().replace(File.separatorChar, '/'); }).toList(); // the first level is the time stamp
            assertTrue(names.containsAll(List.of("one.nbt", "one.nbt.json", "dir/two.nbt", "dir/two.nbt.json", "dir/deep/three.nbt")), names.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> run("delete", null, List.of(""), null));
    }

    @Test void aFolderIsMadeAndListedWhileEmpty() throws IOException {
        run("mkdir", "castles/towers", null, null);
        assertTrue(Files.isDirectory(root.resolve("castles/towers")));
        assertThrows(IllegalArgumentException.class, () -> run("mkdir", "castles/towers", null, null));
        var empty = BlueprintFiles.emptyFolders(root);
        assertEquals(1, empty.size());
        assertEquals("castles/towers/", empty.get(0).file());
        assertEquals(-2, empty.get(0).blocks());
        assertThrows(IllegalArgumentException.class, () -> run("mkdir", "a/b/c/d/e/f/g/h", null, null)); // eight folders: too deep for the listing to reach a file in it
    }

    @Test void cutMovesAndCopyKeepsTheSource() throws IOException {
        blueprint("a.nbt", "a"); blueprint("b.nbt", "b"); Files.createDirectories(root.resolve("dest"));
        run("cut", "dest", List.of("a.nbt"), null);
        assertTrue(Files.exists(root.resolve("dest/a.nbt"))); assertTrue(Files.exists(root.resolve("dest/a.nbt.json"))); assertFalse(Files.exists(root.resolve("a.nbt")));
        run("copy", "dest", List.of("b.nbt"), null);
        assertTrue(Files.exists(root.resolve("dest/b.nbt"))); assertTrue(Files.exists(root.resolve("b.nbt")));
    }

    @Test void aCopyIntoTheSameFolderGetsANewName() throws IOException {
        blueprint("a.nbt", "a");
        run("copy", "", List.of("a.nbt"), null);
        assertTrue(Files.exists(root.resolve("a (2).nbt")));
        assertTrue(Files.exists(root.resolve("a (2).nbt.json")));
        assertEquals("a (2)", Packets.JSON.fromJson(Files.readString(root.resolve("a (2).nbt.json")), BlueprintInfo.class).name());
        run("cut", "", List.of("a.nbt"), null); // a cut into the folder it is already in does nothing
        assertTrue(Files.exists(root.resolve("a.nbt")));
    }

    @Test void anOrphanSidecarAtTheTargetDoesNotStopAPasteOrDescribeTheFile() throws IOException {
        blueprint("a.nbt", "a"); Files.createDirectories(root.resolve("dest"));
        Files.writeString(root.resolve("dest/a.nbt.json"), "{\"name\":\"stale\"}"); // left behind by a delete outside the game
        run("copy", "dest", List.of("a.nbt"), null);
        assertEquals("a", Packets.JSON.fromJson(Files.readString(root.resolve("dest/a.nbt.json")), BlueprintInfo.class).name());
        Files.writeString(root.resolve("dest/b.nbt"), "x"); Files.writeString(root.resolve("b.nbt"), "no sidecar");
        Files.writeString(root.resolve("dest2.json"), "{}"); Files.createDirectories(root.resolve("dest2"));
        Files.writeString(root.resolve("dest2/b.nbt.json"), "{\"name\":\"stale\"}");
        run("cut", "dest2", List.of("b.nbt"), null); // a file with no sidecar of its own leaves none at the target either
        assertTrue(Files.exists(root.resolve("dest2/b.nbt"))); assertFalse(Files.exists(root.resolve("dest2/b.nbt.json")));
    }

    @Test void aPathNamedTwiceIsArchivedOnce() throws IOException {
        blueprint("a.nbt", "a");
        assertTrue(run("delete", null, List.of("a.nbt", "a.nbt"), null).startsWith("Archived 1 item "));
        assertFalse(Files.exists(root.resolve("a.nbt")));
    }

    @Test void aFileNamedWithItsFolderGoesOnlyWithTheFolder() throws IOException {
        blueprint("dir/a.nbt", "a"); Files.createDirectories(root.resolve("dest"));
        assertEquals("Copied 1 item", run("copy", "dest", List.of("dir/", "dir/a.nbt", "dir/"), null));
        assertTrue(Files.exists(root.resolve("dest/dir/a.nbt"))); assertFalse(Files.exists(root.resolve("dest/a.nbt")));
        assertEquals("Moved 1 item", run("cut", "dest", List.of("dir/", "dir/a.nbt"), null)); // a cut of both used to fail on the file the folder had already taken
        assertFalse(Files.exists(root.resolve("dir")));
    }

    @Test void aFolderCannotGoIntoItselfAndACopyBringsItsContents() throws IOException {
        blueprint("dir/a.nbt", "a"); Files.createDirectories(root.resolve("dir/sub")); Files.createDirectories(root.resolve("other"));
        assertThrows(IllegalArgumentException.class, () -> run("cut", "dir/sub", List.of("dir/"), null));
        assertThrows(IllegalArgumentException.class, () -> run("copy", "dir", List.of("dir/"), null));
        run("copy", "other", List.of("dir/"), null);
        assertTrue(Files.exists(root.resolve("other/dir/a.nbt"))); assertTrue(Files.exists(root.resolve("other/dir/a.nbt.json"))); assertTrue(Files.isDirectory(root.resolve("other/dir/sub")));
        assertTrue(Files.exists(root.resolve("dir/a.nbt")));
    }

    @Test void pastingTooDeepIsRefused() throws IOException {
        blueprint("dir/deep/x.nbt", "x"); Files.createDirectories(root.resolve("a/b/c/d/e/f"));
        assertThrows(IllegalArgumentException.class, () -> run("copy", "a/b/c/d/e/f", List.of("dir/"), null)); // x.nbt would be 9 levels down
    }

    @Test void propertiesAreSavedToTheSidecar() throws IOException {
        blueprint("a.nbt", "a");
        BlueprintFiles.run(root, new BlueprintFiles.Op("edit", "a.nbt", null, "Fancy", "Alex", "castle, big"));
        var info = Packets.JSON.fromJson(Files.readString(root.resolve("a.nbt.json")), BlueprintInfo.class);
        assertEquals("Fancy", info.name()); assertEquals("Alex", info.author()); assertEquals("castle, big", info.tags()); assertEquals(3, info.blocks());
        BlueprintFiles.run(root, new BlueprintFiles.Op("edit", "a.nbt", null, "  ", null, null)); // a blank name keeps the old one
        assertEquals("Fancy", Packets.JSON.fromJson(Files.readString(root.resolve("a.nbt.json")), BlueprintInfo.class).name());
    }

    @Test void aFileWithNoSidecarGetsOneWhenItsPropertiesAreEdited() throws IOException {
        Files.writeString(root.resolve("raw.nbt"), "not nbt");
        BlueprintFiles.run(root, new BlueprintFiles.Op("edit", "raw.nbt", null, "Raw", "Me", ""));
        assertEquals("Raw", Packets.JSON.fromJson(Files.readString(root.resolve("raw.nbt.json")), BlueprintInfo.class).name());
    }

    private void blueprintWithPicture(String path, int x) throws IOException {
        var builder = new dev.craftium.core.BlueprintVoxels.Builder(); builder.add(x, 0, 0, "minecraft:stone"); builder.add(0, 0, 0, "minecraft:dirt");
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, "data");
        var info = new BlueprintInfo(path, path, "", "", 2, new Point(3, 1, 1), List.of()).withVoxels(builder.pack(3, 1, 1));
        Files.writeString(file.resolveSibling(file.getFileName() + ".json"), Packets.JSON.toJson(info));
    }

    @Test void aListingHasTheBlueprintsWithTheirPathsAndTheEmptyFolders() throws IOException {
        blueprint("b.nbt", "b"); blueprint("dir/a.nbt", "a"); Files.createDirectories(root.resolve("empty/inside")); Files.writeString(root.resolve("notes.txt"), "x");
        var found = BlueprintFiles.list(root, Integer.MAX_VALUE);
        assertEquals(List.of("b.nbt", "dir/a.nbt", "empty/inside/"), found.stream().map(BlueprintInfo::file).toList());
        assertEquals(-2, found.get(2).blocks());
        assertEquals(List.of(), BlueprintFiles.list(root.resolve("missing"), 10));
    }

    @Test void aListingKeepsPicturesWithinItsBudget() throws IOException {
        blueprintWithPicture("a.nbt", 2); blueprintWithPicture("b.nbt", 1);
        var all = BlueprintFiles.list(root, Integer.MAX_VALUE);
        assertTrue(all.get(0).hasVoxels()); assertTrue(all.get(1).hasVoxels());
        int first = all.get(0).cells().length() + all.get(0).palette().stream().mapToInt(String::length).sum();
        var tight = BlueprintFiles.list(root, first + 1);
        assertTrue(tight.get(0).hasVoxels()); assertFalse(tight.get(1).hasVoxels()); // the second is past the budget: flat thumbnail only
        assertEquals("b.nbt", tight.get(1).file());
    }

    @Test void anUnknownActionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> run("format", null, null, null));
    }
}
