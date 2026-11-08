package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The worked examples in the building skill are specs an AI copies, so each one must be one the game accepts, and the numbers the text
 * tells it to expect in the preview_build reply (where the build lands, how many stairs face each way) must be what the spec makes.
 */
class SkillExamplesTest {
    private static final Pattern JSON = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL);

    private static List<String> specs() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("mcp/skills/craftium-builder/references/examples.md"))) dir = dir.getParent();
        assertNotNull(dir, "examples.md not found above " + Path.of("").toAbsolutePath());
        String text = Files.readString(dir.resolve("mcp/skills/craftium-builder/references/examples.md")).replace("\r\n", "\n");
        var out = new ArrayList<String>();
        var m = JSON.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Map<String, Integer> counts(BuildSpec spec) {
        var counts = new TreeMap<String, Integer>();
        for (var cell : spec.build(spec.place()).cells().values()) counts.merge(cell.block(), 1, Integer::sum);
        return counts;
    }

    private static String box(BuildSpec.Placement p) {
        return p.min() + " " + p.bounds().sx() + "x" + p.bounds().sy() + "x" + p.bounds().sz();
    }

    @Test void everyExampleIsAcceptedAndMakesWhatItsTextSays() throws Exception {
        var specs = specs();
        assertEquals(5, specs.size(), "the cottage's four phases and the tower");
        for (String json : specs) {
            var spec = assertDoesNotThrow(() -> BuildSpec.parse(json), json);
            // An example an AI copies must not draw a warning, or it learns to ignore them.
            assertEquals(java.util.List.of(), spec.build(spec.place()).warnings(), json);
        }

        var blockout = BuildSpec.parse(specs.get(0));
        assertEquals(new Point(99, 64, 199) + " 11x9x9", box(blockout.place()));

        var roof = counts(BuildSpec.parse(specs.get(2)));
        assertEquals(44, roof.get("minecraft:spruce@stairs[facing=south,half=bottom,shape=straight]"));
        assertEquals(44, roof.get("minecraft:spruce@stairs[facing=north,half=bottom,shape=straight]"));
        assertEquals(11, roof.get("minecraft:spruce@slab[type=bottom]"), "the ridge cap, from x = -1 to 9");
        // The long walls are topped (two rows of 9), each gable end is filled 5, 5 and 3 wide on the layers above, and the ridge is 11 long.
        assertEquals(2 * 9 + 2 * (5 + 5 + 3) + 11, roof.get("minecraft:spruce@block"));

        var openings = counts(BuildSpec.parse(specs.get(3)));
        assertEquals(5 * 2, openings.get("minecraft:glass_pane"), "two windows to the south and three to the north, each 2 high");

        var tower = BuildSpec.parse(specs.get(4));
        assertEquals(new Point(36, 70, -16) + " 9x19x9", box(tower.place()));
        var made = counts(tower).keySet();
        assertEquals(Set.of("minecraft:mossy_cobblestone", "minecraft:stone_bricks", "minecraft:dark_oak_planks", "minecraft:air"), made);
    }
}
