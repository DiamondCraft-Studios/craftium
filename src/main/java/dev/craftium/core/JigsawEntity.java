package dev.craftium.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The data of a jigsaw block, which is a block entity on both editions: Java keeps it in a template's block NBT, Bedrock in the
 * {@code block_position_data} of a {@code .mcstructure}. A structure file without it keeps the block and loses every connector.
 * Like {@link BedrockBlockTable} it knows nothing of Minecraft classes, so it can be tested on its own.
 * <p>The Bedrock layout is what a piece saved by Bedrock's own structure block holds (checked against Microsoft's basic dungeon
 * sample): {@code id "JigsawBlock"}, {@code isMovable} as a byte, the two priorities and {@code x, y, z} as ints, and the rest as
 * strings, with the pool under {@code target_pool} where Java says {@code pool}.
 */
public final class JigsawEntity {
    private JigsawEntity() {}

    public static final String EMPTY = "minecraft:empty", AIR = "minecraft:air", ROLLABLE = "rollable";
    /** The id of the block entity in a Bedrock file. */
    public static final String BEDROCK_ID = "JigsawBlock";

    /** What a jigsaw says, with {@code finalState} as the block it turns into. */
    public record Fields(String name, String target, String pool, String joint, String finalState, int selectionPriority, int placementPriority) {}

    /**
     * The {@code block_entity_data} of a jigsaw at the world position x, y, z. The final state keeps its block but not its
     * states (the sample's Bedrock files hold a block name only); {@code lossy}, when given, is set when something was dropped
     * or had no Bedrock block.
     */
    public static Map<String, Object> toBedrock(Fields fields, int x, int y, int z, BedrockBlockTable table, boolean[] lossy) {
        var data = new LinkedHashMap<String, Object>();
        data.put("final_state", bedrockBlock(fields.finalState(), table, lossy));
        data.put("id", BEDROCK_ID);
        data.put("isMovable", (byte) 1);
        data.put("joint", fields.joint());
        data.put("name", fields.name());
        data.put("placement_priority", fields.placementPriority());
        data.put("selection_priority", fields.selectionPriority());
        data.put("target", fields.target());
        data.put("target_pool", fields.pool());
        data.put("x", x);
        data.put("y", y);
        data.put("z", z);
        return data;
    }

    /** The fields of a Bedrock jigsaw's {@code block_entity_data}, with Java's defaults for what is missing. */
    public static Fields fromBedrock(Map<String, Object> data, BedrockBlockTable table, boolean[] lossy) {
        return new Fields(text(data, "name", EMPTY), text(data, "target", EMPTY), text(data, "target_pool", EMPTY), text(data, "joint", ROLLABLE),
                javaBlock(text(data, "final_state", AIR), table, lossy), number(data, "selection_priority"), number(data, "placement_priority"));
    }

    /** True for the block entity of a jigsaw in a Bedrock file. */
    public static boolean isBedrockJigsaw(Map<String, Object> data) {
        return data != null && BEDROCK_ID.equals(data.get("id"));
    }

    /** A Java state such as {@code minecraft:oak_stairs[facing=north]} as a Bedrock block name. */
    private static String bedrockBlock(String state, BedrockBlockTable table, boolean[] lossy) {
        int open = state.indexOf('[');
        String name = open < 0 ? state : state.substring(0, open);
        if (name.isEmpty()) return AIR;
        var properties = new TreeMap<String, String>();
        if (open >= 0 && state.endsWith("]") && open + 1 < state.length() - 1)
            for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) properties.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        if (!properties.isEmpty()) flag(lossy);
        var mapped = table == null ? null : table.toBedrock(name, properties);
        if (mapped != null) return mapped.name();
        // not in the table as written (a stair needs its facing): the name is the same on both editions for nearly every block
        flag(lossy);
        return name;
    }

    /** A Bedrock block name as a Java block name, which is the block's usual state: a Bedrock file holds no states here. */
    private static String javaBlock(String bedrockName, BedrockBlockTable table, boolean[] lossy) {
        var java = table == null ? null : table.toJava(bedrockName, Map.of(), null);
        if (java != null) return java.name();
        flag(lossy);
        return bedrockName.indexOf(':') < 0 ? "minecraft:" + bedrockName : bedrockName;
    }

    private static void flag(boolean[] lossy) {
        if (lossy != null && lossy.length > 0) lossy[0] = true;
    }

    private static String text(Map<String, Object> data, String key, String fallback) {
        return data.get(key) instanceof String s && !s.isEmpty() ? s : fallback;
    }

    private static int number(Map<String, Object> data, String key) {
        return data.get(key) instanceof Number n ? n.intValue() : 0;
    }
}
