package dev.craftium.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.craftium.core.BedrockBlockTable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Java block entity data as Bedrock's {@code block_entity_data} for the kinds whose Bedrock layout is written down (the Minecraft Wiki's Bedrock block entity
 * and item pages): containers (chests, trapped chests, barrels) with their stacks and loot table, and signs with their text. Anything else is left to the
 * caller to report. What a conversion cannot carry (a stack's enchantments or name, a loot table of a datapack, a double chest's pairing) is returned as notes,
 * and so are the conversions that rest on a naming rule that could not be checked without a Bedrock game.
 */
final class BedrockBlockEntities {
    private BedrockBlockEntities() {}

    /** The data to write, and what the player is told about it. */
    record Converted(Map<String, Object> data, List<String> lost, List<String> check) {}

    /** Whether this block's data is converted at all, so the caller can count the rest as left out. */
    static boolean handles(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL) || state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.ALL_HANGING_SIGNS);
    }

    /** The conversion of {@code java}, the data of the block at x, y, z (where the file puts it), or null when {@link #handles} is false. */
    static Converted convert(BlockState state, CompoundTag java, int x, int y, int z) {
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)) return container(state, java, x, y, z);
        if (state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.ALL_HANGING_SIGNS)) return sign(state, java, x, y, z);
        return null;
    }

    // ---- containers

    private static Converted container(BlockState state, CompoundTag java, int x, int y, int z) {
        var lost = new ArrayList<String>();
        var check = new ArrayList<String>();
        var data = new LinkedHashMap<String, Object>();
        data.put("Findable", (byte) 0);
        data.put("id", state.is(Blocks.BARREL) ? "Barrel" : "Chest");
        var items = new ArrayList<Object>();
        int stacks = 0;
        var source = java.getListOrEmpty("Items");
        for (int i = 0; i < source.size(); i++) {
            var stack = source.getCompoundOrEmpty(i);
            String id = stack.getStringOr("id", "minecraft:air");
            if (id.equals("minecraft:air")) continue;
            var out = new LinkedHashMap<String, Object>();
            int count = stack.contains("count") ? stack.getIntOr("count", 1) : stack.getByteOr("Count", (byte) 1);
            out.put("Count", (byte) Math.clamp(count, 1, 127));
            out.put("Damage", (short) 0);
            out.put("Name", bedrockItem(id));
            out.put("Slot", (byte) stack.getByteOr("Slot", (byte) 0));
            out.put("WasPickedUp", (byte) 0);
            items.add(out);
            stacks++;
            if (!stack.getCompoundOrEmpty("components").isEmpty() || stack.contains("tag"))
                lost.add("a stack of " + id + " keeps its item but not its extra data (enchantments, names, potion effects and the like)");
        }
        data.put("Items", items);
        if (stacks > 0) check.add("the items of containers are written by their Java names; a few items are named differently in Bedrock, and an unknown one comes out empty");
        if (java.contains("LootTable")) {
            String table = java.getStringOr("LootTable", "");
            if (table.startsWith("minecraft:")) {
                data.put("LootTable", "loot_tables/" + table.substring(10) + ".json");
                check.add("the loot table " + table + " was written as Bedrock's loot_tables/" + table.substring(10) + ".json; most of the game's tables have the same name in Bedrock, but check");
            } else lost.add("the loot table " + table + " is not a game table, and Java and Bedrock loot tables are different files, so the container is left without it");
            int seed = (int) java.getLongOr("LootTableSeed", 0L);
            if (seed != 0) data.put("LootTableSeed", seed);
        }
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
            var type = state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE) ? state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE).getSerializedName() : "single";
            if (!type.equals("single")) lost.add("half of a double chest keeps its contents but is not paired with the other half in Bedrock (pairing is not written)");
        }
        data.put("x", x);
        data.put("y", y);
        data.put("z", z);
        return new Converted(data, lost, check);
    }

    private static String bedrockItem(String javaId) {
        String block = BedrockBlockTable.get().bedrockNameOf(javaId);
        return block != null ? block : javaId;
    }

    // ---- signs

    private static Converted sign(BlockState state, CompoundTag java, int x, int y, int z) {
        var lost = new ArrayList<String>();
        var check = new ArrayList<String>();
        var data = new LinkedHashMap<String, Object>();
        data.put("BackText", face(java.getCompoundOrEmpty("back_text"), lost, check));
        data.put("FrontText", face(java.getCompoundOrEmpty("front_text"), lost, check));
        data.put("IsWaxed", (byte) java.getByteOr("is_waxed", (byte) 0));
        data.put("id", state.is(BlockTags.ALL_HANGING_SIGNS) ? "HangingSign" : "Sign");
        data.put("x", x);
        data.put("y", y);
        data.put("z", z);
        return new Converted(data, lost, check);
    }

    private static Map<String, Object> face(CompoundTag java, List<String> lost, List<String> check) {
        var out = new LinkedHashMap<String, Object>();
        var lines = new ArrayList<String>();
        var messages = java.getListOrEmpty("messages");
        for (int i = 0; i < messages.size(); i++) {
            var line = new StringBuilder();
            if (!text(messages.get(i), line)) lost.add("sign text that is not plain (a translation or a keybind) is left out");
            lines.add(line.toString());
        }
        while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        boolean glow = java.getByteOr("has_glowing_text", (byte) 0) != 0;
        String colour = java.getStringOr("color", "black");
        int argb = -16777216;
        if (!colour.equals("black")) {
            argb = 0xFF000000 | DyeColor.byName(colour, DyeColor.BLACK).getTextColor();
            check.add("the sign colour " + colour + " was written with Java's dye colour; check how it looks in Bedrock");
        }
        out.put("HideGlowOutline", (byte) 0);
        out.put("IgnoreLighting", (byte) (glow ? 1 : 0));
        out.put("PersistFormatting", (byte) 1);
        out.put("SignTextColor", argb);
        out.put("Text", String.join("\n", lines));
        out.put("TextOwner", "");
        return out;
    }

    /** Appends the plain text of a Java text component (a string, JSON text or a compound), and says whether all of it was plain. */
    static boolean text(Tag tag, StringBuilder out) {
        if (tag instanceof StringTag string) {
            String value = string.value();
            if (value.startsWith("{") || value.startsWith("[") || value.startsWith("\"")) {
                try { return json(JsonParser.parseString(value), out); } catch (RuntimeException e) { out.append(value); return true; }
            }
            out.append(value);
            return true;
        }
        if (tag instanceof CompoundTag compound) {
            boolean plain = !compound.contains("translate") && !compound.contains("keybind") && !compound.contains("score") && !compound.contains("selector");
            out.append(compound.getStringOr("text", ""));
            var extra = compound.getListOrEmpty("extra");
            for (int i = 0; i < extra.size(); i++) plain &= text(extra.get(i), out);
            return plain;
        }
        if (tag instanceof ListTag list) {
            boolean plain = true;
            for (int i = 0; i < list.size(); i++) plain &= text(list.get(i), out);
            return plain;
        }
        return true;
    }

    private static boolean json(JsonElement element, StringBuilder out) {
        if (element.isJsonPrimitive()) { out.append(element.getAsString()); return true; }
        if (element.isJsonArray()) { boolean plain = true; for (var each : element.getAsJsonArray()) plain &= json(each, out); return plain; }
        if (!element.isJsonObject()) return true;
        var object = element.getAsJsonObject();
        boolean plain = !object.has("translate") && !object.has("keybind") && !object.has("score") && !object.has("selector");
        if (object.has("text")) out.append(object.get("text").getAsString());
        if (object.has("extra")) plain &= json(object.get("extra"), out);
        return plain;
    }
}
