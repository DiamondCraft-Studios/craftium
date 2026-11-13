package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what every tool says about itself: its name in the panel, icon letters, section, description, how it uses the brush, which kinds of
 * tool it is, and the key it starts on. The table is what 0.9.0-beta.1 showed players. Reorganising where this data lives must not change it;
 * a tool added later needs a row here, and changing a row on purpose means changing it in this file in the same commit.
 */
class ToolMetadataTest {
    /** name @@ label @@ icon @@ group @@ description @@ brush use @@ picks a shape @@ transform @@ places blocks @@ selection @@ default key. */
    private static final String TOOLS = """
        MAGIC_SELECT @@ Magic Wand @@ MW @@ Select @@ Select connected blocks of the same type. @@ NONE @@ false @@ false @@ false @@ true @@ 77
        BOX_SELECT @@ Box Select @@ BX @@ Select @@ Right-click two corners to select a cuboid. @@ NONE @@ false @@ false @@ false @@ true @@ 66
        FREEHAND_SELECT @@ Freehand Select @@ FS @@ Select @@ Brush a selection; use selection mode to add or subtract. @@ SHAPE @@ true @@ false @@ false @@ true @@ 78
        RULER @@ Ruler @@ RL @@ Utility @@ Add measurement points; use the inspector for circles, splits and center selection. @@ NONE @@ false @@ false @@ false @@ false @@ -1
        PAINTER @@ Painter @@ PA @@ Paint @@ Paint existing blocks with your active material. @@ SHAPE @@ true @@ false @@ true @@ false @@ 80
        PAINT_BUCKET @@ Paint Bucket @@ PB @@ Paint @@ Fill the connected blocks you click with your foreground block. Directions and select type work as in the Magic Wand. @@ NONE @@ false @@ false @@ true @@ false @@ -1
        NOISE_PAINTER @@ Noise Painter @@ NP @@ Paint @@ Blend two materials with coherent seeded noise. @@ SHAPE @@ true @@ false @@ true @@ false @@ 79
        BIOME_PAINTER @@ Biome Painter @@ BP @@ Paint @@ Paint biome cells inside the brush. Biomes use 4-block cells. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        CLENTAMINATOR @@ Clentaminator @@ CL @@ Paint @@ Convert biome and matching surface materials together. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        FREEHAND_DRAW @@ Freehand Draw @@ DR @@ Draw @@ Draw solid shapes; invert to carve. @@ SHAPE @@ true @@ false @@ true @@ false @@ 71
        SCULPT_DRAW @@ Sculpt Draw @@ SC @@ Sculpt @@ Pull the surface outward along its normal. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        ROCK @@ Rock @@ RK @@ Terrain @@ Create a coherent, noise-shaped boulder. @@ SPHERE @@ false @@ false @@ true @@ false @@ 72
        WELD @@ Weld @@ WD @@ Sculpt @@ Fill concave seams using local voxel density. @@ SHAPE @@ true @@ false @@ true @@ false @@ 74
        MELT @@ Melt @@ ML @@ Sculpt @@ Erode exposed corners using local voxel density. @@ SHAPE @@ true @@ false @@ true @@ false @@ 75
        TEXT @@ Text @@ TX @@ Draw @@ Place block lettering using a compact 5 by 7 alphabet. @@ NONE @@ false @@ false @@ true @@ false @@ -1
        ELEVATION @@ Elevation @@ EL @@ Terrain @@ Raise or lower columns with soft radial falloff. @@ DISC @@ false @@ false @@ true @@ false @@ 69
        FLATTEN @@ Flatten @@ FL @@ Terrain @@ Bring terrain toward the target Y level. @@ DISC @@ false @@ false @@ true @@ false @@ -1
        SLOPE @@ Slope @@ SL @@ Terrain @@ Shape a ramp toward your chosen direction and rise. @@ DISC @@ false @@ false @@ true @@ false @@ -1
        SMOOTH @@ Smooth @@ SM @@ Sculpt @@ Smooth voxel surfaces with a weighted neighbour filter. @@ SHAPE @@ true @@ false @@ true @@ false @@ 85
        DISTORT @@ Distort @@ DS @@ Sculpt @@ Warp existing material through a coherent noise field. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        ROUGHEN @@ Roughen @@ RF @@ Sculpt @@ Add small, seeded irregularities to exposed surfaces. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        SHATTER @@ Shatter @@ SH @@ Sculpt @@ Cut cellular fracture seams through a volume. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        EXTRUDE @@ Extrude @@ EX @@ Sculpt @@ Extend exposed faces along the clicked surface normal. @@ NONE @@ false @@ false @@ true @@ false @@ 90
        SHAPE @@ Shape @@ SP @@ Draw @@ Right-click to put down a shape of your foreground block, shape it with Move, Rotate and Scale, then press Enter to build it. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        LINE @@ Line @@ LN @@ Draw @@ Right-click two endpoints to draw a continuous line. @@ SMALL_SHAPE @@ true @@ false @@ true @@ false @@ -1
        ERASE @@ Erase @@ ER @@ Draw @@ Remove blocks inside the brush with undo. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        TINKER @@ Tinker @@ TK @@ Utility @@ Right click a block to change it the way you aim: full blocks, stairs and slabs turn into each other, walls and fences change the arm you point at. Sneak to pick a property to cycle. @@ NONE @@ false @@ false @@ false @@ false @@ -1
        LASSO_SELECT @@ Lasso Select @@ LS @@ Select @@ Draw a screen-space polygon; release to project it into the world. @@ NONE @@ false @@ false @@ false @@ true @@ 76
        GRADIENT_PAINTER @@ Gradient Painter @@ GP @@ Paint @@ Shift-right-click gradient anchors, then paint between them. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        SCRIPT_BRUSH @@ Script Brush @@ LU @@ Paint @@ Run a bounded Lua script for each block in the brush. @@ SHAPE @@ true @@ false @@ true @@ false @@ -1
        MODELLING @@ Modelling @@ MD @@ Draw @@ Place control points and build a hull, triangle strip or lofted surface. @@ NONE @@ false @@ false @@ true @@ false @@ -1
        STAMP @@ Stamp @@ ST @@ Sculpt @@ Scatter the clipboard with spacing, random rotation and chance. @@ DISC @@ false @@ false @@ true @@ false @@ -1
        PATH @@ Path @@ PT @@ Draw @@ Create straight, Bezier, Catmull-Rom or hanging catenary paths. @@ SMALL_SHAPE @@ true @@ false @@ true @@ false @@ -1
        FLOODFILL @@ Floodfill @@ FF @@ Terrain @@ Fill connected space with a block count and directional limits. @@ NONE @@ false @@ false @@ true @@ false @@ -1
        FLUID_BALL @@ Fluid Ball @@ FB @@ Terrain @@ Simulate bounded water, lava or snow flow over terrain. @@ SPHERE @@ false @@ false @@ true @@ false @@ -1
        MODIFY @@ Modify @@ MF @@ Sculpt @@ Translate copies, revolve, rotate copies or twist your selection. @@ NONE @@ false @@ false @@ true @@ false @@ -1
        ANNOTATION @@ Annotation @@ AN @@ Utility @@ Draw world-space notes, outlines and labels without changing blocks. @@ NONE @@ false @@ false @@ false @@ false @@ -1
        SMEAR @@ Smear @@ SR @@ Sculpt @@ Stretch a selection along an offset, stopping at existing solid blocks. @@ NONE @@ false @@ false @@ true @@ false @@ -1
        MOVE @@ Move @@ MV @@ Transform @@ Drag the arrows to move the selected blocks. Enter applies the move. @@ NONE @@ false @@ true @@ true @@ false @@ 86
        ROTATE @@ Rotate @@ RT @@ Transform @@ Drag a ring to rotate the selected blocks around the pivot. Enter applies it. @@ NONE @@ false @@ true @@ true @@ false @@ 82
        SCALE @@ Scale @@ SZ @@ Transform @@ Drag a square on a face to scale the selected blocks around the pivot. Enter applies it. @@ NONE @@ false @@ true @@ true @@ false @@ 67
        PIVOT @@ Pivot @@ PV @@ Transform @@ Click a block, or drag the arrows, to set the point Rotate and Scale work around. @@ NONE @@ false @@ true @@ false @@ false @@ 73
        FLUID_BLOCK @@ Fluid Block @@ FK @@ Terrain @@ Pour your blocks over terrain like powder: they fall, settle and spread thinner towards the edges. @@ SPHERE @@ false @@ false @@ true @@ false @@ -1
        INSPECT @@ Inspect @@ IN @@ Utility @@ Point at a block to see its tags above it; right-click to list them all in chat. Changes nothing. @@ NONE @@ false @@ false @@ false @@ false @@ -1
        """;

