package dev.craftium.client;

import dev.craftium.core.EditorControls.Action;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import dev.craftium.test.GoldenToolExpected;
import dev.craftium.test.GoldenToolGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Drives the editor the way a player does, through the screen's own mouse and key handlers, and records what each tool does with the input:
 * the client state after every step (points added, corners, rulers, a shape put down, a preview waiting) and every block the world ends up
 * changing. It exists so that moving the editor's input handling into one class per kind of tool can be shown to change nothing.
 * <p>
 * Every step aims the camera straight down at a block of the golden terrain and clicks the middle of the screen, so a click lands on the top
 * of that block. A tool that is just a click on the ground must do exactly what the golden test's direct request does, and is checked against
 * {@link GoldenToolExpected}; the others have their own table, {@link InteractionExpected}.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=interaction}. After a change that is meant to alter how a tool takes input, run it with
 * {@code CRAFTIUM_INTERACTION_UPDATE=1}: every scenario then runs twice (one that differs from itself is reported), and the new table is
 * written to {@code interaction/InteractionExpected.java} in the run directory, next to a file for each scenario with what it did.
 */
public final class InteractionGameTest {
    private static final int SETTLE_TICKS = 5, PAUSE_MS = 170;

    /** A script of input for one tool. */
    @FunctionalInterface private interface Script { void run(Driver d); }

    /** One scenario: the options and world it starts from, the tool it uses, what is done with the mouse and keys, and the golden case it must match, if any. */
    private record Scenario(String name, Tool tool, Consumer<Options> options, Consumer<ServerLevel> setup, boolean clipboard, Script script, String golden) {}

    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;
    private final List<String> trace = new ArrayList<>();

