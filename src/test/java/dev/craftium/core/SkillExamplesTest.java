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

    private static List<String> specs() throws Exception { return specs("mcp/skills/craftium-builder/references/examples.md"); }

    private static List<String> specs(String file) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(file))) dir = dir.getParent();
        assertNotNull(dir, file + " not found above " + Path.of("").toAbsolutePath());
        String text = Files.readString(dir.resolve(file)).replace("\r\n", "\n");
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

    /** The blockout skill promises cubes only and its own legend, so every example must keep that promise. */
    @Test void blockoutExamplesUseOnlyCubesAndTheLegend() throws Exception {
        String base = "mcp/skills/craftium-blockout/references/";
        var specs = specs(base + "examples.md");
        assertEquals(4, specs.size(), "a room's three phases and a set");

        Path dir = Path.of("").toAbsolutePath();
        while (!Files.exists(dir.resolve(base + "legend.md"))) dir = dir.getParent();
        String legend = Files.readString(dir.resolve(base + "legend.md"));
        var allowedOps = Set.of("box", "set", "line", "replace", "group", "replace_selection");
        var allowedBlocks = new HashSet<>(Set.of("minecraft:air", "minecraft:brown_concrete", "minecraft:light_gray_concrete", "minecraft:gray_concrete",
            "minecraft:orange_concrete", "minecraft:yellow_concrete", "minecraft:red_concrete", "minecraft:purple_concrete", "minecraft:black_concrete",
            "minecraft:cyan_concrete", "minecraft:magenta_concrete", "minecraft:blue_concrete", "minecraft:red_terracotta"));
        for (String block : allowedBlocks) if (!block.equals("minecraft:air")) assertTrue(legend.contains(block.substring("minecraft:".length())), block + " is not in legend.md");

        var opName = Pattern.compile("\"op\"\\s*:\\s*\"(\\w+)\"");
        for (String json : specs) {
            var spec = assertDoesNotThrow(() -> BuildSpec.parse(json), json);
            assertEquals(java.util.List.of(), spec.build(spec.place()).warnings(), json);
            var m = opName.matcher(json);
            while (m.find()) assertTrue(allowedOps.contains(m.group(1)), "op " + m.group(1) + " is not a cube op");
            assertTrue(allowedBlocks.containsAll(counts(spec).keySet()), counts(spec).keySet().toString());
        }

        assertEquals(new Point(100, 62, 200) + " 11x3x9", box(BuildSpec.parse(specs.get(0)).place()));
        var massing = BuildSpec.parse(specs.get(1));
        assertEquals(new Point(100, 65, 200) + " 11x5x9", box(massing.place()));
        assertEquals(11 * 5 * 9 - 9 * 4 * 7 - 3 * 3, counts(massing).get("minecraft:light_gray_concrete"), "walls and ceiling, less the inside and the doorway");
        var function = counts(BuildSpec.parse(specs.get(2)));
        assertEquals(2, function.get("minecraft:yellow_concrete"));
        assertEquals(1, function.get("minecraft:red_concrete"));
        assertEquals(new Point(100, 62, 200) + " 21x8x9", box(BuildSpec.parse(specs.get(3)).place()));
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