    /** The sections of the Tools panel, in order, with the tools in each in the order shown. */
    private static final String SECTIONS = """
        Select: BOX_SELECT,MAGIC_SELECT,LASSO_SELECT,FREEHAND_SELECT
        Draw: SHAPE,LINE,PATH,MODELLING,FREEHAND_DRAW,TEXT,ERASE
        Paint: PAINTER,PAINT_BUCKET,GRADIENT_PAINTER,NOISE_PAINTER,SCRIPT_BRUSH,BIOME_PAINTER,CLENTAMINATOR
        Sculpt: SMOOTH,ROUGHEN,DISTORT,MELT,WELD,SHATTER,EXTRUDE,SMEAR,SCULPT_DRAW,MODIFY,STAMP
        Terrain: ELEVATION,FLATTEN,SLOPE,ROCK,FLOODFILL,FLUID_BALL,FLUID_BLOCK
        Utility: RULER,TINKER,ANNOTATION,INSPECT
        """;

    private static String describe(Tool t) {
        return String.join(" @@ ", t.name(), t.label, t.icon, t.group, t.description, String.valueOf(t.brushUse()), String.valueOf(t.picksShape()), String.valueOf(t.transform()),
            String.valueOf(t.placesBlocks()), String.valueOf(t.selection()), String.valueOf(EditorControls.toolDefault(t)));
    }

