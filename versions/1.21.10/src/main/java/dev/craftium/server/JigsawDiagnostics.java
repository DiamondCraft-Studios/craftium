package dev.craftium.server;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.*;

/**
 * What a designer wants to know about a set of pools: what is wrong in the files (a template that is not there, a connector that points at a pool that
 * does not exist or at a name nothing in that pool has), and which connectors of a run were left open and the likely reason. All of it is read through
 * vanilla's own pools and templates, never from the files directly.
 */
final class JigsawDiagnostics {
    private JigsawDiagnostics() {}

    private static final ResourceLocation EMPTY = ResourceLocation.fromNamespaceAndPath("minecraft", "empty");
    private static final int MAX_LINES = 14;

    /** The single elements of an element: itself, or those a list holds. */
    private static List<SinglePoolElement> singles(StructurePoolElement element) {
        if (element instanceof SinglePoolElement single) return List.of(single);
        if (element instanceof ListPoolElement list) { var out = new ArrayList<SinglePoolElement>(); for (var inner : list.getElements()) out.addAll(singles(inner)); return out; }
        return List.of();
    }

    private static List<StructureTemplate.JigsawBlockInfo> jigsawsOf(StructureTemplateManager templates, SinglePoolElement element) {
        var template = templates.get(element.getTemplateLocation());
        if (template.isEmpty() || template.get().palettes.isEmpty()) return List.of();
        return template.get().palettes.get(0).jigsaws();
    }

    /** The names of the jigsaws in the elements a pool can pick, and those of its fallback pool. */
    private static Set<ResourceLocation> namesIn(Registry<StructureTemplatePool> pools, StructureTemplateManager templates, StructureTemplatePool pool, Map<StructureTemplatePool, Set<ResourceLocation>> cache) {
        var known = cache.get(pool);
        if (known != null) return known;
        var names = new HashSet<ResourceLocation>();
        cache.put(pool, names);
        for (var pair : pool.getTemplates()) {
            var element = pair.getFirst();
            var inside = singles(element);
            // A feature has a connector of its own (minecraft:bottom), and an empty element has none: ask the element, not the file.
            if (inside.isEmpty()) for (var jigsaw : element.getShuffledJigsawBlocks(templates, net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Rotation.NONE, RandomSource.create(0))) names.add(jigsaw.name());
            for (var single : inside) for (var jigsaw : jigsawsOf(templates, single)) names.add(jigsaw.name());
        }
        var fallback = pool.getFallback().value();
        if (fallback != pool) names.addAll(namesIn(pools, templates, fallback, cache));
        return names;
    }

    /** How many elements of a pool offer a jigsaw with this name, for a connector that asks for it. */
    static int fits(Registry<StructureTemplatePool> pools, StructureTemplateManager templates, StructureTemplatePool pool, ResourceLocation name) {
        int count = 0;
        for (var pair : pool.getTemplates()) if (offers(templates, pair.getFirst()).contains(name)) count++;
        return count;
    }

    /** The jigsaw names one element offers: those of its template, or its own connector when it is a feature. */
    static Set<ResourceLocation> offers(StructureTemplateManager templates, StructurePoolElement element) {
        var names = new HashSet<ResourceLocation>();
        var inside = singles(element);
        if (inside.isEmpty()) for (var jigsaw : element.getShuffledJigsawBlocks(templates, net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Rotation.NONE, RandomSource.create(0))) names.add(jigsaw.name());
        for (var single : inside) for (var jigsaw : jigsawsOf(templates, single)) names.add(jigsaw.name());
        return names;
    }

    private static boolean onlyEmpty(StructureTemplatePool pool) {
        return pool.getTemplates().stream().allMatch(pair -> pair.getFirst().getClass().getSimpleName().equals("EmptyPoolElement"));
    }

    /** Problems in the pools a start pool can reach, one line each. */
    static List<String> lint(Registry<StructureTemplatePool> pools, StructureTemplateManager templates, ResourceLocation start) { return lint(pools, templates, start, true); }

