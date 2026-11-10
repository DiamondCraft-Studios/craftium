package dev.craftium.server;

import dev.craftium.core.BlueprintVoxels;
import dev.craftium.core.Point;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.nio.file.*;

/**
 * Reads the blocks of a blueprint file (structure NBT or Sponge schematic) for the browser's picture without a world, so a file that was
 * only imported, or saved before pictures existed, still gets one. Block ids only: states, entities and block data do not matter here.
 * A legacy numeric .schematic needs the data fixers and is not read.
 */
public final class BlueprintFileVoxels {
    /** What a file holds: its size, how many blocks are in it and the picture data. */
    public record Described(Point size, int blocks, BlueprintVoxels.Packed packed) {}
    private BlueprintFileVoxels() {}

    /** The blueprint at this path, or null when it is not a file this can read. */
    public static Described read(Path path) {
        try {
            if (Files.size(path) > 1_048_576) return null;
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(32L * 1024 * 1024));
            if (root.contains("Schematic")) root = root.getCompoundOrEmpty("Schematic");
            return root.contains("palette") ? structure(root) : sponge(root);
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static boolean air(String id) { return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air") || id.equals("minecraft:structure_void"); }

    private static String base(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static Described structure(CompoundTag root) {
        var palette = root.getListOrEmpty("palette");
        var names = new String[palette.size()];
        for (int i = 0; i < names.length; i++) names[i] = base(palette.getCompoundOrEmpty(i).getStringOr("Name", "minecraft:air"));
        var blocks = root.getListOrEmpty("blocks");
        var builder = new BlueprintVoxels.Builder();
        int sx = 1, sy = 1, sz = 1;
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.getCompoundOrEmpty(i);
            var at = block.getListOrEmpty("pos");
            int state = block.getIntOr("state", -1), x = at.getIntOr(0, 0), y = at.getIntOr(1, 0), z = at.getIntOr(2, 0);
            if (state < 0 || state >= names.length || air(names[state])) continue;
            sx = Math.max(sx, x + 1); sy = Math.max(sy, y + 1); sz = Math.max(sz, z + 1);
            builder.add(x, y, z, names[state]);
        }
        var size = root.getListOrEmpty("size");
        if (size.size() == 3) { sx = Math.max(sx, size.getIntOr(0, 1)); sy = Math.max(sy, size.getIntOr(1, 1)); sz = Math.max(sz, size.getIntOr(2, 1)); }
        return new Described(new Point(sx, sy, sz), builder.count(), builder.pack(sx, sy, sz));
    }

    private static Described sponge(CompoundTag root) {
        int sx = root.getShort("Width").orElse((short) 0) & 0xffff, sy = root.getShort("Height").orElse((short) 0) & 0xffff, sz = root.getShort("Length").orElse((short) 0) & 0xffff;
        if (sx == 0 || sy == 0 || sz == 0 || root.get("Blocks") instanceof ByteArrayTag) return null;
        var data = root.get("Blocks") instanceof CompoundTag blocks ? blocks : root;
        var palette = data.getCompoundOrEmpty("Palette");
        var names = new java.util.HashMap<Integer, String>();
        for (String key : palette.keySet()) names.put(palette.getIntOr(key, 0), base(key));
        byte[] bytes = data.getByteArray(root.getIntOr("Version", 2) >= 3 ? "Data" : "BlockData").orElse(new byte[0]);
        var builder = new BlueprintVoxels.Builder();
        int cursor = 0;
        long volume = (long) sx * sy * sz;
        for (int i = 0; i < volume && cursor < bytes.length; i++) {
            int value = 0, shift = 0, b;
            do { if (cursor >= bytes.length || shift > 28) return null; b = bytes[cursor++] & 255; value |= (b & 127) << shift; shift += 7; } while ((b & 128) != 0);
            String id = names.get(value);
            if (id == null || air(id)) continue;
            builder.add(i % sx, i / (sx * sz), (i / sx) % sz, id);
        }
        return new Described(new Point(sx, sy, sz), builder.count(), builder.pack(sx, sy, sz));
    }
}
