package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The example batches in the craftium-showcase skill must be requests the planner accepts, so the skill cannot teach what the game refuses. */
class ShowcaseSkillExamplesTest {
    private static Path reference() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path file = dir.resolve("mcp/skills/craftium-showcase/references/shots.md");
            if (Files.exists(file)) return file;
        }
        return null;
    }

    @Test void everyExampleBatchInTheSkillIsAcceptedByThePlanner() throws Exception {
        Path file = reference();
        assumeTrue(file != null, "the skill is not in this checkout");
        var blocks = Pattern.compile("```json\\s*(.*?)```", Pattern.DOTALL).matcher(Files.readString(file));
        int checked = 0;
        while (blocks.find()) {
            String json = blocks.group(1);
            if (!json.contains("\"rooms\"")) continue;
            var saved = new ShowcasePlan.Selections() {
                @Override public Point[] find(String name) { return new Point[]{new Point(0, 64, 0), new Point(9, 70, 9)}; }
                @Override public java.util.List<String> names() { return java.util.List.of(); }
            };
            var plan = ShowcasePlan.parse(ShowcasePlan.resolveSelections(JsonParser.parseString(json).getAsJsonObject(), saved), "20261110-120000");
            assertFalse(plan.entries().isEmpty());
            checked++;
        }
        assertEquals(4, checked, "castle, saved selections, retake and 4K examples");
    }

    @Test void theInteriorCameraExampleIsAValidShot() throws Exception {
        Path file = reference();
        assumeTrue(file != null, "the skill is not in this checkout");
        var m = Pattern.compile("```json\\s*(\\{\"name\": \"altar\".*?)```", Pattern.DOTALL).matcher(Files.readString(file));
        assertTrue(m.find());
        var plan = ShowcasePlan.parse(JsonParser.parseString("{\"rooms\":[{\"name\":\"hall\",\"from\":[100,70,40],\"to\":[120,82,60],\"shots\":[" + m.group(1) + "]}]}").getAsJsonObject(), "x");
        assertEquals(85, plan.entries().get(0).spec().fov());
        assertNull(plan.entries().get(0).orbit(), "a placed camera is not re-aimed");
    }
}
