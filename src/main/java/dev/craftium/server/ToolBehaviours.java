package dev.craftium.server;

import dev.craftium.core.Tool;

import java.util.EnumMap;
import java.util.Map;

/** The {@link ToolBehaviour} of each tool the editor ships with. A tool that has none of its own plans the plain way, see {@link BuiltinTools#PLAIN}. */
final class ToolBehaviours {
    private ToolBehaviours() {}

    private static final Map<Tool, ToolBehaviour> BUILTIN = new EnumMap<>(Tool.class);

    static {
        BUILTIN.put(Tool.ANNOTATION, BuiltinTools.ANNOTATION);
        BUILTIN.put(Tool.BOX_SELECT, BuiltinTools.BOX_SELECT);
        BUILTIN.put(Tool.FREEHAND_SELECT, BuiltinTools.FREEHAND_SELECT);
        BUILTIN.put(Tool.MAGIC_SELECT, BuiltinTools.MAGIC_SELECT);
        BUILTIN.put(Tool.RULER, BuiltinTools.RULER);
        BUILTIN.put(Tool.LINE, BuiltinTools.LINE);
        BUILTIN.put(Tool.SHAPE, BuiltinTools.SHAPE);
    }

    static ToolBehaviour of(Tool tool) { return BUILTIN.getOrDefault(tool, BuiltinTools.PLAIN); }

    /** Whether the tool has a behaviour of its own, apart from planning the way most tools do. */
    static boolean hasOwn(Tool tool) { return BUILTIN.containsKey(tool); }
}
