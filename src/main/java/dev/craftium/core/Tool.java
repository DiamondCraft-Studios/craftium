package dev.craftium.core;

public enum Tool {
    MAGIC_SELECT("Magic Wand", "MW", "Selection", "Select connected blocks of the same type."),
    BOX_SELECT("Box Select", "BX", "Selection", "Right-click two corners to select a cuboid."),
    FREEHAND_SELECT("Freehand Select", "FS", "Selection", "Brush a selection; use selection mode to add or subtract."),
    RULER("Ruler", "RL", "Utility", "Add measurement points; use the inspector for circles, splits and center selection."),
    PAINTER("Painter", "PA", "Painting", "Paint existing blocks with your active material."),
    NOISE_PAINTER("Noise Painter", "NP", "Painting", "Blend two materials with coherent seeded noise."),
    BIOME_PAINTER("Biome Painter", "BP", "Painting", "Paint biome cells inside the brush. Biomes use 4-block cells."),
    CLENTAMINATOR("Clentaminator", "CL", "Painting", "Convert biome and matching surface materials together."),
    FREEHAND_DRAW("Freehand Draw", "DR", "Drawing", "Draw solid shapes; invert to carve."),
    SCULPT_DRAW("Sculpt Draw", "SC", "Drawing", "Pull the surface outward along its normal."),
    ROCK("Rock", "RK", "Drawing", "Create a coherent, noise-shaped boulder."),
    WELD("Weld", "WD", "Drawing", "Fill concave seams using local voxel density."),
    MELT("Melt", "ML", "Drawing", "Erode exposed corners using local voxel density."),
    TEXT("Text", "TX", "Drawing", "Place block lettering using a compact 5 by 7 alphabet."),
    ELEVATION("Elevation", "EL", "Terrain", "Raise or lower columns with soft radial falloff."),
    FLATTEN("Flatten", "FL", "Terrain", "Bring terrain toward the target Y level."),
    SLOPE("Slope", "SL", "Terrain", "Shape a ramp toward your chosen direction and rise."),
    SMOOTH("Smooth", "SM", "Sculpting", "Smooth voxel surfaces with a weighted neighbour filter."),
    DISTORT("Distort", "DS", "Sculpting", "Warp existing material through a coherent noise field."),
    ROUGHEN("Roughen", "RF", "Sculpting", "Add small, seeded irregularities to exposed surfaces."),
    SHATTER("Shatter", "SH", "Sculpting", "Cut cellular fracture seams through a volume."),
    EXTRUDE("Extrude", "EX", "Sculpting", "Extend exposed faces along the clicked surface normal."),
    SHAPE("Shape", "SP", "Drawing", "Place a sphere, cuboid, octahedron or cylinder."),
    LINE("Line", "LN", "Drawing", "Right-click two endpoints to draw a continuous line."),
    ERASE("Erase", "ER", "Drawing", "Remove blocks inside the brush with undo."),
    TINKER("Tinker", "TK", "Utility", "Cycle block properties. Sneak to cycle the next property."),
    LASSO_SELECT("Lasso Select", "LS", "Selection", "Draw a screen-space polygon; release to project it into the world."),
    GRADIENT_PAINTER("Gradient Painter", "GP", "Painting", "Shift-right-click gradient anchors, then paint between them."),
    SCRIPT_BRUSH("Script Brush", "LU", "Painting", "Run a bounded Lua script for each block in the brush."),
    MODELLING("Modelling", "MD", "Drawing", "Place control points and build a hull, triangle strip or lofted surface."),
    STAMP("Stamp", "ST", "Drawing", "Scatter the clipboard with spacing, random rotation and chance."),
    PATH("Path", "PT", "Drawing", "Create straight, Bezier, Catmull-Rom or hanging catenary paths."),
    FLOODFILL("Floodfill", "FF", "Fluid", "Fill connected space with a block count and directional limits."),
    FLUID_BALL("Fluid Ball", "FB", "Fluid", "Simulate bounded water, lava or snow flow over terrain."),
    MODIFY("Modify", "MF", "Manipulation", "Translate copies, revolve, rotate copies or twist your selection."),
    ANNOTATION("Annotation", "AN", "Utility", "Draw world-space notes, outlines and labels without changing blocks."),
    SMEAR("Smear", "SR", "Drawing", "Stretch a selection along an offset, stopping at existing solid blocks."),
    MOVE("Move", "MV", "Transform", "Drag the arrows to move the selected blocks. Enter applies the move."),
    ROTATE("Rotate", "RT", "Transform", "Drag a ring to rotate the selected blocks around the pivot. Enter applies it."),
    SCALE("Scale", "SZ", "Transform", "Drag a square on a face to scale the selected blocks around the pivot. Enter applies it."),
    PIVOT("Pivot", "PV", "Transform", "Click a block, or drag the arrows, to set the point Rotate and Scale work around.");

    public final String label, icon, group, description;
    Tool(String label, String icon, String group, String description) {
        this.label=label; this.icon=icon; this.group=group; this.description=description;
    }
    /** The tools that move, rotate or scale the selected blocks around a pivot. */
    public boolean transform() { return this == MOVE || this == ROTATE || this == SCALE || this == PIVOT; }
    public boolean selection() { return this == MAGIC_SELECT || this == BOX_SELECT || this == FREEHAND_SELECT || this == LASSO_SELECT; }
}
