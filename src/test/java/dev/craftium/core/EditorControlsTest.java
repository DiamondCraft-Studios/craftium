package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.craftium.core.EditorControls.*;
import org.junit.jupiter.api.Test;

class EditorControlsTest {
    @Test
    void separatesToolKeysFromChordsAndMouseGestures() {
        assertTrue(Action.USE_TOOL.defaults.matches(true, 1, 0));
        assertFalse(Action.USE_TOOL.defaults.matches(true, 1, 2));
        assertTrue(Action.PAN_CAMERA.defaults.matches(true, 1, 2));
        assertTrue(Action.SAVE_BLUEPRINT.defaults.matches(false, 'P', 2 | 16));
        assertFalse(Action.SAVE_BLUEPRINT.defaults.matches(false, 'P', 0));
        assertFalse(Action.SAVE_BLUEPRINT.defaults.matches(false, 'P', 3));
        assertFalse(Action.HELP.defaults.matches(false, -1, 0));
    }

    @Test
    void nudgesFollowCameraInsteadOfSelectedWorldAxis() {
        assertEquals(new Point(0, 0, 1), EditorControls.nudge(Action.NUDGE_FORWARD, 0));
        assertEquals(new Point(-1, 0, 0), EditorControls.nudge(Action.NUDGE_RIGHT, 0));
        assertEquals(new Point(-1, 0, 0), EditorControls.nudge(Action.NUDGE_FORWARD, 90));
        assertEquals(new Point(0, 0, -1), EditorControls.nudge(Action.NUDGE_RIGHT, 90));
        assertEquals(new Point(0, 0, -1), EditorControls.nudge(Action.NUDGE_FORWARD, 180));
        assertEquals(new Point(1, 0, 0), EditorControls.nudge(Action.NUDGE_FORWARD, -90));
        assertEquals(new Point(0, 1, 0), EditorControls.nudge(Action.NUDGE_UP, 137));
        assertEquals(new Point(0, -1, 0), EditorControls.nudge(Action.NUDGE_DOWN, 137));
        for (int yaw = -720; yaw <= 720; yaw += 45) {
            var f = EditorControls.nudge(Action.NUDGE_FORWARD, yaw);
            var b = EditorControls.nudge(Action.NUDGE_BACK, yaw);
            var r = EditorControls.nudge(Action.NUDGE_RIGHT, yaw);
            var l = EditorControls.nudge(Action.NUDGE_LEFT, yaw);
            assertEquals(new Point(0, 0, 0), f.add(b.x(), b.y(), b.z()));
            assertEquals(new Point(0, 0, 0), r.add(l.x(), l.y(), l.z()));
            assertEquals(0, f.x() * r.x() + f.z() * r.z());
        }
    }
}
