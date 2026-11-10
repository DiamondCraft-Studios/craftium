package dev.craftium.server;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Runs vanilla jigsaw generation for a preview. The pool files are read from disk on every run into a registry of their own, so an edited
 * pool or template shows up without restarting the world, and the world's own registries (frozen when it loaded) are never touched.
 * The pieces vanilla lays out are turned into blocks by vanilla's own processing, without placing anything in the world.
 *
 * <p>The pools come from every pack the world uses and from {@code <world>/datapacks/craftium}, which is read even when the world does not
 * have that pack switched on.
 */
final class JigsawPlanner {
    private JigsawPlanner() {}

    static final String PACK = "craftium";
    /** Pieces and blocks are described in world coordinates; a piece names its template, where it went and how it was turned. The first piece is the start. */
    record Piece(ResourceLocation template, BlockPos pos, String rotation, BoundingBox box) {}
    record Result(Map<Point, BlockState> blocks, List<Piece> pieces, long seed, int unsupported, ResourceLocation start, List<String> report) {}

    /** What the planner reads from: the folder of the craftium datapack, made (with its pack.mcmeta) when it is not there yet. */
    static Path packFolder(MinecraftServer server) throws IOException {
        var folder = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
        var meta = folder.resolve("pack.mcmeta");
        // A pack_format alone is read by the game as an unknown format, so the range is written too. A file this planner made before that is brought up to date.
        boolean outdated = Files.exists(meta) && Files.readString(meta).contains("Jigsaw pools made with Craftium") && !Files.readString(meta).contains("min_format");
        if (!Files.exists(meta) || outdated) {
            Files.createDirectories(folder.resolve("data"));
            int format = net.minecraft.SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
            Files.writeString(meta, "{\n  \"pack\": {\n    \"description\": \"Jigsaw pools made with Craftium\",\n    \"pack_format\": " + format + ",\n    \"min_format\": " + format + ",\n    \"max_format\": " + format + "\n  }\n}\n");
        }
        return folder;
    }

    /**
     * Where the pool editor keeps changes that have not been applied: a pack folder of its own, layered over the craftium pack, so the editor and the
     * preview see them while the datapack itself stays as it was until the player applies them. It is not under datapacks, so the game never loads it.
     */
    static Path draftFolder(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("craftium-draft"); }

    /** Every pool the planner can start from, read fresh, for the command's suggestions. */
    static List<String> poolNames(MinecraftServer server) {
        try (var packs = open(server)) {
            return loadPools(server, packs.manager()).lookupOrThrow(Registries.TEMPLATE_POOL).listElements().map(h -> h.key().location().toString()).sorted().toList();
        } catch (RuntimeException | IOException e) { return List.of(); }
    }

