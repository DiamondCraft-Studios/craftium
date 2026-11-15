package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

final class AnnotationEdits {
    private static final Map<Path, LinkedHashMap<UUID, Annotation>> CACHE = new HashMap<>();

    private AnnotationEdits() {}

    static void clear() {
        CACHE.clear();
    }

    private static Path path(ServerLevel level) {
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("craftium")
                .resolve("annotations-" + level.dimension().toString().replaceAll("[^a-zA-Z0-9_.-]", "_") + ".json");
    }

    private static LinkedHashMap<UUID, Annotation> map(ServerLevel level) {
        return CACHE.computeIfAbsent(path(level), p -> {
            var result = new LinkedHashMap<UUID, Annotation>();
            try {
                if (Files.exists(p))
                    for (var a : Packets.JSON.fromJson(Files.readString(p), Annotation[].class)) result.put(a.id(), a);
            } catch (Exception e) {
                dev.craftium.runtime.ServerPlatform.LOG.warn("Cannot read annotations", e);
            }
            return result;
        });
    }

    static List<Annotation> list(ServerLevel level) {
        return List.copyOf(map(level).values());
    }

    static Session.Edit plan(String action, ServerLevel level, Options o, Point at) {
        var current = map(level);
        var changes = new ArrayList<Annotation.Change>();
        if (action.equals("annotation_clear")) {
            for (var a : current.values()) changes.add(new Annotation.Change(a, null));
        } else if (action.equals("annotation_delete") || action.equals("annotation_update")) {
            var a = current.get(UUID.fromString(o.annotationId));
            if (a == null) throw new IllegalArgumentException("Select an annotation identifier");
            changes.add(new Annotation.Change(
                    a, action.equals("annotation_delete") ? null : create(a.id(), o, o.position1)));
        } else {
            if (current.size() >= 128) throw new IllegalArgumentException("Annotation limit: 128 per dimension");
            if (o.annotationMode.equals("IMAGE")
                    && current.values().stream()
                                    .filter(a -> a.mode().equals("IMAGE"))
                                    .count()
                            >= 8) throw new IllegalArgumentException("Image annotation limit: eight per dimension");
            changes.add(new Annotation.Change(null, create(UUID.randomUUID(), o, at)));
        }
        return new Session.Edit(action.replace('_', ' '), List.of(), List.of(), 2, List.of(), List.copyOf(changes));
    }

    private static Annotation create(UUID id, Options o, Point at) {
        if (!Set.of("TEXT", "LINE", "BOX", "DRAW", "IMAGE").contains(o.annotationMode))
            throw new IllegalArgumentException("Choose TEXT, LINE, BOX, DRAW or IMAGE annotation mode");
        if (o.annotationMode.equals("IMAGE")
                && (o.annotationImageWidth < 1
                        || o.annotationImageHeight < 1
                        || o.annotationPixels.size() != o.annotationImageWidth * o.annotationImageHeight))
            throw new IllegalArgumentException("Choose an annotation image first");
        return new Annotation(
                id,
                o.annotationMode,
                at,
                o.position2,
                o.text,
                o.annotationColor,
                Math.clamp(o.annotationWidth, 1, 8),
                List.copyOf(o.annotationPoints),
                o.annotationMode.equals("IMAGE") ? List.copyOf(o.annotationPixels) : List.of(),
                o.annotationImageWidth,
                o.annotationImageHeight,
                o.annotationImageScale,
                o.annotationNormal);
    }

    static List<Annotation.Change> apply(ServerLevel level, List<Annotation.Change> changes, boolean backwards) {
        var map = map(level);
        var applied = new ArrayList<Annotation.Change>();
        for (var c : changes) {
            var expected = backwards ? c.after() : c.before();
            var desired = backwards ? c.before() : c.after();
            if (!Objects.equals(map.get(c.id()), expected)) continue;
            if (desired == null) map.remove(c.id());
            else map.put(c.id(), desired);
            applied.add(c);
        }
        if (!applied.isEmpty())
            try {
                Path path = path(level);
                Files.createDirectories(path.getParent());
                Files.writeString(path, Packets.JSON.toJson(map.values()));
            } catch (Exception e) {
                dev.craftium.runtime.ServerPlatform.LOG.error("Cannot save annotations", e);
            }
        return List.copyOf(applied);
    }
}
