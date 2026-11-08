package dev.craftium.client;

import dev.craftium.core.BrushMath;
import dev.craftium.core.Options;
import dev.craftium.core.PropertySpec;
import dev.craftium.core.ToolCatalog;
import dev.craftium.core.ToolSpec;
import dev.craftium.core.Tool;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Pins what the editor's panels lay out for each tool: with the editor open on a fixed window, a tool is chosen, and every label written and
 * every clickable area made in one frame is recorded through {@link PanelLog}, with where it sits. Each tool is recorded with its default
 * options, and the options that change what the panel shows are recorded too: the shape, the Modify mode, the annotation mode and so on. It
 * exists so that describing the panels as data, and adding a form for the properties of a plugin's tool, can be shown to change no tool's panel.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=panel}. After a change that is meant to alter a panel, run it with {@code CRAFTIUM_PANEL_UPDATE=1}: every
 * case then runs twice (one that differs from itself is reported), and the new table is written to {@code panel/PanelExpected.java} in the run
 * directory, next to a file for each case with what it laid out.
 */
public final class PanelGameTest {
    private record Case(String name, Tool tool, Consumer<Options> options, List<PropertySpec> properties) {
        Case(String name, Tool tool, Consumer<Options> options) { this(name, tool, options, null); }
    }

    /** The settings a tool would declare for itself; no built-in tool does, so the cases that show the form give one to a tool for as long as they run. */
    private static final List<PropertySpec> DECLARED = List.of(
        PropertySpec.number("scale", "Scale", 0.5, 4, 1),
        PropertySpec.integer("count", "Count", 1, 8, 3),
        PropertySpec.toggle("hollow", "Hollow", false),
        PropertySpec.choice("axis", "Axis", List.of("X", "Y", "Z"), 1));

    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;

