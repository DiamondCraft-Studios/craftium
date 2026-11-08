package dev.craftium.core;

import java.util.*;

/**
 * The block ids nearest to one that does not exist, for an error message an AI (or a person) can act on: "spruce_stair" is not a block,
 * but "minecraft:spruce_stairs" is. Names are compared by their words ({@code _}-separated, a plural "s" ignored, a word of four letters or
 * more matching the start of a longer one), then by spelling, so a word left out or added costs more than a letter mistyped.
 */
public final class BlockNames {
    private BlockNames() {}

    /** The block id part of a block name, with its namespace: {@code "oak_stairs[facing=north]"} is {@code "minecraft:oak_stairs"}. */
    public static String id(String name) {
        int bracket = name.indexOf('[');
        String id = (bracket < 0 ? name : name.substring(0, bracket)).trim();
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** Up to {@code limit} ids from {@code known} nearest to {@code wanted}, best first; none when nothing is close. */
    public static List<String> suggest(String wanted, Collection<String> known, int limit) {
        String target = path(id(wanted));
        String[] words = target.split("_");
        record Scored(String id, double score) {}
        var scored = new ArrayList<Scored>();
        for (String candidate : known) {
            String path = path(candidate);
            String[] theirs = path.split("_");
            int missing = 0, matched = 0;
            for (String word : words) { if (has(theirs, word)) matched++; else missing++; }
            int extra = 0;
            for (String word : theirs) if (!has(words, word)) extra++;
            int distance = distance(target, path);
            if (matched == 0 && distance > 2) continue;
            scored.add(new Scored(candidate, missing * 3 + extra + distance * 0.1));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).thenComparing(Scored::id));
        var out = new ArrayList<String>();
        for (var s : scored) { if (out.size() >= limit) break; out.add(s.id()); }
        return out;
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return (colon < 0 ? id : id.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    private static boolean has(String[] words, String word) {
        for (String other : words) if (same(word, other)) return true;
        return false;
    }

    private static boolean same(String a, String b) {
        if (a.equals(b) || stem(a).equals(stem(b))) return true;
        // "cobble" is the start of "cobblestone"; short words ("oak", "red") must match whole, or they would match half the game
        return a.length() >= 4 && b.startsWith(a) || b.length() >= 4 && a.startsWith(b);
    }

    private static String stem(String word) { return word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word; }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            var swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }
}
