package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The icon ids are what a tool, a setting or a plugin names to choose an icon, so an id that is listed keeps meaning the same icon at the same
 * size in the same category. The table is the catalog as 0.9.0-beta.1 drew it; adding an icon needs a row here, and removing or moving one
 * breaks whatever named it.
 */
class IconCatalogTest {
    /** id @@ category @@ size. */
    private static final String ICONS = """
        craftium:tool/box_select @@ Tools/Select @@ 24x24
        craftium:tool/magic_select @@ Tools/Select @@ 24x24
        craftium:tool/lasso_select @@ Tools/Select @@ 24x24
        craftium:tool/freehand_select @@ Tools/Select @@ 24x24
        craftium:tool/shape @@ Tools/Draw @@ 24x24
        craftium:tool/line @@ Tools/Draw @@ 24x24
        craftium:tool/path @@ Tools/Draw @@ 24x24
        craftium:tool/modelling @@ Tools/Draw @@ 24x24
        craftium:tool/freehand_draw @@ Tools/Draw @@ 24x24
        craftium:tool/text @@ Tools/Draw @@ 24x24
        craftium:tool/erase @@ Tools/Draw @@ 24x24
        craftium:tool/painter @@ Tools/Paint @@ 24x24
        craftium:tool/paint_bucket @@ Tools/Paint @@ 24x24
        craftium:tool/gradient_painter @@ Tools/Paint @@ 24x24
        craftium:tool/noise_painter @@ Tools/Paint @@ 24x24
        craftium:tool/script_brush @@ Tools/Paint @@ 24x24
        craftium:tool/biome_painter @@ Tools/Paint @@ 24x24
        craftium:tool/clentaminator @@ Tools/Paint @@ 24x24
        craftium:tool/smooth @@ Tools/Sculpt @@ 24x24
        craftium:tool/roughen @@ Tools/Sculpt @@ 24x24
        craftium:tool/distort @@ Tools/Sculpt @@ 24x24
        craftium:tool/melt @@ Tools/Sculpt @@ 24x24
        craftium:tool/weld @@ Tools/Sculpt @@ 24x24
        craftium:tool/shatter @@ Tools/Sculpt @@ 24x24
        craftium:tool/extrude @@ Tools/Sculpt @@ 24x24
        craftium:tool/smear @@ Tools/Sculpt @@ 24x24
        craftium:tool/sculpt_draw @@ Tools/Sculpt @@ 24x24
        craftium:tool/modify @@ Tools/Sculpt @@ 24x24
        craftium:tool/stamp @@ Tools/Sculpt @@ 24x24
        craftium:tool/elevation @@ Tools/Terrain @@ 24x24
        craftium:tool/flatten @@ Tools/Terrain @@ 24x24
        craftium:tool/slope @@ Tools/Terrain @@ 24x24
        craftium:tool/rock @@ Tools/Terrain @@ 24x24
        craftium:tool/floodfill @@ Tools/Terrain @@ 24x24
        craftium:tool/fluid_ball @@ Tools/Terrain @@ 24x24
        craftium:tool/fluid_block @@ Tools/Terrain @@ 24x24
        craftium:tool/ruler @@ Tools/Utility @@ 24x24
        craftium:tool/tinker @@ Tools/Utility @@ 24x24
        craftium:tool/annotation @@ Tools/Utility @@ 24x24
        craftium:tool/inspect @@ Tools/Utility @@ 24x24
        craftium:tool/move @@ Tools/Transform @@ 24x24
        craftium:tool/rotate @@ Tools/Transform @@ 24x24
        craftium:tool/scale @@ Tools/Transform @@ 24x24
        craftium:tool/pivot @@ Tools/Transform @@ 24x24
        craftium:compare/any @@ Block matching @@ 24x24
        craftium:compare/block @@ Block matching @@ 24x24
        craftium:compare/state @@ Block matching @@ 24x24
        craftium:compare/tag @@ Block matching @@ 24x24
        craftium:compare/nbt @@ Block matching @@ 24x24
        craftium:shape/sphere @@ Shapes @@ 24x24
        craftium:shape/cube @@ Shapes @@ 24x24
        craftium:shape/octahedron @@ Shapes @@ 24x24
        craftium:shape/cylinder @@ Shapes @@ 24x24
        craftium:shape/plane @@ Shapes @@ 24x24
        craftium:shape/disk @@ Shapes @@ 24x24
        craftium:shape/pyramid @@ Shapes @@ 24x24
        craftium:shape/cone @@ Shapes @@ 24x24
        craftium:shape/hemisphere @@ Shapes @@ 24x24
        craftium:shape/torus @@ Shapes @@ 24x24
        craftium:shape/tube @@ Shapes @@ 24x24
        craftium:shape/polygon @@ Shapes @@ 24x24
        craftium:shape/irregular_polygon @@ Shapes @@ 24x24
        craftium:shape/star @@ Shapes @@ 24x24
        craftium:ui/hard_hat @@ Interface @@ 19x19
        craftium:ui/chain_linked @@ Interface @@ 17x9
        craftium:ui/chain_broken @@ Interface @@ 17x9
        craftium:ui/pipette @@ Interface @@ 30x30
        craftium:ui/check @@ Interface @@ 12x12
        """;