    /** As above; without {@code follow} only the pool itself is looked at, not the pools its connectors lead to. */
    static List<String> lint(Registry<StructureTemplatePool> pools, StructureTemplateManager templates, ResourceLocation start, boolean follow) {
        var lines = new ArrayList<String>();
        var seen = new HashSet<ResourceLocation>();
        var queue = new ArrayDeque<ResourceLocation>(List.of(start));
        var names = new HashMap<StructureTemplatePool, Set<ResourceLocation>>();
        while (!queue.isEmpty() && seen.size() < 200) {
            var id = queue.poll();
            if (!seen.add(id)) continue;
            var holder = pools.get(ResourceKey.create(Registries.TEMPLATE_POOL, id));
            if (holder.isEmpty()) continue;
            var pool = holder.get().value();
            if (pool.getTemplates().isEmpty()) lines.add("WARN  pool " + id + " has no elements");
            else if (onlyEmpty(pool)) lines.add("WARN  pool " + id + " only has empty elements");
            for (var pair : pool.getTemplates()) {
                for (var single : singles(pair.getFirst())) {
                    var location = single.getTemplateLocation();
                    // vanilla makes an empty template for a name it cannot find, so a template with no blocks is the same as a missing one
                    var loaded = templates.get(location);
                    if (loaded.isEmpty() || loaded.get().palettes.isEmpty() || loaded.get().palettes.get(0).blocks().isEmpty()) { lines.add("ERROR pool " + id + ": template " + location + " is missing or empty"); continue; }
                    for (var jigsaw : jigsawsOf(templates, single)) {
                        var target = jigsaw.pool().location();
                        if (target.equals(EMPTY)) continue;
                        var targetHolder = pools.get(jigsaw.pool());
                        if (targetHolder.isEmpty()) { lines.add("ERROR " + shortName(location) + " connector '" + jigsaw.name().getPath() + "' points to pool " + target + ", which does not exist"); continue; }
                        if (follow) queue.add(target);
                        var wanted = jigsaw.target();
                        if (!namesIn(pools, templates, targetHolder.get().value(), names).contains(wanted))
                            lines.add("WARN  " + shortName(location) + " connector '" + jigsaw.name().getPath() + "' wants target '" + wanted.getPath() + "', which no element of " + target + " offers");
                    }
                }
            }
        }
        return dedupe(lines);
    }

    /** The connectors of a run's pieces that nothing was placed against, with the likely reason. Pieces are in the order they were placed. */
    static List<String> openConnectors(Registry<StructureTemplatePool> pools, StructureTemplateManager templates, List<PoolElementStructurePiece> pieces) {
        record Found(int piece, StructureTemplate.JigsawBlockInfo info) {}
        var byPosition = new HashMap<net.minecraft.core.BlockPos, Found>();
        var all = new ArrayList<Found>();
        for (int i = 0; i < pieces.size(); i++) {
            var piece = pieces.get(i);
            for (var jigsaw : piece.getElement().getShuffledJigsawBlocks(templates, piece.getPosition(), piece.getRotation(), RandomSource.create(0))) {
                var found = new Found(i, jigsaw);
                byPosition.put(jigsaw.info().pos(), found);
                all.add(found);
            }
        }
        var names = new HashMap<StructureTemplatePool, Set<ResourceLocation>>();
        var lines = new ArrayList<String>();
        int open = 0;
        for (var found : all) {
            var jigsaw = found.info();
            if (jigsaw.pool().location().equals(EMPTY)) continue;
            var front = jigsaw.info().pos().relative(net.minecraft.world.level.block.JigsawBlock.getFrontFacing(jigsaw.info().state()));
            var other = byPosition.get(front);
            if (other != null && other.piece() != found.piece()) continue;
            open++;
            if (lines.size() >= MAX_LINES) continue;
            var template = templateName(pieces.get(found.piece()).getElement());
            var at = jigsaw.info().pos();
            var holder = pools.get(jigsaw.pool());
            String reason;
            if (holder.isEmpty()) reason = "pool " + jigsaw.pool().location() + " does not exist";
            else if (!namesIn(pools, templates, holder.get().value(), names).contains(jigsaw.target())) reason = "no element of " + jigsaw.pool().location() + " has a jigsaw named '" + jigsaw.target().getPath() + "'";
            else reason = "no piece fit here, or the depth ran out";
            lines.add("OPEN  " + template + " at " + at.getX() + " " + at.getY() + " " + at.getZ() + " (" + jigsaw.name().getPath() + " to " + jigsaw.pool().location().getPath() + "): " + reason);
        }
        if (open > lines.size()) lines.add("      ...and " + (open - lines.size()) + " more open connectors");
        lines.add(0, open == 0 ? "No connectors were left open." : open + " of " + all.size() + " connectors were left open:");
        return lines;
    }

    private static String templateName(StructurePoolElement element) {
        var singles = singles(element);
        return singles.isEmpty() ? "feature" : shortName(singles.get(0).getTemplateLocation());
    }

    private static String shortName(ResourceLocation location) {
        var path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static List<String> dedupe(List<String> lines) {
        var unique = new ArrayList<>(new LinkedHashSet<>(lines));
        if (unique.size() > MAX_LINES) { int more = unique.size() - MAX_LINES; unique = new ArrayList<>(unique.subList(0, MAX_LINES)); unique.add("      ...and " + more + " more problems"); }
        return unique;
    }
}
