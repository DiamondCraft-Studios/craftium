package dev.craftium.test;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Runs each world-changing tool on fixed terrain with fixed options and compares a hash of every block it changed with
 * {@link GoldenToolExpected}. It exists so that reorganising how tools are described and planned can be shown to change nothing.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=golden ./gradlew :versions:mc-26.2:runClientGameTest}. After a change that is meant to alter a
 * tool's output, run it with {@code CRAFTIUM_GOLDEN_UPDATE=1}: every case then runs twice (a case that differs from itself is not
 * deterministic and is reported), and the new table is written to {@code golden/GoldenToolExpected.java} in the run directory, next
 * to one file per case listing each changed block, so the difference can be read before the table is copied over.
 */
public final class GoldenToolGameTest {
    private static final int MIN_X = -14, MAX_X = 14, MIN_Y = -6, MAX_Y = 20, MIN_Z = -14, MAX_Z = 14;
    private static final Point UP = new Point(0, 1, 0);

    /** One tool run: the options it uses, what the world holds first, and where it is clicked. */
    public record Case(String name, Tool tool, Consumer<Options> options, Consumer<ServerLevel> setup, boolean clipboard, Point... clicks) {}

    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext world;

    private GoldenToolGameTest(ClientGameTestContext ctx, TestSingleplayerContext world) { this.ctx = ctx; this.world = world; }

    public static void run(ClientGameTestContext ctx, TestSingleplayerContext world) { new GoldenToolGameTest(ctx, world).runAll(); }

    // ---- the terrain -------------------------------------------------------------------------------------------------------------

    /** Rolling ground with a bowl near the middle and a few sand patches: enough relief and variety for the sculpting tools. */
    public static int height(int x, int z) { return 2 + Math.floorMod(x * 3 + z * 5, 4) + (x * x + z * z) / 40; }

