package dev.craftium.server;

import com.google.gson.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * The pool editor's side of the server. A pool is edited as the JSON of its file, so anything the editor does not understand (a custom element type, an
 * inline processor list) is kept exactly as written; every change is written to the craftium datapack (a pool from another pack is overridden there,
 * under the same name) and so reaches the live preview through the file watcher. Templates are changed through their NBT. Each command is one undo step.
 *
 * <p>A command is a JSON object with an {@code op}; the answer is a model (also JSON) of the pools, the pool being edited and the pools it leads to.
 */
final class JigsawEditor {
    private JigsawEditor() {}

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String POOLS = "worldgen/template_pool/";
    private static final int THUMBNAIL_VOLUME = 60_000, THUMBNAILS = 30, MAX_TEMPLATES = 80;

    /** The bytes a file had before a command changed it (null: it was not there), so the command can be undone. */
    private record Snap(Path file, byte[] bytes) {}

    static final class Memory {
        String pool = "", query = "", message = "";
        /** Set by the names command: the next model carries only the list of pools, which is all an autocomplete wants. */
        boolean names;
        /** What a retarget would change, for the player to confirm: set by the check, shown in the next model, forgotten by any other command. */
        JsonObject retarget;
        final Deque<List<Snap>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    }
    private static final Map<UUID, Memory> MEMORY = new ConcurrentHashMap<>();
    static Memory memory(ServerPlayer player) { return MEMORY.computeIfAbsent(player.getUUID(), id -> new Memory()); }

    /** The files a command changes, written through here so that they can be taken back. */
    private static final class Edit {
        final List<Snap> snaps = new ArrayList<>();
        void write(Path file, byte[] data) throws IOException {
            snaps.add(new Snap(file, Files.exists(file) ? Files.readAllBytes(file) : null));
            Files.createDirectories(file.getParent());
            Files.write(file, data);
        }
        void delete(Path file) throws IOException {
            if (!Files.exists(file)) return;
            snaps.add(new Snap(file, Files.readAllBytes(file)));
            Files.delete(file);
        }
    }

    // ---- entry point

    /** Does one command and sends the player the model that results. Throws {@link IllegalArgumentException} with the reason when it cannot be done. */
    static String act(ServerPlayer player, String json) {
        var memory = memory(player);
        JsonObject command;
        try { command = JsonParser.parseString(json.isBlank() ? "{\"op\":\"open\"}" : json).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("The pool editor could not read that command"); }
        try {
            var server = player.level().getServer();
            JigsawPlanner.withPacks(server, env -> {
                var edit = new Edit();
                memory.message = run(player, env, memory, edit, command);
                if (!edit.snaps.isEmpty()) { memory.undo.push(edit.snaps); memory.redo.clear(); }
                return null;
            });
            // The answer is worked out with the packs opened again, so a draft this command has just made is part of what the player is shown.
            JigsawPlanner.withPacks(server, env -> { EditorServer.session(player).jigsawModel(model(player, env, memory)); return null; });
            return memory.message;
        } catch (IOException e) {
            throw new IllegalArgumentException("The pool editor could not read or write its files: " + e.getMessage());
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IllegalArgumentException("A pool or template file could not be read: " + e.getMessage());
        }
    }

