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

    @Test void everyToolsIconIsInTheIconCatalog() {
        for (Tool tool : Tool.values()) {
            var icon = IconCatalog.find(tool.spec().iconId());
            assertTrue(icon.isPresent(), tool.name());
            assertEquals(tool.label, icon.get().label(), tool.name());
            assertEquals("Tools/" + tool.group, icon.get().category(), tool.name());
        }
    }

    @Test void everyToolTakesInputTheWayItAlwaysDid() {
        // The tools whose use button does something other than send a request for the block under the cursor, and what each does.
        var special = new EnumMap<Tool, InteractionKind>(Tool.class);
        special.put(Tool.PATH, InteractionKind.POINTS);
        special.put(Tool.MODELLING, InteractionKind.POINTS);
        special.put(Tool.BOX_SELECT, InteractionKind.BOX);
        special.put(Tool.GRADIENT_PAINTER, InteractionKind.SHIFT_POINTS);
        special.put(Tool.SLOPE, InteractionKind.ANCHOR_PAIR);
        for (Tool tool : Tool.values()) assertEquals(special.getOrDefault(tool, InteractionKind.CLICK), tool.spec().interaction(), tool.name());
    }

    @Test void onlyTheGeneralInteractionKindsAreOpenToPlugins() {
        assertTrue(InteractionKind.CLICK.forPlugins);
        assertTrue(InteractionKind.POINTS.forPlugins);
        assertTrue(InteractionKind.BOX.forPlugins);
        assertFalse(InteractionKind.SHIFT_POINTS.forPlugins);
        assertFalse(InteractionKind.ANCHOR_PAIR.forPlugins);
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