    private PanelGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new PanelGameTest(c, w).runAll(); }

    private static List<Case> cases() {
        var all = new ArrayList<Case>();
        for (Tool tool : Tool.values()) all.add(new Case("panel_" + tool.name().toLowerCase(), tool, o -> {}));
        for (BrushMath.Shape shape : BrushMath.Shape.values())
            all.add(new Case("freehand_draw_shape_" + shape.name().toLowerCase(), Tool.FREEHAND_DRAW, o -> o.shape = shape));
        for (Options.ShapeKind kind : Options.ShapeKind.values())
            all.add(new Case("shape_kind_" + kind.name().toLowerCase(), Tool.SHAPE, o -> o.shapeKind = kind));
        for (Options.ModifyMode mode : Options.ModifyMode.values())
            all.add(new Case("modify_" + mode.name().toLowerCase(), Tool.MODIFY, o -> o.modifyMode = mode));
        all.add(new Case("modify_rotate_custom_angle", Tool.MODIFY, o -> { o.modifyMode = Options.ModifyMode.ROTATE_COPIES; o.modifyType = Options.RotateType.CUSTOM_ANGLE; }));
        for (String mode : new String[]{"TEXT", "DRAW", "BOX", "LINE", "IMAGE"})
            all.add(new Case("annotation_" + mode.toLowerCase(), Tool.ANNOTATION, o -> o.annotationMode = mode));
        all.add(new Case("distort_separate_axes", Tool.DISTORT, o -> o.distortSeparate = true));
        all.add(new Case("ruler_circle", Tool.RULER, o -> o.rulerMode = Options.RulerMode.CIRCLE));
        for (Options.PathMode mode : Options.PathMode.values())
            all.add(new Case("path_" + mode.name().toLowerCase(), Tool.PATH, o -> o.pathMode = mode));
        for (Options.Compare compare : Options.Compare.values())
            all.add(new Case("magic_select_compare_" + compare.name().toLowerCase(), Tool.MAGIC_SELECT, o -> o.compare = compare));
        // The form a tool that declares settings gets, in place of the built-in controls. The panel record holds where each label and area is, not the values they show; the values are in PropertyFormTest.
        all.add(new Case("declared_properties_form", Tool.TEXT, o -> {}, DECLARED));
        return all;
    }

    private void runAll() {
        boolean update = "1".equals(System.getenv("CRAFTIUM_PANEL_UPDATE"));
        var out = Path.of("panel");
        c.runOnClient(mc -> { mc.getWindow().setWindowed(1200, 800); mc.options.guiScale().set(2); mc.resizeGui(); mc.player.getAbilities().flying = true; });
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);
        String only = System.getenv("CRAFTIUM_PANEL_ONLY");
        var results = new TreeMap<String, String>();
        var problems = new ArrayList<String>();
        for (Case one : cases()) {
            if (only != null && java.util.Arrays.stream(only.split(",")).noneMatch(one.name::contains)) continue;
            var first = record(one);
            write(out, one.name, first);
            String summary = summary(first);
            if (update) {
                var again = record(one);
                write(out, one.name + ".run2", again);
                String second = summary(again);
                if (!summary.equals(second)) problems.add(one.name + " is not deterministic: " + summary + " then " + second);
            }
            results.put(one.name, summary);
            System.out.println("CRAFTIUM_PANEL " + one.name + " " + summary);
        }
        if (update) { writeExpected(out, results); System.out.println("CRAFTIUM_PANEL_UPDATED " + out.toAbsolutePath().resolve("PanelExpected.java")); }
        else {
            for (var e : results.entrySet()) {
                String expected = PanelExpected.CASES.get(e.getKey());
                if (expected == null) problems.add(e.getKey() + " has no expected value; run with CRAFTIUM_PANEL_UPDATE=1");
                else if (!expected.equals(e.getValue())) problems.add(e.getKey() + " changed: expected " + expected + " but got " + e.getValue() + " (see " + out.toAbsolutePath().resolve(e.getKey() + ".txt") + ")");
            }
            if (only == null) for (String name : PanelExpected.CASES.keySet()) if (!results.containsKey(name)) problems.add(name + " is expected but no longer runs");
        }
        if (!problems.isEmpty()) throw new AssertionError("What the panels lay out differs:\n  " + String.join("\n  ", problems));
        System.out.println("CRAFTIUM_PANEL_PASS " + results.size() + " cases");
    }

    /** Sets the case up and returns what the panels laid out in one frame. */
    private List<String> record(Case one) {
        if (one.properties == null) return recordNow(one);
        var original = c.computeOnClient(mc -> declare(one.tool, one.properties));
        try {
            c.runOnClient(mc -> { ClientState.toolProperties.remove(one.tool.spec().id()); });
            return recordNow(one);
        } finally {
            c.runOnClient(mc -> { restore(one.tool, original); ClientState.toolProperties.remove(original.id()); });
        }
    }

    /** Gives {@code tool} a spec that declares {@code properties} and returns the spec it had. */
    @SuppressWarnings("unchecked")
    private static ToolSpec declare(Tool tool, List<PropertySpec> properties) {
        var old = tool.spec();
        put(tool, new ToolSpec(old.id(), old.label(), old.icon(), old.iconId(), old.section(), old.description(), old.brushUse(), old.interaction(), old.defaultKey(), old.useHint(),
            old.confirms(), old.radiusShortcut(), old.buildsOnRelease(), old.placesBlocks(), old.selection(), old.transform(), properties));
        return old;
    }

    private static void restore(Tool tool, ToolSpec spec) { put(tool, spec); }

    @SuppressWarnings("unchecked")
    private static void put(Tool tool, ToolSpec spec) {
        try {
            var field = ToolCatalog.class.getDeclaredField("BUILTIN");
            field.setAccessible(true);
            ((java.util.Map<Tool, ToolSpec>) field.get(null)).put(tool, spec);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private List<String> recordNow(Case one) {
        c.runOnClient(mc -> {
            ClientState.options = new Options();
            ClientState.options.confirmEdits = false;
            CraftiumClient.resetEditor(); ClientState.rulerPoints.clear(); ClientState.rulers.clear(); ModifyPreview.edit(); ShapeObject.cancel();
            one.options.accept(ClientState.options);
            // The info panel names the block under the cursor, and the view widget follows the camera's turn; both depend on where the player stands and looks when the
            // world has loaded. High in the air and looking level, there is nothing under the cursor, and the camera is the one the table was recorded with.
            mc.player.setPos(0.5, 250, 0.5); mc.player.setXRot(0); mc.player.setYRot(0); mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); mc.player.setOldPosAndRot();
            ClientState.noticeUntil = 0; // a toast from an earlier case (choosing a transform tool with nothing selected) would still be showing for a few seconds
            resetToolList((EditorScreen) mc.gui.screen());
            ((EditorScreen) mc.gui.screen()).select(one.tool);
        });
        c.waitTicks(3);
        // The screen clears the record at the start of each frame, so after a few frames it holds exactly the last one.
        c.runOnClient(mc -> PanelLog.sink = new ArrayList<>());
        c.waitTicks(3);
        return c.computeOnClient(mc -> {
            var lines = new ArrayList<String>();
            // The frame rate readout changes from frame to frame; where it sits is what is recorded.
            for (String line : PanelLog.sink) lines.add(line.replaceAll("'\\d+ FPS'", "'# FPS'"));
            PanelLog.sink = null;
            return lines;
        });
    }

    /** The tool list is scrolled to show the tool chosen, and keeps its scroll from one case to the next; a case must not start from another's. */
    private static void resetToolList(EditorScreen screen) {
        try {
            var scroll = EditorScreen.class.getDeclaredField("toolScroll");
            var scrolledTo = EditorScreen.class.getDeclaredField("toolScrolledTo");
            scroll.setAccessible(true);
            scrolledTo.setAccessible(true);
            scroll.setInt(screen, 0);
            scrolledTo.set(screen, null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** "<lines> <labels> <areas> <hash of the lines>". */
    private static String summary(List<String> lines) {
        long texts = lines.stream().filter(l -> l.startsWith("text ")).count();
        return lines.size() + " texts=" + texts + " controls=" + (lines.size() - texts) + " " + dev.craftium.test.GoldenToolGameTest.hash(lines);
    }

    private static void write(Path out, String name, List<String> lines) {
        try {
            Files.createDirectories(out);
            Files.write(out.resolve(name + ".txt"), lines, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private static void writeExpected(Path out, java.util.Map<String, String> results) {
        var source = new StringBuilder("package dev.craftium.client;\n\nimport java.util.Map;\n\n/** Written by {@link PanelGameTest} when run with CRAFTIUM_PANEL_UPDATE=1: \"<lines> <labels> <areas> <hash of the lines>\" for each case. */\nfinal class PanelExpected {\n    private PanelExpected() {}\n    static final Map<String, String> CASES = Map.ofEntries(\n");
        var entries = new ArrayList<String>();
        for (var e : results.entrySet()) entries.add("        Map.entry(\"" + e.getKey() + "\", \"" + e.getValue() + "\")");
        source.append(String.join(",\n", entries)).append("\n    );\n}\n");
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("PanelExpected.java"), source.toString());
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
