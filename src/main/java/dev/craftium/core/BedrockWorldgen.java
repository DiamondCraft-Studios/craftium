package dev.craftium.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the JSON of a Java jigsaw set (template pools, processor lists, structures and structure sets) into the files a Bedrock behavior pack holds. The
 * field names and ranges are those of Microsoft's worldgen reference (the template pool and processor pages of 2026-06-11, the structure page of 2025-10-30).
 * Nothing is dropped quietly: whatever Bedrock cannot hold, or holds differently, or could not be checked without a Bedrock game, is written into the
 * {@link Report}. It knows nothing about Minecraft classes, so it can be tested on its own.
 */
public final class BedrockWorldgen {
    private BedrockWorldgen() {}

    /** The {@code format_version} of the worldgen files: the one every example of Microsoft's current pages uses. */
    public static final String FORMAT_VERSION = "1.21.20";

    /** What an export has to say about one thing: Bedrock cannot hold it (LOST), holds it differently (CHANGED), or it could not be checked without a Bedrock game (CHECK). */
    public enum Kind { LOST, CHANGED, CHECK }

    public record Note(Kind kind, String where, String text) {
        public String line() { return kind.name() + "  " + where + ": " + text; }
    }

    /** The notes of an export, in the order they were made and without repeats. */
    public static final class Report {
        private final List<Note> notes = new ArrayList<>();
        public void lost(String where, String text) { add(Kind.LOST, where, text); }
        public void changed(String where, String text) { add(Kind.CHANGED, where, text); }
        public void check(String where, String text) { add(Kind.CHECK, where, text); }
        public void add(Kind kind, String where, String text) {
            var note = new Note(kind, where, text);
            if (!notes.contains(note)) notes.add(note);
        }
        public List<Note> notes() { return List.copyOf(notes); }
        public boolean isEmpty() { return notes.isEmpty(); }
        public int count(Kind kind) { return (int) notes.stream().filter(n -> n.kind() == kind).count(); }
    }

    /** What the converter needs to know about the rest of the export. Ids are Java's ({@code namespace:path}). */
    public interface Context {
        /** Whether the pool is written into the pack. */
        boolean hasPool(String id);
        /** Whether the template is written into the pack as a {@code .mcstructure}. */
        boolean hasTemplate(String id);
        /** The id of a processor list that is written into the pack, or null when it is not (a list of the game's own is never copied). */
        String processorList(String id);
        /** Whether the structure is written into the pack. */
        boolean hasStructure(String id);
    }

    /** A converted pool, and the processor lists it wrote inline, by the ids it made for them. */
    public record Pool(JsonObject json, Map<String, JsonObject> inlineProcessors) {}

    private static String plain(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }
    private static String full(String id) { return id.indexOf(':') < 0 ? "minecraft:" + id : id; }

    private static JsonObject wrap(String key, String id, JsonObject body) {
        var description = new JsonObject();
        description.addProperty("identifier", id);
        var inner = new JsonObject();
        inner.add("description", description);
        for (var entry : body.entrySet()) inner.add(entry.getKey(), entry.getValue());
        var root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.add(key, inner);
        return root;
    }

    // ---- template pools

