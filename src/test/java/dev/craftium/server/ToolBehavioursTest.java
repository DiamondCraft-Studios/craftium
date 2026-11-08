package dev.craftium.server;

import dev.craftium.core.Tool;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolBehavioursTest {
    @Test void everyToolHasABehaviour() {
        for (Tool tool : Tool.values()) assertNotNull(ToolBehaviours.of(tool), tool.name());
    }

    /** The tools that do more with a click than plan what they make where it was clicked. Adding one here is a decision, not an accident. */
    @Test void onlyTheseToolsHaveABehaviourOfTheirOwn() {
        Set<Tool> own = EnumSet.of(Tool.ANNOTATION, Tool.BOX_SELECT, Tool.FREEHAND_SELECT, Tool.MAGIC_SELECT, Tool.RULER, Tool.LINE, Tool.SHAPE);
        for (Tool tool : Tool.values()) assertEquals(own.contains(tool), ToolBehaviours.hasOwn(tool), tool.name());
    }

    @Test void theOtherToolsPlanThePlainWay() {
        for (Tool tool : Tool.values()) if (!ToolBehaviours.hasOwn(tool)) assertSame(BuiltinTools.PLAIN, ToolBehaviours.of(tool), tool.name());
    }
}
