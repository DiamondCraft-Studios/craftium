package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ShowcasePlanTest {
    private static ShowcasePlan.Plan plan(String json) { return ShowcasePlan.parse(JsonParser.parseString(json).getAsJsonObject(), "20261110-120000"); }

    private static final String ROOM = "{\"name\":\"Throne Room\",\"from\":[0,64,0],\"to\":[20,74,30],\"shots\":%s}";

    private static ShowcasePlan.Plan oneRoom(String shots) { return plan("{\"rooms\":[" + ROOM.formatted(shots) + "]}"); }

    @Test void namesBecomeSafeFileNames() {
        assertEquals("throne-room", ShowcasePlan.safeName("  Throne Room ", "room"));
        assertEquals("a-b-c", ShowcasePlan.safeName("../a\\b:c", "room"));
        assertEquals("x".repeat(ShowcasePlan.MAX_NAME), ShowcasePlan.safeName("x".repeat(200), "room"));
        assertThrows(IllegalArgumentException.class, () -> ShowcasePlan.safeName("../..", "room"));
        assertThrows(IllegalArgumentException.class, () -> ShowcasePlan.safeName("", "room"));
    }

    @Test void filesGoUnderTheRoomAndNeverEscape() {
        var p = oneRoom("[\"hero\", {\"name\":\"../../etc/x\",\"preset\":\"top\"}]");
        assertEquals("showcase-20261110-120000", p.job());
        assertEquals("throne-room/throne-room_hero.png", p.entries().get(0).path());
        assertEquals("throne-room/throne-room_etc-x.png", p.entries().get(1).path());
        for (var e : p.entries()) assertFalse(e.path().contains(".."));
    }

    @Test void aPresetLooksAtTheRoomFromItsSideAtADistanceThatFramesIt() {
        var south = oneRoom("[\"south\"]").entries().get(0);
        // The room's middle is (10.5, 69.5, 15.5); south of it the camera stands at larger z and faces north (yaw 180).
        assertTrue(south.spec().z() > 15.5 + 20);
        assertEquals(10.5, south.spec().x(), 0.5);
        assertEquals(180, Math.abs(south.spec().yaw()), 0.5);
        assertTrue(south.spec().pitch() > 0 && south.spec().pitch() < 15, "a little above level, looking slightly down");
        // Farther than the bounding sphere's radius, so the whole room is in front of the lens.
        double radius = 0.5 * Math.sqrt(21 * 21 + 11 * 11 + 31 * 31);
        double d = Math.sqrt(Math.pow(south.spec().x() - 10.5, 2) + Math.pow(south.spec().y() - 69.5, 2) + Math.pow(south.spec().z() - 15.5, 2));
        assertTrue(d > radius);
        // Worked out from the box's corners, so never farther than the cruder sphere fit, and never so close the box spills out of the picture.
        assertTrue(d <= ShowcasePlan.autoDistance(radius, 70, 1920, 1080) + 0.01);
        double wider = ShowcasePlan.fitDistance(0, 64, 0, 21, 75, 31, new double[]{10.5, 69.5, 15.5}, 180, 6, 50, 1920, 1080);
        assertTrue(wider > d, "a narrower lens needs more distance");
    }

    @Test void aCubeIsFramedTighterThanASphereWouldFrameIt() {
        double sphere = ShowcasePlan.autoDistance(0.5 * Math.sqrt(75), 70, 1920, 1080);
        double fit = ShowcasePlan.fitDistance(0, 0, 0, 5, 5, 5, new double[]{2.5, 2.5, 2.5}, 180, 0, 70, 1920, 1080);
        assertTrue(fit < sphere, "fit " + fit + " sphere " + sphere);
        // Seen face on from 180, its corners are what limit it: half the cube's height (2.5) over the distance to its near face (fit - 2.5) stays within the fill.
        assertEquals(2.5 / (ShowcasePlan.FILL * Math.tan(Math.toRadians(35))) + 2.5, fit, 0.05);
    }

    @Test void aWideRoomIsLimitedByItsWidthOnANarrowPicture() {
        double wide = ShowcasePlan.fitDistance(0, 0, 0, 40, 4, 4, new double[]{20, 2, 2}, 180, 0, 70, 1920, 1080);
        double tall = ShowcasePlan.fitDistance(0, 0, 0, 4, 40, 4, new double[]{2, 20, 2}, 180, 0, 70, 1920, 1080);
        assertTrue(tall > wide, "the same box stood on end needs more distance, since the picture is wider than tall");
    }

    @Test void aShotWorkedOutFromABearingCanBeRaisedAndAPlacedCameraCannot() {
        var orbit = oneRoom("[\"south\",{\"name\":\"gate\",\"position\":[0,70,-10],\"look_at\":[0,70,0]}]").entries();
        var up = ShowcasePlan.raised(orbit.get(0), 45);
        assertNotNull(up);
        assertTrue(up.spec().y() > orbit.get(0).spec().y());
        assertTrue(up.spec().pitch() > orbit.get(0).spec().pitch());
        assertEquals(orbit.get(0).path(), up.path());
        assertNull(ShowcasePlan.raised(orbit.get(1), 45));
        assertEquals(new Point(0, 64, 0), orbit.get(0).min());
        assertEquals(new Point(20, 74, 30), orbit.get(0).max());
    }

    @Test void theCompassSidesPutTheCameraOnThatSide() {
        double mx = 10.5, mz = 15.5;
        var s = oneRoom("[\"north\",\"east\",\"south\",\"west\"]").entries();
        assertTrue(s.get(0).spec().z() < mz); assertEquals(mx, s.get(0).spec().x(), 0.5);
        assertTrue(s.get(1).spec().x() > mx); assertEquals(mz, s.get(1).spec().z(), 0.5);
        assertTrue(s.get(2).spec().z() > mz);
        assertTrue(s.get(3).spec().x() < mx);
        // Looking from the east means facing west: the game's yaw 90.
        assertEquals(90, s.get(1).spec().yaw(), 0.5);
        assertEquals(-90, s.get(3).spec().yaw(), 0.5);
    }

    @Test void aShotCanBeAimedAnywhereWithItsOwnLensAndSize() {
        var e = oneRoom("[{\"name\":\"detail\",\"target\":[5,70,5],\"azimuth\":90,\"elevation\":0,\"distance\":10,\"fov\":40,\"width\":3840,\"height\":2160}]").entries().get(0);
        assertEquals(15, e.spec().x(), 1e-6);
        assertEquals(70, e.spec().y(), 1e-6);
        assertEquals(5, e.spec().z(), 1e-6);
        assertEquals(40, e.spec().fov());
        assertEquals(3840, e.spec().width());
        assertEquals(new Point(5, 70, 5), e.aim());
    }

    @Test void aCameraCanBePlacedOutrightAndAimedAtAPoint() {
        var e = oneRoom("[{\"name\":\"gate\",\"position\":[0,70,-10],\"look_at\":[0,70,0]}]").entries().get(0);
        assertEquals(0, e.spec().yaw(), 1e-4);   // south is +z, the game's yaw 0
        assertEquals(0, e.spec().pitch(), 1e-4);
        var down = oneRoom("[{\"name\":\"d\",\"position\":[0,80,0],\"look_at\":[0,70,10]}]").entries().get(0);
        assertTrue(down.spec().pitch() > 40 && down.spec().pitch() < 50);
        var plain = oneRoom("[{\"name\":\"p\",\"position\":[1,2,3],\"yaw\":270,\"pitch\":10}]").entries().get(0);
        assertEquals(-90, plain.spec().yaw(), 1e-4);
    }

    @Test void settingsComeFromTheShotThenTheRoomThenTheDefaults() {
        var p = plan("{\"defaults\":{\"fov\":55,\"width\":1280,\"height\":720},\"rooms\":[{\"name\":\"a\",\"from\":[0,0,0],\"to\":[4,4,4],\"fov\":80,\"shots\":[\"hero\",{\"preset\":\"top\",\"fov\":30}]},"
            + "{\"name\":\"b\",\"from\":[0,0,0],\"to\":[4,4,4],\"shots\":[\"hero\"]}]}");
        assertEquals(80, p.entries().get(0).spec().fov());
        assertEquals(30, p.entries().get(1).spec().fov());
        assertEquals(55, p.entries().get(2).spec().fov());
        assertEquals(1280, p.entries().get(0).spec().width());
        assertTrue(p.entries().get(0).spec().hideHud());
        assertFalse(plan("{\"defaults\":{\"hide_hud\":false},\"rooms\":[{\"name\":\"a\",\"from\":[0,0,0],\"to\":[1,1,1],\"shots\":[\"hero\"]}]}").entries().get(0).spec().hideHud());
    }

    @Test void theDefaultIs1080pAndFourKIsTheMost() {
        assertEquals(1920, oneRoom("[\"hero\"]").entries().get(0).spec().width());
        assertEquals(1080, oneRoom("[\"hero\"]").entries().get(0).spec().height());
        assertThrows(IllegalArgumentException.class, () -> oneRoom("[{\"preset\":\"hero\",\"width\":7680,\"height\":4320}]"));
        assertThrows(IllegalArgumentException.class, () -> oneRoom("[{\"preset\":\"hero\",\"fov\":150}]"));
    }

    @Test void straightDownStillHasADirection() {
        var e = oneRoom("[\"top\"]").entries().get(0).spec();
        assertEquals(89, e.pitch(), 1);
        assertTrue(Float.isFinite(e.yaw()));
    }

    @Test void badRequestsAreRefusedWithTheReason() {
        assertThrows(IllegalArgumentException.class, () -> plan("{}"));
        assertThrows(IllegalArgumentException.class, () -> plan("{\"rooms\":[]}"));
        var noShots = assertThrows(IllegalArgumentException.class, () -> plan("{\"rooms\":[{\"name\":\"a\",\"from\":[0,0,0],\"to\":[1,1,1]}]}"));
        assertTrue(noShots.getMessage().contains("hero"), "names the presets it knows");
        assertThrows(IllegalArgumentException.class, () -> oneRoom("[\"sideways\"]"));
        assertThrows(IllegalArgumentException.class, () -> oneRoom("[\"hero\",\"hero\"]"));
        assertThrows(IllegalArgumentException.class, () -> oneRoom("[{\"preset\":\"hero\",\"elevation\":95}]"));
        assertThrows(IllegalArgumentException.class, () -> oneRoom("[{\"name\":\"a\",\"position\":[0,0,0],\"look_at\":[0,0,0]}]"));
        assertThrows(IllegalArgumentException.class, () -> plan("{\"rooms\":[" + ROOM.formatted("[\"hero\"]") + "," + ROOM.formatted("[\"hero\"]") + "]}"));
        assertThrows(IllegalArgumentException.class, () -> plan("{\"rooms\":[{\"name\":\"a\",\"from\":[0.5,0,0],\"to\":[1,1,1],\"shots\":[\"hero\"]}]}"));
    }

    @Test void aJobIsCappedAtOneHundredPictures() {
        var shots = new StringBuilder("[");
        for (int i = 0; i < ShowcasePlan.MAX_SHOTS + 1; i++) shots.append(i > 0 ? "," : "").append("{\"name\":\"s").append(i).append("\",\"preset\":\"hero\"}");
        shots.append("]");
        assertThrows(IllegalArgumentException.class, () -> oneRoom(shots.toString()));
    }
}