    /** A Java pool as a {@code minecraft:template_pool}. An element Bedrock cannot place is left out and reported. */
    public static Pool pool(String id, JsonObject java, Context context, Report report) {
        var inline = new LinkedHashMap<String, JsonObject>();
        var elements = new JsonArray();
        var list = java.has("elements") && java.get("elements").isJsonArray() ? java.getAsJsonArray("elements") : new JsonArray();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.get(i).getAsJsonObject();
            String where = "pool " + id + ", element " + (i + 1);
            var element = element(id, entry.getAsJsonObject("element"), context, report, where, inline);
            if (element == null) continue;
            var out = new JsonObject();
            out.add("element", element);
            int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
            out.addProperty("weight", Math.clamp(weight, 1, 200));
            elements.add(out);
        }
        if (elements.isEmpty() && !list.isEmpty()) report.lost("pool " + id, "none of its elements could be written, so the pool is empty in the pack");
        var body = new JsonObject();
        body.add("elements", elements);
        String fallback = java.has("fallback") ? java.get("fallback").getAsString() : "minecraft:empty";
        if (!full(fallback).equals("minecraft:empty")) {
            if (context.hasPool(fallback)) body.addProperty("fallback", fallback);
            else report.lost("pool " + id, "its fallback pool " + fallback + " is not in the pack, so nothing closes the ends that pool's pieces leave open");
        }
        return new Pool(wrap("minecraft:template_pool", id, body), inline);
    }

    private static JsonObject element(String pool, JsonObject java, Context context, Report report, String where, Map<String, JsonObject> inline) {
        String type = java.has("element_type") ? full(java.get("element_type").getAsString()) : "";
        switch (type) {
            case "minecraft:single_pool_element", "minecraft:legacy_single_pool_element" -> {
                String template = java.has("location") ? full(java.get("location").getAsString()) : "";
                if (!context.hasTemplate(template)) { report.lost(where, "template " + template + " is not in the pack (a game template is never copied, and a missing one cannot be)"); return null; }
                var out = new JsonObject();
                out.addProperty("element_type", type);
                out.addProperty("location", template.replace(':', '/'));
                processors(pool, java.get("processors"), out, context, report, where, inline);
                out.addProperty("projection", java.has("projection") ? plain(java.get("projection").getAsString()) : "rigid");
                if (java.has("override_liquid_settings")) report.lost(where, "override_liquid_settings has no Bedrock field on a pool element");
                return out;
            }
            case "minecraft:list_pool_element" -> {
                var inner = new JsonArray();
                var children = java.has("elements") && java.get("elements").isJsonArray() ? java.getAsJsonArray("elements") : new JsonArray();
                for (int i = 0; i < children.size(); i++) {
                    var child = element(pool, children.get(i).getAsJsonObject(), context, report, where + ", part " + (i + 1), inline);
                    if (child == null) { report.lost(where, "a list element lost part " + (i + 1) + ", so the pieces placed together are not all there"); continue; }
                    inner.add(child);
                }
                if (inner.isEmpty()) return null;
                var out = new JsonObject();
                out.addProperty("element_type", type);
                out.add("elements", inner);
                out.addProperty("projection", java.has("projection") ? plain(java.get("projection").getAsString()) : "rigid");
                return out;
            }
            case "minecraft:empty_pool_element" -> {
                var out = new JsonObject();
                out.addProperty("element_type", type);
                return out;
            }
            case "minecraft:feature_pool_element" -> {
                report.lost(where, "a placed feature (" + (java.has("feature") ? java.get("feature").getAsString() : "?") + ") has no Bedrock counterpart; the element is left out");
                return null;
            }
            default -> {
                report.lost(where, "element type " + (type.isEmpty() ? "(missing)" : type) + " is not one Bedrock knows");
                return null;
            }
        }
    }

    private static void processors(String pool, JsonElement value, JsonObject out, Context context, Report report, String where, Map<String, JsonObject> inline) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive()) {
            String id = full(value.getAsString());
            if (id.equals("minecraft:empty")) return;
            String known = context.processorList(id);
            if (known != null) out.addProperty("processors", known);
            else report.lost(where, "processor list " + id + " is not in the pack (a game list is never copied), so the piece is placed without it");
            return;
        }
        // an inline list becomes a file of its own: Bedrock takes an id here, and an inline form that nothing documents is not worth a guess
        String id = pool + "_processors_" + (inline.size() + 1);
        var converted = processorList(id, value.getAsJsonObject(), context, report);
        inline.put(id, converted);
        out.addProperty("processors", id);
        report.changed(where, "an inline processor list was written as the list " + id);
    }

    // ---- processor lists

    /** A Java processor list (an object with {@code processors}, or the bare array) as a {@code minecraft:processor_list}. */
    public static JsonObject processorList(String id, JsonElement java, Context context, Report report) {
        var source = java.isJsonArray() ? java.getAsJsonArray() : java.getAsJsonObject().has("processors") ? java.getAsJsonObject().getAsJsonArray("processors") : new JsonArray();
        var processors = new JsonArray();
        for (int i = 0; i < source.size(); i++) {
            var converted = processor(source.get(i).getAsJsonObject(), report, "processor list " + id + ", processor " + (i + 1));
            if (converted != null) processors.add(converted);
        }
        var body = new JsonObject();
        body.add("processors", processors);
        return wrap("minecraft:processor_list", id, body);
    }

    private static JsonObject processor(JsonObject java, Report report, String where) {
        String type = java.has("processor_type") ? full(java.get("processor_type").getAsString()) : "";
        var out = new JsonObject();
        switch (type) {
            case "minecraft:rule" -> {
                out.addProperty("processor_type", type);
                var rules = new JsonArray();
                var list = java.has("rules") ? java.getAsJsonArray("rules") : new JsonArray();
                for (int i = 0; i < list.size(); i++) {
                    var rule = rule(list.get(i).getAsJsonObject(), report, where + ", rule " + (i + 1));
                    if (rule != null) rules.add(rule);
                }
                out.add("rules", rules);
                return out;
            }
            case "minecraft:capped" -> {
                var delegate = java.has("delegate") ? processor(java.getAsJsonObject("delegate"), report, where + ", delegate") : null;
                if (delegate == null) return null;
                out.addProperty("processor_type", type);
                out.add("limit", intProvider(java.get("limit")));
                out.add("delegate", delegate);
                return out;
            }
            case "minecraft:block_ignore" -> {
                out.addProperty("processor_type", type);
                var blocks = new JsonArray();
                if (java.has("blocks")) for (var each : java.getAsJsonArray("blocks")) {
                    String name = each.isJsonObject() ? each.getAsJsonObject().get("Name").getAsString() : each.getAsString();
                    String mapped = block(name, report, where);
                    if (mapped != null) blocks.add(mapped);
                }
                out.add("blocks", blocks);
                return out;
            }
            case "minecraft:protected_blocks" -> {
                report.lost(where, "protected_blocks takes a Java block tag (" + (java.has("value") ? java.get("value").getAsString() : "?") + "), and Bedrock's block tags are different");
                return null;
            }
            default -> {
                report.lost(where, "processor type " + (type.isEmpty() ? "(missing)" : type) + " has no Bedrock equivalent");
                return null;
            }
        }
    }

    private static JsonElement intProvider(JsonElement java) {
        if (java == null || java.isJsonNull()) return new JsonPrimitive(1);
        if (java.isJsonPrimitive()) return java;
        var in = java.getAsJsonObject();
        var out = new JsonObject();
        String type = plain(in.has("type") ? in.get("type").getAsString() : "constant");
        out.addProperty("type", type);
        for (var key : List.of("value", "min_inclusive", "max_inclusive")) if (in.has(key)) out.add(key, in.get(key));
        return out;
    }

    private static JsonObject rule(JsonObject java, Report report, String where) {
        var out = new JsonObject();
        for (var key : List.of("input_predicate", "location_predicate")) {
            if (!java.has(key)) continue;
            var predicate = blockPredicate(java.getAsJsonObject(key), report, where + ", " + key);
            if (predicate == null) { report.lost(where, "the rule is left out because its " + key + " cannot be written for Bedrock"); return null; }
            out.add(key, predicate);
        }
        if (java.has("position_predicate")) {
            var position = java.getAsJsonObject("position_predicate");
            String type = position.has("predicate_type") ? full(position.get("predicate_type").getAsString()) : "minecraft:always_true";
            if (!type.equals("minecraft:always_true") && !type.equals("minecraft:axis_aligned_linear_pos")) { report.lost(where, "the rule is left out: position predicate " + type + " has no Bedrock equivalent"); return null; }
            var copy = new JsonObject();
            for (var entry : position.entrySet()) copy.add(entry.getKey(), entry.getValue());
            copy.addProperty("predicate_type", type);
            out.add("position_predicate", copy);
        }
        if (!java.has("output_state")) { report.lost(where, "the rule is left out: it has no output_state"); return null; }
        var output = blockSpecifier(java.getAsJsonObject("output_state"), report, where + ", output_state");
        if (output == null) { report.lost(where, "the rule is left out because the block it makes has no Bedrock equivalent"); return null; }
        out.add("output_state", output);
        if (java.has("block_entity_modifier")) {
            var modifier = java.getAsJsonObject("block_entity_modifier");
            String type = modifier.has("type") ? full(modifier.get("type").getAsString()) : "minecraft:passthrough";
            switch (type) {
                case "minecraft:passthrough", "minecraft:clear" -> { var copy = new JsonObject(); copy.addProperty("type", type); out.add("block_entity_modifier", copy); }
                case "minecraft:append_loot" -> report.lost(where, "append_loot " + (modifier.has("loot_table") ? modifier.get("loot_table").getAsString() : "") + ": Java and Bedrock loot tables are different files, so the loot table is not added");
                default -> report.lost(where, "block entity modifier " + type + " has no Bedrock equivalent");
            }
        }
        return out;
    }

    private static JsonObject blockPredicate(JsonObject java, Report report, String where) {
        String type = java.has("predicate_type") ? full(java.get("predicate_type").getAsString()) : "minecraft:always_true";
        var out = new JsonObject();
        out.addProperty("predicate_type", type);
        switch (type) {
            case "minecraft:always_true" -> { return out; }
            case "minecraft:block_match", "minecraft:random_block_match" -> {
                String block = block(java.get("block").getAsString(), report, where);
                if (block == null) return null;
                out.addProperty("block", block);
                if (type.equals("minecraft:random_block_match")) out.add("probability", java.get("probability"));
                return out;
            }
            case "minecraft:blockstate_match", "minecraft:random_blockstate_match" -> {
                var state = blockSpecifier(java.getAsJsonObject("block_state"), report, where);
                if (state == null) return null;
                out.add("block_state", state);
                if (type.equals("minecraft:random_blockstate_match")) out.add("probability", java.get("probability"));
                return out;
            }
            case "minecraft:tag_match" -> {
                report.lost(where, "tag_match uses the Java block tag " + (java.has("tag") ? java.get("tag").getAsString() : "?") + ", and Bedrock's block tags are different");
                return null;
            }
            default -> { report.lost(where, "predicate " + type + " has no Bedrock equivalent"); return null; }
        }
    }

    /** A Java {@code {Name, Properties}} as a Bedrock {@code {name, states}}, with the states typed the way the block table has them. */
    private static JsonObject blockSpecifier(JsonObject java, Report report, String where) {
        String name = full(java.has("Name") ? java.get("Name").getAsString() : java.has("name") ? java.get("name").getAsString() : "");
        String mapped = block(name, report, where);
        if (mapped == null) return null;
        var out = new JsonObject();
        out.addProperty("name", mapped);
        var properties = new TreeMap<String, String>();
        var source = java.has("Properties") ? java.getAsJsonObject("Properties") : java.has("properties") ? java.getAsJsonObject("properties") : null;
        if (source != null) for (var entry : source.entrySet()) properties.put(entry.getKey(), entry.getValue().getAsString());
        if (!properties.isEmpty()) {
            var bedrock = BedrockBlockTable.get().toBedrock(name, properties);
            if (bedrock == null) { report.check(where, "the states of " + name + " could not be matched to Bedrock's, so the block is written without them"); }
            else {
                var states = new JsonObject();
                bedrock.states().forEach((k, v) -> {
                    if (v instanceof Byte b) states.addProperty(k, b != 0);
                    else if (v instanceof Integer n) states.addProperty(k, n);
                    else states.addProperty(k, String.valueOf(v));
                });
                out.add("states", states);
            }
        }
        return out;
    }

    private static String block(String javaName, Report report, String where) {
        String mapped = BedrockBlockTable.get().bedrockNameOf(javaName);
        if (mapped == null) { report.lost(where, "the block " + full(javaName) + " has no Bedrock equivalent"); return null; }
        return mapped;
    }

    // ---- structures

    /** The heightmaps both editions have; the others (motion blocking and the like) are reported. */
    private static String heightmap(String java, Report report, String where) {
        return switch (java) {
            case "WORLD_SURFACE_WG" -> { report.changed(where, "WORLD_SURFACE_WG (the heightmap used while the world is made) is world_surface in Bedrock"); yield "world_surface"; }
            case "WORLD_SURFACE" -> "world_surface";
            case "OCEAN_FLOOR_WG" -> { report.changed(where, "OCEAN_FLOOR_WG (the heightmap used while the world is made) is ocean_floor in Bedrock"); yield "ocean_floor"; }
            case "OCEAN_FLOOR" -> "ocean_floor";
            default -> { report.lost(where, "the heightmap " + java + " has no Bedrock equivalent, so the start is not projected onto the ground (Bedrock starts at Y 0 plus the start height)"); yield null; }
        };
    }

    /** A Java structure as a {@code minecraft:jigsaw}, or null when it is not a jigsaw structure or starts from a pool that is not in the pack. */
    public static JsonObject structure(String id, JsonObject java, Context context, Report report) {
        String where = "structure " + id;
        if (!full(java.has("type") ? java.get("type").getAsString() : "").equals("minecraft:jigsaw")) { report.lost(where, "it is not a jigsaw structure"); return null; }
        String start = java.has("start_pool") ? java.get("start_pool").getAsString() : "";
        if (!context.hasPool(start)) { report.lost(where, "it starts from the pool " + start + ", which is not in the pack, so the structure is not written"); return null; }
        var body = new JsonObject();
        body.add("biome_filters", biomes(java.get("biomes"), report, where));
        body.addProperty("step", java.has("step") ? java.get("step").getAsString() : "surface_structures");
        body.addProperty("terrain_adaptation", java.has("terrain_adaptation") ? java.get("terrain_adaptation").getAsString() : "none");
        body.addProperty("start_pool", start);
        if (java.has("start_jigsaw_name")) body.addProperty("start_jigsaw_name", java.get("start_jigsaw_name").getAsString());
        body.addProperty("max_depth", java.has("size") ? java.get("size").getAsInt() : 6);
        body.add("start_height", startHeight(java.get("start_height"), report, where));
        if (java.has("project_start_to_heightmap")) {
            String map = heightmap(java.get("project_start_to_heightmap").getAsString(), report, where);
            if (map != null) body.addProperty("heightmap_projection", map);
        }
        body.add("max_distance_from_center", maxDistance(java.get("max_distance_from_center")));
        if (java.has("dimension_padding")) body.add("dimension_padding", java.get("dimension_padding"));
        if (java.has("liquid_settings")) body.addProperty("liquid_settings", java.get("liquid_settings").getAsString());
        if (java.has("pool_aliases") && java.get("pool_aliases").isJsonArray()) body.add("pool_aliases", aliases(java.getAsJsonArray("pool_aliases"), context, report, where));
        if (java.has("use_expansion_hack") && java.get("use_expansion_hack").getAsBoolean())
            report.check(where, "use_expansion_hack (a village-style flag) has no Bedrock counterpart, so the structure is placed without it");
        if (java.has("spawn_overrides") && java.get("spawn_overrides").isJsonObject() && !java.getAsJsonObject("spawn_overrides").isEmpty())
            report.lost(where, "its spawn overrides (mobs that spawn inside it) have no Bedrock field in a structure");
        return wrap("minecraft:jigsaw", id, body);
    }

    private static JsonElement maxDistance(JsonElement java) {
        if (java == null || java.isJsonNull()) return new JsonPrimitive(80);
        if (java.isJsonPrimitive()) { var out = new JsonObject(); out.add("horizontal", java); out.add("vertical", java); return out; }
        return java;
    }

    private static JsonObject startHeight(JsonElement java, Report report, String where) {
        var out = new JsonObject();
        if (java != null && java.isJsonObject()) {
            var in = java.getAsJsonObject();
            String type = in.has("type") ? plain(in.get("type").getAsString()) : "constant";
            if (type.equals("uniform") && in.has("min_inclusive") && in.has("max_inclusive")) {
                out.addProperty("type", "uniform");
                out.add("min", in.get("min_inclusive"));
                out.add("max", in.get("max_inclusive"));
                return out;
            }
            if (type.equals("constant") && !in.has("type")) { out.addProperty("type", "constant"); out.add("value", in); return out; }
            if (type.equals("constant") && in.has("value")) { out.addProperty("type", "constant"); out.add("value", in.get("value")); return out; }
            report.lost(where, "the start height provider " + type + " has no Bedrock equivalent; the start height is 0");
        }
        var zero = new JsonObject();
        zero.addProperty("absolute", 0);
        out.addProperty("type", "constant");
        out.add("value", zero);
        return out;
    }

    /** Java biome tags as Bedrock's {@code biome_filters}. Only the tags whose Bedrock names are known are exact; the rest are written by the same rule and marked to check. */
    private static JsonArray biomes(JsonElement java, Report report, String where) {
        var values = new ArrayList<String>();
        if (java == null || java.isJsonNull()) values.add("#minecraft:is_overworld");
        else if (java.isJsonArray()) java.getAsJsonArray().forEach(e -> values.add(e.getAsString()));
        else values.add(java.getAsString());
        var tests = new JsonArray();
        for (String value : values) {
            var test = new JsonObject();
            if (value.startsWith("#")) {
                String tag = plain(value.substring(1));
                String bedrock;
                if (tag.equals("is_overworld")) bedrock = "overworld";
                else if (tag.equals("is_nether")) bedrock = "nether";
                else if (tag.equals("is_end")) bedrock = "the_end";
                else if (tag.startsWith("has_structure/")) bedrock = "has_structure_" + tag.substring(14).replace('/', '_');
                else {
                    bedrock = (tag.startsWith("is_") ? tag.substring(3) : tag).replace('/', '_');
                    report.check(where, "the biome tag " + value + " was written as the Bedrock tag " + bedrock + ", by the usual naming; check that Bedrock has it");
                }
                test.addProperty("test", "has_biome_tag");
                test.addProperty("operator", "==");
                test.addProperty("value", bedrock);
            } else {
                test.addProperty("test", "is_biome");
                test.addProperty("operator", "==");
                test.addProperty("value", plain(value));
                report.check(where, "the biome filter for " + value + " uses is_biome, which could not be checked without a Bedrock game");
            }
            tests.add(test);
        }
        if (tests.size() == 1) return tests;
        var any = new JsonObject();
        any.add("any_of", tests);
        var out = new JsonArray();
        out.add(any);
        return out;
    }

    private static JsonArray aliases(JsonArray java, Context context, Report report, String where) {
        var out = new JsonArray();
        for (var each : java) {
            var in = each.getAsJsonObject();
            var alias = new JsonObject();
            for (var entry : in.entrySet()) alias.add(entry.getKey(), entry.getKey().equals("type") ? new JsonPrimitive(plain(entry.getValue().getAsString())) : entry.getValue());
            out.add(alias);
        }
        report.check(where, "pool aliases were copied with their Bedrock type names, and could not be checked without a Bedrock game");
        return out;
    }

    // ---- structure sets

    /** A Java structure set as a {@code minecraft:structure_set}. Structures that are not in the pack are left out; null when none is left. */
    public static JsonObject structureSet(String id, JsonObject java, Context context, Report report) {
        String where = "structure set " + id;
        var placementIn = java.has("placement") ? java.getAsJsonObject("placement") : new JsonObject();
        String type = full(placementIn.has("type") ? placementIn.get("type").getAsString() : "minecraft:random_spread");
        if (!type.equals("minecraft:random_spread")) { report.lost(where, "placement type " + type + " is not written; only random_spread is"); return null; }
        var structures = new JsonArray();
        if (java.has("structures")) for (var each : java.getAsJsonArray("structures")) {
            var entry = each.getAsJsonObject();
            String structure = entry.get("structure").getAsString();
            if (!context.hasStructure(structure)) { report.lost(where, "the structure " + structure + " is not in the pack, so it is left out of the set"); continue; }
            var out = new JsonObject();
            out.addProperty("structure", structure);
            out.addProperty("weight", entry.has("weight") ? entry.get("weight").getAsInt() : 1);
            structures.add(out);
        }
        if (structures.isEmpty()) return null;
        int spacing = placementIn.has("spacing") ? placementIn.get("spacing").getAsInt() : 32;
        int separation = placementIn.has("separation") ? placementIn.get("separation").getAsInt() : 8;
        var placement = new JsonObject();
        placement.addProperty("type", type);
        placement.addProperty("salt", placementIn.has("salt") ? placementIn.get("salt").getAsInt() : 0);
        placement.addProperty("separation", separation);
        placement.addProperty("spacing", spacing);
        placement.addProperty("spread_type", placementIn.has("spread_type") ? plain(placementIn.get("spread_type").getAsString()) : "linear");
        for (var key : List.of("frequency", "locate_offset", "exclusion_zone", "frequency_reduction_method"))
            if (placementIn.has(key)) report.lost(where, "the placement field " + key + " has no Bedrock equivalent");
        if (separation * 2 >= spacing) report.check(where, "separation " + separation + " is not below half of spacing " + spacing + "; the community wiki says Bedrock wants it below half, so it may refuse the set");
        var body = new JsonObject();
        body.add("placement", placement);
        body.add("structures", structures);
        return wrap("minecraft:structure_set", id, body);
    }
}
