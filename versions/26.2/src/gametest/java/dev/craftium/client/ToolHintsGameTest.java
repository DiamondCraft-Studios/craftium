package dev.craftium.client;

import dev.craftium.core.Tool;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins the shortcut hints the bottom bar shows for every tool, in each combination of "a gizmo is up" and "the Shape tool has one down", and
 * which tools show the radius shortcut. These come from client code that needs Minecraft's key names, so they are checked in the game.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=toolhints}. After a change that is meant to alter a hint, run it with
 * {@code CRAFTIUM_TOOLHINTS_UPDATE=1} and copy {@code toolhints/ToolHintsExpected.java} from the run directory over {@link ToolHintsExpected}.
 */
public final class ToolHintsGameTest {
    private ToolHintsGameTest() {}

    static List<String> lines() {
        var lines = new ArrayList<String>();
        for (boolean gizmo : new boolean[]{false, true})
            for (boolean shape : new boolean[]{false, true})
                lines.add("none gizmo=" + gizmo + " shape=" + shape + " :: " + String.join(" | ", ToolHints.of(null, gizmo, shape)));
        for (Tool tool : Tool.values()) {
            lines.add(tool.name() + " radiusHint=" + ToolHints.brush(tool));
            for (boolean gizmo : new boolean[]{false, true})
                for (boolean shape : new boolean[]{false, true})
                    lines.add(tool.name() + " gizmo=" + gizmo + " shape=" + shape + " :: " + String.join(" | ", ToolHints.of(tool, gizmo, shape)));
        }
        return lines;
    }

    public static void run(ClientGameTestContext ctx) {
        var actual = ctx.computeOnClient(mc -> lines());
        if ("1".equals(System.getenv("CRAFTIUM_TOOLHINTS_UPDATE"))) {
            var source = new StringBuilder("package dev.craftium.client;\n\nimport java.util.List;\n\n/** Written by {@link ToolHintsGameTest} when run with CRAFTIUM_TOOLHINTS_UPDATE=1. */\nfinal class ToolHintsExpected {\n    private ToolHintsExpected() {}\n    static final List<String> LINES = List.of(\n");
            var quoted = new ArrayList<String>();
            for (String line : actual) quoted.add("        \"" + line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            source.append(String.join(",\n", quoted)).append("\n    );\n}\n");
            try {
                Files.createDirectories(Path.of("toolhints"));
                Files.writeString(Path.of("toolhints", "ToolHintsExpected.java"), source.toString());
            } catch (java.io.IOException e) { throw new AssertionError(e); }
            System.out.println("CRAFTIUM_TOOLHINTS_UPDATED " + Path.of("toolhints").toAbsolutePath());
            return;
        }
        var problems = new ArrayList<String>();
        for (int i = 0; i < Math.max(actual.size(), ToolHintsExpected.LINES.size()); i++) {
            String a = i < actual.size() ? actual.get(i) : "(missing)", e = i < ToolHintsExpected.LINES.size() ? ToolHintsExpected.LINES.get(i) : "(missing)";
            if (!a.equals(e)) problems.add("expected: " + e + "\n       got: " + a);
        }
        if (!problems.isEmpty()) throw new AssertionError("Tool hints differ in " + problems.size() + " lines:\n  " + String.join("\n  ", problems.subList(0, Math.min(problems.size(), 20))));
        System.out.println("CRAFTIUM_TOOLHINTS_PASS " + actual.size() + " lines");
    }
}