    static Result run(ServerLevel level, String poolName, String jigsawName, long seed, int depth, int maxDistance, BlockPos origin) throws IOException {
        var server = level.getServer();
        var poolId = ResourceLocation.tryParse(poolName.contains(":") ? poolName : "craftium:" + poolName);
        if (poolId == null) throw new IllegalArgumentException("Not a pool name: " + poolName);
        try (var packs = open(server)) {
            var access = loadPools(server, packs.manager());
            var pools = access.lookupOrThrow(Registries.TEMPLATE_POOL);
            var key = ResourceKey.create(Registries.TEMPLATE_POOL, poolId);
            Holder<StructureTemplatePool> start = pools.get(key).orElseThrow(() -> new IllegalArgumentException(
                    "No pool called " + poolId + ". Pools are read from data/<namespace>/worldgen/template_pool in " + packs.folder()));
            // The template manager caches what it has read. Pointing it at this run's packs clears that, so edited templates are read again,
            // and it is pointed back at the server's own afterwards.
            var templates = server.getStructureManager();
            templates.onResourceManagerReload(packs.manager());
            try {
                var chunks = level.getChunkSource();
                var generator = chunks.getGenerator();
                var context = new Structure.GenerationContext(access, generator, generator.getBiomeSource(), chunks.randomState(), templates, seed,
                        new ChunkPos(origin), level, biome -> true);
                var jigsaw = jigsawName.isBlank() ? Optional.<ResourceLocation>empty() : Optional.ofNullable(ResourceLocation.tryParse(jigsawName.contains(":") ? jigsawName : "minecraft:" + jigsawName));
                var stub = JigsawPlacement.addPieces(context, start, jigsaw, depth, origin, false, Optional.empty(), new JigsawStructure.MaxDistance(maxDistance),
                        PoolAliasLookup.EMPTY, DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING);
                if (stub.isEmpty()) throw new IllegalArgumentException("Nothing was generated. The start pool may be empty, the start jigsaw name may not match, or the start is too close to the world's height limits");
                var container = stub.get().getPiecesBuilder().build();
                var pieces = new ArrayList<Piece>();
                var blocks = new LinkedHashMap<Point, BlockState>();
                int[] unsupported = {0};
                for (var generated : container.pieces()) {
                    if (!(generated instanceof PoolElementStructurePiece piece)) continue;
                    pieces.add(new Piece(templateOf(piece.getElement()), piece.getPosition(), piece.getRotation().name().toLowerCase(Locale.ROOT), piece.getBoundingBox()));
                    blocksOf(level, templates, piece, piece.getElement(), blocks, unsupported);
                }
                var placed = container.pieces().stream().filter(PoolElementStructurePiece.class::isInstance).map(PoolElementStructurePiece.class::cast).toList();
                return new Result(blocks, pieces, seed, unsupported[0], poolId, report(pools, templates, poolId, placed));
            } finally {
                templates.onResourceManagerReload(server.getResourceManager());
            }
        }
    }

    /** What a piece of work on the pools and templates is given: this run's packs, the folder of the craftium pack, and the server's template manager pointed at them. */
    record Env(MinecraftServer server, MultiPackResourceManager manager, Path folder, Path draft, StructureTemplateManager templates) {}
    @FunctionalInterface interface Work<T> { T run(Env env) throws IOException; }

    /** Does the work with the template manager pointed at this run's packs (so edited templates are read again), and points it back afterwards. */
    static <T> T withPacks(MinecraftServer server, Work<T> work) throws IOException {
        try (var packs = open(server)) {
            var templates = server.getStructureManager();
            templates.onResourceManagerReload(packs.manager());
            try { return work.run(new Env(server, packs.manager(), packs.folder(), draftFolder(server), templates)); }
            finally { templates.onResourceManagerReload(server.getResourceManager()); }
        }
    }

    /** The pool check and the connectors left open, as text lines. A problem in the diagnostics themselves is reported, never allowed to lose the run. */
    private static List<String> report(Registry<StructureTemplatePool> pools, StructureTemplateManager templates, ResourceLocation start, List<PoolElementStructurePiece> placed) {
        var lines = new ArrayList<String>();
        try {
            var problems = JigsawDiagnostics.lint(pools, templates, start);
            lines.add(problems.isEmpty() ? "Pool check: no problems found." : "Pool check:");
            lines.addAll(problems);
            lines.addAll(JigsawDiagnostics.openConnectors(pools, templates, placed));
        } catch (RuntimeException e) {
            lines.add("The diagnostics failed: " + e);
        }
        return lines;
    }

    /** The blocks one piece places, with the processors, rotation and mirror vanilla would use but nothing written to the world. */
    private static void blocksOf(ServerLevel level, StructureTemplateManager templates, PoolElementStructurePiece piece, StructurePoolElement element, Map<Point, BlockState> out, int[] unsupported) {
        if (element instanceof ListPoolElement list) { for (var inner : list.getElements()) blocksOf(level, templates, piece, inner, out, unsupported); return; }
        if (!(element instanceof SinglePoolElement single)) { if (!element.toString().contains("Empty")) unsupported[0]++; return; }
        var template = templates.getOrCreate(single.getTemplateLocation());
        if (template.palettes.isEmpty()) return;
        var settings = single.getSettings(piece.getRotation(), piece.getBoundingBox(), LiquidSettings.APPLY_WATERLOGGING, false);
        var blocks = settings.getRandomPalette(template.palettes, piece.getPosition()).blocks();
        var box = settings.getBoundingBox();
        for (var info : StructureTemplate.processBlockInfos(level, piece.getPosition(), piece.getPosition(), settings, blocks)) {
            if (box != null && !box.isInside(info.pos())) continue;
            out.put(new Point(info.pos().getX(), info.pos().getY(), info.pos().getZ()), info.state().mirror(settings.getMirror()).rotate(settings.getRotation()));
        }
    }

