package dev.craftium.server;

import dev.craftium.core.MaterialGrouper;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.BlockFamilies;
import net.minecraft.world.level.block.Block;

/**
 * Which blocks are the same thing in another material: oak stairs and spruce stairs are both the kind {@code stairs}, of the materials {@code minecraft:oak} and
 * {@code minecraft:spruce}. Built once from the game's block registry, so it includes every mod installed:
 * <ol>
 *   <li>Vanilla's own families ({@link BlockFamilies}, the table the game's data generators use) are taken as they are, so the odd names ({@code stone_brick_stairs}
 *       for {@code stone_bricks}, {@code cut_copper}) are right by Mojang's own word.</li>
 *   <li>Every other block, which is vanilla's logs and leaves and all of every mod's, is sorted by name ({@link MaterialGrouper}) and joins a material of the same
 *       namespace and name, so vanilla's logs go with its planks and a mod's blocks with each other.</li>
 * </ol>
 */
public final class MaterialFamilies {
    /** The blocks of the registry sorted into materials. */
    public static final class Index {
        private final Map<Block, MaterialGrouper.Slot> slots = new HashMap<>();
        private final Map<String, Map<String, Block>> materials = new HashMap<>();
        private final int registrySize;

        private Index(int registrySize) {
            this.registrySize = registrySize;
        }

        private void put(Block block, String material, String kind) {
            if (slots.containsKey(block)) return;
            var members = materials.computeIfAbsent(material, key -> new HashMap<>());
            if (members.putIfAbsent(kind, block) == null) slots.put(block, new MaterialGrouper.Slot(material, kind));
        }
        /** Where the block sits, or null when it is part of no material with other kinds. */
        public MaterialGrouper.Slot slot(Block block) {
            return slots.get(block);
        }
        /** The block of this kind in the material, or null when the material has none. */
        public Block member(String material, String kind) {
            var members = materials.get(material);
            return members == null ? null : members.get(kind);
        }
        /** The kinds the material has. */
        public Set<String> kinds(String material) {
            return materials.getOrDefault(material, Map.of()).keySet();
        }
    }

    private MaterialFamilies() {}

    private static Index cached;
    /** The index of the blocks registered now; made again if the registry has grown, as when a mod adds blocks late. */
    public static synchronized Index index() {
        int size = BuiltInRegistries.BLOCK.size();
        if (cached == null || cached.registrySize != size) cached = build(size);
        return cached;
    }

    private static Index build(int size) {
        var index = new Index(size);
        // A block can be a variant of one family and the base of another (stone_bricks is a variant of stone, and the
        // base of the stone brick stairs and slab), and
        // it is a member of the family it is the base of: so every base is placed first, and the variants after.
        record Placed(String material, Map<String, Block> variants) {}
        var placed = new ArrayList<Placed>();
        BlockFamilies.getAllFamilies().forEach(family -> {
            var named = new LinkedHashMap<String, Block>();
            for (var variant : family.getVariants().entrySet()) {
                String kind = kind(variant.getKey().name());
                if (kind != null) named.put(kind, variant.getValue());
            }
            String material = materialOf(family.getBaseBlock(), named);
            if (material == null) return;
            index.put(family.getBaseBlock(), material, MaterialGrouper.BASE);
            placed.add(new Placed(material, named));
        });
        for (var family : placed) family.variants().forEach((kind, block) -> index.put(block, family.material(), kind));
        var rest = new LinkedHashMap<String, Block>();
        for (var block : BuiltInRegistries.BLOCK) if (index.slot(block) == null) rest.put(id(block), block);
        MaterialGrouper.group(rest.keySet())
                .forEach((id, slot) -> index.put(rest.get(id), slot.material(), slot.kind()));
        return index;
    }
    /** The name of a vanilla family variant as a kind; signs are left out, as swapping one would lose its text. */
    private static String kind(String variant) {
        return switch (variant) {
            case "CUSTOM_FENCE" -> "fence";
            case "CUSTOM_FENCE_GATE" -> "fence_gate";
            case "SIGN", "WALL_SIGN" -> null;
            default -> variant.toLowerCase(Locale.ROOT);
        };
    }
    /** A vanilla family's material, named after its stairs, slab or wall when it has one ({@code stone_brick}, not {@code stone_bricks}), else after its base block. */
    private static String materialOf(Block base, Map<String, Block> variants) {
        for (var kind : List.of("stairs", "slab", "wall", "fence", "door")) {
            var block = variants.get(kind);
            if (block == null) continue;
            String path = BuiltInRegistries.BLOCK.getKey(block).getPath(), ending = "_" + kind;
            if (path.endsWith(ending) && path.length() > ending.length())
                return BuiltInRegistries.BLOCK.getKey(block).getNamespace() + ":"
                        + path.substring(0, path.length() - ending.length());
        }
        var key = BuiltInRegistries.BLOCK.getKey(base);
        return key.getNamespace() + ":" + key.getPath().replaceAll("_(planks|block)$", "");
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
    /** Whether the block belongs to a material that has other kinds, so that its material can be changed. */
    public static boolean applicable(Block block) {
        var index = index();
        var slot = index.slot(block);
        return slot != null && index.kinds(slot.material()).size() > 1;
    }
}
