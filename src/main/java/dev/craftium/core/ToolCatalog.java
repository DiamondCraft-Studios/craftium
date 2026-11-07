package dev.craftium.core;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The tools the editor ships with, each described by one {@link ToolSpec}. Whatever the editor wants to know about a tool that is not what the
 * tool does to the world comes from here, so a fact about a tool is written down once instead of in a switch in each place that needs it.
 */
public final class ToolCatalog {
    private static final Map<Tool, ToolSpec> BUILTIN = build();

    private ToolCatalog() {}

    public static ToolSpec of(Tool tool) { return BUILTIN.get(tool); }

    private static Map<Tool, ToolSpec> build() {
        var specs = new EnumMap<Tool, ToolSpec>(Tool.class);
        for (Tool tool : Tool.values()) specs.put(tool, describe(tool));
        return specs;
    }

    private static ToolSpec describe(Tool tool) {
        boolean selection = tool == Tool.MAGIC_SELECT || tool == Tool.BOX_SELECT || tool == Tool.FREEHAND_SELECT || tool == Tool.LASSO_SELECT;
        boolean transform = tool == Tool.MOVE || tool == Tool.ROTATE || tool == Tool.SCALE || tool == Tool.PIVOT;
        return new ToolSpec("craftium:" + tool.name().toLowerCase(Locale.ROOT), tool.label, tool.icon, IconCatalog.tool(tool), tool.group, tool.description,
            brushUse(tool), interaction(tool), defaultKey(tool), useHint(tool),
            transform && tool != Tool.PIVOT || tool == Tool.SHAPE,
            !selection && !transform && tool != Tool.RULER && tool != Tool.ANNOTATION && tool != Tool.MODIFY && tool != Tool.LINE && tool != Tool.PATH && tool != Tool.MODELLING && tool != Tool.SHAPE,
            !selection && tool != Tool.RULER && tool != Tool.ANNOTATION && tool != Tool.TINKER && tool != Tool.PIVOT,
            selection, transform);
    }

    private static Tool.BrushUse brushUse(Tool tool) {
        return switch (tool) {
            case FREEHAND_SELECT, PAINTER, NOISE_PAINTER, BIOME_PAINTER, CLENTAMINATOR, FREEHAND_DRAW, SHAPE, ERASE, SCULPT_DRAW, WELD, MELT, SMOOTH, DISTORT, ROUGHEN, SHATTER, GRADIENT_PAINTER, SCRIPT_BRUSH -> Tool.BrushUse.SHAPE;
            case LINE, PATH -> Tool.BrushUse.SMALL_SHAPE;
            case ELEVATION, FLATTEN, SLOPE, STAMP -> Tool.BrushUse.DISC;
            case ROCK, FLUID_BALL -> Tool.BrushUse.SPHERE;
            default -> Tool.BrushUse.NONE;
        };
    }

    /** How the tool takes input in the viewport. Every tool not named here is a plain click. */
    private static InteractionKind interaction(Tool tool) {
        return switch (tool) {
            case PATH, MODELLING -> InteractionKind.POINTS;
            case BOX_SELECT -> InteractionKind.BOX;
            case LINE -> InteractionKind.LINE;
            case RULER -> InteractionKind.RULER;
            case PIVOT -> InteractionKind.PIVOT;
            case ANNOTATION -> InteractionKind.ANNOTATION;
            case MOVE, ROTATE, SCALE -> InteractionKind.TRANSFORM;
            case GRADIENT_PAINTER -> InteractionKind.SHIFT_POINTS;
            case SLOPE -> InteractionKind.ANCHOR_PAIR;
            default -> InteractionKind.CLICK;
        };
    }

    /** The key a tool starts on, from Axiom's published table where it names one; the rest start unbound. */
    private static int defaultKey(Tool tool) {
        return switch (tool) {
            case MAGIC_SELECT -> 'M'; case BOX_SELECT -> 'B'; case FREEHAND_SELECT -> 'N'; case LASSO_SELECT -> 'L'; case PAINTER -> 'P'; case NOISE_PAINTER -> 'O';
            case FREEHAND_DRAW -> 'G'; case MOVE -> 'V'; case ROTATE -> 'R'; case SCALE -> 'C'; case PIVOT -> 'I'; case ROCK -> 'H'; case WELD -> 'J'; case MELT -> 'K';
            case ELEVATION -> 'E'; case SMOOTH -> 'U'; case EXTRUDE -> 'Z';
            default -> -1;
        };
    }

    private static String useHint(Tool tool) {
        return switch (tool) {
            case MAGIC_SELECT -> "Select connected";
            case BOX_SELECT -> "Set corner";
            case FREEHAND_SELECT -> "Brush selection";
            case LASSO_SELECT -> "Draw lasso";
            case RULER -> "Add point";
            case LINE, PATH, MODELLING -> "Add point";
            case SHAPE -> "Put down shape";
            case MOVE -> "Drag arrows to move";
            case ROTATE -> "Drag rings to rotate";
            case SCALE -> "Drag squares to scale";
            case PIVOT -> "Set pivot";
            case MODIFY -> "Fix preview";
            case ANNOTATION -> "Annotate";
            case TINKER -> "Tinker with block";
            case TEXT -> "Place text";
            case STAMP -> "Stamp clipboard";
            case ERASE -> "Erase";
            case PAINTER, NOISE_PAINTER, BIOME_PAINTER, CLENTAMINATOR, GRADIENT_PAINTER, SCRIPT_BRUSH -> "Paint";
            default -> "Use " + tool.label;
        };
    }
}