    private static String run(ServerPlayer player, JigsawPlanner.Env env, Memory memory, Edit edit, JsonObject c) throws IOException {
        String op = text(c, "op");
        if (!op.equals("retarget_check")) memory.retarget = null;
        switch (op) {
            case "open" -> { if (c.has("pool")) memory.pool = poolId(text(c, "pool")).toString(); return ""; }
            case "templates" -> { memory.query = c.has("query") ? text(c, "query") : ""; return ""; }
            case "undo" -> { return restore(memory.undo, memory.redo, "Undid the last change"); }
            case "redo" -> { return restore(memory.redo, memory.undo, "Redid the change"); }
            case "pool_new" -> {
                var id = poolId(text(c, "id"));
                if (exists(env, id)) throw new IllegalArgumentException("A pool called " + id + " already exists");
                var pool = new JsonObject();
                pool.addProperty("fallback", "minecraft:empty");
                pool.add("elements", new JsonArray());
                writePool(env, edit, id, pool);
                memory.pool = id.toString();
                return "Made pool " + id;
            }
            case "pool_copy" -> {
                var from = poolId(text(c, "from")); var to = poolId(text(c, "to"));
                if (exists(env, to)) throw new IllegalArgumentException("A pool called " + to + " already exists");
                writePool(env, edit, to, readPool(env, from));
                memory.pool = to.toString();
                return "Copied " + from + " to " + to;
            }
            case "pool_rename" -> {
                var from = poolId(text(c, "from")); var to = poolId(text(c, "to"));
                if (!ours(env, from)) throw new IllegalArgumentException("Only a pool in the craftium pack can be renamed; copy it first");
                if (exists(env, to)) throw new IllegalArgumentException("A pool called " + to + " already exists");
                writePool(env, edit, to, readPool(env, from));
                removePool(env, edit, from);
                memory.pool = to.toString();
                return "Renamed " + from + " to " + to + ". Connectors that point at the old name are not changed";
            }
            case "pool_delete" -> {
                var id = poolId(text(c, "id"));
                if (!ours(env, id)) throw new IllegalArgumentException("Only a pool in the craftium pack can be deleted");
                removePool(env, edit, id);
                if (memory.pool.equals(id.toString())) memory.pool = "";
                return "Deleted " + id + " (final when you apply)";
            }
            case "description_set" -> {
                var id = poolId(text(c, "pool"));
                var pool = readPool(env, id);
                var note = text(c, "text").strip();
                if (note.length() > 1000) throw new IllegalArgumentException("A description is at most 1,000 characters");
                // Kept as $comment, which the game ignores, and written first so it is the first thing a person reading the file sees.
                var ordered = new JsonObject();
                if (!note.isEmpty()) ordered.addProperty("$comment", note);
                for (var entry : pool.entrySet()) if (!entry.getKey().equals("$comment")) ordered.add(entry.getKey(), entry.getValue());
                writePool(env, edit, id, ordered);
                return note.isEmpty() ? "Removed the description of " + id : "Described " + id;
            }
            case "fallback_set" -> {
                var id = poolId(text(c, "pool"));
                var pool = readPool(env, id);
                pool.addProperty("fallback", poolId(text(c, "fallback")).toString());
                writePool(env, edit, id, pool);
                return "Set the fallback of " + id;
            }
            case "element_add" -> {
                var id = poolId(text(c, "pool"));
                var pool = readPool(env, id);
                pool.getAsJsonArray("elements").add(single(text(c, "template"), c.has("weight") ? c.get("weight").getAsInt() : 1, c.has("projection") ? text(c, "projection") : "rigid"));
                writePool(env, edit, id, pool);
                return "Added " + text(c, "template") + " to " + id;
            }
            case "element_from_selection" -> {
                var selection = EditorServer.session(player).selection;
                if (selection.size() == 0) throw new IllegalArgumentException("Select the blocks to turn into a template first");
                var min = selection.min(); var max = selection.max();
                var name = poolId(text(c, "name"));
                var size = new Vec3i(max.x() - min.x() + 1, max.y() - min.y() + 1, max.z() - min.z() + 1);
                var template = new StructureTemplate();
                template.fillFromWorld((ServerLevel) player.level(), new BlockPos(min.x(), min.y(), min.z()), size, false, List.of(Blocks.STRUCTURE_VOID));
                writeTemplate(env, edit, name, template.save(new CompoundTag()));
                var id = poolId(text(c, "pool"));
                var pool = readPool(env, id);
                pool.getAsJsonArray("elements").add(single(name.toString(), 1, "rigid"));
                writePool(env, edit, id, pool);
                return "Saved the selection as " + name + " and added it to " + id;
            }
            case "element_remove" -> {
                var id = poolId(text(c, "pool"));
                var pool = readPool(env, id);
                var elements = pool.getAsJsonArray("elements");
                int index = index(c, elements);
                elements.remove(index);
                writePool(env, edit, id, pool);
                return "Removed element " + (index + 1) + " from " + id;
            }
            case "element_move" -> {
                var id = poolId(text(c, "pool"));
                var pool = readPool(env, id);
                var elements = pool.getAsJsonArray("elements");
                int index = index(c, elements), to = Math.clamp(c.get("to").getAsInt(), 0, elements.size() - 1);
                var list = new ArrayList<JsonElement>();
                elements.forEach(list::add);
                list.add(to, list.remove(index));
                var moved = new JsonArray();
                list.forEach(moved::add);
                pool.add("elements", moved);
                writePool(env, edit, id, pool);
                return "Moved element " + (index + 1) + " to place " + (to + 1);
            }
            case "element_set" -> {
                var id = poolId(text(c, "pool"));
                var pool = readPool(env, id);
                var elements = pool.getAsJsonArray("elements");
                var entry = elements.get(index(c, elements)).getAsJsonObject();
                var element = entry.getAsJsonObject("element");
                if (c.has("weight")) entry.addProperty("weight", Math.clamp(c.get("weight").getAsInt(), 1, 150));
                if (c.has("projection")) { var p = text(c, "projection"); if (!p.equals("rigid") && !p.equals("terrain_matching")) throw new IllegalArgumentException("A projection is rigid or terrain_matching"); element.addProperty("projection", p); }
                if (c.has("processors")) { if (!element.has("processors")) throw new IllegalArgumentException("This element has no processor list"); element.addProperty("processors", poolId(text(c, "processors")).toString()); }
                if (c.has("template")) { if (!element.has("location")) throw new IllegalArgumentException("This element has no template"); element.addProperty("location", poolId(text(c, "template")).toString()); }
                writePool(env, edit, id, pool);
                return "Changed element " + (index(c, elements) + 1) + " of " + id;
            }
            case "jigsaw_set" -> { return setJigsaw(env, edit, c); }
            case "names" -> { memory.names = true; return ""; }
            case "retarget_check" -> { memory.retarget = retargetCheck(env, c); return ""; }
            case "retarget" -> { return retarget(env, edit, c); }
            case "apply" -> { return apply(env, memory); }
            case "discard" -> { return discard(env, memory); }
            default -> throw new IllegalArgumentException("Unknown pool editor command: " + text(c, "op"));
        }
    }

