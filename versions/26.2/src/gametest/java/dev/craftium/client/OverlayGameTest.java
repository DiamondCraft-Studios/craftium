package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import dev.craftium.test.GoldenToolGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Pins what each tool draws in the world: the editor is opened over the golden terrain with the camera above a block, a tool is chosen, and
 * every call the overlay receives for one tick's drawing and one frame's (the handles) is recorded through {@link OverlayLog}. Each tool is
 * recorded hovering, and the states that have a drawing of their own are recorded too: the corners of a box, the points of a path with one
 * selected, a Modify edit with its anchor, a shape put down, the biome label. It exists so that moving that drawing into the interactions can
 * be shown to change nothing.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=overlay}. After a change that is meant to alter what a tool draws, run it with
 * {@code CRAFTIUM_OVERLAY_UPDATE=1}: every case then runs twice (one that differs from itself is reported), and the new table is written to
 * {@code overlay/OverlayExpected.java} in the run directory, next to a file for each case with the calls it recorded.
 */
public final class OverlayGameTest {
    private record Case(String name, Tool tool, Consumer<Options> options, Runnable state, boolean selection) {
        Case(String name, Tool tool, Consumer<Options> options, Runnable state) { this(name, tool, options, state, false); }
    }
    private boolean selected;

    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;

