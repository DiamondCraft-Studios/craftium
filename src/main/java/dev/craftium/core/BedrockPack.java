package dev.craftium.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A Bedrock behavior pack as one {@code .mcpack} file: a zip with {@code manifest.json} at its root next to the folders of the pack ({@code worldgen} and
 * {@code structures}), which Bedrock imports when the file is opened. The two UUIDs of the manifest are made fresh for every export. It knows nothing about
 * Minecraft classes.
 */
public final class BedrockPack {
    private BedrockPack() {}

    /** The oldest game the pack asks for: 1.26.30. */
    public static final int[] MIN_ENGINE_VERSION = {1, 26, 30};
    public static final int[] PACK_VERSION = {1, 0, 0};

    private static JsonArray numbers(int[] values) {
        var array = new JsonArray();
        for (int v : values) array.add(v);
        return array;
    }

    /** The manifest of a behavior pack that holds data (worldgen and structure files only). */
    public static JsonObject manifest(String name, String description, UUID header, UUID module) {
        var headerObject = new JsonObject();
        headerObject.addProperty("name", name);
        headerObject.addProperty("description", description);
        headerObject.addProperty("uuid", header.toString());
        headerObject.add("version", numbers(PACK_VERSION));
        headerObject.add("min_engine_version", numbers(MIN_ENGINE_VERSION));
        var moduleObject = new JsonObject();
        moduleObject.addProperty("type", "data");
        moduleObject.addProperty("uuid", module.toString());
        moduleObject.add("version", numbers(PACK_VERSION));
        var modules = new JsonArray();
        modules.add(moduleObject);
        var authors = new JsonArray();
        authors.add("Craftium");
        var metadata = new JsonObject();
        metadata.add("authors", authors);
        var root = new JsonObject();
        root.addProperty("format_version", 2);
        root.add("header", headerObject);
        root.add("modules", modules);
        root.add("metadata", metadata);
        return root;
    }

    /**
     * The pack as zip bytes: the manifest first, then every file by path in order. {@code uuids} makes the two ids (random in a real export, fixed in a test).
     * A path is relative to the root of the pack and uses {@code /}.
     */
    public static byte[] zip(String name, String description, Map<String, byte[]> files, Supplier<UUID> uuids) throws IOException {
        var out = new ByteArrayOutputStream();
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            add(zip, "manifest.json", gson.toJson(manifest(name, description, uuids.get(), uuids.get())).getBytes(StandardCharsets.UTF_8));
            for (var entry : new TreeMap<>(files).entrySet()) {
                String path = entry.getKey();
                if (path.startsWith("/") || path.contains("..") || path.contains("\\") || path.equals("manifest.json")) throw new IllegalArgumentException("Not a path inside the pack: " + path);
                add(zip, path, entry.getValue());
            }
        }
        return out.toByteArray();
    }

    private static void add(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        var entry = new ZipEntry(path);
        entry.setTime(0); // the same bytes for the same pack, whenever it is made (the two UUIDs apart)
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * The name offered for a pack that starts from this pool ({@code namespace:path}, a bare name being in craftium): the first part of its path, or, for a pool
     * with no folder, the namespace (a pool is usually named for what it is, such as halls or start, and the namespace for the set it belongs to) unless that is
     * craftium or minecraft, when it is the pool's own name. Written as words with a capital.
     */
    public static String defaultName(String pool) {
        String id = pool == null ? "" : pool.strip();
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "craftium" : id.substring(0, colon), path = colon < 0 ? id : id.substring(colon + 1);
        String base = path.contains("/") ? path.substring(0, path.indexOf('/')) : namespace.equals("craftium") || namespace.equals("minecraft") ? path : namespace;
        String words = base.replace('_', ' ').replace('-', ' ').strip();
        return words.isEmpty() ? "Craftium pack" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The name of the {@code .mcpack} file for a pack called {@code name}: letters, digits, spaces, dots, dashes and underscores only. */
    public static String fileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9 ._-]+", "_").strip().replaceAll("^[._]+|[. ]+$", "");
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60).strip();
        return (cleaned.isEmpty() ? "craftium_pack" : cleaned) + ".mcpack";
    }
}
