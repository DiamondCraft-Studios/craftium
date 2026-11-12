package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The expected layout is what Bedrock's own structure block saved in Microsoft's basic dungeon sample (a piece named nscap): the block
 * minecraft:jigsaw with facing_direction and rotation, and a block entity with exactly these keys and tag types.
 */
class JigsawEntityTest {
    private static final Set<String> SAMPLE_KEYS = Set.of("final_state", "id", "isMovable", "joint", "name", "placement_priority", "selection_priority", "target", "target_pool", "x", "y", "z");
    private static final JigsawEntity.Fields FIELDS = new JigsawEntity.Fields("mike:ns7x4", "mike:ns7x4", "mike:ns7x4", "rollable", "minecraft:stone_bricks", 2, 1);

    @Test void bedrockDataHasTheKeysAndTypesBedrockWrites() {
        var lossy = new boolean[1];
        var data = JigsawEntity.toBedrock(FIELDS, -5, -60, 17, BedrockBlockTable.get(), lossy);
        assertEquals(SAMPLE_KEYS, data.keySet());
        assertEquals("JigsawBlock", data.get("id"));
        assertEquals((byte) 1, data.get("isMovable"));
        assertEquals("mike:ns7x4", data.get("target_pool"));
        assertEquals("minecraft:stone_bricks", data.get("final_state"));
        assertEquals(2, data.get("selection_priority"));
        assertEquals(1, data.get("placement_priority"));
        assertEquals(-60, data.get("y"));
        assertFalse(lossy[0], "a plain block loses nothing");
    }

    @Test void aFinalStateKeepsItsBlockAndSaysThatItsStatesWereDropped() {
        var lossy = new boolean[1];
        var fields = new JigsawEntity.Fields("a", "b", "c", "aligned", "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", 0, 0);
        var data = JigsawEntity.toBedrock(fields, 0, 0, 0, BedrockBlockTable.get(), lossy);
        assertEquals("minecraft:oak_stairs", data.get("final_state"));
        assertEquals("aligned", data.get("joint"));
        assertTrue(lossy[0]);
    }

    @Test void theJigsawBlockMapsToBedrockStatesTheSampleUses() {
        var table = BedrockBlockTable.get();
        var north = table.toBedrock("minecraft:jigsaw", Map.of("orientation", "north_up"));
        var south = table.toBedrock("minecraft:jigsaw", Map.of("orientation", "south_up"));
        assertEquals("minecraft:jigsaw", north.name());
        assertEquals(2, north.states().get("facing_direction"));
        assertEquals(0, north.states().get("rotation"));
        assertEquals(3, south.states().get("facing_direction"));
        var back = table.toJava("minecraft:jigsaw", north.states(), new boolean[1]);
        assertEquals("north_up", back.properties().get("orientation"));
    }

    @Test void fieldsSurviveJavaToBedrockAndBack() {
        var data = JigsawEntity.toBedrock(FIELDS, 1, 2, 3, BedrockBlockTable.get(), null);
        var back = JigsawEntity.fromBedrock(data, BedrockBlockTable.get(), null);
        assertEquals(FIELDS, back);
    }

    @Test void missingFieldsTakeJavasDefaults() {
        var back = JigsawEntity.fromBedrock(new HashMap<>(), BedrockBlockTable.get(), null);
        assertEquals(new JigsawEntity.Fields("minecraft:empty", "minecraft:empty", "minecraft:empty", "rollable", "minecraft:air", 0, 0), back);
    }

    @Test void theDataSurvivesAStructureFilesNbt() {
        var data = JigsawEntity.toBedrock(FIELDS, -5, -60, 17, BedrockBlockTable.get(), null);
        var positions = new LinkedHashMap<String, Object>();
        positions.put("36", new LinkedHashMap<String, Object>(Map.of("block_entity_data", data)));
        var defaults = new LinkedHashMap<String, Object>();
        defaults.put("block_palette", new ArrayList<Object>());
        defaults.put("block_position_data", positions);
        var root = new LinkedHashMap<String, Object>();
        root.put("palette", new LinkedHashMap<String, Object>(Map.of("default", defaults)));
        var back = BedrockNbt.read(BedrockNbt.write(root));
        @SuppressWarnings("unchecked") var entity = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) back.get("palette")).get("default"))
                .get("block_position_data")).get("36");
        @SuppressWarnings("unchecked") var read = (Map<String, Object>) entity.get("block_entity_data");
        assertTrue(JigsawEntity.isBedrockJigsaw(read));
        assertEquals((byte) 1, read.get("isMovable"));
        assertEquals(FIELDS, JigsawEntity.fromBedrock(read, BedrockBlockTable.get(), null));
        assertFalse(JigsawEntity.isBedrockJigsaw(Map.of("id", "Chest")));
        assertFalse(JigsawEntity.isBedrockJigsaw(null));
    }
}
