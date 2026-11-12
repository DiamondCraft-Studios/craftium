package dev.craftium.client;

import com.google.gson.JsonParser;
import dev.craftium.core.BedrockNbt;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;

/**
 * Opt-in (CRAFTIUM_TEST_SCOPE=bedrock-pack): makes a small jigsaw set in a real world (two pieces, two pools, a processor list, a structure and its set),
 * asks the pool editor's server side for a Bedrock pack the way its Export button does, and reads the .mcpack back: the manifest, the converted JSON, a piece's
 * jigsaw data, and the report. Also checks that a pool of the game is refused.
 */
public final class BedrockPackGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c) {
        if (!"bedrock-pack".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))) return;
        try (var world = c.worldBuilder().adjustSettings(s -> { s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE); s.setAllowCommands(true); }).create()) {
            c.runOnClient(mc -> ClientState.options = new dev.craftium.core.Options());
            world.getServer().runCommand("tp @a 0 10 18 180 25");
            c.waitTicks(20);
            world.getClientWorld().waitForChunksDownload();
            ClientBedrockPack.revealAfterSaving = false;

            // a hall: a hollow stone brick box with a jigsaw at each end and a chest, and a cap
            BlockPos base = c.computeOnClient(mc -> mc.player.blockPosition().offset(-3, 6, 6));
            int x = base.getX(), y = base.getY(), z = base.getZ();
            var server = world.getServer();
            server.runCommand("fill " + x + " " + y + " " + z + " " + (x + 6) + " " + (y + 3) + " " + (z + 2) + " air");
            server.runCommand("fill " + x + " " + y + " " + z + " " + (x + 6) + " " + (y + 3) + " " + (z + 2) + " stone_bricks hollow");
            server.runCommand("setblock " + x + " " + (y + 1) + " " + (z + 1) + " jigsaw[orientation=west_up]{name:\"outpost:door\",target:\"outpost:door\",pool:\"outpost:caps\",final_state:\"minecraft:stone_bricks\",joint:\"aligned\"}");
            server.runCommand("setblock " + (x + 6) + " " + (y + 1) + " " + (z + 1) + " jigsaw[orientation=east_up]{name:\"outpost:door\",target:\"outpost:door\",pool:\"outpost:caps\",final_state:\"minecraft:stone_bricks\",joint:\"aligned\"}");
            server.runCommand("setblock " + (x + 3) + " " + (y + 1) + " " + (z + 1) + " chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}");
            server.runCommand("fill " + x + " " + (y + 10) + " " + z + " " + (x + 2) + " " + (y + 12) + " " + z + " stone_bricks");
            server.runCommand("setblock " + (x + 1) + " " + (y + 10) + " " + z + " jigsaw[orientation=south_up]{name:\"outpost:door\",target:\"minecraft:empty\",pool:\"minecraft:empty\",final_state:\"minecraft:air\",joint:\"aligned\"}");
            c.waitTicks(10);
            Path folder = server.computeOnServer(s -> s.getWorldPath(LevelResource.DATAPACK_DIR).resolve("craftium"));
            server.runOnServer(s -> {
                try {
                    var level = s.overworld();
                    save(level, new BlockPos(x, y, z), new Vec3i(7, 4, 3), folder.resolve("data/outpost/structure/hall.nbt"));
                    save(level, new BlockPos(x, y + 10, z), new Vec3i(3, 3, 1), folder.resolve("data/outpost/structure/cap.nbt"));
                    write(folder.resolve("data/outpost/worldgen/template_pool/halls.json"), """
                        {"fallback":"outpost:caps","elements":[{"weight":4,"element":{"element_type":"minecraft:single_pool_element","location":"outpost:hall","processors":"outpost:weather","projection":"rigid"}},
                          {"weight":1,"element":{"element_type":"minecraft:feature_pool_element","feature":"minecraft:oak_checked","projection":"rigid"}}]}""");
                    write(folder.resolve("data/outpost/worldgen/template_pool/caps.json"), """
                        {"fallback":"minecraft:empty","elements":[{"weight":1,"element":{"element_type":"minecraft:single_pool_element","location":"outpost:cap","processors":"minecraft:empty","projection":"rigid"}}]}""");
                    write(folder.resolve("data/outpost/worldgen/processor_list/weather.json"), """
                        {"processors":[{"processor_type":"minecraft:rule","rules":[{"input_predicate":{"block":"minecraft:stone_bricks","predicate_type":"minecraft:random_block_match","probability":0.2},
                          "location_predicate":{"predicate_type":"minecraft:always_true"},"output_state":{"Name":"minecraft:mossy_stone_bricks"}}]}]}""");
                    write(folder.resolve("data/outpost/worldgen/structure/ruin.json"), """
                        {"type":"minecraft:jigsaw","biomes":"#minecraft:is_overworld","max_distance_from_center":80,"project_start_to_heightmap":"WORLD_SURFACE_WG","size":4,"spawn_overrides":{},
                         "start_height":{"absolute":0},"start_pool":"outpost:halls","step":"surface_structures","terrain_adaptation":"none","use_expansion_hack":false}""");
                    write(folder.resolve("data/outpost/worldgen/structure_set/ruin.json"), """
                        {"placement":{"type":"minecraft:random_spread","salt":12345,"separation":8,"spacing":32},"structures":[{"structure":"outpost:ruin","weight":1}]}""");
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });

            Path out = Files.createTempDirectory("craftium-bedrock").resolve("Outpost test.mcpack");
            c.runOnClient(mc -> {
                ClientBedrockPack.expect(out);
                ClientState.options.jigsawEdit = "{\"op\":\"bedrock_export\",\"pool\":\"outpost:halls\",\"name\":\"Outpost test\"}";
                CraftiumClient.send("craftium:jigsaw_edit", null, null);
            });
            for (int i = 0; i < 600 && !Files.exists(out); i++) c.waitTick();
            require(Files.exists(out), "the pack never arrived: " + c.computeOnClient(mc -> ClientState.status.message()));
            System.out.println("CRAFTIUM_BEDROCK pack bytes=" + Files.size(out));

            var entries = read(Files.readAllBytes(out));
            System.out.println("CRAFTIUM_BEDROCK entries=" + entries.keySet());
            for (String path : List.of("manifest.json", "worldgen/template_pools/outpost_halls.json", "worldgen/template_pools/outpost_caps.json", "worldgen/processors/outpost_weather.json",
                    "worldgen/structures/outpost_ruin.json", "worldgen/structure_sets/outpost_ruin.json", "structures/outpost/hall.mcstructure", "structures/outpost/cap.mcstructure", "craftium-export-report.txt"))
                require(entries.containsKey(path), "the pack lacks " + path);
            var manifest = JsonParser.parseString(new String(entries.get("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            require(manifest.getAsJsonObject("header").get("name").getAsString().equals("Outpost test"), "pack name");
            require(manifest.getAsJsonObject("header").getAsJsonArray("min_engine_version").toString().equals("[1,26,30]"), "min engine version");
            UUID.fromString(manifest.getAsJsonObject("header").get("uuid").getAsString());
            UUID.fromString(manifest.getAsJsonArray("modules").get(0).getAsJsonObject().get("uuid").getAsString());

            var pool = JsonParser.parseString(new String(entries.get("worldgen/template_pools/outpost_halls.json"), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("minecraft:template_pool");
            require(pool.get("fallback").getAsString().equals("outpost:caps"), "fallback");
            require(pool.getAsJsonArray("elements").size() == 1, "the feature element should be left out");
            var element = pool.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element");
            require(element.get("location").getAsString().equals("outpost/hall") && element.get("processors").getAsString().equals("outpost:weather"), "element: " + element);
            var structure = JsonParser.parseString(new String(entries.get("worldgen/structures/outpost_ruin.json"), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("minecraft:jigsaw");
            require(structure.get("max_depth").getAsInt() == 4 && structure.get("heightmap_projection").getAsString().equals("world_surface"), "structure: " + structure);

            var hall = BedrockNbt.read(entries.get("structures/outpost/hall.mcstructure"));
            require(String.valueOf(hall.get("size")).replace(" ", "").equals("[7, 4, 3]".replace(" ", "")), "hall size " + hall.get("size"));
            @SuppressWarnings("unchecked") var palette = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) hall.get("structure")).get("palette")).get("default");
            @SuppressWarnings("unchecked") var positions = (Map<String, Object>) palette.get("block_position_data");
            @SuppressWarnings("unchecked") var entities = positions.values().stream().map(v -> (Map<String, Object>) ((Map<String, Object>) v).get("block_entity_data")).toList();
            var jigsaws = entities.stream().filter(e -> "JigsawBlock".equals(e.get("id"))).toList();
            require(jigsaws.size() == 2, "the hall should keep its two jigsaws, found " + jigsaws.size() + " of " + entities.size() + " block entities");
            require(entities.stream().anyMatch(e -> "Chest".equals(e.get("id"))), "the chest of the hall should keep its data: " + entities);
            var data = jigsaws.get(0);
            require("outpost:door".equals(data.get("name")) && "outpost:caps".equals(data.get("target_pool")), "jigsaw data " + data);

            String report = new String(entries.get("craftium-export-report.txt"), StandardCharsets.UTF_8);
            require(report.contains("placed feature") && !report.contains("block entities (chests"), "the report should name the feature, and no longer call the chest lost:\n" + report);
            System.out.println("CRAFTIUM_BEDROCK report:\n" + report);

            // a pool of the game is refused, and nothing is sent
            Path none = out.resolveSibling("none.mcpack");
            c.runOnClient(mc -> {
                ClientBedrockPack.expect(none);
                ClientState.options.jigsawEdit = "{\"op\":\"bedrock_export\",\"pool\":\"minecraft:village/plains/houses\",\"name\":\"Villages\"}";
                CraftiumClient.send("craftium:jigsaw_edit", null, null);
            });
            c.waitTicks(40);
            require(!Files.exists(none), "a game pool must not be exported");
            String said = c.computeOnClient(mc -> ClientState.status.message());
            require(said != null && said.contains("game's pools"), "the refusal should say why: " + said);
            // the pool editor's window for it
            c.runOnClient(mc -> { ClientState.options.jigsawPool = "outpost:halls"; JigsawEditorScreen.open(null); });
            c.waitTicks(40);
            c.takeScreenshot("pool-editor-dropdown-closed");
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var toggle = JigsawEditorScreen.class.getDeclaredMethod("toggleMenu");
                    toggle.setAccessible(true);
                    toggle.invoke(screen);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(10);
            c.takeScreenshot("pool-editor-dropdown-open");
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var toggle = JigsawEditorScreen.class.getDeclaredMethod("toggleMenu");
                    toggle.setAccessible(true);
                    toggle.invoke(screen);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(10);
            // a click on a jigsaw block in the preview picks its connector and opens it
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var find = JigsawEditorScreen.class.getDeclaredMethod("jigsawPoint", int.class);
                    find.setAccessible(true);
                    var point = (double[]) find.invoke(screen, 1);
                    require(point != null, "the second jigsaw block should be visible in the preview");
                    var pick = JigsawEditorScreen.class.getDeclaredMethod("pickJigsawInPreview", double.class, double.class);
                    pick.setAccessible(true);
                    pick.invoke(screen, point[0], point[1]);
                    var field = JigsawEditorScreen.class.getDeclaredField("pickedJig");
                    field.setAccessible(true);
                    require(String.valueOf(field.get(screen)).contains("[6,1,1]"), "the click should pick the connector at 6 1 1: " + field.get(screen));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(10);
            // a click into a text box selects all of its text
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var field = JigsawEditorScreen.class.getDeclaredField("poolBox");
                    field.setAccessible(true);
                    var box = (net.minecraft.client.gui.components.EditBox) field.get(screen);
                    var scale = JigsawEditorScreen.class.getDeclaredField("uiScale");
                    scale.setAccessible(true);
                    float ui = (float) scale.get(screen);
                    box.setValue("outpost:halls");
                    var event = new net.minecraft.client.input.MouseButtonEvent((box.getX() + 8) * ui, (box.getY() + 8) * ui, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                    ((net.minecraft.client.gui.screens.Screen) screen).mouseClicked(event, false);
                    box = (net.minecraft.client.gui.components.EditBox) field.get(screen); // the screen may have been laid out again
                    require("outpost:halls".equals(box.getHighlighted()), "a click into a box should select all its text, selected: '" + box.getHighlighted() + "'");
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(10);
            c.takeScreenshot("pool-editor-picked");
            // the target dropdown of the picked connector: the elements of its pool with picture, name and path
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var picked = JigsawEditorScreen.class.getDeclaredField("pickedJig");
                    picked.setAccessible(true);
                    var menu = JigsawEditorScreen.class.getDeclaredField("targetMenuKey");
                    menu.setAccessible(true);
                    menu.set(screen, picked.get(screen));
                    var init = JigsawEditorScreen.class.getDeclaredMethod("init");
                    init.setAccessible(true);
                    init.invoke(screen);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(40);
            c.takeScreenshot("pool-editor-target-menu");
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var menu = JigsawEditorScreen.class.getDeclaredField("targetMenuKey");
                    menu.setAccessible(true);
                    menu.set(screen, null);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            // the Single view of the connectors: the picked one in a window on top
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var view = JigsawEditorScreen.class.getDeclaredMethod("setView", boolean.class);
                    view.setAccessible(true);
                    view.invoke(screen, true);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(10);
            c.takeScreenshot("pool-editor-single");
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var view = JigsawEditorScreen.class.getDeclaredMethod("setView", boolean.class);
                    view.setAccessible(true);
                    view.invoke(screen, false);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(10);
            c.runOnClient(mc -> {
                try {
                    var screen = dev.craftium.client.compat.ClientCompat.screen(mc);
                    var open = JigsawEditorScreen.class.getDeclaredMethod("openBedrock");
                    open.setAccessible(true);
                    open.invoke(screen);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            c.waitTicks(40);
            c.takeScreenshot("bedrock-pack-window");
            System.out.println("CRAFTIUM_BEDROCK done");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void save(net.minecraft.server.level.ServerLevel level, BlockPos at, Vec3i size, Path file) throws java.io.IOException {
        var template = new StructureTemplate();
        template.fillFromWorld(level, at, size, false, List.of(Blocks.STRUCTURE_VOID));
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(template.save(new CompoundTag()), file);
    }

    private static void write(Path file, String text) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> read(byte[] zip) throws java.io.IOException {
        var out = new LinkedHashMap<String, byte[]>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) out.put(entry.getName(), in.readAllBytes());
        }
        return out;
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