    private OverlayGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new OverlayGameTest(c, w).runAll(); }

    private static final Point HOVER = GoldenToolGameTest.surface(0, 0);

    private static List<Case> cases() {
        var all = new ArrayList<Case>();
        for (Tool tool : Tool.values()) all.add(new Case("hover_" + tool.name().toLowerCase(), tool, o -> {}, () -> {}));
        Point a = GoldenToolGameTest.surface(-4, -4), b = GoldenToolGameTest.surface(6, 6), d = GoldenToolGameTest.surface(5, -5);
        all.add(new Case("box_one_corner", Tool.BOX_SELECT, o -> {}, () -> ClientState.boxCorners.add(a)));
        all.add(new Case("box_two_corners", Tool.BOX_SELECT, o -> {}, () -> { ClientState.boxCorners.add(a); ClientState.boxCorners.add(b); }));
        all.add(new Case("path_three_points_last_selected", Tool.PATH, o -> {}, () -> { ClientState.options.nodes.add(a); ClientState.options.nodes.add(b); ClientState.options.nodes.add(d); selectNode(2); }));
        all.add(new Case("path_three_points_first_selected", Tool.PATH, o -> {}, () -> { ClientState.options.nodes.add(a); ClientState.options.nodes.add(b); ClientState.options.nodes.add(d); selectNode(0); }));
        all.add(new Case("slope_two_points", Tool.SLOPE, o -> {}, () -> { ClientState.options.nodes.add(a); ClientState.options.nodes.add(b); selectNode(1); }));
        all.add(new Case("modify_anchored", Tool.MODIFY, o -> { o.modifyMode = Options.ModifyMode.TRANSLATE_COPIES; o.copies = 2; o.offsetX = -6; o.offsetZ = 5; }, () -> ModifyPreview.place(a)));
        Consumer<Options> translate = o -> { o.modifyMode = Options.ModifyMode.TRANSLATE_COPIES; o.copies = 2; o.offsetX = -6; o.offsetZ = 5; };
        all.add(new Case("modify_selection_hover", Tool.MODIFY, translate, () -> {}, true));
        all.add(new Case("modify_selection_anchored", Tool.MODIFY, translate, () -> ModifyPreview.place(a), true));
        Consumer<Options> revolve = o -> { o.modifyMode = Options.ModifyMode.REVOLVE; o.copies = 3; };
        all.add(new Case("modify_revolve_hover", Tool.MODIFY, revolve, () -> {}, true));
        all.add(new Case("modify_revolve_anchored", Tool.MODIFY, revolve, () -> ModifyPreview.place(a), true));
        all.add(new Case("box_select_with_selection", Tool.BOX_SELECT, o -> {}, () -> {}, true));
        all.add(new Case("shape_placed", Tool.SHAPE, o -> { o.radius = 3; o.block = "minecraft:andesite"; }, () -> ShapeObject.place(b)));
        all.add(new Case("biome_painter_visualised", Tool.BIOME_PAINTER, o -> o.visualizeBiome = true, () -> {}));
        all.add(new Case("clentaminator_visualised", Tool.CLENTAMINATOR, o -> o.visualizeBiome = true, () -> {}));
        all.add(new Case("biome_painter_not_visualised", Tool.BIOME_PAINTER, o -> o.visualizeBiome = false, () -> {}));
        return all;
    }

    private void request(String action) {
        try { Thread.sleep(170); } catch (InterruptedException e) { throw new AssertionError(e); }
        c.runOnClient(mc -> CraftiumClient.send(action, null, new Point(0, 1, 0)));
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(5);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 200);
        w.getConnection().waitForClientboundPackets();
    }

    private static void selectNode(int index) {
        try {
            var field = EditorScreen.class.getDeclaredField("selectedNode");
            field.setAccessible(true);
            field.setInt(net.minecraft.client.Minecraft.getInstance().gui.screen(), index);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void runAll() {
        boolean update = "1".equals(System.getenv("CRAFTIUM_OVERLAY_UPDATE"));
        var out = Path.of("overlay");
        c.runOnClient(mc -> { mc.getWindow().setWindowed(1200, 800); mc.options.guiScale().set(2); mc.resizeGui(); mc.player.getAbilities().flying = true; });
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        GoldenToolGameTest.reset(w, null);
        w.getConnection().waitForClientboundPackets();
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);
        String only = System.getenv("CRAFTIUM_OVERLAY_ONLY");
        var results = new TreeMap<String, String>();
        var problems = new ArrayList<String>();
        for (Case one : cases()) {
            if (only != null && java.util.Arrays.stream(only.split(",")).noneMatch(one.name::contains)) continue;
            var first = record(one);
            write(out, one.name, first);
            String summary = summary(first);
            if (update) {
                String second = summary(record(one));
                if (!summary.equals(second)) problems.add(one.name + " is not deterministic: " + summary + " then " + second);
            }
            results.put(one.name, summary);
            System.out.println("CRAFTIUM_OVERLAY " + one.name + " " + summary);
        }
        if (update) { writeExpected(out, results); System.out.println("CRAFTIUM_OVERLAY_UPDATED " + out.toAbsolutePath().resolve("OverlayExpected.java")); }
        else {
            for (var e : results.entrySet()) {
                String expected = OverlayExpected.CASES.get(e.getKey());
                if (expected == null) problems.add(e.getKey() + " has no expected value; run with CRAFTIUM_OVERLAY_UPDATE=1");
                else if (!expected.equals(e.getValue())) problems.add(e.getKey() + " changed: expected " + expected + " but got " + e.getValue() + " (see " + out.toAbsolutePath().resolve(e.getKey() + ".txt") + ")");
            }
            if (only == null) for (String name : OverlayExpected.CASES.keySet()) if (!results.containsKey(name)) problems.add(name + " is expected but no longer runs");
        }
        if (!problems.isEmpty()) throw new AssertionError("What the tools draw differs:\n  " + String.join("\n  ", problems));
        System.out.println("CRAFTIUM_OVERLAY_PASS " + results.size() + " cases");
    }

    /** Sets the case up, aims the camera above the hover block, and returns what one tick's drawing and one frame's drew. */
    private List<String> record(Case one) {
        if (selected) { request("clear"); selected = false; }
        c.runOnClient(mc -> {
            ClientState.options = new Options();
            ClientState.options.confirmEdits = false;
            CraftiumClient.resetEditor(); ClientState.rulerPoints.clear(); ClientState.rulers.clear(); ModifyPreview.edit(); ShapeObject.cancel(); resetShapeHover();
            one.options.accept(ClientState.options);
            ((EditorScreen) mc.gui.screen()).select(one.tool);
            if (one.selection) { ClientState.options.position1 = GoldenToolGameTest.CLIPBOARD_FROM; ClientState.options.position2 = GoldenToolGameTest.CLIPBOARD_TO; }
            var player = mc.player;
            player.setPos(HOVER.x() + .5, HOVER.y() + 1 + 6 - player.getEyeHeight(), HOVER.z() + .5);
            player.setXRot(90); player.setYRot(0); player.setDeltaMovement(Vec3.ZERO); player.setOldPosAndRot();
        });
        if (one.selection) { request("select_box"); selected = true; }
        c.waitTicks(4);
        c.runOnClient(mc -> one.state.run());
        c.waitTicks(4);
        // The tick's drawing and the frame's are recorded in one go, so no frame of the game's own is mixed in.
        return c.computeOnClient(mc -> {
            var lines = new ArrayList<String>();
            OverlayLog.sink = lines;
            try { CraftiumClient.preview(mc); Overlay.runFrame(); }
            finally { OverlayLog.sink = null; }
            return lines;
        });
    }

    /**
     * "<calls> <how many of each kind> <hash of the calls>". The hash is of the calls sorted, not in the order they were made: what a tool
     * shows is the same wherever in a tick it is drawn, and which class draws it, and when, is what the interactions take over.
     */
    private static String summary(List<String> lines) {
        var kinds = new TreeMap<String, Integer>();
        for (String line : lines) kinds.merge(line.substring(0, line.indexOf(' ')), 1, Integer::sum);
        return lines.size() + " " + kinds.toString().replace(" ", "") + " " + GoldenToolGameTest.hash(lines.stream().sorted().toList());
    }

    private static void write(Path out, String name, List<String> lines) {
        try {
            Files.createDirectories(out);
            Files.write(out.resolve(name + ".txt"), lines, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private static void resetShapeHover() {
        try {
            var hover = ShapeObject.class.getDeclaredField("hover");
            var identity = ShapeObject.class.getDeclaredField("IDENTITY");
            hover.setAccessible(true);
            identity.setAccessible(true);
            hover.set(null, identity.get(null));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void writeExpected(Path out, java.util.Map<String, String> results) {
        var source = new StringBuilder("package dev.craftium.client;\n\nimport java.util.Map;\n\n/** Written by {@link OverlayGameTest} when run with CRAFTIUM_OVERLAY_UPDATE=1: \"<calls> <calls of each kind> <hash of the calls, sorted>\" for each case. */\nfinal class OverlayExpected {\n    private OverlayExpected() {}\n    static final Map<String, String> CASES = Map.ofEntries(\n");
        var entries = new ArrayList<String>();
        for (var e : results.entrySet()) entries.add("        Map.entry(\"" + e.getKey() + "\", \"" + e.getValue() + "\")");
        source.append(String.join(",\n", entries)).append("\n    );\n}\n");
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("OverlayExpected.java"), source.toString());
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
