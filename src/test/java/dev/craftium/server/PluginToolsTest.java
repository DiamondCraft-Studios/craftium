package dev.craftium.server;

import dev.craftium.core.IconCatalog;
import dev.craftium.core.InteractionKind;
import dev.craftium.core.PropertySpec;
import dev.craftium.core.Tool;
import dev.craftium.core.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginToolsTest {
    private static final ToolBehaviour NOTHING = use -> {};

    private static ToolSpec spec(String id, PropertySpec... properties) {
        return new ToolSpec(id, "Wall", "WL", IconCatalog.tool(Tool.SHAPE), "Plugins", "A wall", Tool.BrushUse.NONE, InteractionKind.CLICK, -1, "Build", false, false, false, true, false, false, List.of(properties));
    }

    @AfterEach void clean() { PluginTools.clear(); }

    @Test void aToolIsFoundOnceItIsRegistered() {
        assertNull(PluginTools.find("wall:build"));
        PluginTools.register(spec("wall:build"), NOTHING);
        assertSame(NOTHING, PluginTools.find("wall:build").behaviour());
        assertEquals("wall:build", PluginTools.find("wall:build").spec().id());
        assertNull(PluginTools.find("wall:other"));
        assertNull(PluginTools.find(null));
    }

    @Test void theEditorsOwnNamespaceAndMalformedIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PluginTools.register(spec("craftium:painter"), NOTHING));
        assertThrows(IllegalArgumentException.class, () -> PluginTools.register(spec("painter"), NOTHING));
        assertThrows(IllegalArgumentException.class, () -> PluginTools.register(spec("Wall:Build"), NOTHING));
        assertThrows(IllegalArgumentException.class, () -> PluginTools.register(spec("wall:build"), null));
        assertThrows(IllegalArgumentException.class, () -> PluginTools.register(null, NOTHING));
    }

    @Test void aToolCannotBeRegisteredTwice() {
        PluginTools.register(spec("wall:build"), NOTHING);
        assertThrows(IllegalArgumentException.class, () -> PluginTools.register(spec("wall:build"), use -> {}));
        assertSame(NOTHING, PluginTools.find("wall:build").behaviour());
    }

    @Test void unregisteringANamespaceTakesBackItsToolsAndNoOthers() {
        PluginTools.register(spec("wall:build"), NOTHING);
        PluginTools.register(spec("wall:clear"), NOTHING);
        PluginTools.register(spec("wallpaper:build"), NOTHING);
        assertEquals(2, PluginTools.unregister("wall"));
        assertNull(PluginTools.find("wall:build"));
        assertNotNull(PluginTools.find("wallpaper:build"));
        assertEquals(0, PluginTools.unregister("wall"));
        assertDoesNotThrow(() -> PluginTools.register(spec("wall:build"), NOTHING));
    }

    @Test void theSettingsOfARequestAreHeldToWhatTheToolDeclared() {
        PluginTools.register(spec("wall:build", PropertySpec.integer("height", "Height", 1, 5, 2), PropertySpec.toggle("hollow", "Hollow", false), PropertySpec.number("scale", "Scale", 0.5, 4, 1)), NOTHING);
        var tool = PluginTools.find("wall:build");
        var values = tool.settings(Map.of("height", 99.0, "hollow", 1.0, "undeclared", 7.0));
        assertEquals(5, values.integer("height"), "past the range is held to it");
        assertTrue(values.toggled("hollow"));
        assertEquals(1.0, values.number("scale"), "what the request did not say is what the tool started on");
        assertThrows(IllegalArgumentException.class, () -> values.number("undeclared"), "a setting the tool did not declare is not kept");
    }

    @Test void aRequestWithNoSettingsGivesTheStartingValues() {
        PluginTools.register(spec("wall:build", PropertySpec.integer("height", "Height", 1, 5, 2)), NOTHING);
        var tool = PluginTools.find("wall:build");
        assertEquals(2, tool.settings(Map.of()).integer("height"));
        assertEquals(2, tool.settings(null).integer("height"));
    }
}
