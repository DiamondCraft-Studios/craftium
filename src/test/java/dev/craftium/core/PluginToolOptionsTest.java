package dev.craftium.core;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The fields of a request that name a plugin's tool and carry its settings, and what the server accepts of them. */
class PluginToolOptionsTest {
    @Test void aRequestForOneOfTheEditorsOwnToolsNamesNoPluginTool() {
        var o = new Options();
        o.validate();
        assertNull(o.pluginTool);
        assertTrue(o.toolSettings.isEmpty());
    }

    @Test void aPluginToolIsNamedPluginIdColonTool() {
        for (String ok : new String[]{"wall:build", "a:b", "my_plugin2:do_it"}) {
            var o = new Options();
            o.pluginTool = ok;
            assertDoesNotThrow(o::validate, ok);
        }
        for (String bad : new String[]{"", "wall", "wall:", ":build", "Wall:build", "wall:Build", "1wall:build", "wall:build:again", "wall build:x", "wall:b-c"}) {
            var o = new Options();
            o.pluginTool = bad;
            assertThrows(IllegalArgumentException.class, o::validate, bad);
        }
    }

    @Test void settingsAreNamedLikeProperties() {
        var o = new Options();
        o.toolSettings.put("wall_height", 4.0);
        assertDoesNotThrow(o::validate);
        for (String bad : new String[]{"", "Height", "2up", "a-b", "a b", "a:b"}) {
            var p = new Options();
            p.toolSettings.put(bad, 1.0);
            assertThrows(IllegalArgumentException.class, p::validate, bad);
        }
    }

    @Test void aSettingValueMustBeANumber() {
        for (Double bad : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var o = new Options();
            o.toolSettings.put("a", bad);
            assertThrows(IllegalArgumentException.class, o::validate, String.valueOf(bad));
        }
    }

    @Test void aRequestCannotCarryAnEndlessListOfSettings() {
        var o = new Options();
        for (int i = 0; i < Options.MAX_TOOL_SETTINGS; i++) o.toolSettings.put("s" + i, 1.0);
        assertDoesNotThrow(o::validate);
        o.toolSettings.put("one_more", 1.0);
        assertThrows(IllegalArgumentException.class, o::validate);
    }

    @Test void aRequestWithAnExplicitNullForTheSettingsIsTakenAsNone() {
        var o = new Options();
        o.toolSettings = null;
        o.validate();
        assertNotNull(o.toolSettings);
        assertTrue(o.toolSettings.isEmpty());
    }

    @Test void thePluginToolAndItsSettingsSurviveTheWire() {
        var o = new Options();
        o.pluginTool = "wall:build";
        var settings = new HashMap<String, Double>(Map.of("height", 3.0, "hollow", 1.0));
        o.toolSettings = settings;
        var back = new Gson().fromJson(new Gson().toJson(o), Options.class);
        assertEquals("wall:build", back.pluginTool);
        assertEquals(settings, back.toolSettings);
    }

    @Test void thePluginToolsCapabilityIsOneThisBuildAdvertisesOnBothSides() {
        assertEquals("plugin-tools", Handshake.PLUGIN_TOOLS);
        assertTrue(Handshake.CAPABILITIES.contains(Handshake.PLUGIN_TOOLS));
        assertTrue(Handshake.FABRIC_SERVER_CAPABILITIES.contains(Handshake.PLUGIN_TOOLS));
    }
}
