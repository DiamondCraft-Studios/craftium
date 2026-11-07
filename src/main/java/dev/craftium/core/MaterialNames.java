package dev.craftium.core;

import java.util.*;

/**
 * Block ids named by material and kind, such as {@code spruce@stairs} or {@code stone_bricks@wall}, so a build can name its material once
 * and take the stairs, slabs and walls of it without knowing how each is spelled ({@code stone_brick_stairs}, but {@code stone_bricks}).
 * This class only lists the ids worth trying, best first; the server keeps the first that exists in the game.
 */
public final class MaterialNames {
    private MaterialNames() {}

    /** The kinds a material can be asked for. */
    public static final List<String> KINDS = List.of("block", "planks", "stairs", "slab", "wall", "fence", "fence_gate", "door", "trapdoor",
        "button", "pressure_plate", "log", "wood", "stripped_log", "stripped_wood", "leaves", "pillar", "bricks", "tiles", "chiseled", "cut",
        "smooth", "polished", "mossy", "cracked");

    /** Whether a block name is a material reference ({@code spruce@stairs}, with or without a namespace and a state). */
    public static boolean isReference(String name) {
        int bracket = name.indexOf('[');
        return (bracket < 0 ? name : name.substring(0, bracket)).contains("@");
    }

    /**
     * The ids to try for a reference, best first, each with the reference's namespace: {@code minecraft:stone_bricks@stairs} gives
     * {@code minecraft:stone_brick_stairs} among others. A block state written after the reference is not part of them.
     */
    public static List<String> candidates(String reference) {
        int bracket = reference.indexOf('[');
        String plain = bracket < 0 ? reference : reference.substring(0, bracket);
        int at = plain.indexOf('@');
        if (at < 0) throw new IllegalArgumentException("\"" + reference + "\" is not a material@kind name");
        String material = plain.substring(0, at), kind = plain.substring(at + 1);
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("\"" + kind + "\" is not a kind of block; use one of " + String.join(", ", KINDS));
        int colon = material.indexOf(':');
        String namespace = colon < 0 ? "minecraft:" : material.substring(0, colon + 1);
        String m = colon < 0 ? material : material.substring(colon + 1);
        var out = new LinkedHashSet<String>();
        for (String b : bases(m)) {
            switch (kind) {
                case "block" -> { out.add(m); out.add(b + "_planks"); out.add(b + "s"); out.add(b + "_block"); out.add(b); }
                case "log" -> { out.add(b + "_log"); out.add(b + "_stem"); }
                case "wood" -> { out.add(b + "_wood"); out.add(b + "_hyphae"); }
                case "stripped_log" -> { out.add("stripped_" + b + "_log"); out.add("stripped_" + b + "_stem"); }
                case "stripped_wood" -> { out.add("stripped_" + b + "_wood"); out.add("stripped_" + b + "_hyphae"); }
                case "chiseled", "cut", "smooth", "polished" -> { out.add(kind + "_" + m); out.add(kind + "_" + b); }
                case "mossy", "cracked" -> { out.add(kind + "_" + m); out.add(kind + "_" + b + "s"); out.add(kind + "_" + b); }
                default -> out.add(b + "_" + kind);
            }
        }
        var named = new ArrayList<String>();
        for (String id : out) named.add(namespace + id);
        return named;
    }

    /** The forms of a material name its kinds are spelled from: {@code stone_bricks} is also {@code stone_brick}, {@code oak_planks} is {@code oak}. */
    static List<String> bases(String m) {
        var out = new LinkedHashSet<String>();
        out.add(m);
        for (String suffix : List.of("_planks", "_block", "_log", "_wood"))
            if (m.endsWith(suffix) && m.length() > suffix.length()) out.add(m.substring(0, m.length() - suffix.length()));
        if (m.endsWith("s") && m.length() > 3 && !m.endsWith("ss")) out.add(m.substring(0, m.length() - 1));
        return new ArrayList<>(out);
    }
}
