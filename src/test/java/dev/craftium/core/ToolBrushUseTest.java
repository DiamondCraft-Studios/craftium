package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

class ToolBrushUseTest {
    @Test void toolsThatBuildFromTheShapeAreTheOnesThatPickOne() {
        for (Tool tool : Tool.values())
            assertEquals(tool.brushUse() == Tool.BrushUse.SHAPE || tool.brushUse() == Tool.BrushUse.SMALL_SHAPE, tool.picksShape(), tool.name());
    }

    @Test void toolsWithoutABrushShowNoneAndSelectionAndTransformToolsHaveNone() {
        for (Tool tool : EnumSet.of(Tool.EXTRUDE, Tool.TINKER, Tool.FLOODFILL, Tool.MODELLING, Tool.SMEAR, Tool.TEXT, Tool.MODIFY, Tool.RULER, Tool.ANNOTATION))
            assertEquals(Tool.BrushUse.NONE, tool.brushUse(), tool.name());
        for (Tool tool : Tool.values())
            if (tool != Tool.FREEHAND_SELECT && (tool.selection() || tool.transform())) assertEquals(Tool.BrushUse.NONE, tool.brushUse(), tool.name());
    }

    @Test void radiusOnlyToolsAreNotGivenAShapePicker() {
        for (Tool tool : EnumSet.of(Tool.ELEVATION, Tool.FLATTEN, Tool.SLOPE, Tool.STAMP, Tool.ROCK, Tool.FLUID_BALL, Tool.FLUID_BLOCK)) assertFalse(tool.picksShape(), tool.name());
        assertEquals(Tool.BrushUse.SMALL_SHAPE, Tool.LINE.brushUse());
        assertEquals(Tool.BrushUse.SMALL_SHAPE, Tool.PATH.brushUse());
    }
}
