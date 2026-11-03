package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ToolSectionsTest {
    @Test void everyPanelToolIsInExactlyOneSection() {
        var seen = new ArrayList<Tool>();
        Tool.SECTIONS.values().forEach(seen::addAll);
        assertEquals(seen.size(), new HashSet<>(seen).size(), "a tool is listed in two sections");
        for (Tool tool : Tool.values()) assertEquals(!tool.transform(), seen.contains(tool), tool + " is missing from or wrongly in the sections");
    }

    @Test void sectionNamesMatchToolGroups() {
        Tool.SECTIONS.forEach((section, tools) -> tools.forEach(tool -> assertEquals(section, tool.group, tool.label)));
    }
}
