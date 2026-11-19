package dev.craftium.core;

import java.util.*;

/**
 * Sorts block ids into materials and kinds from their names alone, which is how the blocks of mods are told to belong together: {@code mod:maple_planks},
 * {@code mod:maple_stairs} and {@code mod:stripped_maple_log} are the kinds {@code base}, {@code stairs} and {@code stripped_log} of the material {@code mod:maple}.
 * <ul>
 *   <li>The kind is read from the end of the name ({@code _stairs}, {@code _slab}, {@code _wall}, {@code _fence_gate}, {@code _log} and so on), and {@code stripped_} in front of a log or wood.</li>
 *   <li>A material is a namespace and what is left of the name, so the blocks of two mods never mix, whatever they are called.</li>
 *   <li>A block with no kind in its name ({@code cobblestone}, {@code stone_bricks}) is the {@code base} of a material only if blocks of that material exist, whether its
 *       name is the material's or has an {@code s} or {@code _block} on it: {@code stone_bricks} joins {@code stone_brick_stairs}, and {@code glass}, with nothing like it, joins nothing.
 *       The first such block takes the place.</li>
 * </ul>
 * Pure: it takes ids and gives ids, so it works for any mod and is tested without the game. Signs are left out, as they hold text that a swap would lose.
 */
public final class MaterialGrouper {
    private MaterialGrouper() {}

    /** The kind of the block that gives a material its name, the full block: planks, cobblestone, a block of metal. */
    public static final String BASE = "base";

    /** Where a block sits: the material, as {@code namespace:name}, and the kind within it. */
    public record Slot(String material, String kind) {}

    /** The kinds named by the end of a block's name, longest first, and the kind each stands for. */
    private static final List<Map.Entry<String, String>> SUFFIXES = List.of(
            Map.entry("fence_gate", "fence_gate"),
            Map.entry("pressure_plate", "pressure_plate"),
            Map.entry("trapdoor", "trapdoor"),
            Map.entry("stairs", "stairs"),
            Map.entry("leaves", "leaves"),
            Map.entry("planks", BASE),
            Map.entry("button", "button"),
            Map.entry("hyphae", "wood"),
            Map.entry("fence", "fence"),
            Map.entry("slab", "slab"),
            Map.entry("wall", "wall"),
            Map.entry("door", "door"),
            Map.entry("wood", "wood"),
            Map.entry("stem", "log"),
            Map.entry("log", "log"));

    /** Things named {@code _stem} that are not logs: the stems of crops and of the huge mushrooms. */
    private static final Set<String> NOT_LOGS =
            Set.of("melon", "pumpkin", "attached_melon", "attached_pumpkin", "mushroom");

    /** The kinds that can come after {@code stripped_}. */
    private static final Set<String> STRIPPABLE = Set.of("log", "wood");

    /** Sorts the ids; the ones that belong to no material are not in the result. */
    public static Map<String, Slot> group(Collection<String> ids) {
        var out = new LinkedHashMap<String, Slot>();
        var materials = new HashSet<String>();
        var hasBase = new HashSet<String>();
        var unkinded = new ArrayList<String>();
        for (String id : ids) {
            var slot = slot(namespace(id), path(id));
            if (slot == null) {
                unkinded.add(id);
                continue;
            }
            out.put(id, slot);
            materials.add(slot.material());
            if (slot.kind().equals(BASE)) hasBase.add(slot.material());
        }
        for (String id : unkinded) {
            String namespace = namespace(id);
            for (String candidate : candidates(path(id))) {
                String material = namespace + ":" + candidate;
                if (!materials.contains(material)) continue;
                if (hasBase.add(material)) out.put(id, new Slot(material, BASE));
                break; // the nearest material is the one it belongs to, whether or not its place was free
            }
        }
        return out;
    }
    /** The material and kind a name states outright, or null when it states no kind. */
    static Slot slot(String namespace, String path) {
        boolean stripped = path.startsWith("stripped_");
        String rest = stripped ? path.substring("stripped_".length()) : path;
        for (var suffix : SUFFIXES) {
            String ending = "_" + suffix.getKey();
            if (!rest.endsWith(ending) || rest.length() == ending.length()) continue;
            String kind = suffix.getValue();
            if (suffix.getKey().equals("stem") && NOT_LOGS.contains(rest.substring(0, rest.length() - ending.length())))
                return null;
            if (stripped && !STRIPPABLE.contains(kind)) return null;
            return new Slot(
                    namespace + ":" + rest.substring(0, rest.length() - ending.length()),
                    stripped ? "stripped_" + kind : kind);
        }
        return null;
    }
    /** The materials a block with no kind in its name could be the base of, nearest first: its own name, without {@code _block}, without a plural {@code s}. */
    static List<String> candidates(String path) {
        var out = new LinkedHashSet<String>();
        out.add(path);
        if (path.endsWith("_block") && path.length() > 6) out.add(path.substring(0, path.length() - 6));
        if (path.endsWith("s") && !path.endsWith("ss") && path.length() > 3)
            out.add(path.substring(0, path.length() - 1));
        return new ArrayList<>(out);
    }

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }
}