    private static ResourceLocation templateOf(StructurePoolElement element) {
        if (element instanceof SinglePoolElement single) return single.getTemplateLocation();
        if (element instanceof ListPoolElement list && !list.getElements().isEmpty()) return templateOf(list.getElements().get(0));
        return ResourceLocation.fromNamespaceAndPath("minecraft", "empty");
    }

    /** The packs of this run: the server's own, and the craftium folder when the world has not switched it on. Closing must not close the server's packs. */
    private record Packs(MultiPackResourceManager manager, Path folder) implements AutoCloseable { @Override public void close() { /* the packs belong to the server or are folders */ } }

    private static Packs open(MinecraftServer server) throws IOException {
        var folder = packFolder(server);
        var packs = new ArrayList<PackResources>(server.getResourceManager().listPacks().toList());
        if (packs.stream().noneMatch(p -> p.packId().equals("file/" + PACK)))
            packs.add(new PathPackResources(new PackLocationInfo("file/" + PACK, Component.literal(PACK), PackSource.WORLD, Optional.empty()), folder));
        var draft = draftFolder(server);
        if (Files.isDirectory(draft)) packs.add(new PathPackResources(new PackLocationInfo("file/craftium-draft", Component.literal("craftium draft"), PackSource.WORLD, Optional.empty()), draft));
        return new Packs(new MultiPackResourceManager(PackType.SERVER_DATA, packs), folder);
    }

    /** All template pools of the packs, decoded by vanilla's loader against the world's other registries (processor lists and the like). */
    static net.minecraft.core.RegistryAccess.Frozen loadPools(MinecraftServer server, MultiPackResourceManager packs) {
        var wanted = RegistryDataLoader.WORLDGEN_REGISTRIES.stream().filter(data -> data.key().equals(Registries.TEMPLATE_POOL)).toList();
        var context = new ArrayList<HolderLookup.RegistryLookup<?>>();
        server.registryAccess().registries().forEach(entry -> { if (!entry.key().equals(Registries.TEMPLATE_POOL)) context.add(entry.value()); });
        try {
            return RegistryDataLoader.load(packs, context, wanted);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("A pool file could not be read: " + firstLine(e));
        }
    }

    private static String firstLine(Throwable e) {
        var message = String.valueOf(e.getMessage());
        int cut = message.indexOf('\n');
        return cut < 0 ? message : message.substring(0, cut);
    }

    /** One box per piece for the client to outline, coloured by template (the start piece white), with the template's last name part as its label. */
    static GhostCodec.Layout layout(Result result) {
        var boxes = new int[result.pieces().size() * 7];
        var names = new ArrayList<String>();
        int at = 0;
        for (var piece : result.pieces()) {
            var box = piece.box();
            boxes[at++] = box.minX(); boxes[at++] = box.minY(); boxes[at++] = box.minZ();
            boxes[at++] = box.maxX(); boxes[at++] = box.maxY(); boxes[at++] = box.maxZ();
            boxes[at++] = names.isEmpty() ? -1 : Math.floorMod(piece.template().toString().hashCode() * 31, 360);
            var path = piece.template().getPath();
            names.add(path.substring(path.lastIndexOf('/') + 1));
        }
        return new GhostCodec.Layout(boxes, names);
    }

    /** A planned edit from a result: every block goes exactly where vanilla put it, whatever the player's brush settings. */
    static Session.Edit toEdit(ServerLevel level, Session session, Result result, String name) {
        var plan = new EditPlanner(level, session, new Options());
        plan.exact = true;
        for (var entry : result.blocks().entrySet()) plan.put(entry.getKey(), entry.getValue());
        return plan.finish(name);
    }
}
