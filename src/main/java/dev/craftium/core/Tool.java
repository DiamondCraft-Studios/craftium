package dev.craftium.core;

import java.util.*;

public enum Tool {
    MAGIC_SELECT("Magic Wand", "MW", "Select", "Select connected blocks of the same type."),
    BOX_SELECT("Box Select", "BX", "Select", "Right-click two corners to select a cuboid."),
    FREEHAND_SELECT("Freehand Select", "FS", "Select", "Brush a selection; use selection mode to add or subtract."),
    RULER("Ruler", "RL", "Utility", "Add measurement points; use the inspector for circles, splits and center selection."),
    PAINTER("Painter", "PA", "Paint", "Paint existing blocks with your active material."),
    PAINT_BUCKET("Paint Bucket", "PB", "Paint", "Fill the connected blocks you click with your foreground block. Directions and select type work as in the Magic Wand."),
    NOISE_PAINTER("Noise Painter", "NP", "Paint", "Blend two materials with coherent seeded noise."),
    BIOME_PAINTER("Biome Painter", "BP", "Paint", "Paint biome cells inside the brush. Biomes use 4-block cells."),
    CLENTAMINATOR("Clentaminator", "CL", "Paint", "Convert biome and matching surface materials together."),
    FREEHAND_DRAW("Freehand Draw", "DR", "Draw", "Draw solid shapes; invert to carve."),
    SCULPT_DRAW("Sculpt Draw", "SC", "Sculpt", "Pull the surface outward along its normal."),
    ROCK("Rock", "RK", "Terrain", "Create a coherent, noise-shaped boulder."),
    WELD("Weld", "WD", "Sculpt", "Fill concave seams using local voxel density."),
    MELT("Melt", "ML", "Sculpt", "Erode exposed corners using local voxel density."),
    TEXT("Text", "TX", "Draw", "Place block lettering using a compact 5 by 7 alphabet."),
    ELEVATION("Elevation", "EL", "Terrain", "Raise or lower columns with soft radial falloff."),
    FLATTEN("Flatten", "FL", "Terrain", "Bring terrain toward the target Y level."),
    SLOPE("Slope", "SL", "Terrain", "Shape a ramp toward your chosen direction and rise."),
    SMOOTH("Smooth", "SM", "Sculpt", "Smooth voxel surfaces with a weighted neighbour filter."),
    DISTORT("Distort", "DS", "Sculpt", "Warp existing material through a coherent noise field."),
    ROUGHEN("Roughen", "RF", "Sculpt", "Add small, seeded irregularities to exposed surfaces."),
    SHATTER("Shatter", "SH", "Sculpt", "Cut cellular fracture seams through a volume."),
    EXTRUDE("Extrude", "EX", "Sculpt", "Extend exposed faces along the clicked surface normal."),
    SHAPE("Shape", "SP", "Draw", "Right-click to put down a shape of your foreground block, shape it with Move, Rotate and Scale, then press Enter to build it."),
    LINE("Line", "LN", "Draw", "Right-click two endpoints to draw a continuous line."),
    ERASE("Erase", "ER", "Draw", "Remove blocks inside the brush with undo."),
    TINKER("Tinker", "TK", "Utility", "Right click a block to change it the way you aim: full blocks, stairs and slabs turn into each other, walls and fences change the arm you point at. Sneak to pick a property to cycle."),
    LASSO_SELECT("Lasso Select", "LS", "Select", "Draw a screen-space polygon; release to project it into the world."),
    GRADIENT_PAINTER("Gradient Painter", "GP", "Paint", "Shift-right-click gradient anchors, then paint between them."),
    SCRIPT_BRUSH("Script Brush", "LU", "Paint", "Run a bounded Lua script for each block in the brush."),
    MODELLING("Modelling", "MD", "Draw", "Place control points and build a hull, triangle strip or lofted surface."),
    STAMP("Stamp", "ST", "Sculpt", "Scatter the clipboard with spacing, random rotation and chance."),
    PATH("Path", "PT", "Draw", "Create straight, Bezier, Catmull-Rom or hanging catenary paths."),
    FLOODFILL("Floodfill", "FF", "Terrain", "Fill connected space with a block count and directional limits."),
    FLUID_BALL("Fluid Ball", "FB", "Terrain", "Simulate bounded water, lava or snow flow over terrain."),
    MODIFY("Modify", "MF", "Sculpt", "Translate copies, revolve, rotate copies or twist your selection."),
    ANNOTATION("Annotation", "AN", "Utility", "Draw world-space notes, outlines and labels without changing blocks."),
    SMEAR("Smear", "SR", "Sculpt", "Stretch a selection along an offset, stopping at existing solid blocks."),
    MOVE("Move", "MV", "Transform", "Drag the arrows to move the selected blocks. Enter applies the move."),
    ROTATE("Rotate", "RT", "Transform", "Drag a ring to rotate the selected blocks around the pivot. Enter applies it."),
    SCALE("Scale", "SZ", "Transform", "Drag a square on a face to scale the selected blocks around the pivot. Enter applies it."),
    PIVOT("Pivot", "PV", "Transform", "Click a block, or drag the arrows, to set the point Rotate and Scale work around."),
    INSPECT("Inspect", "IN", "Utility", "Point at a block to see its tags above it; right-click to list them all in chat. Changes nothing.");

