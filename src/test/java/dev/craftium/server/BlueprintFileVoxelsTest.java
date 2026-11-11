package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;
import dev.craftium.core.BlueprintInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintFileVoxelsTest {
    @TempDir Path dir;

    private static ListTag ints(int... values) { var list = new ListTag(); for (int v : values) list.add(IntTag.valueOf(v)); return list; }

    @Test void aStructureFileGivesItsBlocksAndSize() throws Exception {
        var root = new CompoundTag();
        var palette = new ListTag();
        for (String name : List.of("minecraft:air", "minecraft:stone", "minecraft:oak_stairs")) { var entry = new CompoundTag(); entry.putString("Name", name); palette.add(entry); }
        var blocks = new ListTag();
        int[][] at = {{0, 0, 0, 1}, {1, 0, 0, 2}, {2, 1, 3, 1}, {1, 1, 1, 0}};
        for (int[] b : at) { var block = new CompoundTag(); block.put("pos", ints(b[0], b[1], b[2])); block.putInt("state", b[3]); blocks.add(block); }
        root.put("size", ints(3, 2, 4)); root.put("palette", palette); root.put("blocks", blocks);
        Path file = dir.resolve("a.nbt");
        NbtIo.writeCompressed(root, file);
        var described = BlueprintFileVoxels.read(file);
        assertNotNull(described);
        assertEquals(3, described.blocks());
        assertEquals(3, described.size().x());
        var info = new BlueprintInfo("a.nbt", "a", "", "", described.blocks(), described.size(), List.of()).withVoxels(described.packed());
        assertNotNull(info.voxels());
    }

    @Test void aSpongeSchematicGivesItsBlocks() throws Exception {
        var root = new CompoundTag();
        root.putInt("Version", 2); root.putShort("Width", (short) 2); root.putShort("Height", (short) 1); root.putShort("Length", (short) 2);
        var palette = new CompoundTag(); palette.putInt("minecraft:air", 0); palette.putInt("minecraft:stone[variant=x]", 1);
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[]{1, 0, 0, 1});
        Path file = dir.resolve("a.schem");
        NbtIo.writeCompressed(root, file);
        var described = BlueprintFileVoxels.read(file);
        assertNotNull(described);
        assertEquals(2, described.blocks());
        assertEquals(List.of("minecraft:stone"), described.packed().palette());
    }

    @Test void somethingThatIsNotABlueprintIsNull() throws Exception {
        Path file = dir.resolve("bad.nbt");
        Files.writeString(file, "not nbt");
        assertNull(BlueprintFileVoxels.read(file));
    }
}
