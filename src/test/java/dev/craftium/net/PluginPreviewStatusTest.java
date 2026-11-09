package dev.craftium.net;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** The ghost a plugin's tool shows travels in a status of its own, and a status that does not carry one leaves it alone. */
class PluginPreviewStatusTest {
    @Test void aPluginOnlyStatusIsABackgroundOneThatCarriesTheGhostAndNothingElse() {
        var s = Packets.pluginOnly("ghost");
        assertTrue(s.background(), "it is not a reply to anything the player asked");
        assertEquals("ghost", s.pluginPreviewData());
        assertEquals("", s.message());
        assertFalse(s.error());
        assertFalse(s.busy());
    }

    @Test void aStatusThatClearsTheGhostCarriesAnEmptyOneNotNothing() {
        assertEquals("", Packets.pluginOnly("").pluginPreviewData());
    }

    @Test void theStatusOfOtherPlayersPreviewsLeavesThePluginGhostAlone() {
        assertNull(Packets.sharedOnly(java.util.List.of()).pluginPreviewData(), "null means unchanged");
    }

    @Test void aStatusFromAServerThatKnowsNoPluginsHasNoGhostAndSoLeavesItAlone() {
        var old = Packets.JSON.fromJson("{\"message\":\"Ready\",\"error\":false,\"busy\":false,\"selectionSize\":0,\"undo\":0,\"redo\":0}", Packets.Status.class);
        assertNull(old.pluginPreviewData());
    }

    @Test void theGhostSurvivesTheWire() {
        var back = Packets.JSON.fromJson(Packets.JSON.toJson(Packets.pluginOnly("ghost")), Packets.Status.class);
        assertEquals("ghost", back.pluginPreviewData());
        assertTrue(back.background());
    }
}
