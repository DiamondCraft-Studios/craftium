package dev.craftium.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BedrockWorldgenTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    /** An export that holds the pools, templates, processor lists and structures named here. */
    private static BedrockWorldgen.Context holds(Set<String> pools, Set<String> templates, Set<String> processors, Set<String> structures) {
        return new BedrockWorldgen.Context() {
            @Override public boolean hasPool(String id) { return pools.contains(id); }
            @Override public boolean hasTemplate(String id) { return templates.contains(id); }
            @Override public String processorList(String id) { return processors.contains(id) ? id : null; }
            @Override public boolean hasStructure(String id) { return structures.contains(id); }
        };
    }

    private static boolean has(BedrockWorldgen.Report report, BedrockWorldgen.Kind kind, String text) {
        return report.notes().stream().anyMatch(n -> n.kind() == kind && n.line().contains(text));
    }

    // ---- pools

    @Test void aPoolKeepsItsElementsWeightsFallbackAndProcessorsAsBedrockWritesThem() {
        var report = new BedrockWorldgen.Report();
        var context = holds(Set.of("outpost:wall"), Set.of("outpost:hall"), Set.of("outpost:weather"), Set.of());
        var java = json("""
            {"fallback":"outpost:wall","elements":[
              {"weight":4,"element":{"element_type":"minecraft:legacy_single_pool_element","location":"outpost:hall","processors":"outpost:weather","projection":"terrain_matching"}},
              {"weight":3,"element":{"element_type":"minecraft:empty_pool_element"}},
              {"weight":1,"element":{"element_type":"minecraft:single_pool_element","location":"outpost:hall","processors":"minecraft:empty","projection":"rigid"}}]}
            """);
        var pool = BedrockWorldgen.pool("outpost:halls", java, context, report);
        var root = pool.json();
        assertEquals("1.21.20", root.get("format_version").getAsString());
        var body = root.getAsJsonObject("minecraft:template_pool");
        assertEquals("outpost:halls", body.getAsJsonObject("description").get("identifier").getAsString());
        assertEquals("outpost:wall", body.get("fallback").getAsString());
        var elements = body.getAsJsonArray("elements");
        assertEquals(3, elements.size());
        var first = elements.get(0).getAsJsonObject();
        assertEquals(4, first.get("weight").getAsInt());
        var element = first.getAsJsonObject("element");
        assertEquals("minecraft:legacy_single_pool_element", element.get("element_type").getAsString());
        assertEquals("outpost/hall", element.get("location").getAsString(), "a location is a path inside the structures folder: no colon");
        assertEquals("outpost:weather", element.get("processors").getAsString());
        assertEquals("terrain_matching", element.get("projection").getAsString());
        assertEquals("minecraft:empty_pool_element", elements.get(1).getAsJsonObject().getAsJsonObject("element").get("element_type").getAsString());
        assertFalse(elements.get(2).getAsJsonObject().getAsJsonObject("element").has("processors"), "minecraft:empty is no processor list");
        assertTrue(report.isEmpty(), report.notes().toString());
        assertTrue(pool.inlineProcessors().isEmpty());
    }

    @Test void anElementBedrockCannotPlaceIsLeftOutAndSaidSo() {
        var report = new BedrockWorldgen.Report();
        var context = holds(Set.of(), Set.of("outpost:hall"), Set.of(), Set.of());
        var java = json("""
            {"fallback":"minecraft:empty","elements":[
              {"weight":2,"element":{"element_type":"minecraft:feature_pool_element","feature":"minecraft:oak","projection":"rigid"}},
              {"weight":2,"element":{"element_type":"minecraft:single_pool_element","location":"minecraft:village/plains/house","processors":"minecraft:empty","projection":"rigid"}},
              {"weight":2,"element":{"element_type":"minecraft:single_pool_element","location":"outpost:hall","processors":"minecraft:street_plains","projection":"rigid"}}]}
            """);
        var pool = BedrockWorldgen.pool("outpost:halls", java, context, report);
        var body = pool.json().getAsJsonObject("minecraft:template_pool");
        assertEquals(1, body.getAsJsonArray("elements").size());
        assertFalse(body.has("fallback"), "minecraft:empty is written as no fallback");
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "placed feature"), report.notes().toString());
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "minecraft:village/plains/house"), "a game template is never copied");
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "minecraft:street_plains"), "a game processor list is never copied");
        assertFalse(body.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element").has("processors"));
    }

    @Test void aFallbackThatIsNotInThePackIsReportedBecauseItClosesTheEnds() {
        var report = new BedrockWorldgen.Report();
        var java = json("{\"fallback\":\"outpost:caps\",\"elements\":[]}");
        var body = BedrockWorldgen.pool("outpost:halls", java, holds(Set.of(), Set.of(), Set.of(), Set.of()), report).json().getAsJsonObject("minecraft:template_pool");
        assertFalse(body.has("fallback"));
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "outpost:caps"));
    }

    @Test void aListElementKeepsItsPartsAndAnInlineProcessorListBecomesAFile() {
        var report = new BedrockWorldgen.Report();
        var context = holds(Set.of(), Set.of("outpost:hall", "outpost:moss"), Set.of(), Set.of());
        var java = json("""
            {"fallback":"minecraft:empty","elements":[{"weight":1,"element":{"element_type":"minecraft:list_pool_element","projection":"rigid","elements":[
               {"element_type":"minecraft:single_pool_element","location":"outpost:hall","processors":{"processors":[{"processor_type":"minecraft:rule","rules":[
                  {"input_predicate":{"predicate_type":"minecraft:random_block_match","block":"minecraft:stone_bricks","probability":0.2},"location_predicate":{"predicate_type":"minecraft:always_true"},"output_state":{"Name":"minecraft:mossy_stone_bricks"}}]}]},"projection":"rigid"},
               {"element_type":"minecraft:single_pool_element","location":"outpost:moss","processors":"minecraft:empty","projection":"rigid"}]}}]}
            """);
        var pool = BedrockWorldgen.pool("outpost:halls", java, context, report);
        var list = pool.json().getAsJsonObject("minecraft:template_pool").getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element");
        assertEquals("minecraft:list_pool_element", list.get("element_type").getAsString());
        assertEquals(2, list.getAsJsonArray("elements").size());
        assertEquals("outpost:halls_processors_1", list.getAsJsonArray("elements").get(0).getAsJsonObject().get("processors").getAsString());
        assertEquals(1, pool.inlineProcessors().size());
        var rule = pool.inlineProcessors().get("outpost:halls_processors_1").getAsJsonObject("minecraft:processor_list").getAsJsonArray("processors").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals("minecraft:mossy_stone_bricks", rule.getAsJsonObject("output_state").get("name").getAsString());
        assertTrue(has(report, BedrockWorldgen.Kind.CHANGED, "inline processor list"));
    }

    // ---- processor lists

    @Test void aRuleKeepsItsPredicatesAndItsOutputInBedrocksFieldNames() {
        var report = new BedrockWorldgen.Report();
        var java = json("""
            {"processors":[{"processor_type":"minecraft:rule","rules":[
              {"input_predicate":{"block":"minecraft:cobblestone","predicate_type":"minecraft:random_block_match","probability":0.3},"location_predicate":{"block":"minecraft:water","predicate_type":"minecraft:block_match"},
               "output_state":{"Name":"minecraft:oak_planks"}},
              {"input_predicate":{"block":"minecraft:gravel","predicate_type":"minecraft:block_match"},"location_predicate":{"predicate_type":"minecraft:always_true"},
               "position_predicate":{"predicate_type":"minecraft:axis_aligned_linear_pos","axis":"y","min_chance":0.0,"max_chance":0.5,"min_dist":0,"max_dist":6},"output_state":{"Name":"minecraft:dirt"}},
              {"input_predicate":{"tag":"minecraft:logs","predicate_type":"minecraft:tag_match"},"location_predicate":{"predicate_type":"minecraft:always_true"},"output_state":{"Name":"minecraft:oak_planks"}}]}]}
            """);
        var list = BedrockWorldgen.processorList("outpost:weather", java, holds(Set.of(), Set.of(), Set.of(), Set.of()), report);
        var body = list.getAsJsonObject("minecraft:processor_list");
        assertEquals("outpost:weather", body.getAsJsonObject("description").get("identifier").getAsString());
        var rules = body.getAsJsonArray("processors").get(0).getAsJsonObject().getAsJsonArray("rules");
        assertEquals(2, rules.size(), "the rule on a Java block tag cannot be written");
        var first = rules.get(0).getAsJsonObject();
        assertEquals("minecraft:random_block_match", first.getAsJsonObject("input_predicate").get("predicate_type").getAsString());
        assertEquals(0.3, first.getAsJsonObject("input_predicate").get("probability").getAsDouble(), 1e-9);
        assertEquals("minecraft:oak_planks", first.getAsJsonObject("output_state").get("name").getAsString());
        assertEquals("minecraft:axis_aligned_linear_pos", rules.get(1).getAsJsonObject().getAsJsonObject("position_predicate").get("predicate_type").getAsString());
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "tag_match"), report.notes().toString());
    }

    @Test void aBlockIsRenamedWhereBedrockCallsItSomethingElse() {
        String expected = BedrockBlockTable.get().bedrockNameOf("minecraft:dirt_path");
        assertNotNull(expected);
        var report = new BedrockWorldgen.Report();
        var java = json("""
            {"processors":[{"processor_type":"minecraft:rule","rules":[{"input_predicate":{"block":"minecraft:dirt_path","predicate_type":"minecraft:block_match"},
              "location_predicate":{"predicate_type":"minecraft:always_true"},"output_state":{"Name":"minecraft:grass_block","Properties":{"snowy":"false"}}}]}]}
            """);
        var rule = BedrockWorldgen.processorList("a:b", java, holds(Set.of(), Set.of(), Set.of(), Set.of()), report).getAsJsonObject("minecraft:processor_list")
                .getAsJsonArray("processors").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals(expected, rule.getAsJsonObject("input_predicate").get("block").getAsString());
    }

    @Test void aBlockBedrockLacksLeavesOutTheRuleThatNeedsItAndTheProcessorsWithoutAnEquivalentAreReported() {
        var report = new BedrockWorldgen.Report();
        var java = json("""
            {"processors":[{"processor_type":"minecraft:rule","rules":[{"input_predicate":{"block":"minecraft:not_a_block","predicate_type":"minecraft:block_match"},
              "location_predicate":{"predicate_type":"minecraft:always_true"},"output_state":{"Name":"minecraft:dirt"}}]},
              {"processor_type":"minecraft:gravity","heightmap":"WORLD_SURFACE_WG","offset":-1},
              {"processor_type":"minecraft:protected_blocks","value":"#minecraft:features_cannot_replace"},
              {"processor_type":"minecraft:capped","limit":{"type":"minecraft:uniform","min_inclusive":1,"max_inclusive":3},"delegate":{"processor_type":"minecraft:block_ignore","blocks":[{"Name":"minecraft:dirt"},"minecraft:stone"]}}]}
            """);
        var processors = BedrockWorldgen.processorList("a:b", java, holds(Set.of(), Set.of(), Set.of(), Set.of()), report).getAsJsonObject("minecraft:processor_list").getAsJsonArray("processors");
        assertEquals(2, processors.size(), "the rule keeps its place, empty; gravity and protected_blocks are left out; capped stays");
        assertTrue(processors.get(0).getAsJsonObject().getAsJsonArray("rules").isEmpty());
        var capped = processors.get(1).getAsJsonObject();
        assertEquals("minecraft:capped", capped.get("processor_type").getAsString());
        assertEquals("uniform", capped.getAsJsonObject("limit").get("type").getAsString(), "Bedrock names the int provider types without a namespace");
        assertEquals(2, capped.getAsJsonObject("delegate").getAsJsonArray("blocks").size());
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "minecraft:not_a_block"));
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "minecraft:gravity"));
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "protected_blocks"));
    }

    @Test void aLootTableOnARuleIsReportedBecauseTheFilesAreDifferent() {
        var report = new BedrockWorldgen.Report();
        var java = json("""
            {"processors":[{"processor_type":"minecraft:rule","rules":[{"input_predicate":{"block":"minecraft:gravel","predicate_type":"minecraft:block_match"},
              "location_predicate":{"predicate_type":"minecraft:always_true"},"output_state":{"Name":"minecraft:suspicious_gravel"},
              "block_entity_modifier":{"type":"minecraft:append_loot","loot_table":"minecraft:archaeology/trail_ruins_common"}}]}]}
            """);
        var rule = BedrockWorldgen.processorList("a:b", java, holds(Set.of(), Set.of(), Set.of(), Set.of()), report).getAsJsonObject("minecraft:processor_list")
                .getAsJsonArray("processors").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
        assertFalse(rule.has("block_entity_modifier"));
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "append_loot"));
    }

    // ---- structures

    @Test void aVillageStyleStructureBecomesABedrockJigsawWithItsFieldsRenamed() {
        var report = new BedrockWorldgen.Report();
        var java = json("""
            {"type":"minecraft:jigsaw","biomes":"#minecraft:has_structure/village_plains","max_distance_from_center":80,"project_start_to_heightmap":"WORLD_SURFACE_WG","size":6,
             "spawn_overrides":{},"start_height":{"absolute":0},"start_pool":"outpost:halls","step":"surface_structures","terrain_adaptation":"beard_thin","use_expansion_hack":true}
            """);
        var root = BedrockWorldgen.structure("outpost:ruin", java, holds(Set.of("outpost:halls"), Set.of(), Set.of(), Set.of()), report);
        assertNotNull(root);
        var body = root.getAsJsonObject("minecraft:jigsaw");
        assertEquals("outpost:ruin", body.getAsJsonObject("description").get("identifier").getAsString());
        assertEquals(6, body.get("max_depth").getAsInt(), "Java's size is Bedrock's max_depth");
        assertEquals("constant", body.getAsJsonObject("start_height").get("type").getAsString());
        assertEquals(0, body.getAsJsonObject("start_height").getAsJsonObject("value").get("absolute").getAsInt());
        assertEquals("world_surface", body.get("heightmap_projection").getAsString());
        assertEquals(80, body.getAsJsonObject("max_distance_from_center").get("horizontal").getAsInt());
        assertEquals(80, body.getAsJsonObject("max_distance_from_center").get("vertical").getAsInt());
        var filter = body.getAsJsonArray("biome_filters").get(0).getAsJsonObject();
        assertEquals("has_biome_tag", filter.get("test").getAsString());
        assertEquals("has_structure_village_plains", filter.get("value").getAsString());
        assertEquals("beard_thin", body.get("terrain_adaptation").getAsString());
        assertTrue(has(report, BedrockWorldgen.Kind.CHANGED, "WORLD_SURFACE_WG"));
        assertTrue(has(report, BedrockWorldgen.Kind.CHECK, "use_expansion_hack"));
    }

    @Test void theOverworldTagAndAListOfBiomesAndAHeightRangeAreWrittenAsBedrockHasThem() {
        var report = new BedrockWorldgen.Report();
        var java = json("""
            {"type":"minecraft:jigsaw","biomes":["#minecraft:is_overworld","#minecraft:is_nether"],"size":20,"start_pool":"a:b","step":"underground_structures","terrain_adaptation":"encapsulate",
             "start_height":{"type":"minecraft:uniform","min_inclusive":{"absolute":-40},"max_inclusive":{"absolute":-20}},"max_distance_from_center":{"horizontal":116,"vertical":60},
             "dimension_padding":10,"liquid_settings":"ignore_waterlogging","start_jigsaw_name":"a:door",
             "pool_aliases":[{"type":"minecraft:direct","alias":"a:x","target":"a:y"}]}
            """);
        var body = BedrockWorldgen.structure("a:deep", java, holds(Set.of("a:b"), Set.of(), Set.of(), Set.of()), report).getAsJsonObject("minecraft:jigsaw");
        var any = body.getAsJsonArray("biome_filters").get(0).getAsJsonObject().getAsJsonArray("any_of");
        assertEquals("overworld", any.get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("nether", any.get(1).getAsJsonObject().get("value").getAsString());
        var height = body.getAsJsonObject("start_height");
        assertEquals("uniform", height.get("type").getAsString());
        assertEquals(-40, height.getAsJsonObject("min").get("absolute").getAsInt());
        assertEquals(-20, height.getAsJsonObject("max").get("absolute").getAsInt());
        assertEquals(116, body.getAsJsonObject("max_distance_from_center").get("horizontal").getAsInt());
        assertEquals(10, body.get("dimension_padding").getAsInt());
        assertEquals("ignore_waterlogging", body.get("liquid_settings").getAsString());
        assertEquals("a:door", body.get("start_jigsaw_name").getAsString());
        assertEquals("direct", body.getAsJsonArray("pool_aliases").get(0).getAsJsonObject().get("type").getAsString());
        assertFalse(body.has("heightmap_projection"), "no heightmap in Java is none in Bedrock");
    }

    @Test void aStructureThatStartsOutsideThePackIsNotWritten() {
        var report = new BedrockWorldgen.Report();
        var java = json("{\"type\":\"minecraft:jigsaw\",\"start_pool\":\"minecraft:village/plains/town_centers\",\"size\":6,\"start_height\":{\"absolute\":0}}");
        assertNull(BedrockWorldgen.structure("a:v", java, holds(Set.of(), Set.of(), Set.of(), Set.of()), report));
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "minecraft:village/plains/town_centers"));
    }

    @Test void aHeightProviderBedrockLacksFallsBackToZeroAndIsReported() {
        var report = new BedrockWorldgen.Report();
        var java = json("{\"type\":\"minecraft:jigsaw\",\"start_pool\":\"a:b\",\"size\":2,\"start_height\":{\"type\":\"minecraft:trapezoid\",\"min_inclusive\":{\"absolute\":0},\"max_inclusive\":{\"absolute\":9}}}");
        var body = BedrockWorldgen.structure("a:t", java, holds(Set.of("a:b"), Set.of(), Set.of(), Set.of()), report).getAsJsonObject("minecraft:jigsaw");
        assertEquals(0, body.getAsJsonObject("start_height").getAsJsonObject("value").get("absolute").getAsInt());
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "trapezoid"));
    }

    // ---- structure sets

    @Test void aStructureSetKeepsItsPlacementAndOnlyTheStructuresThatAreInThePack() {
        var report = new BedrockWorldgen.Report();
        var java = json("""
            {"placement":{"type":"minecraft:random_spread","salt":10387312,"separation":8,"spacing":34},
             "structures":[{"structure":"outpost:ruin","weight":2},{"structure":"minecraft:village_plains","weight":1}]}
            """);
        var root = BedrockWorldgen.structureSet("outpost:ruin", java, holds(Set.of(), Set.of(), Set.of(), Set.of("outpost:ruin")), report);
        var body = root.getAsJsonObject("minecraft:structure_set");
        var placement = body.getAsJsonObject("placement");
        assertEquals("minecraft:random_spread", placement.get("type").getAsString());
        assertEquals(34, placement.get("spacing").getAsInt());
        assertEquals(8, placement.get("separation").getAsInt());
        assertEquals(10387312, placement.get("salt").getAsInt());
        assertEquals("linear", placement.get("spread_type").getAsString());
        assertEquals(1, body.getAsJsonArray("structures").size());
        assertEquals(2, body.getAsJsonArray("structures").get(0).getAsJsonObject().get("weight").getAsInt());
        assertTrue(has(report, BedrockWorldgen.Kind.LOST, "minecraft:village_plains"));
        assertFalse(has(report, BedrockWorldgen.Kind.CHECK, "separation"), "8 is below half of 34");
    }

    @Test void aSeparationAtOrAboveHalfTheSpacingIsFlaggedAndASetWithNothingLeftIsNotWritten() {
        var report = new BedrockWorldgen.Report();
        var close = json("{\"placement\":{\"type\":\"minecraft:random_spread\",\"salt\":1,\"separation\":10,\"spacing\":20},\"structures\":[{\"structure\":\"a:s\",\"weight\":1}]}");
        assertNotNull(BedrockWorldgen.structureSet("a:s", close, holds(Set.of(), Set.of(), Set.of(), Set.of("a:s")), report));
        assertTrue(has(report, BedrockWorldgen.Kind.CHECK, "separation 10"));
        assertNull(BedrockWorldgen.structureSet("a:s", close, holds(Set.of(), Set.of(), Set.of(), Set.of()), new BedrockWorldgen.Report()));
        var rings = json("{\"placement\":{\"type\":\"minecraft:concentric_rings\"},\"structures\":[]}");
        assertNull(BedrockWorldgen.structureSet("a:s", rings, holds(Set.of(), Set.of(), Set.of(), Set.of()), report));
    }
}