    private static List<String> lines() { return ICONS.lines().map(String::strip).filter(s -> !s.isEmpty()).toList(); }

    private static String describe(IconSpec icon) { return icon.id() + " @@ " + icon.category() + " @@ " + icon.width() + "x" + icon.height(); }

    @Test void everyIconThatWasListedIsStillListedWhereItWas() {
        var current = new HashSet<String>();
        for (IconSpec icon : IconCatalog.all()) current.add(describe(icon));
        for (String line : lines()) assertTrue(current.contains(line), line + " was removed, renamed, moved or resized");
    }

    @Test void everyIconInTheCatalogHasARow() {
        var pinned = new HashSet<>(lines());
        for (IconSpec icon : IconCatalog.all()) assertTrue(pinned.contains(describe(icon)), describe(icon) + " needs a row in IconCatalogTest");
    }

    @Test void idsAreUniqueNamespacedAndLabelled() {
        var seen = new HashSet<String>();
        for (IconSpec icon : IconCatalog.all()) {
            assertTrue(seen.add(icon.id()), "duplicate " + icon.id());
            assertTrue(icon.id().matches("craftium:[a-z_]+/[a-z0-9_]+"), icon.id());
            assertFalse(icon.label().isBlank(), icon.id());
            assertTrue(icon.width() > 0 && icon.height() > 0, icon.id());
        }
    }

    @Test void everyToolBlockMatchAndShapeHasAnIcon() {
        for (Tool tool : Tool.values()) assertTrue(IconCatalog.find(IconCatalog.tool(tool)).isPresent(), tool.name());
        for (Options.Compare compare : Options.Compare.values()) assertTrue(IconCatalog.find(IconCatalog.compare(compare)).isPresent(), compare.name());
        for (BrushMath.Shape shape : BrushMath.Shape.values()) assertTrue(IconCatalog.find(IconCatalog.shape(shape)).isPresent(), shape.name());
    }

    @Test void categoriesAreListedInOrderAndEveryIconIsInOne() {
        assertEquals(List.of("Tools/Select", "Tools/Draw", "Tools/Paint", "Tools/Sculpt", "Tools/Terrain", "Tools/Utility", "Tools/Transform",
            IconCatalog.CATEGORY_MATCHING, IconCatalog.CATEGORY_SHAPES, IconCatalog.CATEGORY_INTERFACE), IconCatalog.categories());
        int listed = 0;
        for (String category : IconCatalog.categories()) listed += IconCatalog.inCategory(category).size();
        assertEquals(IconCatalog.all().size(), listed);
    }

    @Test void blockIconsAreNamedByTheirBlockAndAreNotListed() {
        assertTrue(IconCatalog.isBlock(IconCatalog.block("minecraft:stone")));
        assertTrue(IconCatalog.exists("block:minecraft:oak_stairs"));
        assertEquals("minecraft:stone", IconCatalog.blockId("block:minecraft:stone"));
        assertFalse(IconCatalog.isBlock("block:"));
        assertFalse(IconCatalog.isBlock("block:stone"));
        assertFalse(IconCatalog.isBlock("craftium:tool/painter"));
        assertFalse(IconCatalog.isBlock(null));
        assertFalse(IconCatalog.all().stream().anyMatch(icon -> IconCatalog.isBlock(icon.id())));
    }

    @Test void unknownIconsDoNotExist() {
        assertFalse(IconCatalog.exists("craftium:tool/not_a_tool"));
        assertFalse(IconCatalog.exists(""));
        assertTrue(IconCatalog.find("craftium:tool/painter").isPresent());
    }
}
