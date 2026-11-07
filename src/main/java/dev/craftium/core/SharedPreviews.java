package dev.craftium.core;

import java.util.*;

/**
 * Which AI previews a player is shown besides their own: the ones other players are holding, in the same world (dimension), who are still
 * connected. Pure, so it needs no game: the server describes each AI workspace as an {@link Entry} and this decides.
 */
public final class SharedPreviews {
    /** One AI workspace that is holding a preview. {@code world} is whatever identifies the owner's dimension, compared by identity. */
    public record Entry(UUID owner, String name, Object world, boolean online, String data, long id) {}
    /** A preview to show: whose it is and its ghost data. */
    public record Shown(String owner, String data, long id) {}

    private SharedPreviews() {}

    /** The previews {@code viewer}, who is in {@code viewerWorld}, is shown: not their own, not from another world, not from someone who has left, and not empty. */
    public static List<Shown> select(UUID viewer, Object viewerWorld, Collection<Entry> all) {
        var out = new ArrayList<Shown>();
        for (var entry : all) {
            if (entry.owner().equals(viewer) || !entry.online() || entry.world() != viewerWorld) continue;
            if (entry.data() == null || entry.data().isEmpty()) continue;
            out.add(new Shown(entry.name(), entry.data(), entry.id()));
        }
        out.sort(Comparator.comparing(Shown::owner).thenComparingLong(Shown::id)); // the same set is always in the same order, so its key is stable
        return out;
    }

    /** Names exactly what is shown, to tell whether it changed: who has a preview and which one. Equal keys mean nothing needs sending. */
    public static String key(List<Shown> shown) {
        var sb = new StringBuilder();
        for (var one : shown) sb.append(one.owner()).append('#').append(one.id()).append(';');
        return sb.toString();
    }
}