    @Test void everyToolStillSaysWhatItDid() {
        var expected = new LinkedHashMap<String, String>();
        for (String line : TOOLS.lines().map(String::strip).filter(s -> !s.isEmpty()).toList()) expected.put(line.substring(0, line.indexOf(' ')), line);
        for (var entry : expected.entrySet()) assertEquals(entry.getValue(), describe(Tool.valueOf(entry.getKey())), entry.getKey());
    }

    @Test void everyToolIsInTheTable() {
        // A tool without a row here was added after 0.9.0-beta.1: give it one so later changes to it are caught.
        var named = new HashSet<String>();
        for (String line : TOOLS.lines().map(String::strip).filter(s -> !s.isEmpty()).toList()) named.add(line.substring(0, line.indexOf(' ')));
        for (Tool tool : Tool.values()) assertTrue(named.contains(tool.name()), tool.name() + " needs a row in ToolMetadataTest");
    }

    @Test void theToolsPanelSectionsKeepTheirOrder() {
        var actual = new StringBuilder();
        for (var entry : Tool.SECTIONS.entrySet()) {
            var names = new ArrayList<String>();
            for (Tool tool : entry.getValue()) names.add(tool.name());
            actual.append(entry.getKey()).append(": ").append(String.join(",", names)).append('\n');
        }
        assertEquals(SECTIONS.lines().map(String::strip).filter(s -> !s.isEmpty()).toList(), actual.toString().lines().toList());
    }
}