    private static String restore(Deque<List<Snap>> from, Deque<List<Snap>> to, String message) throws IOException {
        if (from.isEmpty()) throw new IllegalArgumentException("Nothing to " + (message.startsWith("Undid") ? "undo" : "redo"));
        var steps = from.pop();
        var now = new ArrayList<Snap>();
        for (int i = steps.size() - 1; i >= 0; i--) {
            var snap = steps.get(i);
            now.add(new Snap(snap.file(), Files.exists(snap.file()) ? Files.readAllBytes(snap.file()) : null));
            if (snap.bytes() == null) Files.deleteIfExists(snap.file());
            else { Files.createDirectories(snap.file().getParent()); Files.write(snap.file(), snap.bytes()); }
        }
        to.push(now);
        return message;
    }

    // ---- files

    private static String text(JsonObject c, String key) {
        if (!c.has(key) || !c.get(key).isJsonPrimitive()) throw new IllegalArgumentException("The pool editor command needs '" + key + "'");
        return c.get(key).getAsString();
    }
    private static int index(JsonObject c, JsonArray elements) {
        int index = c.get("index").getAsInt();
        if (index < 0 || index >= elements.size()) throw new IllegalArgumentException("There is no element " + (index + 1));
        return index;
    }
    private static ResourceLocation poolId(String name) {
        var id = ResourceLocation.tryParse(name.contains(":") ? name : "craftium:" + name);
        if (id == null) throw new IllegalArgumentException("Not a valid name: " + name + " (lower case letters, digits, / . _ -)");
        return id;
    }
    private static ResourceLocation resource(ResourceLocation id) { return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), POOLS + id.getPath() + ".json"); }
    private static String relPool(ResourceLocation id) { return "data/" + id.getNamespace() + "/worldgen/template_pool/" + id.getPath() + ".json"; }
    private static String relTemplate(ResourceLocation id) { return "data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt"; }
    /** The file in the craftium pack, which the game reads, and the one in the draft, which holds the changes not applied yet. */
    private static Path poolFile(JigsawPlanner.Env env, ResourceLocation id) { return env.folder().resolve(relPool(id)); }
    private static Path templateFile(JigsawPlanner.Env env, ResourceLocation id) { return env.folder().resolve(relTemplate(id)); }
    private static Path draftPoolFile(JigsawPlanner.Env env, ResourceLocation id) { return env.draft().resolve(relPool(id)); }
    private static Path draftTemplateFile(JigsawPlanner.Env env, ResourceLocation id) { return env.draft().resolve(relTemplate(id)); }

    /** Files deleted in the draft but still in the pack, one path a line; they go when the changes are applied. */
    private static final String TOMBSTONES = "deleted.txt";
    private static Set<String> tombstones(JigsawPlanner.Env env) throws IOException {
        var file = env.draft().resolve(TOMBSTONES);
        var out = new LinkedHashSet<String>();
        if (Files.exists(file)) for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line.strip());
        return out;
    }
    private static void addTombstone(JigsawPlanner.Env env, Edit edit, String relative) throws IOException {
        var all = tombstones(env);
        if (!all.add(relative)) return;
        edit.write(env.draft().resolve(TOMBSTONES), (String.join("\n", all) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Whether a pool is the craftium pack's own (or changed in the draft), and not deleted there. */
    private static boolean ours(JigsawPlanner.Env env, ResourceLocation id) throws IOException {
        return Files.exists(draftPoolFile(env, id)) || Files.exists(poolFile(env, id)) && !tombstones(env).contains(relPool(id));
    }
    /** Whether a pool of this name exists in any pack, counting the draft and not counting a pool deleted in it. */
    private static boolean exists(JigsawPlanner.Env env, ResourceLocation id) throws IOException {
        return Files.exists(draftPoolFile(env, id)) || !tombstones(env).contains(relPool(id)) && env.manager().getResource(resource(id)).isPresent();
    }
    /** Takes a pool out: its draft file goes, and its file in the pack is marked to go when the changes are applied. */
    private static void removePool(JigsawPlanner.Env env, Edit edit, ResourceLocation id) throws IOException {
        edit.delete(draftPoolFile(env, id));
        if (Files.exists(poolFile(env, id))) addTombstone(env, edit, relPool(id));
    }

    private static JsonObject parse(Path file) throws IOException { return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(); }

    /** A pool's JSON: from the draft when it has been changed there, else from the craftium pack, else from the pack that has it. */
    private static JsonObject readPool(JigsawPlanner.Env env, ResourceLocation id) throws IOException {
        var draft = draftPoolFile(env, id);
        var file = poolFile(env, id);
        boolean deleted = tombstones(env).contains(relPool(id));
        JsonObject pool;
        if (Files.exists(draft)) pool = parse(draft);
        else if (Files.exists(file) && !deleted) pool = parse(file);
        else {
            var found = deleted ? java.util.Optional.<net.minecraft.server.packs.resources.Resource>empty() : env.manager().getResource(resource(id));
            if (found.isEmpty()) throw new IllegalArgumentException("No pool called " + id);
            try (var reader = found.get().openAsReader()) { pool = JsonParser.parseReader(reader).getAsJsonObject(); }
        }
        if (!pool.has("elements") || !pool.get("elements").isJsonArray()) pool.add("elements", new JsonArray());
        if (!pool.has("fallback")) pool.addProperty("fallback", "minecraft:empty");
        return pool;
    }
    private static void writePool(JigsawPlanner.Env env, Edit edit, ResourceLocation id, JsonObject pool) throws IOException {
        edit.write(draftPoolFile(env, id), PRETTY.toJson(pool).getBytes(StandardCharsets.UTF_8));
    }
    private static void writeTemplate(JigsawPlanner.Env env, Edit edit, ResourceLocation id, CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        edit.write(draftTemplateFile(env, id), bytes.toByteArray());
    }

    // ---- retargeting: pointing the connectors that lead to one pool at another

    /** The templates a pool uses, as its JSON names them, the elements of a list element included. */
    private static Set<String> templatesOf(JsonObject pool) {
        var out = new LinkedHashSet<String>();
        for (var e : pool.getAsJsonArray("elements")) if (e.isJsonObject()) collectTemplates(e.getAsJsonObject().get("element"), out);
        return out;
    }
    private static void collectTemplates(JsonElement element, Set<String> out) {
        if (element == null || !element.isJsonObject()) return;
        var o = element.getAsJsonObject();
        if (o.has("location") && o.get("location").isJsonPrimitive()) out.add(poolId(o.get("location").getAsString()).toString());
        if (o.has("elements") && o.get("elements").isJsonArray()) for (var inner : o.getAsJsonArray("elements")) collectTemplates(inner, out);
    }

    /** A template as NBT: from the draft, else the craftium pack, else as the game has it; null when there is no such template. */
    private static CompoundTag readTag(JigsawPlanner.Env env, ResourceLocation id) throws IOException {
        var draft = draftTemplateFile(env, id);
        var file = templateFile(env, id);
        if (Files.exists(draft)) return NbtIo.readCompressed(draft, NbtAccounter.unlimitedHeap());
        if (Files.exists(file)) return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        var template = env.templates().get(id);
        if (template.isEmpty() || template.get().palettes.isEmpty()) return null;
        return template.get().save(new CompoundTag());
    }

    /** The names the connectors of a template that lead to a pool ask for. */
    private static List<String> connectorsTo(CompoundTag tag, String pool) {
        var names = new ArrayList<String>();
        var blocks = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            var nbt = blocks.getCompoundOrEmpty(i).getCompoundOrEmpty("nbt");
            if (nbt.getStringOr("id", "").equals("minecraft:jigsaw") && nbt.getStringOr("pool", "").equals(pool)) names.add(nbt.getStringOr("target", "minecraft:empty"));
        }
        return names;
    }

    /** Points the connectors of a template that lead to one pool at another, and says how many. */
    private static int retargetIn(CompoundTag tag, String from, String to) {
        int changed = 0;
        var blocks = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.getCompoundOrEmpty(i);
            var nbt = block.getCompoundOrEmpty("nbt");
            if (!nbt.getStringOr("id", "").equals("minecraft:jigsaw") || !nbt.getStringOr("pool", "").equals(from)) continue;
            nbt.putString("pool", to);
            block.put("nbt", nbt);
            blocks.set(i, block);
            changed++;
        }
        if (changed > 0) tag.put("blocks", blocks);
        return changed;
    }

    /** What pointing the connectors of a pool that lead to one pool at another would change, so the player can see it before it is done. */
    private static JsonObject retargetCheck(JigsawPlanner.Env env, JsonObject c) throws IOException {
        var pool = poolId(text(c, "pool"));
        var from = poolId(text(c, "from")).toString();
        var to = poolId(text(c, "to")).toString();
        if (from.equals(to)) throw new IllegalArgumentException("Those connectors already point at " + to);
        var affected = new JsonArray();
        var affectedIds = new ArrayList<String>();
        var asked = new TreeSet<String>();
        var counts = new HashMap<String, Integer>();
        int connectors = 0;
        for (var id : templatesOf(readPool(env, pool))) {
            var tag = readTag(env, ResourceLocation.parse(id));
            if (tag == null) continue;
            var names = connectorsTo(tag, from);
            if (names.isEmpty()) continue;
            connectors += names.size();
            affected.add(id);
            affectedIds.add(id);
            for (var name : names) { asked.add(name); counts.merge(name, 1, Integer::sum); }
        }
        if (connectors == 0) throw new IllegalArgumentException("No connector of " + pool + " points at " + from);
        var out = new JsonObject();
        out.addProperty("pool", pool.toString());
        out.addProperty("from", from);
        out.addProperty("to", to);
        out.addProperty("connectors", connectors);
        out.add("templates", affected);
        var shared = new JsonArray();
        int unmatched = 0;
        boolean toMissing = false;
        try {
            Registry<StructureTemplatePool> pools = JigsawPlanner.loadPools(env.server(), env.manager()).lookupOrThrow(Registries.TEMPLATE_POOL);
            var all = pools.listElements().toList();
            for (var template : affectedIds) {
                var users = new TreeSet<String>();
                for (var holder : all) {
                    if (holder.key().location().equals(pool)) continue;
                    for (var pair : holder.value().getTemplates())
                        for (var single : singles(pair.getFirst())) if (single.getTemplateLocation().toString().equals(template)) users.add(holder.key().location().toString());
                }
                if (users.isEmpty()) continue;
                var item = new JsonObject();
                item.addProperty("template", template);
                var list = new JsonArray();
                users.stream().limit(4).forEach(list::add);
                item.add("pools", list);
                item.addProperty("more", Math.max(0, users.size() - 4));
                shared.add(item);
            }
            if (!to.equals("minecraft:empty")) {
                var target = pools.get(ResourceKey.create(Registries.TEMPLATE_POOL, ResourceLocation.parse(to)));
                if (target.isEmpty()) toMissing = true;
                else for (var name : asked) if (JigsawDiagnostics.fits(pools, env.templates(), target.get().value(), ResourceLocation.parse(name)) == 0) unmatched += counts.get(name);
            }
        } catch (RuntimeException e) { /* without the pools there is nothing to say about sharing or fit */ }
        out.add("shared", shared);
        out.addProperty("unmatched", unmatched);
        out.addProperty("toMissing", toMissing);
        return out;
    }

    /** Points every connector in the templates of a pool that leads to one pool at another, as one undo step. */
    private static String retarget(JigsawPlanner.Env env, Edit edit, JsonObject c) throws IOException {
        var pool = poolId(text(c, "pool"));
        var from = poolId(text(c, "from")).toString();
        var to = poolId(text(c, "to")).toString();
        if (from.equals(to)) throw new IllegalArgumentException("Those connectors already point at " + to);
        int total = 0, templates = 0;
        for (var id : templatesOf(readPool(env, pool))) {
            var tag = readTag(env, ResourceLocation.parse(id));
            if (tag == null) continue;
            int changed = retargetIn(tag, from, to);
            if (changed == 0) continue;
            writeTemplate(env, edit, ResourceLocation.parse(id), tag);
            total += changed;
            templates++;
        }
        if (total == 0) throw new IllegalArgumentException("No connector of " + pool + " points at " + from);
        return "Pointed " + total + (total == 1 ? " connector" : " connectors") + " of " + pool + " in " + templates + (templates == 1 ? " template" : " templates") + " at " + to + " instead of " + from;
    }

    // ---- applying and discarding

    private static String idOf(String relative) {
        var parts = relative.split("/", 3);
        if (parts.length < 3) return relative;
        var rest = parts[2];
        rest = rest.startsWith("worldgen/template_pool/") ? rest.substring(23) : rest.startsWith("structure/") ? rest.substring(10) : rest;
        int dot = rest.lastIndexOf('.');
        return parts[1] + ":" + (dot > 0 ? rest.substring(0, dot) : rest);
    }
    private static String relative(JigsawPlanner.Env env, Path file) { return env.draft().relativize(file).toString().replace(java.io.File.separatorChar, '/'); }

    /** What the draft holds: the pools and templates changed and the pools deleted. */
    private static JsonObject unapplied(JigsawPlanner.Env env) throws IOException {
        var pools = new JsonArray(); var templates = new JsonArray(); var deleted = new JsonArray();
        if (Files.isDirectory(env.draft()))
            try (var walk = Files.walk(env.draft())) {
                for (var file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    var rel = relative(env, file);
                    if (rel.equals(TOMBSTONES)) continue;
                    if (rel.contains("/worldgen/template_pool/")) pools.add(idOf(rel)); else if (rel.contains("/structure/")) templates.add(idOf(rel));
                }
            }
        for (var rel : tombstones(env)) deleted.add(idOf(rel));
        var out = new JsonObject();
        out.add("pools", pools);
        out.add("templates", templates);
        out.add("deleted", deleted);
        out.addProperty("count", pools.size() + templates.size() + deleted.size());
        return out;
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (var walk = Files.walk(folder)) {
            for (var path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }

    /** Moves the draft into the craftium pack, deletes what was deleted, and starts again with an empty draft. The undo history ends here. */
    private static String apply(JigsawPlanner.Env env, Memory memory) throws IOException {
        int count = unapplied(env).get("count").getAsInt();
        if (count == 0) throw new IllegalArgumentException("There is nothing to apply");
        var dead = tombstones(env);
        if (Files.isDirectory(env.draft()))
            try (var walk = Files.walk(env.draft())) {
                for (var file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    var rel = relative(env, file);
                    if (rel.equals(TOMBSTONES)) continue;
                    var target = env.folder().resolve(rel);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    dead.remove(rel);
                }
            }
        for (var rel : dead) Files.deleteIfExists(env.folder().resolve(rel));
        deleteTree(env.draft());
        memory.undo.clear();
        memory.redo.clear();
        return "Applied " + count + (count == 1 ? " change" : " changes") + " to datapacks/craftium";
    }

    /** Throws the draft away, so the pools and templates are as they were when the changes were last applied. */
    private static String discard(JigsawPlanner.Env env, Memory memory) throws IOException {
        int count = unapplied(env).get("count").getAsInt();
        deleteTree(env.draft());
        memory.undo.clear();
        memory.redo.clear();
        return count == 0 ? "There was nothing to discard" : "Discarded " + count + (count == 1 ? " change" : " changes");
    }

    private static JsonObject single(String template, int weight, String projection) {
        var element = new JsonObject();
        element.addProperty("element_type", "minecraft:single_pool_element");
        element.addProperty("location", poolId(template).toString());
        element.addProperty("processors", "minecraft:empty");
        element.addProperty("projection", projection);
        var entry = new JsonObject();
        entry.addProperty("weight", Math.clamp(weight, 1, 150));
        entry.add("element", element);
        return entry;
    }

    /** Changes one connector of a template in its NBT; a template from another pack or the game's saved structures is first copied to the craftium pack. */
    private static String setJigsaw(JigsawPlanner.Env env, Edit edit, JsonObject c) throws IOException {
        var id = poolId(text(c, "template"));
        var file = Files.exists(draftTemplateFile(env, id)) ? draftTemplateFile(env, id) : templateFile(env, id);
        CompoundTag tag;
        if (Files.exists(file)) tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        else {
            var template = env.templates().get(id);
            if (template.isEmpty() || template.get().palettes.isEmpty()) throw new IllegalArgumentException("Template " + id + " is missing");
            tag = template.get().save(new CompoundTag());
        }
        var at = c.getAsJsonArray("pos");
        var blocks = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.getCompoundOrEmpty(i);
            var pos = block.getListOrEmpty("pos");
            if (pos.getIntOr(0, Integer.MIN_VALUE) != at.get(0).getAsInt() || pos.getIntOr(1, Integer.MIN_VALUE) != at.get(1).getAsInt() || pos.getIntOr(2, Integer.MIN_VALUE) != at.get(2).getAsInt()) continue;
            var nbt = block.getCompoundOrEmpty("nbt");
            if (!nbt.getStringOr("id", "").equals("minecraft:jigsaw")) continue;
            if (c.has("name")) nbt.putString("name", poolId(text(c, "name")).toString());
            if (c.has("target")) nbt.putString("target", poolId(text(c, "target")).toString());
            if (c.has("pool")) nbt.putString("pool", poolId(text(c, "pool")).toString());
            if (c.has("joint")) { var joint = text(c, "joint"); if (!joint.equals("rollable") && !joint.equals("aligned")) throw new IllegalArgumentException("A joint is rollable or aligned"); nbt.putString("joint", joint); }
            if (c.has("placement")) nbt.putInt("placement_priority", Math.clamp(c.get("placement").getAsInt(), 0, 1000));
            if (c.has("selection")) nbt.putInt("selection_priority", Math.clamp(c.get("selection").getAsInt(), 0, 1000));
            if (c.has("final")) nbt.putString("final_state", text(c, "final"));
            block.put("nbt", nbt);
            blocks.set(i, block);
            tag.put("blocks", blocks);
            writeTemplate(env, edit, id, tag);
            return "Changed a connector of " + id;
        }
        throw new IllegalArgumentException("There is no connector at that place in " + id);
    }

    // ---- the model

    private static String model(ServerPlayer player, JigsawPlanner.Env env, Memory memory) throws IOException {
        var root = new JsonObject();
        root.addProperty("message", memory.message);
        root.addProperty("folder", env.folder().toString());
        root.addProperty("undo", memory.undo.size());
        root.addProperty("redo", memory.redo.size());
        root.addProperty("selection", EditorServer.session(player).selection.size());
        root.add("unapplied", unapplied(env));
        if (memory.retarget != null) root.add("retarget", memory.retarget);
        Registry<StructureTemplatePool> pools = null;
        try { pools = JigsawPlanner.loadPools(env.server(), env.manager()).lookupOrThrow(Registries.TEMPLATE_POOL); }
        catch (RuntimeException e) { root.addProperty("registryError", String.valueOf(e.getMessage())); }

        var list = new JsonArray();
        var found = env.manager().listResources("worldgen/template_pool", id -> id.getPath().endsWith(".json"));
        var ids = new TreeMap<ResourceLocation, String>(Comparator.comparing(ResourceLocation::toString));
        for (var entry : found.entrySet()) {
            var path = entry.getKey().getPath();
            ids.put(ResourceLocation.fromNamespaceAndPath(entry.getKey().getNamespace(), path.substring(POOLS.length(), path.length() - 5)), entry.getValue().sourcePackId());
        }
        var gone = tombstones(env);
        for (var entry : ids.entrySet()) {
            if (gone.contains(relPool(entry.getKey())) && !Files.exists(draftPoolFile(env, entry.getKey()))) continue; // deleted in the draft
            var item = new JsonObject();
            item.addProperty("id", entry.getKey().toString());
            boolean ours = ours(env, entry.getKey());
            item.addProperty("source", ours ? "craftium" : entry.getValue().equals("vanilla") ? "vanilla" : "pack");
            if (ours && pools != null) {
                int errors = 0, warnings = 0;
                for (var line : JigsawDiagnostics.lint(pools, env.templates(), entry.getKey(), false)) { if (line.startsWith("ERROR")) errors++; else warnings++; }
                item.addProperty("errors", errors);
                item.addProperty("warnings", warnings);
            }
            list.add(item);
        }
        root.add("pools", list);
        if (memory.names) { memory.names = false; return PRETTY.toJson(root); }

        var templates = new JsonArray();
        var query = memory.query.toLowerCase(Locale.ROOT);
        env.templates().listTemplates().filter(id -> id.toString().contains(query))
                .sorted(Comparator.comparing((ResourceLocation id) -> id.getNamespace().equals("minecraft")).thenComparing(ResourceLocation::toString))
                .limit(MAX_TEMPLATES).forEach(id -> templates.add(id.toString()));
        root.add("templates", templates);
        root.addProperty("query", memory.query);

        if (!memory.pool.isEmpty()) {
            var id = poolId(memory.pool);
            try { root.add("pool", poolModel(env, pools, id)); root.add("graph", graph(env, pools, id)); }
            catch (IllegalArgumentException e) { root.addProperty("poolError", e.getMessage()); }
        }
        return PRETTY.toJson(root);
    }

    private static JsonObject poolModel(JigsawPlanner.Env env, Registry<StructureTemplatePool> pools, ResourceLocation id) throws IOException {
        var raw = readPool(env, id);
        var model = new JsonObject();
        model.addProperty("id", id.toString());
        model.addProperty("description", raw.has("$comment") && raw.get("$comment").isJsonPrimitive() ? raw.get("$comment").getAsString() : "");
        model.addProperty("overridden", ours(env, id));
        model.addProperty("fallback", raw.get("fallback").isJsonPrimitive() ? raw.get("fallback").getAsString() : "(inline)");
        var elements = new JsonArray();
        var entries = raw.getAsJsonArray("elements");
        int total = 0;
        for (var e : entries) total += e.getAsJsonObject().has("weight") ? e.getAsJsonObject().get("weight").getAsInt() : 1;
        int thumbs = 0;
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i).getAsJsonObject();
            var element = entry.getAsJsonObject("element");
            var out = new JsonObject();
            out.addProperty("index", i);
            int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
            out.addProperty("weight", weight);
            out.addProperty("share", total == 0 ? 0 : Math.round(weight * 1000.0 / total) / 10.0);
            var type = element.has("element_type") ? element.get("element_type").getAsString().replace("minecraft:", "") : "?";
            out.addProperty("type", type.replace("_pool_element", ""));
            out.addProperty("projection", element.has("projection") ? element.get("projection").getAsString() : "rigid");
            boolean editable = type.equals("single_pool_element") || type.equals("legacy_single_pool_element");
            out.addProperty("editable", editable);
            if (element.has("processors")) out.addProperty("processors", element.get("processors").isJsonPrimitive() ? element.get("processors").getAsString() : "(inline)");
            if (element.has("feature")) out.addProperty("feature", element.get("feature").isJsonPrimitive() ? element.get("feature").getAsString() : "(inline)");
            if (type.equals("list_pool_element")) out.addProperty("count", element.has("elements") ? element.getAsJsonArray("elements").size() : 0);
            if (element.has("location") && element.get("location").isJsonPrimitive()) {
                var template = ResourceLocation.tryParse(element.get("location").getAsString());
                out.addProperty("template", element.get("location").getAsString());
                if (template != null) templateModel(env, pools, template, out, thumbs < THUMBNAILS);
                if (out.has("thumb")) thumbs++;
            }
            elements.add(out);
        }
        model.add("elements", elements);
        return model;
    }

    /** What the editor shows of a template: its size, its connectors with how many elements each can meet, and a small picture of it. */
    private static void templateModel(JigsawPlanner.Env env, Registry<StructureTemplatePool> pools, ResourceLocation id, JsonObject out, boolean picture) {
        var found = env.templates().get(id);
        if (found.isEmpty() || found.get().palettes.isEmpty() || found.get().palettes.get(0).blocks().isEmpty()) { out.addProperty("missing", true); return; }
        var template = found.get();
        var size = template.getSize();
        out.add("size", ints(size.getX(), size.getY(), size.getZ()));
        var palette = template.palettes.get(0);
        var jigsaws = new JsonArray();
        int i = 0;
        for (var jigsaw : palette.jigsaws()) {
            var item = new JsonObject();
            item.addProperty("i", i++);
            item.addProperty("name", jigsaw.name().toString());
            item.addProperty("pool", jigsaw.pool().location().toString());
            item.addProperty("target", jigsaw.target().toString());
            item.addProperty("joint", jigsaw.jointType().getSerializedName());
            item.addProperty("placement", jigsaw.placementPriority());
            item.addProperty("selection", jigsaw.selectionPriority());
            item.addProperty("final", jigsaw.info().nbt() == null ? "minecraft:air" : jigsaw.info().nbt().getStringOr("final_state", "minecraft:air"));
            var at = jigsaw.info().pos();
            item.add("pos", ints(at.getX(), at.getY(), at.getZ()));
            String issue = "";
            int fits = -1;
            if (!jigsaw.pool().location().equals(ResourceLocation.fromNamespaceAndPath("minecraft", "empty")) && pools != null) {
                var target = pools.get(jigsaw.pool());
                if (target.isEmpty()) issue = "pool " + jigsaw.pool().location() + " does not exist";
                else {
                    fits = JigsawDiagnostics.fits(pools, env.templates(), target.get().value(), jigsaw.target());
                    if (fits == 0) issue = "no element of " + jigsaw.pool().location() + " offers '" + jigsaw.target().getPath() + "'";
                }
            }
            item.addProperty("fits", fits);
            item.addProperty("issue", issue);
            jigsaws.add(item);
        }
        out.add("jigsaws", jigsaws);
        if (picture && (long) size.getX() * size.getY() * size.getZ() <= THUMBNAIL_VOLUME) out.add("thumb", thumbnail(template, size));
    }

    /** The blocks of a template as a byte per cell (0 is empty), x first, then z, then y, with the names the numbers stand for; deflated, as base64. */
    private static JsonObject thumbnail(StructureTemplate template, Vec3i size) {
        var names = new ArrayList<String>();
        var index = new HashMap<String, Integer>();
        var cells = new byte[size.getX() * size.getY() * size.getZ()];
        for (var info : template.palettes.get(0).blocks()) {
            var state = info.state();
            if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;
            var name = BlockStateParser.serialize(state);
            int slot = index.computeIfAbsent(name, key -> { names.add(key); return names.size(); });
            var at = info.pos();
            if (at.getX() < 0 || at.getY() < 0 || at.getZ() < 0 || at.getX() >= size.getX() || at.getY() >= size.getY() || at.getZ() >= size.getZ()) continue;
            cells[at.getX() + size.getX() * (at.getZ() + size.getZ() * at.getY())] = (byte) Math.min(slot, 255);
        }
        var packed = new ByteArrayOutputStream();
        try (var out = new DeflaterOutputStream(packed, new Deflater(Deflater.BEST_COMPRESSION))) { out.write(cells); }
        catch (IOException e) { throw new IllegalStateException(e); }
        var thumb = new JsonObject();
        thumb.add("size", ints(size.getX(), size.getY(), size.getZ()));
        var palette = new JsonArray();
        names.stream().limit(255).forEach(palette::add);
        thumb.add("palette", palette);
        thumb.addProperty("data", Base64.getEncoder().encodeToString(packed.toByteArray()));
        return thumb;
    }

    /** The pools a pool leads to, as nodes and arrows, to show loops and dead ends. */
    private static JsonObject graph(JigsawPlanner.Env env, Registry<StructureTemplatePool> pools, ResourceLocation start) {
        var graph = new JsonObject();
        var nodes = new JsonArray();
        var edges = new JsonArray();
        if (pools == null) { graph.add("nodes", nodes); graph.add("edges", edges); return graph; }
        var seen = new LinkedHashSet<ResourceLocation>();
        var queue = new ArrayDeque<ResourceLocation>(List.of(start));
        while (!queue.isEmpty() && seen.size() < 40) {
            var id = queue.poll();
            if (!seen.add(id)) continue;
            var holder = pools.get(ResourceKey.create(Registries.TEMPLATE_POOL, id));
            var node = new JsonObject();
            node.addProperty("id", id.toString());
            node.addProperty("missing", holder.isEmpty());
            node.addProperty("elements", holder.isEmpty() ? 0 : holder.get().value().getTemplates().size());
            nodes.add(node);
            if (holder.isEmpty()) continue;
            var counts = new TreeMap<String, Integer>();
            for (var pair : holder.get().value().getTemplates())
                for (var single : singles(pair.getFirst())) {
                    var template = env.templates().get(single.getTemplateLocation());
                    if (template.isEmpty() || template.get().palettes.isEmpty()) continue;
                    for (var jigsaw : template.get().palettes.get(0).jigsaws()) {
                        var target = jigsaw.pool().location();
                        if (target.getPath().equals("empty") && target.getNamespace().equals("minecraft")) continue;
                        counts.merge(target.toString(), 1, Integer::sum);
                        queue.add(target);
                    }
                }
            counts.forEach((to, count) -> { var edge = new JsonObject(); edge.addProperty("from", id.toString()); edge.addProperty("to", to); edge.addProperty("count", count); edges.add(edge); });
        }
        for (var edge : edges) { var to = edge.getAsJsonObject().get("to").getAsString(); var id = ResourceLocation.tryParse(to); if (id != null && !seen.contains(id) && nodes.size() < 48) { var node = new JsonObject(); node.addProperty("id", to); node.addProperty("missing", pools.get(ResourceKey.create(Registries.TEMPLATE_POOL, id)).isEmpty()); node.addProperty("elements", 0); node.addProperty("more", true); nodes.add(node); seen.add(id); } }
        graph.add("nodes", nodes);
        graph.add("edges", edges);
        return graph;
    }

    private static List<SinglePoolElement> singles(StructurePoolElement element) {
        if (element instanceof SinglePoolElement single) return List.of(single);
        if (element instanceof ListPoolElement list) { var out = new ArrayList<SinglePoolElement>(); for (var inner : list.getElements()) out.addAll(singles(inner)); return out; }
        return List.of();
    }

    private static JsonArray ints(int... values) { var array = new JsonArray(); for (int v : values) array.add(v); return array; }
}
