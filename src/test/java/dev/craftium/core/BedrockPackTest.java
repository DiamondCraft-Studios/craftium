package dev.craftium.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BedrockPackTest {
    private static Map<String, String> read(byte[] zip) throws Exception {
        var out = new LinkedHashMap<String, String>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) out.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test void theManifestIsABehaviorPackThatAsksForGame1_26_30() {
        var header = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var module = UUID.fromString("22222222-2222-4222-8222-222222222222");
        var manifest = BedrockPack.manifest("Outpost", "Made with Craftium", header, module);
        assertEquals(2, manifest.get("format_version").getAsInt());
        var head = manifest.getAsJsonObject("header");
        assertEquals("Outpost", head.get("name").getAsString());
        assertEquals(header.toString(), head.get("uuid").getAsString());
        assertEquals("[1,26,30]", head.getAsJsonArray("min_engine_version").toString());
        assertEquals("[1,0,0]", head.getAsJsonArray("version").toString());
        var data = manifest.getAsJsonArray("modules").get(0).getAsJsonObject();
        assertEquals("data", data.get("type").getAsString());
        assertEquals(module.toString(), data.get("uuid").getAsString());
        assertNotEquals(head.get("uuid"), data.get("uuid"), "the pack and its module never share an id");
    }

    @Test void theZipHasTheManifestFirstAndEveryFileAtItsPath() throws Exception {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("worldgen/template_pools/outpost_hall.json", "{}".getBytes(StandardCharsets.UTF_8));
        files.put("structures/outpost/hall.mcstructure", new byte[]{1, 2, 3});
        var zip = BedrockPack.zip("Outpost", "d", files, UUID::randomUUID);
        var entries = read(zip);
        assertEquals(List.of("manifest.json", "structures/outpost/hall.mcstructure", "worldgen/template_pools/outpost_hall.json"), new ArrayList<>(entries.keySet()));
        var manifest = JsonParser.parseString(entries.get("manifest.json")).getAsJsonObject();
        assertEquals("Outpost", manifest.getAsJsonObject("header").get("name").getAsString());
    }

    @Test void everyExportGetsFreshIds() throws Exception {
        var one = JsonParser.parseString(read(BedrockPack.zip("A", "d", Map.of(), UUID::randomUUID)).get("manifest.json")).getAsJsonObject();
        var two = JsonParser.parseString(read(BedrockPack.zip("A", "d", Map.of(), UUID::randomUUID)).get("manifest.json")).getAsJsonObject();
        assertNotEquals(one.getAsJsonObject("header").get("uuid"), two.getAsJsonObject("header").get("uuid"));
        UUID.fromString(one.getAsJsonObject("header").get("uuid").getAsString());
        UUID.fromString(one.getAsJsonArray("modules").get(0).getAsJsonObject().get("uuid").getAsString());
    }

    @Test void aPathOutsideThePackIsRefused() {
        for (String bad : new String[]{"/etc/x", "../x", "a\\b", "manifest.json"})
            assertThrows(IllegalArgumentException.class, () -> BedrockPack.zip("A", "d", Map.of(bad, new byte[0]), UUID::randomUUID), bad);
    }

    @Test void theOfferedNameComesFromTheFirstPartOfThePoolsPath() {
        assertEquals("Outpost", BedrockPack.defaultName("craftium:outpost/hall"));
        assertEquals("My ruin", BedrockPack.defaultName("craftium:my_ruin"));
        assertEquals("Dungeon", BedrockPack.defaultName("dungeon:start"));
        assertEquals("Outpost", BedrockPack.defaultName("outpost:halls"));
        assertEquals("Villages", BedrockPack.defaultName("villages/plains"));
        assertEquals("Tower", BedrockPack.defaultName("tower"));
        assertEquals("Craftium pack", BedrockPack.defaultName(""));
        assertEquals("Craftium pack", BedrockPack.defaultName(null));
    }

    @Test void theFileNameKeepsOnlySafeCharacters() {
        assertEquals("Outpost.mcpack", BedrockPack.fileName("Outpost"));
        assertEquals("My ruin_1.mcpack", BedrockPack.fileName("My ruin:*?1"));
        assertEquals("craftium_pack.mcpack", BedrockPack.fileName("  "));
        assertEquals("craftium_pack.mcpack", BedrockPack.fileName(null));
        assertTrue(BedrockPack.fileName("x".repeat(200)).length() <= 67);
    }
}
