package dev.craftium.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftium.core.BedrockPack;
import dev.craftium.core.BedrockWorldgen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Makes a Bedrock behavior pack (an {@code .mcpack}) from a pool and everything it can reach: the pools its pieces' connectors lead to and their fallbacks,
 * the pieces as {@code .mcstructure} files, the processor lists they name, and the structures and structure sets that start from those pools. What is
 * the game's own is never copied (its pools, templates and processor lists are Mojang's files, and Bedrock has its own), and everything Bedrock cannot hold,
 * holds differently or could not be checked is listed in a report that goes into the pack and into the answer.
 */
final class JigsawBedrockExport {
    private JigsawBedrockExport() {}

    /** What one transfer to the client can hold: the packet allows 1.4 million characters of Base64. */
    static final int LIMIT = 1_000_000;
    private static final ResourceLocation EMPTY = ResourceLocation.fromNamespaceAndPath("minecraft", "empty");
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int NOTES_SHOWN = 40;

    record Built(byte[] pack, String fileName, JsonObject summary) {}

    private static ResourceLocation parse(String id) {
        var out = ResourceLocation.tryParse(id.contains(":") ? id : "craftium:" + id);
        if (out == null) throw new IllegalArgumentException("Not a pool name: " + id);
        return out;
    }

    /** The files the draft has deleted but the pack still holds, one path a line (the pool editor writes them): they must not be exported. */
    private static Set<String> deleted(JigsawPlanner.Env env) throws IOException {
        var out = new HashSet<String>();
        var file = env.draft().resolve("deleted.txt");
        if (Files.exists(file)) for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line.strip());
        return out;
    }

    private static JsonElement read(Resource resource) throws IOException {
        try (var reader = resource.openAsReader()) { return JsonParser.parseReader(reader); }
    }

    private static Resource resource(JigsawPlanner.Env env, ResourceLocation id, String folder, String extension, Set<String> deleted) {
        String relative = "data/" + id.getNamespace() + "/" + folder + "/" + id.getPath() + extension;
        if (deleted.contains(relative)) return null;
        return env.manager().getResource(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), folder + "/" + id.getPath() + extension)).orElse(null);
    }

    private static boolean game(Resource resource) { return resource != null && resource.sourcePackId().equals("vanilla"); }

    /** Every template an element names, through list elements. */
    private static void templatesOf(JsonObject element, Collection<String> out) {
        if (element == null) return;
        String type = element.has("element_type") ? element.get("element_type").getAsString() : "";
        if (element.has("location")) out.add(element.get("location").getAsString().contains(":") ? element.get("location").getAsString() : "minecraft:" + element.get("location").getAsString());
        if (type.endsWith("list_pool_element") && element.has("elements")) for (var each : element.getAsJsonArray("elements")) templatesOf(each.getAsJsonObject(), out);
    }

    private static void processorsOf(JsonObject element, Collection<String> out) {
        if (element == null) return;
        if (element.has("processors") && element.get("processors").isJsonPrimitive()) {
            String id = element.get("processors").getAsString();
            out.add(id.contains(":") ? id : "minecraft:" + id);
        }
        if (element.has("elements") && element.get("elements").isJsonArray()) for (var each : element.getAsJsonArray("elements")) processorsOf(each.getAsJsonObject(), out);
    }

    private static String stem(String id, Set<String> used) {
        String base = id.replace(':', '_').replace('/', '_');
        String name = base;
        for (int n = 2; !used.add(name); n++) name = base + "_" + n;
        return name;
    }

    /**
     * Builds the pack for {@code startPool}. Throws {@link IllegalArgumentException} with the reason when there is nothing to export (the pool is missing, is the
     * game's, or holds no piece that can be written).
     */
    static Built build(JigsawPlanner.Env env, String startPool, String packName) throws IOException {
        var report = new BedrockWorldgen.Report();
        var deleted = deleted(env);
        var start = parse(startPool);
        var pools = new LinkedHashMap<String, JsonObject>();
        var templates = new LinkedHashMap<String, StructureTemplate>();
        var queue = new ArrayDeque<ResourceLocation>();
        var seen = new HashSet<String>();
        queue.add(start);
        while (!queue.isEmpty()) {
            var id = queue.poll();
            if (id.equals(EMPTY) || !seen.add(id.toString())) continue;
            var resource = resource(env, id, "worldgen/template_pool", ".json", deleted);
            if (resource == null) {
                if (id.equals(start)) throw new IllegalArgumentException("No pool called " + id);
                report.lost("pool " + id, "does not exist, so the connectors that lead to it find nothing");
                continue;
            }
            if (id.getNamespace().equals("minecraft") || game(resource)) {
                if (id.equals(start)) throw new IllegalArgumentException(id + " is one of the game's pools. Bedrock has its own, and its villages and bastions cannot be changed, so there is nothing to export: pick a pool of your own");
                report.lost("pool " + id, id.getNamespace().equals("minecraft") && !game(resource)
                        ? "changes one of the game's pools, which Bedrock does not allow, so it is not exported"
                        : "is one of the game's pools, which is not copied (Bedrock has its own), so connectors that lead to it find nothing");
                continue;
            }
            var json = read(resource).getAsJsonObject();
            pools.put(id.toString(), json);
            if (json.has("fallback")) queue.add(parse(json.get("fallback").getAsString()));
            var named = new ArrayList<String>();
            if (json.has("elements")) for (var entry : json.getAsJsonArray("elements")) templatesOf(entry.getAsJsonObject().getAsJsonObject("element"), named);
            for (String name : named) {
                var template = ResourceLocation.tryParse(name);
                if (template == null || templates.containsKey(name)) continue;
                if (game(resource(env, template, "structure", ".nbt", deleted))) continue; // a game template: the pool's converter says so
                var found = env.templates().get(template);
                if (found.isEmpty() || found.get().palettes.isEmpty() || found.get().palettes.get(0).blocks().isEmpty()) {
                    report.lost("pool " + id, "the template " + name + " is missing or empty, so the elements that use it are left out");
                    continue;
                }
                templates.put(name, found.get());
                for (var jigsaw : found.get().palettes.get(0).jigsaws()) queue.add(jigsaw.pool().location());
            }
        }
        if (templates.isEmpty()) throw new IllegalArgumentException("The pool " + start + " and the pools it leads to hold no piece of your own to export (the game's pieces are never copied)");

        // processor lists the pools name, that are not the game's
        var processors = new LinkedHashMap<String, JsonElement>();
        var named = new ArrayList<String>();
        for (var json : pools.values()) if (json.has("elements")) for (var entry : json.getAsJsonArray("elements")) processorsOf(entry.getAsJsonObject().getAsJsonObject("element"), named);
        for (String name : named) {
            var id = ResourceLocation.tryParse(name);
            if (id == null || id.equals(EMPTY) || processors.containsKey(name)) continue;
            var resource = resource(env, id, "worldgen/processor_list", ".json", deleted);
            if (resource == null || game(resource)) continue;
            processors.put(name, read(resource));
        }

        // the structures and structure sets that start from these pools
        var structures = new LinkedHashMap<String, JsonObject>();
        for (var entry : env.manager().listResources("worldgen/structure", id -> id.getPath().endsWith(".json")).entrySet()) {
            var id = idOf(entry.getKey(), "worldgen/structure/");
            if (game(entry.getValue()) || deleted.contains("data/" + entry.getKey().getNamespace() + "/" + entry.getKey().getPath())) continue;
            var json = read(entry.getValue()).getAsJsonObject();
            if (json.has("start_pool") && pools.containsKey(json.get("start_pool").getAsString())) structures.put(id, json);
        }

        var exportedStructures = new LinkedHashMap<String, JsonObject>();
        var inline = new LinkedHashMap<String, JsonObject>();
        var context = new BedrockWorldgen.Context() {
            @Override public boolean hasPool(String id) { return pools.containsKey(id); }
            @Override public boolean hasTemplate(String id) { return templates.containsKey(id); }
            @Override public String processorList(String id) { return processors.containsKey(id) ? id : null; }
            @Override public boolean hasStructure(String id) { return exportedStructures.containsKey(id); }
        };

        var files = new TreeMap<String, byte[]>();
        var used = new HashSet<String>();
        for (var entry : pools.entrySet()) {
            var pool = BedrockWorldgen.pool(entry.getKey(), entry.getValue(), context, report);
            files.put("worldgen/template_pools/" + stem(entry.getKey(), used) + ".json", bytes(pool.json()));
            inline.putAll(pool.inlineProcessors());
        }
        used.clear();
        for (var entry : processors.entrySet()) files.put("worldgen/processors/" + stem(entry.getKey(), used) + ".json", bytes(BedrockWorldgen.processorList(entry.getKey(), entry.getValue(), context, report)));
        for (var entry : inline.entrySet()) files.put("worldgen/processors/" + stem(entry.getKey(), used) + ".json", bytes(entry.getValue()));
        used.clear();
        for (var entry : structures.entrySet()) {
            var converted = BedrockWorldgen.structure(entry.getKey(), entry.getValue(), context, report);
            if (converted == null) continue;
            exportedStructures.put(entry.getKey(), entry.getValue());
            files.put("worldgen/structures/" + stem(entry.getKey(), used) + ".json", bytes(converted));
        }
        used.clear();
        int sets = 0;
        for (var entry : env.manager().listResources("worldgen/structure_set", id -> id.getPath().endsWith(".json")).entrySet()) {
            var id = idOf(entry.getKey(), "worldgen/structure_set/");
            if (game(entry.getValue()) || deleted.contains("data/" + entry.getKey().getNamespace() + "/" + entry.getKey().getPath())) continue;
            var json = read(entry.getValue()).getAsJsonObject();
            boolean ours = false;
            if (json.has("structures")) for (var each : json.getAsJsonArray("structures")) ours |= exportedStructures.containsKey(each.getAsJsonObject().get("structure").getAsString());
            if (!ours) continue;
            var converted = BedrockWorldgen.structureSet(id, json, context, report);
            if (converted == null) continue;
            files.put("worldgen/structure_sets/" + stem(id, used) + ".json", bytes(converted));
            sets++;
        }

        // the pieces
        for (var entry : templates.entrySet()) {
            var template = entry.getValue();
            var size = template.getSize();
            int sx = size.getX(), sy = size.getY(), sz = size.getZ();
            if ((long) sx * sy * sz > 4_000_000L) { report.lost("piece " + entry.getKey(), "is too large to convert (" + sx + " by " + sy + " by " + sz + ")"); continue; }
            var cells = new BlockSnapshot[sx * sy * sz];
            for (var info : template.palettes.get(0).blocks()) {
                var p = info.pos();
                if (p.getX() < 0 || p.getY() < 0 || p.getZ() < 0 || p.getX() >= sx || p.getY() >= sy || p.getZ() >= sz) continue;
                cells[(p.getY() * sz + p.getZ()) * sx + p.getX()] = new BlockSnapshot(info.state(), info.nbt() == null ? null : info.nbt().copy());
            }
            var builder = StructureBuilder.ofCells(sx, sy, sz, cells);
            var data = builder.bedrockBytes();
            for (String note : builder.notes) report.lost("piece " + entry.getKey(), note);
            var id = ResourceLocation.parse(entry.getKey());
            files.put("structures/" + id.getNamespace() + "/" + id.getPath() + ".mcstructure", data);
        }

        String name = packName == null || packName.isBlank() ? BedrockPack.defaultName(startPool) : packName.strip();
        if (name.length() > 80) name = name.substring(0, 80);
        files.put("craftium-export-report.txt", reportText(name, start.toString(), pools.size(), templates.size(), processors.size() + inline.size(), exportedStructures.size(), sets, report).getBytes(StandardCharsets.UTF_8));
        byte[] pack = BedrockPack.zip(name, "Made with Craftium from " + start, files, UUID::randomUUID);

        var summary = new JsonObject();
        summary.addProperty("name", name);
        summary.addProperty("fileName", BedrockPack.fileName(name));
        summary.addProperty("pool", start.toString());
        summary.addProperty("pools", pools.size());
        summary.addProperty("pieces", templates.size());
        summary.addProperty("processors", processors.size() + inline.size());
        summary.addProperty("structures", exportedStructures.size());
        summary.addProperty("structureSets", sets);
        summary.addProperty("bytes", pack.length);
        summary.addProperty("tooBig", pack.length > LIMIT);
        summary.addProperty("lost", report.count(BedrockWorldgen.Kind.LOST));
        summary.addProperty("changed", report.count(BedrockWorldgen.Kind.CHANGED));
        summary.addProperty("check", report.count(BedrockWorldgen.Kind.CHECK));
        var notes = new JsonArray();
        report.notes().stream().sorted(Comparator.comparing(BedrockWorldgen.Note::kind)).limit(NOTES_SHOWN).forEach(n -> {
            var item = new JsonObject();
            item.addProperty("kind", n.kind().name());
            item.addProperty("text", n.where() + ": " + n.text());
            notes.add(item);
        });
        summary.add("notes", notes);
        summary.addProperty("moreNotes", Math.max(0, report.notes().size() - NOTES_SHOWN));
        return new Built(pack, BedrockPack.fileName(name), summary);
    }

    private static String idOf(ResourceLocation location, String prefix) {
        String path = location.getPath();
        return location.getNamespace() + ":" + path.substring(prefix.length(), path.length() - 5);
    }

    private static byte[] bytes(JsonElement json) { return PRETTY.toJson(json).getBytes(StandardCharsets.UTF_8); }

    private static String reportText(String name, String pool, int pools, int pieces, int processors, int structures, int sets, BedrockWorldgen.Report report) {
        var out = new StringBuilder();
        out.append(name).append(" - made with Craftium from ").append(pool).append("\n\n");
        out.append("Pools: ").append(pools).append("   Pieces: ").append(pieces).append("   Processor lists: ").append(processors)
                .append("   Structures: ").append(structures).append("   Structure sets: ").append(sets).append("\n\n");
        out.append("LOST    = Bedrock cannot hold it, so it is not in the pack.\n");
        out.append("CHANGED = Bedrock holds it differently.\n");
        out.append("CHECK   = could not be tested without a Bedrock game: try it and tell Craftium if it fails.\n\n");
        if (report.isEmpty()) out.append("Nothing was lost, changed or left unchecked.\n");
        else for (var kind : BedrockWorldgen.Kind.values()) {
            var of = report.notes().stream().filter(n -> n.kind() == kind).toList();
            if (of.isEmpty()) continue;
            out.append(kind.name()).append(" (").append(of.size()).append(")\n");
            for (var note : of) out.append("  - ").append(note.where()).append(": ").append(note.text()).append("\n");
            out.append("\n");
        }
        out.append("To use it: open the .mcpack in Minecraft Bedrock (or unzip it into development_behavior_packs), turn on the Data-Driven Jigsaw Structures experiment\n");
        out.append("in the world, add this behavior pack to the world, and try /place jigsaw or /locate structure.\n");
        return out.toString();
    }
}
