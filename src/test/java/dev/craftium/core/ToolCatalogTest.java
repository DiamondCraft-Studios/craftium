package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {
    @Test void everyToolHasASpecWithAUniqueNamespacedId() {
        var ids = new HashSet<String>();
        for (Tool tool : Tool.values()) {
            var spec = ToolCatalog.of(tool);
            assertNotNull(spec, tool.name());
            assertEquals("craftium:" + tool.name().toLowerCase(Locale.ROOT), spec.id());
            assertEquals(tool.name().toLowerCase(Locale.ROOT), spec.path());
            assertTrue(ids.add(spec.id()), "duplicate id " + spec.id());
        }
    }

    @Test void aSpecCarriesTheToolsOwnLabelIconSectionAndDescription() {
        for (Tool tool : Tool.values()) {
            var spec = tool.spec();
            assertEquals(tool.label, spec.label(), tool.name());
            assertEquals(tool.icon, spec.icon(), tool.name());
            assertEquals(tool.group, spec.section(), tool.name());
            assertEquals(tool.description, spec.description(), tool.name());
        }
    }

    @Test void theToolMethodsAnswerFromTheSpec() {
        for (Tool tool : Tool.values()) {
            var spec = tool.spec();
            assertEquals(spec.brushUse(), tool.brushUse(), tool.name());
            assertEquals(spec.picksShape(), tool.picksShape(), tool.name());
            assertEquals(spec.transform(), tool.transform(), tool.name());
            assertEquals(spec.placesBlocks(), tool.placesBlocks(), tool.name());
            assertEquals(spec.selection(), tool.selection(), tool.name());
            assertEquals(spec.defaultKey(), EditorControls.toolDefault(tool), tool.name());
        }
    }

    @Test void everyToolsDefaultKeyIsALetterOrUnbound() {
        for (Tool tool : Tool.values()) {
            int key = ToolCatalog.of(tool).defaultKey();
            assertTrue(key == -1 || key >= 'A' && key <= 'Z', tool.name());
        }
    }
}