    private static BlockState terrain(int x, int y, int z) {
        int h = height(x, z);
        if (y > h) return Blocks.AIR.defaultBlockState();
        if (y == h) return (Math.floorMod(x + 2 * z, 9) == 0 ? Blocks.SAND : Blocks.GRASS_BLOCK).defaultBlockState();
        return (y >= h - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState();
    }

    public static Point surface(int x, int z) { return new Point(x, height(x, z), z); }

    /** A small mixed block of gold, planks and a stair, floating over the east side, for the tools that work from a selection or clipboard. */
    public static final Point CLIPBOARD_FROM = new Point(8, 10, -2), CLIPBOARD_TO = new Point(10, 12, 0);

    /** Builds the terrain and the floating block again, runs {@code setup} on top of it, and puts the biomes back. */
    public static void reset(TestSingleplayerContext world, Consumer<ServerLevel> setup) {
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            var pos = new BlockPos.MutableBlockPos();
            for (int x = MIN_X; x <= MAX_X; x++)
                for (int y = MIN_Y; y <= MAX_Y; y++)
                    for (int z = MIN_Z; z <= MAX_Z; z++) level.setBlock(pos.set(x, y, z), terrain(x, y, z), 2 | 16);
            for (int x = CLIPBOARD_FROM.x(); x <= CLIPBOARD_TO.x(); x++)
                for (int y = CLIPBOARD_FROM.y(); y <= CLIPBOARD_TO.y(); y++)
                    for (int z = CLIPBOARD_FROM.z(); z <= CLIPBOARD_TO.z(); z++) {
                        boolean corner = (x == CLIPBOARD_FROM.x() || x == CLIPBOARD_TO.x()) && (z == CLIPBOARD_FROM.z() || z == CLIPBOARD_TO.z());
                        level.setBlock(pos.set(x, y, z), (corner ? Blocks.GOLD_BLOCK : (x + y + z) % 2 == 0 ? Blocks.OAK_PLANKS : Blocks.STONE_BRICK_STAIRS).defaultBlockState(), 2 | 16);
                    }
            if (setup != null) setup.accept(level);
        });
        // Biome paint is kept in the chunks, so a case that paints biomes would otherwise leave the next run with nothing to change.
        world.getServer().runCommand("fillbiome " + MIN_X + " " + MIN_Y + " " + MIN_Z + " " + MAX_X + " " + MAX_Y + " " + MAX_Z + " minecraft:plains");
    }

    /** A flat square of stone, {@code half} blocks each way from the centre, floating clear of the ground: a face the surface tools can fill or extrude. */
    private static void platform(ServerLevel level, int cx, int y, int cz, int half) {
        for (int x = cx - half; x <= cx + half; x++)
            for (int z = cz - half; z <= cz + half; z++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2 | 16);
    }

    /** Stairs facing each way on the surface, for the tools that carry a block's orientation across. */
    private static void stairs(ServerLevel level, int cx, int cz) {
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        int i = 0;
        for (int x = cx - 2; x <= cx + 2; x++)
            for (int z = cz - 2; z <= cz + 2; z++)
                level.setBlock(new BlockPos(x, height(x, z), z), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(facing, net.minecraft.core.Direction.from2DDataValue(i++ % 4)), 2 | 16);
    }

    // ---- the cases ---------------------------------------------------------------------------------------------------------------

    private static Consumer<Options> base(Consumer<Options> more) {
        return o -> {
            o.radius = 4; o.strength = 2; o.seed = 42; o.noiseScale = 6; o.block = "minecraft:andesite"; o.secondary = "minecraft:stone";
            o.text = "BUILD"; o.biome = "minecraft:desert"; o.inSelection = false; o.keepExisting = false; o.confirmEdits = false;
            more.accept(o);
        };
    }

    public static List<Case> cases() {
        Point a = surface(0, 0), b = surface(-6, 5), c = surface(6, 6), d = surface(-4, -4);
        var all = new ArrayList<Case>();
        // Every tool that changes the world from a single click on the ground, with the brush at its defaults.
        for (Tool tool : new Tool[]{Tool.PAINTER, Tool.NOISE_PAINTER, Tool.FREEHAND_DRAW, Tool.ERASE, Tool.SHAPE, Tool.SCULPT_DRAW, Tool.ROCK, Tool.WELD, Tool.MELT,
                Tool.TEXT, Tool.ELEVATION, Tool.FLATTEN, Tool.SLOPE, Tool.SMOOTH, Tool.DISTORT, Tool.ROUGHEN, Tool.SHATTER, Tool.EXTRUDE, Tool.BIOME_PAINTER, Tool.CLENTAMINATOR})
            all.add(new Case(tool.name().toLowerCase(java.util.Locale.ROOT), tool, base(o -> o.tool = tool), null, false, a));
        // The same tools where an option changes which code runs.
        all.add(new Case("painter_type_replace", Tool.PAINTER, base(o -> { o.tool = Tool.PAINTER; o.block = "minecraft:oak_stairs"; o.typeReplace = true; o.surfaceOnly = false; }), level -> stairs(level, -6, 5), false, b));
        all.add(new Case("painter_copy_properties", Tool.PAINTER, base(o -> { o.tool = Tool.PAINTER; o.block = "minecraft:oak_stairs"; o.copyProperties = true; o.surfaceOnly = false; }), level -> stairs(level, -6, 5), false, b));
        all.add(new Case("noise_painter_palette", Tool.NOISE_PAINTER, base(o -> { o.tool = Tool.NOISE_PAINTER; o.materials = "minecraft:andesite,minecraft:stone,minecraft:gravel"; }), null, false, c));
        all.add(new Case("shape_hollow", Tool.SHAPE, base(o -> { o.tool = Tool.SHAPE; o.hollow = true; o.radius = 5; }), null, false, c));
        all.add(new Case("shape_invert", Tool.SHAPE, base(o -> { o.tool = Tool.SHAPE; o.invert = true; o.radius = 3; }), null, false, a));
        all.add(new Case("sculpt_draw_invert", Tool.SCULPT_DRAW, base(o -> { o.tool = Tool.SCULPT_DRAW; o.invert = true; }), null, false, b));
        all.add(new Case("elevation_invert", Tool.ELEVATION, base(o -> { o.tool = Tool.ELEVATION; o.invert = true; o.radius = 6; }), null, false, d));
        // In the middle of the box, so a fill that spreads from it stays inside the part of the world the test captures and resets.
        Point top = new Point(0, 13, 0);
        Consumer<ServerLevel> slab = level -> platform(level, 0, 13, 0, 2);
        all.add(new Case("extrude_platform", Tool.EXTRUDE, base(o -> { o.tool = Tool.EXTRUDE; o.extrudeCount = 2; }), slab, false, top));
        all.add(new Case("extrude_invert", Tool.EXTRUDE, base(o -> { o.tool = Tool.EXTRUDE; o.invert = true; o.extrudeCount = 1; }), slab, false, top));
        all.add(new Case("extrude_smart", Tool.EXTRUDE, base(o -> { o.tool = Tool.EXTRUDE; o.smartExtrude = true; o.extrudeCount = 2; }), slab, false, top));
        all.add(new Case("smooth_density", Tool.SMOOTH, base(o -> { o.tool = Tool.SMOOTH; o.radius = 6; o.smoothRatio = 70; }), null, false, d));
        all.add(new Case("painter_symmetry", Tool.PAINTER, base(o -> { o.tool = Tool.PAINTER; o.symmetryX = true; o.symmetryOrigin = new Point(0, 0, 0); }), null, false, b));
        // Tools that carry their own input.
        all.add(new Case("script_brush", Tool.SCRIPT_BRUSH, base(o -> { o.tool = Tool.SCRIPT_BRUSH; o.script = "if (x + y + z) % 3 == 0 then return blocks.gold_block end"; }), null, false, a));
        all.add(new Case("tinker", Tool.TINKER, base(o -> o.tool = Tool.TINKER),
                level -> level.setBlock(new BlockPos(4, height(4, 4) + 1, 4), Blocks.STONE_BRICK_STAIRS.defaultBlockState(), 2 | 16), false, new Point(4, height(4, 4) + 1, 4)));
        all.add(new Case("line", Tool.LINE, base(o -> { o.tool = Tool.LINE; o.radius = 1; }), null, false, surface(-10, -10), surface(10, 8)));
        all.add(new Case("path_catmull_rom", Tool.PATH, base(o -> { o.tool = Tool.PATH; o.radius = 1; o.pathMode = Options.PathMode.CATMULL_ROM;
            o.nodes = new ArrayList<>(List.of(new Point(-8, 12, -8), new Point(-2, 16, 0), new Point(4, 12, 6), new Point(10, 15, 10))); }), null, false, new Point(-8, 12, -8)));
        all.add(new Case("path_bresenham", Tool.PATH, base(o -> { o.tool = Tool.PATH; o.pathMode = Options.PathMode.BRESENHAM; o.extendToGround = true;
            o.nodes = new ArrayList<>(List.of(new Point(-6, 8, 6), new Point(6, 9, -6))); }), null, false, new Point(-6, 8, 6)));
        all.add(new Case("modelling_hull", Tool.MODELLING, base(o -> { o.tool = Tool.MODELLING;
            o.nodes = new ArrayList<>(List.of(new Point(-8, 12, -8), new Point(-3, 12, -8), new Point(-8, 17, -8), new Point(-8, 12, -3))); }), null, false, new Point(-8, 12, -8)));
        all.add(new Case("gradient_painter", Tool.GRADIENT_PAINTER, base(o -> { o.tool = Tool.GRADIENT_PAINTER; o.radius = 6; o.materials = "minecraft:andesite,minecraft:stone,minecraft:blackstone";
            o.nodes = new ArrayList<>(List.of(new Point(-6, 2, 0), new Point(6, 2, 0))); }), null, false, a));
        all.add(new Case("floodfill", Tool.FLOODFILL, base(o -> { o.tool = Tool.FLOODFILL; o.blockLimit = 200; o.floodUp = false; o.floodDown = false; o.floodHorizontal = true; }), slab, false, top));
        all.add(new Case("floodfill_up_down", Tool.FLOODFILL, base(o -> { o.tool = Tool.FLOODFILL; o.blockLimit = 120; o.floodUp = true; o.floodDown = true; o.floodHorizontal = true; o.floodGap = 1; }), slab, false, top));
        all.add(new Case("fluid_ball_snow", Tool.FLUID_BALL, base(o -> { o.tool = Tool.FLUID_BALL; o.fluidType = Options.FluidType.SNOW; o.radius = 2; o.flowLength = 4; o.requireSupport = true; }), slab, false, top));
        // Tools that work from the clipboard or the selection made from it.
        all.add(new Case("stamp", Tool.STAMP, base(o -> { o.tool = Tool.STAMP; o.baseChance = 1; o.randomYaw = false; o.randomFlip = false; o.minSpacing = 1; o.radius = 3; }), null, true, d));
        all.add(new Case("stamp_random", Tool.STAMP, base(o -> { o.tool = Tool.STAMP; o.baseChance = .6; o.randomYaw = true; o.randomFlip = true; o.minSpacing = .5; o.radius = 6; }), null, true, d));
        all.add(new Case("smear", Tool.SMEAR, base(o -> { o.tool = Tool.SMEAR; o.offsetX = 0; o.offsetY = 5; o.offsetZ = 0; }), null, true, new Point(9, 11, -1)));
        all.add(new Case("modify_translate", Tool.MODIFY, base(o -> { o.tool = Tool.MODIFY; o.modifyMode = Options.ModifyMode.TRANSLATE_COPIES; o.copies = 2; o.offsetX = -6; o.offsetY = 0; o.offsetZ = 5; }), null, true, new Point(9, 11, -1)));
        return all;
    }

    // ---- running and comparing ---------------------------------------------------------------------------------------------------

    private void runAll() {
        boolean update = "1".equals(System.getenv("CRAFTIUM_GOLDEN_UPDATE"));
        var out = Path.of("golden");
        var results = new TreeMap<String, String>();
        var problems = new ArrayList<String>();
        var covered = new java.util.TreeSet<String>();
        // Random ticks turn grass under a new block into dirt between two captures, which would read as a tool that is not deterministic.
        world.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        for (Case test : cases()) {
            String first = execute(test, out);
            if (update) {
                String second = execute(test, out);
                if (!first.equals(second)) problems.add(test.name + " is not deterministic: " + first + " then " + second);
            }
            results.put(test.name, first);
            covered.add(test.tool.name());
            System.out.println("CRAFTIUM_GOLDEN " + test.name + " " + first);
        }
        System.out.println("CRAFTIUM_GOLDEN_COVERED " + String.join(" ", covered));
        if (update) { writeExpected(out, results); System.out.println("CRAFTIUM_GOLDEN_UPDATED " + out.toAbsolutePath().resolve("GoldenToolExpected.java")); }
        else {
            for (var e : results.entrySet()) {
                String expected = GoldenToolExpected.CASES.get(e.getKey());
                if (expected == null) problems.add(e.getKey() + " has no expected value; run with CRAFTIUM_GOLDEN_UPDATE=1");
                else if (!expected.equals(e.getValue())) problems.add(e.getKey() + " changed: expected " + expected + " but got " + e.getValue() + " (see " + out.toAbsolutePath().resolve(e.getKey() + ".txt") + ")");
            }
            for (String name : GoldenToolExpected.CASES.keySet()) if (!results.containsKey(name)) problems.add(name + " is expected but no longer runs");
        }
        if (!problems.isEmpty()) throw new AssertionError("Golden tool output differs:\n  " + String.join("\n  ", problems));
        System.out.println("CRAFTIUM_GOLDEN_PASS " + results.size() + " cases");
    }

    /** Runs one case on freshly built terrain and returns {@code "<blocks changed> <hash>"}. */
    private String execute(Case test, Path out) {
        reset(world, test.setup);
        ctx.runOnClient(mc -> ClientState.options = new Options());
        request("clear", null);
        if (test.clipboard) {
            options(o -> { o.position1 = CLIPBOARD_FROM; o.position2 = CLIPBOARD_TO; });
            request("select_box", null);
            request("copy", null);
        }
        options(test.options);
        var before = capture(world);
        for (Point click : test.clicks) request("use", click);
        var lines = changes(before, capture(world));
        try {
            Files.createDirectories(out);
            Files.write(out.resolve(test.name + ".txt"), lines, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        return lines.size() + " " + hash(lines);
    }

    /** Every block in the test box, then the biome of each 4 by 4 by 4 cell, as text keyed by position. */
    public static Map<String, String> capture(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var map = new LinkedHashMap<String, String>();
            var pos = new BlockPos.MutableBlockPos();
            for (int x = MIN_X; x <= MAX_X; x++)
                for (int y = MIN_Y; y <= MAX_Y; y++)
                    for (int z = MIN_Z; z <= MAX_Z; z++) map.put(x + "," + y + "," + z, level.getBlockState(pos.set(x, y, z)).toString());
            for (int x = MIN_X; x <= MAX_X; x += 4)
                for (int y = MIN_Y; y <= MAX_Y; y += 4)
                    for (int z = MIN_Z; z <= MAX_Z; z += 4) map.put("biome " + x + "," + y + "," + z, level.getBiome(pos.set(x, y, z)).getRegisteredName());
            return map;
        });
    }

    /** Each position whose text differs between two captures, as {@code "<position>: <before> -> <after>"}, in capture order. */
    public static List<String> changes(Map<String, String> before, Map<String, String> after) {
        var lines = new ArrayList<String>();
        for (var e : after.entrySet()) if (!e.getValue().equals(before.get(e.getKey()))) lines.add(e.getKey() + ": " + before.get(e.getKey()) + " -> " + e.getValue());
        return lines;
    }

    /** The first eight bytes of a SHA-256 of the lines, as hex. */
    public static String hash(List<String> lines) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) { digest.update(line.getBytes(StandardCharsets.UTF_8)); digest.update((byte) '\n'); }
            byte[] bytes = digest.digest();
            var hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", bytes[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void writeExpected(Path out, Map<String, String> results) {
        var source = new StringBuilder("package dev.craftium.test;\n\nimport java.util.Map;\n\n/** Written by {@link GoldenToolGameTest} when run with CRAFTIUM_GOLDEN_UPDATE=1: \"<blocks changed> <hash of the change list>\" for each case. */\npublic final class GoldenToolExpected {\n    private GoldenToolExpected() {}\n    public static final Map<String, String> CASES = Map.ofEntries(\n");
        var entries = new ArrayList<String>();
        for (var e : results.entrySet()) entries.add("        Map.entry(\"" + e.getKey() + "\", \"" + e.getValue() + "\")");
        source.append(String.join(",\n", entries)).append("\n    );\n}\n");
        try { Files.writeString(out.resolve("GoldenToolExpected.java"), source.toString(), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    // ---- driving the editor ------------------------------------------------------------------------------------------------------

    private void options(Consumer<Options> set) { ctx.runOnClient(mc -> { ClientState.options.confirmEdits = false; set.accept(ClientState.options); }); }

    private void request(String action, Point at) {
        // The production server throttles requests by wall-clock time, including in accelerated game tests.
        try { Thread.sleep(80); } catch (InterruptedException e) { throw new AssertionError(e); }
        ctx.runOnClient(mc -> { ClientState.options.confirmEdits = false; CraftiumClient.send(action, at, UP); });
        world.getConnection().waitForServerboundPackets();
        ctx.waitTicks(4);
        world.getConnection().waitForClientboundPackets();
        ctx.waitFor(mc -> !ClientState.status.busy(), 400);
        world.getConnection().waitForClientboundPackets();
        if (ctx.computeOnClient(mc -> ClientState.status.error()))
            throw new AssertionError("Request " + action + " failed: " + ctx.computeOnClient(mc -> ClientState.status.message()));
    }
}