    public final String label, icon, group, description;
    Tool(String label, String icon, String group, String description) {
        this.label=label; this.icon=icon; this.group=group; this.description=description;
    }
    /**
     * What a tool makes of the brush under the cursor. SHAPE builds from the chosen shape and radius; SMALL_SHAPE does too, but the shape is kept to
     * a radius of 4 (a line or path is drawn with it); DISC and SPHERE use only the radius, as a flat disc or a round ball whatever shape is picked;
     * NONE has no brush at all, working on the clicked face, block, selection or its own points.
     */
    public enum BrushUse { SHAPE, SMALL_SHAPE, DISC, SPHERE, NONE }
    /** This tool's facts, kept in {@link ToolCatalog}: the methods below read them so the callers that ask a {@code Tool} keep working. */
    public ToolSpec spec() { return ToolCatalog.of(this); }
    public BrushUse brushUse() { return spec().brushUse(); }
    /** Whether the shape picker means anything for this tool. */
    public boolean picksShape() { return spec().picksShape(); }
    /** The tools that move, rotate or scale the selected blocks around a pivot. */
    public boolean transform() { return spec().transform(); }
    /** Whether the tool builds, removes or moves blocks, so the block limit applies to it: not the selecting, measuring, annotating or single-block Tinker tools. */
    public boolean placesBlocks() { return spec().placesBlocks(); }
    public boolean selection() { return spec().selection(); }

    /**
     * The sections of the Tools panel in display order, each with its tools in the order they are shown: by workflow, then by how
     * often they are reached for. The transform tools are pinned over the viewport instead, so they are not listed.
     */
    public static final Map<String, List<Tool>> SECTIONS = sections();
    private static Map<String, List<Tool>> sections() {
        var all = new LinkedHashMap<String, List<Tool>>();
        all.put("Select", List.of(BOX_SELECT, MAGIC_SELECT, LASSO_SELECT, FREEHAND_SELECT));
        all.put("Draw", List.of(SHAPE, LINE, PATH, MODELLING, FREEHAND_DRAW, TEXT, ERASE));
        all.put("Paint", List.of(PAINTER, PAINT_BUCKET, GRADIENT_PAINTER, NOISE_PAINTER, SCRIPT_BRUSH, BIOME_PAINTER, CLENTAMINATOR));
        all.put("Sculpt", List.of(SMOOTH, ROUGHEN, DISTORT, MELT, WELD, SHATTER, EXTRUDE, SMEAR, SCULPT_DRAW, MODIFY, STAMP));
        all.put("Terrain", List.of(ELEVATION, FLATTEN, SLOPE, ROCK, FLOODFILL, FLUID_BALL));
        all.put("Utility", List.of(RULER, TINKER, ANNOTATION, INSPECT));
        return Collections.unmodifiableMap(all);
    }
}