    private InteractionGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new InteractionGameTest(c, w).runAll(); }

    // ---- the scenarios -----------------------------------------------------------------------------------------------------------

    /** The golden cases that are a plain click, or two, on the ground. The rest of the golden cases need input a click cannot give. */
    private static final Set<String> CLICK_CASES = Set.of("painter", "noise_painter", "freehand_draw", "erase", "sculpt_draw", "rock", "weld", "melt", "text", "elevation",
        "flatten", "smooth", "distort", "roughen", "shatter", "extrude_platform", "biome_painter", "clentaminator", "script_brush", "tinker", "line", "floodfill", "fluid_ball_snow",
        "stamp");

    private static List<Scenario> scenarios() {
        var all = new ArrayList<Scenario>();
        for (var golden : GoldenToolGameTest.cases())
            if (CLICK_CASES.contains(golden.name()))
                all.add(new Scenario("click_" + golden.name(), golden.tool(), golden.options(), golden.setup(), golden.clipboard(), d -> { for (Point click : golden.clicks()) d.click(click); }, golden.name()));

        Point a = GoldenToolGameTest.surface(0, 0), b = GoldenToolGameTest.surface(-6, 5), c = GoldenToolGameTest.surface(6, 6), e = GoldenToolGameTest.surface(-4, -4);
        Point f = GoldenToolGameTest.surface(5, -5), g = GoldenToolGameTest.surface(-8, -8);
        Consumer<ServerLevel> slab = level -> { for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(new BlockPos(x, 13, z), Blocks.STONE.defaultBlockState(), 2 | 16); };
        Point top = new Point(0, 13, 0), structureTop = new Point(9, 12, -1);

        // Selecting.
        all.add(new Scenario("box_select_then_delete", Tool.BOX_SELECT, o -> o.confirmEdits = false, null, false, d -> { d.click(e); d.click(c); d.key(GLFW_KEY_DELETE, 0); }, null));
        all.add(new Scenario("magic_select_then_delete", Tool.MAGIC_SELECT, o -> { o.confirmEdits = false; o.selectLimit = 40; }, null, false, d -> { d.click(a); d.key(GLFW_KEY_DELETE, 0); }, null));
        all.add(new Scenario("freehand_select", Tool.FREEHAND_SELECT, o -> { o.confirmEdits = false; o.radius = 3; }, null, false, d -> d.click(b), null));
        all.add(new Scenario("lasso_select", Tool.LASSO_SELECT, o -> o.confirmEdits = false, null, false, d -> d.lasso(a, new int[][]{{-60, -40}, {60, -40}, {60, 40}, {-60, 40}}), null));
        // Measuring and marking.
        all.add(new Scenario("ruler_two_points", Tool.RULER, o -> o.confirmEdits = false, null, false, d -> { d.click(e); d.click(c); }, null));
        all.add(new Scenario("pivot_click", Tool.PIVOT, o -> o.confirmEdits = false, null, false, d -> d.click(b), null));
        all.add(new Scenario("annotation_text", Tool.ANNOTATION, o -> { o.confirmEdits = false; o.annotationMode = "TEXT"; o.text = "HERE"; }, null, false, d -> d.click(a), null));
        all.add(new Scenario("annotation_draw", Tool.ANNOTATION, o -> { o.confirmEdits = false; o.annotationMode = "DRAW"; }, null, false, d -> d.drag(e, a, c), null));
        // Tools made of points.
        all.add(new Scenario("path_points_then_enter", Tool.PATH, o -> { o.confirmEdits = false; o.radius = 1; o.pathMode = Options.PathMode.CATMULL_ROM; }, null, false,
            d -> { d.click(g); d.click(e); d.click(c); d.key('E', 0); d.key(GLFW_KEY_ENTER, 0); }, null));
        all.add(new Scenario("modelling_points_then_enter", Tool.MODELLING, o -> o.confirmEdits = false, null, false,
            d -> { d.click(g); d.click(e); d.click(c); d.click(f); d.key(GLFW_KEY_ENTER, 0); }, null));
        all.add(new Scenario("gradient_shift_anchors_then_paint", Tool.GRADIENT_PAINTER, o -> { o.confirmEdits = false; o.radius = 6; o.materials = "minecraft:andesite,minecraft:stone,minecraft:blackstone"; }, null, false,
            d -> { d.shift(true); d.click(e); d.click(c); d.shift(false); d.click(a); }, null));
        all.add(new Scenario("slope_two_clicks", Tool.SLOPE, o -> { o.confirmEdits = false; o.radius = 4; o.strength = 2; }, null, false, d -> { d.click(e); d.click(c); }, null));
        // Tools that put something down first.
        all.add(new Scenario("shape_place_then_enter", Tool.SHAPE, o -> { o.confirmEdits = false; o.radius = 3; o.block = "minecraft:andesite"; }, null, false, d -> { d.click(c); d.key(GLFW_KEY_ENTER, 0); }, null));
        all.add(new Scenario("shape_place_then_escape", Tool.SHAPE, o -> { o.confirmEdits = false; o.radius = 3; o.block = "minecraft:andesite"; }, null, false, d -> { d.click(c); d.key(GLFW_KEY_ESCAPE, 0); }, null));
        all.add(new Scenario("modify_anchor_then_enter", Tool.MODIFY, o -> { o.confirmEdits = false; o.modifyMode = Options.ModifyMode.TRANSLATE_COPIES; o.copies = 2; o.offsetX = -6; o.offsetY = 0; o.offsetZ = 5; }, null, true,
            d -> { d.click(structureTop); d.key(GLFW_KEY_ENTER, 0); }, null));
        all.add(new Scenario("smear_clipboard", Tool.SMEAR, o -> { o.confirmEdits = false; o.offsetX = 0; o.offsetY = 5; o.offsetZ = 0; }, null, true, d -> d.click(structureTop), null));
        // A click that must be confirmed before it builds.
        all.add(new Scenario("extrude_large_click_confirmed", Tool.EXTRUDE, o -> { o.confirmEdits = false; o.extrudeCount = 2; o.block = "minecraft:andesite"; o.largeSelectionWarnings = true; o.hoverPreviewLimit = 1; }, slab, false,
            d -> { d.click(top); d.key(GLFW_KEY_ENTER, 0); }, "extrude_platform"));
        all.add(new Scenario("floodfill_large_click_confirmed", Tool.FLOODFILL, o -> { o.confirmEdits = false; o.blockLimit = 200; o.block = "minecraft:andesite"; o.floodUp = false; o.floodDown = false; o.floodHorizontal = true; o.largeSelectionWarnings = true; o.hoverPreviewLimit = 1; }, slab, false,
            d -> { d.click(top); d.key(GLFW_KEY_ENTER, 0); }, "floodfill"));
        // A stroke, and undoing it.
        all.add(new Scenario("painter_stroke", Tool.PAINTER, o -> { o.confirmEdits = false; o.radius = 2; o.block = "minecraft:andesite"; }, null, false, d -> d.drag(e, a, c), null));
        all.add(new Scenario("painter_click_then_undo", Tool.PAINTER, o -> { o.confirmEdits = false; o.radius = 3; o.block = "minecraft:andesite"; }, null, false, d -> { d.click(a); d.key('Z', 2); }, null));
        return all;
    }

    // ---- running and comparing ---------------------------------------------------------------------------------------------------

    private void runAll() {
        boolean update = "1".equals(System.getenv("CRAFTIUM_INTERACTION_UPDATE"));
        var out = Path.of("interaction");
        var results = new TreeMap<String, String>();
        var problems = new ArrayList<String>();
        c.runOnClient(mc -> { mc.getWindow().setWindowed(1200, 800); mc.options.guiScale().set(2); mc.resizeGui(); mc.player.getAbilities().flying = true; });
        // Random ticks turn grass under a new block into dirt between two captures, which would read as a scenario that is not deterministic.
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);
        // For looking into one scenario: CRAFTIUM_INTERACTION_ONLY=<part of its name> runs only those, and then compares only those.
        String only = System.getenv("CRAFTIUM_INTERACTION_ONLY");
        for (Scenario scenario : scenarios()) {
            if (only != null && !scenario.name.contains(only)) continue;
            String first = execute(scenario, out, "");
            if (update) {
                String second = execute(scenario, out, ".run2");
                if (!first.equals(second)) problems.add(scenario.name + " is not deterministic: " + first + " then " + second);
            }
            results.put(scenario.name, first);
            System.out.println("CRAFTIUM_INTERACTION " + scenario.name + " " + first);
            if (scenario.golden != null) {
                String world = first.substring(0, first.indexOf(" client:")), expected = GoldenToolExpected.CASES.get(scenario.golden);
                if (!world.equals(expected)) problems.add(scenario.name + " built " + world + " by input, but the direct request for " + scenario.golden + " builds " + expected);
            }
        }
        if (update) { writeExpected(out, results); System.out.println("CRAFTIUM_INTERACTION_UPDATED " + out.toAbsolutePath().resolve("InteractionExpected.java")); }
        else {
            for (var e : results.entrySet()) {
                String expected = InteractionExpected.CASES.get(e.getKey());
                if (expected == null) problems.add(e.getKey() + " has no expected value; run with CRAFTIUM_INTERACTION_UPDATE=1");
                else if (!expected.equals(e.getValue())) problems.add(e.getKey() + " changed: expected " + expected + " but got " + e.getValue() + " (see " + out.toAbsolutePath().resolve(e.getKey() + ".txt") + ")");
            }
            if (only == null) for (String name : InteractionExpected.CASES.keySet()) if (!results.containsKey(name)) problems.add(name + " is expected but no longer runs");
        }
        if (!problems.isEmpty()) throw new AssertionError("Editor input differs:\n  " + String.join("\n  ", problems));
        System.out.println("CRAFTIUM_INTERACTION_PASS " + results.size() + " scenarios");
    }

    /**
     * Runs one scenario on fresh terrain with the editor open and returns {@code "<blocks changed> <hash> client:<hash of the trace>"}. The
     * trace is written to {@code <scenario><fileSuffix>.txt}; a second run of the same scenario has its own suffix, so the two can be compared.
     */
    private String execute(Scenario s, Path out, String fileSuffix) {
        GoldenToolGameTest.reset(w, s.setup);
        w.getConnection().waitForClientboundPackets();
        c.runOnClient(mc -> {
            ClientState.options = new Options();
            ClientState.options.confirmEdits = false; // before anything is sent: a fresh Options stages every edit for Enter, and a staged one would wait for the scenario's own Enter
            CraftiumClient.resetEditor(); ClientState.rulerPoints.clear(); ClientState.rulers.clear(); ModifyPreview.edit();
        });
        // Annotations are kept by the server and "clear" does not remove them, so a scenario would otherwise start with the last one's.
        pause();
        c.runOnClient(mc -> CraftiumClient.send("annotation_clear", GoldenToolGameTest.surface(0, 0), new Point(0, 1, 0)));
        settle();
        // It is built over a few ticks like any edit, so the status still lists the annotations for a moment after the reply.
        try { c.waitFor(mc -> ClientState.status.annotations() == null || ClientState.status.annotations().isEmpty(), 300); }
        catch (AssertionError e) {
            throw new AssertionError("Annotations were not cleared before " + s.name + ": " + c.computeOnClient(mc -> ClientState.status.annotations() + " / message=" + ClientState.status.message() + " / error=" + ClientState.status.error()), e);
        }
        request("clear");
        if (s.clipboard) {
            options(o -> { o.position1 = GoldenToolGameTest.CLIPBOARD_FROM; o.position2 = GoldenToolGameTest.CLIPBOARD_TO; });
            request("select_box"); request("copy");
        }
        options(s.options);
        c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).select(s.tool));
        c.waitTicks(2);
        trace.clear();
        var before = GoldenToolGameTest.capture(w);
        new Driver().note("start");
        s.script.run(new Driver());
        var lines = GoldenToolGameTest.changes(before, GoldenToolGameTest.capture(w));
        try {
            Files.createDirectories(out);
            var file = new ArrayList<String>(trace);
            file.add("--- blocks changed");
            file.addAll(lines);
            Files.write(out.resolve(s.name + fileSuffix + ".txt"), file, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        return lines.size() + " " + GoldenToolGameTest.hash(lines) + " client:" + GoldenToolGameTest.hash(trace);
    }

    private static void writeExpected(Path out, java.util.Map<String, String> results) {
        var source = new StringBuilder("package dev.craftium.client;\n\nimport java.util.Map;\n\n/** Written by {@link InteractionGameTest} when run with CRAFTIUM_INTERACTION_UPDATE=1: \"<blocks changed> <hash> client:<hash of the client trace>\" for each scenario. */\nfinal class InteractionExpected {\n    private InteractionExpected() {}\n    static final Map<String, String> CASES = Map.ofEntries(\n");
        var entries = new ArrayList<String>();
        for (var e : results.entrySet()) entries.add("        Map.entry(\"" + e.getKey() + "\", \"" + e.getValue() + "\")");
        source.append(String.join(",\n", entries)).append("\n    );\n}\n");
        try { Files.createDirectories(out); Files.writeString(out.resolve("InteractionExpected.java"), source.toString(), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    // ---- driving the editor ------------------------------------------------------------------------------------------------------

    private void options(Consumer<Options> set) { c.runOnClient(mc -> { ClientState.options.confirmEdits = false; set.accept(ClientState.options); }); }

    /** A request that is not part of what is being tested, such as selecting the clipboard's blocks before a tool that uses them. */
    private void request(String action) {
        pause();
        c.runOnClient(mc -> CraftiumClient.send(action, null, new Point(0, 1, 0)));
        settle();
        if (c.computeOnClient(mc -> ClientState.status.error())) throw new AssertionError("Request " + action + " failed: " + c.computeOnClient(mc -> ClientState.status.message()));
    }

    private static void pause() { try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) { throw new AssertionError(e); } }

    private void settle() {
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(SETTLE_TICKS);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 200);
        w.getConnection().waitForClientboundPackets();
    }

    /** What the client knows about the editor's state, in a form that is the same every run. */
    private String client() {
        return c.computeOnClient(mc -> {
            var o = ClientState.options;
            var s = ClientState.status;
            // The numbers in a message are left out: a tool that builds past the box the test captures (text, copies) reports fewer changes the second
            // time, because what it built outside the box is still there. The blocks inside the box are what is compared.
            return "tool=" + o.tool + " nodes=" + o.nodes + " box=" + ClientState.boxCorners + " rulerPoints=" + ClientState.rulerPoints + " rulers=" + ClientState.rulers.size() + " shape=" + ShapeObject.active()
                + " placement=" + PlacementPreview.active() + " modifyAnchored=" + ModifyPreview.anchored() + " pending=" + ClientState.hasPending() + " selection=" + s.selectionSize()
                + " annotations=" + (s.annotations() == null ? 0 : s.annotations().size()) + " annotationPoints=" + o.annotationPoints.size()
                + " pivot=" + o.pivotSet + "@" + o.pivotX + "," + o.pivotY + "," + o.pivotZ + " error=" + s.error() + " message=" + s.message().replaceAll("\\d+", "#");
        });
    }

    /** The mouse and keys of one scenario. Every action waits for the server and writes the client's state to the trace. */
    private final class Driver {
        void note(String label) { trace.add(label + " :: " + client()); }

        /** Puts the camera above {@code block}, looking straight down, so the middle of the screen is on its top face. */
        private void aim(Point block) {
            c.runOnClient(mc -> {
                var player = mc.player;
                player.setPos(block.x() + .5, block.y() + 1 + 6 - player.getEyeHeight(), block.z() + .5);
                player.setXRot(90); player.setYRot(0); player.setDeltaMovement(Vec3.ZERO); player.setOldPosAndRot();
            });
            c.waitTicks(4);
        }

        private MouseButtonEvent event(net.minecraft.client.Minecraft mc, int offsetX, int offsetY) {
            return new MouseButtonEvent(mc.getWindow().getGuiScaledWidth() / 2.0 + offsetX, mc.getWindow().getGuiScaledHeight() / 2.0 + offsetY,
                new MouseButtonInfo(EditorBindings.get(Action.USE_TOOL).key(), 0));
        }

        /** Clicks the top of {@code block} with the button that uses a tool. */
        void click(Point block) {
            pause();
            aim(block);
            c.runOnClient(mc -> { var screen = (EditorScreen) mc.gui.screen(); var e = event(mc, 0, 0); screen.mouseClicked(e, false); screen.mouseReleased(e); });
            settle();
            note("click " + block);
        }

        /** Presses over the first block, moves over the rest, and lets go over the last, a tick's wait and a pause apart. */
        void drag(Point... path) {
            pause();
            aim(path[0]);
            c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).mouseClicked(event(mc, 0, 0), false));
            settle();
            note("press " + path[0]);
            for (int i = 1; i < path.length; i++) {
                pause(); pause();
                aim(path[i]);
                c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).mouseDragged(event(mc, 0, 0), 0, 0));
                settle();
                note("drag " + path[i]);
            }
            c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).mouseReleased(event(mc, 0, 0)));
            settle();
            note("release");
        }

        /** A lasso: the camera above {@code block}, the button pressed at the first corner (in GUI pixels from the middle of the screen), moved through the rest, and let go. */
        void lasso(Point block, int[][] corners) {
            pause();
            aim(block);
            c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).mouseClicked(event(mc, corners[0][0], corners[0][1]), false));
            for (int i = 1; i < corners.length; i++) { int[] corner = corners[i]; c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).mouseDragged(event(mc, corner[0], corner[1]), 0, 0)); }
            c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).mouseReleased(event(mc, corners[corners.length - 1][0], corners[corners.length - 1][1])));
            settle();
            note("lasso");
        }

        /** A keystroke, pressed and let go. */
        void key(int key, int modifiers) {
            pause();
            c.runOnClient(mc -> { var screen = mc.gui.screen(); var e = new KeyEvent(key, 0, modifiers); screen.keyPressed(e); screen.keyReleased(e); });
            settle();
            note("key " + key + "/" + modifiers);
        }

        void shift(boolean down) { if (down) c.getInput().holdKey(GLFW_KEY_LEFT_SHIFT); else c.getInput().releaseKey(GLFW_KEY_LEFT_SHIFT); c.waitTicks(2); }
    }
}
