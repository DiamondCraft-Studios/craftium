package dev.craftium.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildSpecTest {
    private static final BuildSpec.Bounds AREA = new BuildSpec.Bounds(16, 16, 16);

    private static BuildSpec.Result build(String json) { return BuildSpec.parse(json).build(AREA); }

    @Test void solidBoxFillsEveryCellAndHollowKeepsOnlyTheShell() {
        assertEquals(27, build("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]}").cells().size());
        var hollow = build("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\",\"hollow\":true}]}");
        assertEquals(26, hollow.cells().size());
        assertFalse(hollow.cells().containsKey(new Point(1, 1, 1)));
    }

    @Test void bareBlockNamesGetTheMinecraftNamespace() {
        var result = build("{\"ops\":[{\"op\":\"set\",\"at\":[1,1,1],\"block\":\"sandstone\"}]}");
        assertEquals("minecraft:sandstone", result.cells().get(new Point(1, 1, 1)).block());
    }

    @Test void paletteNamesResolveAndBlockStatesAreKept() {
        var spec = BuildSpec.parse("{\"palette\":{\"slab\":\"minecraft:smooth_sandstone_slab[type=top]\"},\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"slab\"}]}");
        assertEquals("minecraft:smooth_sandstone_slab[type=top]", spec.build(AREA).cells().get(new Point(0, 0, 0)).block());
        assertTrue(spec.blocks().contains("minecraft:smooth_sandstone_slab[type=top]"));
    }

    @Test void laterOperationsOverwriteEarlierOnesAndAirCarves() {
        var result = build("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[3,3,3],\"block\":\"stone\"},{\"op\":\"box\",\"from\":[1,1,1],\"to\":[2,2,2],\"block\":\"air\"}]}");
        assertEquals("minecraft:air", result.cells().get(new Point(1, 1, 1)).block());
        assertEquals("minecraft:stone", result.cells().get(new Point(0, 0, 0)).block());
    }

    @Test void cellsOutsideTheAreaAreDroppedAndCounted() {
        var result = build("{\"ops\":[{\"op\":\"box\",\"from\":[14,0,0],\"to\":[17,0,0],\"block\":\"stone\"}]}");
        assertEquals(2, result.cells().size());
        assertEquals(2, result.clipped());
    }

    @Test void cylinderIsRoundCappedByHeightAndSymmetric() {
        var result = build("{\"ops\":[{\"op\":\"cylinder\",\"base\":[5,0,5],\"radius\":3,\"height\":8,\"block\":\"stone\"}]}");
        var cells = result.cells();
        assertTrue(cells.containsKey(new Point(5, 0, 5)) && cells.containsKey(new Point(5, 7, 5)));
        assertFalse(cells.containsKey(new Point(5, 8, 5)));
        assertFalse(cells.containsKey(new Point(5, -1, 5)));
        for (var p : cells.keySet()) assertTrue(cells.containsKey(new Point(10 - p.x(), p.y(), p.z())) && cells.containsKey(new Point(p.x(), p.y(), 10 - p.z())), "not symmetric at " + p);
    }

    @Test void cylinderCanLieAlongAnotherAxis() {
        var cells = build("{\"ops\":[{\"op\":\"cylinder\",\"base\":[0,4,4],\"radius\":1,\"height\":6,\"axis\":\"x\",\"block\":\"stone\"}]}").cells();
        assertTrue(cells.containsKey(new Point(5, 4, 4)));
        assertFalse(cells.containsKey(new Point(6, 4, 4)));
    }

    @Test void hollowSphereHasNoCentreButStillTouchesTheSurface() {
        var sphere = build("{\"ops\":[{\"op\":\"sphere\",\"center\":[7,7,7],\"radius\":5,\"block\":\"stone\"}]}").cells();
        var hollow = build("{\"ops\":[{\"op\":\"sphere\",\"center\":[7,7,7],\"radius\":5,\"block\":\"stone\",\"hollow\":true}]}").cells();
        assertTrue(sphere.containsKey(new Point(7, 7, 7)));
        assertFalse(hollow.containsKey(new Point(7, 7, 7)));
        assertTrue(hollow.containsKey(new Point(12, 7, 7)));
        assertTrue(sphere.keySet().containsAll(hollow.keySet()));
    }

    @Test void coneNarrowsToTheTopAndPyramidKeepsSquareLayers() {
        var cone = build("{\"ops\":[{\"op\":\"cone\",\"base\":[7,0,7],\"radius\":6,\"height\":6,\"block\":\"stone\"}]}").cells();
        long bottom = cone.keySet().stream().filter(p -> p.y() == 0).count(), top = cone.keySet().stream().filter(p -> p.y() == 5).count();
        assertTrue(bottom > top && top >= 1);
        var pyramid = build("{\"ops\":[{\"op\":\"pyramid\",\"base\":[7,0,7],\"radius\":4,\"height\":5,\"block\":\"stone\"}]}").cells();
        assertEquals(81, pyramid.keySet().stream().filter(p -> p.y() == 0).count());
        assertTrue(pyramid.containsKey(new Point(3, 0, 3)) && !pyramid.containsKey(new Point(3, 2, 3)));
    }

    @Test void lineReachesBothEndsAndThicknessWidensIt() {
        var thin = build("{\"ops\":[{\"op\":\"line\",\"from\":[0,0,0],\"to\":[10,5,0],\"block\":\"stone\"}]}").cells();
        assertTrue(thin.containsKey(new Point(0, 0, 0)) && thin.containsKey(new Point(10, 5, 0)));
        var thick = build("{\"ops\":[{\"op\":\"line\",\"from\":[2,2,2],\"to\":[10,2,2],\"thickness\":3,\"block\":\"stone\"}]}").cells();
        assertTrue(thick.containsKey(new Point(5, 3, 2)) && thick.containsKey(new Point(5, 2, 3)));
    }

    @Test void repeatPlacesCopiesAtTheStep() {
        var cells = build("{\"ops\":[{\"op\":\"cylinder\",\"base\":[1,0,1],\"radius\":1,\"height\":4,\"block\":\"stone\",\"repeat\":{\"count\":3,\"step\":[5,0,0]}}]}").cells();
        assertTrue(cells.containsKey(new Point(1, 0, 1)) && cells.containsKey(new Point(6, 0, 1)) && cells.containsKey(new Point(11, 0, 1)));
        assertFalse(cells.containsKey(new Point(3, 0, 1)));
    }

    @Test void replaceCellsCarryTheirFilter() {
        var cell = build("{\"ops\":[{\"op\":\"replace\",\"from\":[0,0,0],\"to\":[1,0,0],\"find\":\"stone\",\"block\":\"gold_block\"}]}").cells().get(new Point(0, 0, 0));
        assertEquals("minecraft:gold_block", cell.block());
        assertEquals("minecraft:stone", cell.only());
    }

    @Test void malformedSpecsFailWithAMessageNamingTheOperation() {
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[]}"));
        var missing = assertThrows(IllegalArgumentException.class, () -> build("{\"ops\":[{\"op\":\"cylinder\",\"base\":[0,0,0],\"height\":3,\"block\":\"stone\"}]}"));
        assertTrue(missing.getMessage().contains("ops[0] (cylinder)") && missing.getMessage().contains("radius"), missing.getMessage());
        var unknown = assertThrows(IllegalArgumentException.class, () -> build("{\"ops\":[{\"op\":\"teleport\",\"block\":\"stone\"}]}"));
        assertTrue(unknown.getMessage().contains("unknown operation"));
        assertThrows(IllegalArgumentException.class, () -> build("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"Stone; /op me\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> build("{\"ops\":[{\"op\":\"set\",\"at\":[0.5,0,0],\"block\":\"stone\"}]}"));
    }

    @Test void limitsStopOversizedSpecs() {
        var tooMany = new StringBuilder("{\"ops\":[");
        for (int i = 0; i <= BuildSpec.MAX_OPS; i++) tooMany.append(i == 0 ? "" : ",").append("{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"}");
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(tooMany + "]}"));
        assertThrows(IllegalArgumentException.class, () -> build("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\",\"repeat\":{\"count\":65,\"step\":[0,0,0]}}]}"));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"}]}").build(new BuildSpec.Bounds(300, 4, 4)));
        assertThrows(IllegalArgumentException.class, () -> build("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[99999,99999,99999],\"block\":\"stone\"}]}"));
    }
}
