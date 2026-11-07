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

    @Test void aReplaceActsOnWhatTheSpecPlacedEarlier() {
        var result = build("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,0,0],\"block\":\"stone\"},{\"op\":\"set\",\"at\":[1,0,0],\"block\":\"dirt\"},"
                + "{\"op\":\"replace\",\"from\":[0,0,0],\"to\":[1,0,0],\"find\":\"stone\",\"block\":\"gold_block\"}]}");
        assertEquals("minecraft:gold_block", result.cells().get(new Point(0, 0, 0)).block());
        assertNull(result.cells().get(new Point(0, 0, 0)).only());
        assertEquals("minecraft:dirt", result.cells().get(new Point(1, 0, 0)).block());
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

    // ---- absolute coordinates: the area is worked out from the operations ------------------------------------

    private static BuildSpec.Result placed(BuildSpec spec) { return spec.build(spec.place()); }

    @Test void aSpecInWorldCoordinatesPlacesItselfWhereItsNumbersSay() {
        var spec = BuildSpec.parse("{\"ops\":[{\"op\":\"box\",\"from\":[100,64,-50],\"to\":[102,66,-48],\"block\":\"stone\"}]}");
        var place = spec.place();
        assertEquals(new Point(100, 64, -50), place.min());
        assertEquals(new BuildSpec.Bounds(3, 3, 3), place.bounds());
        var result = spec.build(place);
        assertEquals(27, result.cells().size());
        assertTrue(result.cells().containsKey(new Point(0, 0, 0)) && result.cells().containsKey(new Point(2, 2, 2)), "cells are relative to the placement's minimum corner");
        assertEquals(0, result.clipped());
    }

    @Test void theOriginIsAddedToEveryCoordinate() {
        var spec = BuildSpec.parse("{\"origin\":[1000,10,-20],\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"},{\"op\":\"set\",\"at\":[5,0,0],\"block\":\"stone\"}]}");
        var place = spec.place();
        assertEquals(new Point(1000, 10, -20), place.min());
        assertEquals(new BuildSpec.Bounds(6, 2, 2), place.bounds());
    }

    @Test void everyKindOfOperationFitsInsideTheAreaWorkedOutForIt() {
        for (String op : new String[]{
                "{\"op\":\"sphere\",\"center\":[-300,70,40],\"radius\":6,\"block\":\"stone\"}",
                "{\"op\":\"sphere\",\"center\":[5,5,5],\"radii\":[3,7,2],\"block\":\"stone\",\"hollow\":true}",
                "{\"op\":\"cylinder\",\"base\":[10,0,10],\"radius\":4,\"height\":9,\"axis\":\"x\",\"block\":\"stone\"}",
                "{\"op\":\"cone\",\"base\":[0,64,0],\"radius\":5,\"height\":8,\"block\":\"stone\"}",
                "{\"op\":\"pyramid\",\"base\":[-7,3,9],\"radius\":6,\"height\":7,\"axis\":\"z\",\"block\":\"stone\"}",
                "{\"op\":\"line\",\"from\":[0,0,0],\"to\":[20,9,-13],\"thickness\":4,\"block\":\"stone\"}",
                "{\"op\":\"set\",\"at\":[[1,2,3],[9,-4,0],[-6,8,8]],\"block\":\"stone\"}",
                "{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\",\"repeat\":{\"count\":10,\"step\":[-3,2,4]}}"}) {
            var spec = BuildSpec.parse("{\"ops\":[" + op + "]}");
            var result = spec.build(spec.place());
            assertEquals(0, result.clipped(), op);
            assertFalse(result.cells().isEmpty(), op);
            // The area is no larger than it needs to be: something touches each of its six faces' planes.
            var b = spec.place().bounds();
            assertTrue(result.cells().keySet().stream().anyMatch(p -> p.x() == 0) && result.cells().keySet().stream().anyMatch(p -> p.x() == b.sx() - 1), op);
            assertTrue(result.cells().keySet().stream().anyMatch(p -> p.y() == 0) && result.cells().keySet().stream().anyMatch(p -> p.y() == b.sy() - 1), op);
            assertTrue(result.cells().keySet().stream().anyMatch(p -> p.z() == 0) && result.cells().keySet().stream().anyMatch(p -> p.z() == b.sz() - 1), op);
        }
    }

    @Test void aSpecInWorldCoordinatesBuildsTheSameBlocksAsOneRelativeToACorner() {
        String shape = "{\"op\":\"cylinder\",\"base\":[%d,%d,%d],\"radius\":3,\"height\":6,\"block\":\"stone\"}";
        var relative = BuildSpec.parse("{\"ops\":[" + String.format(shape, 3, 0, 3) + "]}").build(new BuildSpec.Bounds(7, 6, 7));
        var absolute = placed(BuildSpec.parse("{\"ops\":[" + String.format(shape, 503, 64, -97) + "]}"));
        assertEquals(relative.cells().keySet(), absolute.cells().keySet());
    }

    @Test void aSpecThatSpansMoreThanTheBuildLimitIsRefusedWithAMessage() {
        var e = assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[300,0,0],\"block\":\"stone\"}]}").place());
        assertTrue(e.getMessage().contains("301") && e.getMessage().contains("x"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"},{\"op\":\"set\",\"at\":[0,0,250],\"block\":\"stone\",\"repeat\":{\"count\":64,\"step\":[0,0,1]}}]}").place());
    }

    @Test void coordinatesReachTheEdgeOfTheWorldButNotBeyond() {
        assertEquals(new Point(29_999_990, 64, -29_999_990), BuildSpec.parse("{\"ops\":[{\"op\":\"box\",\"from\":[29999990,64,-29999990],\"to\":[29999995,66,-29999985],\"block\":\"stone\"}]}").place().min());
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"set\",\"at\":[30000001,0,0],\"block\":\"stone\"}]}").place());
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"origin\":[1,2],\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"}]}"));
    }

    @Test void placingNamesTheOperationAtFault() {
        var e = assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"},{\"op\":\"cylinder\",\"base\":[0,0,0],\"block\":\"stone\"}]}").place());
        assertTrue(e.getMessage().contains("ops[1]"), e.getMessage());
    }

    @Test void replaceSelectionIsKeptApartFromTheShapesAndItsBlocksAreResolved() {
        var spec = BuildSpec.parse("{\"palette\":{\"deep\":\"minecraft:deepslate_tile_stairs\"},\"ops\":["
            + "{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"},"
            + "{\"op\":\"replace_selection\",\"find\":\"spruce_stairs\",\"block\":\"deep\",\"keep\":[\"facing\",\"half\"]}]}");
        assertTrue(spec.hasShapes());
        assertEquals(1, build("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"}]}").cells().size());
        assertEquals(1, spec.build(AREA).cells().size(), "the selection operation places nothing by itself");
        var op = spec.selectionOps().get(0);
        assertEquals("minecraft:deepslate_tile_stairs", op.block());
        assertEquals("minecraft:spruce_stairs", op.find());
        assertEquals(java.util.List.of("facing", "half"), op.keep());
        assertTrue(spec.blocks().containsAll(java.util.Set.of("minecraft:stone", "minecraft:deepslate_tile_stairs", "minecraft:spruce_stairs")));
    }

    @Test void aSpecOfSelectionOperationsAloneHasNoShapesAndNoAreaOfItsOwn() {
        var spec = BuildSpec.parse("{\"ops\":[{\"op\":\"replace_selection\",\"block\":\"deepslate\"}]}");
        assertFalse(spec.hasShapes());
        assertNull(spec.selectionOps().get(0).find());
        assertNull(spec.selectionOps().get(0).keep(), "no keep list means keep every property the two blocks share");
        assertThrows(IllegalArgumentException.class, spec::place);
    }

    @Test void badSelectionOperationsNameTheOperationAtFault() {
        var noBlock = assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"replace_selection\"}]}"));
        assertTrue(noBlock.getMessage().contains("ops[0] (replace_selection)"), noBlock.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"replace_selection\",\"block\":\"stone\",\"keep\":\"facing\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"replace_selection\",\"block\":\"stone\",\"keep\":[\"Facing!\"]}]}"));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[{\"op\":\"replace_selection\",\"block\":\"stone\",\"repeat\":{\"count\":2,\"step\":[1,0,0]}}]}"));
    }

    @Test void aSelectionOperationListedBeforeAShapeIsRefusedInsteadOfRunningLast() {
        var e = assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":["
            + "{\"op\":\"replace_selection\",\"block\":\"deepslate\"},"
            + "{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"}]}"));
        assertTrue(e.getMessage().contains("ops[1] (box)") && e.getMessage().contains("after all the shapes"), e.getMessage());
        // After the shapes it is fine, and several selection operations may follow one another.
        var ok = BuildSpec.parse("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"},"
            + "{\"op\":\"replace_selection\",\"block\":\"deepslate\"},{\"op\":\"replace_selection\",\"block\":\"tuff\"}]}");
        assertEquals(2, ok.selectionOps().size());
    }

    @Test void anEmptyOpsListIsRefusedBeforeAnythingElse() {
        var e = assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse("{\"ops\":[]}"));
        assertTrue(e.getMessage().contains("\"ops\" is empty"), e.getMessage());
    }

    // ---- materials, mixes, roofs and groups ---------------------------------------------------------------------

    private static IllegalArgumentException refused(String json) {
        return assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(json).build(AREA));
    }

    @Test void kindsAreFilledInFromTheMaterialOfTheSpecOrTheOperation() {
        var cells = build("""
            {"material":"spruce","ops":[
              {"op":"set","at":[0,0,0],"block":"@stairs[facing=east]"},
              {"op":"set","at":[1,0,0],"block":"@slab","material":"stone_bricks"},
              {"op":"set","at":[2,0,0],"block":"oak@planks"}]}""").cells();
        assertEquals("minecraft:spruce@stairs[facing=east]", cells.get(new Point(0, 0, 0)).block());
        assertEquals("minecraft:stone_bricks@slab", cells.get(new Point(1, 0, 0)).block());
        assertEquals("minecraft:oak@planks", cells.get(new Point(2, 0, 0)).block());
    }

    @Test void aKindWithoutAMaterialOrAnUnknownKindIsRefused() {
        var e = refused("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"@stairs\"}]}");
        assertTrue(e.getMessage().contains("ops[0] (set)") && e.getMessage().contains("material"), e.getMessage());
        e = refused("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"spruce@stair\"}]}");
        assertTrue(e.getMessage().contains("not a kind of block"), e.getMessage());
    }

    @Test void aMixIsTheSameEachBuildAndFollowsItsWeights() {
        String json = """
            {"palette":{"wall":{"mix":[["stone_bricks",3],["mossy_stone_bricks",1]]}},
             "ops":[{"op":"box","from":[0,0,0],"to":[15,15,0],"block":"wall"}]}""";
        var first = build(json).cells();
        var again = build(json).cells();
        long mossy = first.values().stream().filter(c -> c.block().equals("minecraft:mossy_stone_bricks")).count();
        assertTrue(mossy > 30 && mossy < 100, "mossy " + mossy + " of 256");
        for (var p : first.keySet()) assertEquals(first.get(p).block(), again.get(p).block());
        // Another seed gives another pattern.
        var other = build(json.replace("]]}}", "]],\"seed\":7}}")).cells();
        assertTrue(first.keySet().stream().anyMatch(p -> !first.get(p).block().equals(other.get(p).block())));
    }

    @Test void aMixCanStandWhereOneBlockIsWantedOnlyWhereItMakesSense() {
        var cells = build("{\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[3,0,0],\"block\":{\"mix\":[\"stone\",\"andesite\"]}}]}").cells();
        assertEquals(4, cells.size());
        var e = refused("{\"palette\":{\"m\":{\"mix\":[\"stone\",\"andesite\"]}},\"ops\":[{\"op\":\"replace\",\"from\":[0,0,0],\"to\":[1,0,0],\"find\":\"m\",\"block\":\"dirt\"}]}");
        assertTrue(e.getMessage().contains("one block"), e.getMessage());
        e = refused("{\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":{\"mix\":[[\"stone\",0]]}}]}");
        assertTrue(e.getMessage().contains("1 to 1000"), e.getMessage());
    }

    @Test void aRoofTakesItsBlocksFromItsMaterial() {
        var spec = BuildSpec.parse("{\"ops\":[{\"op\":\"roof\",\"from\":[0,4,0],\"to\":[6,4,4],\"material\":\"spruce\"}]}");
        var result = spec.build(spec.place());
        assertEquals(0, result.clipped());
        assertTrue(result.cells().values().stream().anyMatch(c -> c.block().equals("minecraft:spruce@stairs[facing=south,half=bottom,shape=straight]")));
        assertTrue(result.cells().values().stream().anyMatch(c -> c.block().equals("minecraft:spruce@slab[type=bottom]")));
        assertTrue(spec.blocks().contains("minecraft:spruce@block"));
        assertEquals(java.util.List.of("ops[0] (roof)"), spec.usesOf("minecraft:spruce@stairs"));
        var e = refused("{\"ops\":[{\"op\":\"roof\",\"from\":[0,4,0],\"to\":[6,5,4],\"material\":\"spruce\"}]}");
        assertTrue(e.getMessage().contains("ops[0] (roof)") && e.getMessage().contains("same y"), e.getMessage());
    }

    @Test void aGroupTurnsItsBlocksAndTheirStates() {
        var cells = build("""
            {"ops":[{"op":"group","rotate":90,"ops":[
              {"op":"set","at":[0,0,0],"block":"oak_stairs[facing=north]"},
              {"op":"set","at":[2,0,0],"block":"stone"}]}]}""").cells();
        assertEquals(2, cells.size());
        assertEquals(Turn.CLOCKWISE, cells.get(new Point(0, 0, 0)).turn());
        assertEquals("minecraft:stone", cells.get(new Point(0, 0, 2)).block());
    }

    @Test void aGroupMirrorsInPlaceOrMovesToAt() {
        var cells = build("""
            {"ops":[{"op":"group","mirror":"x","ops":[
              {"op":"set","at":[3,0,0],"block":"stone"},
              {"op":"set","at":[5,0,0],"block":"dirt"}]}]}""").cells();
        assertEquals("minecraft:dirt", cells.get(new Point(3, 0, 0)).block());
        assertEquals("minecraft:stone", cells.get(new Point(5, 0, 0)).block());
        assertTrue(cells.get(new Point(3, 0, 0)).turn().mirrored());
        var moved = BuildSpec.parse("{\"ops\":[{\"op\":\"group\",\"at\":[8,2,8],\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,0,1],\"block\":\"stone\"}]}]}");
        var placed = moved.place();
        assertEquals(new Point(8, 2, 8), placed.min());
        assertEquals(4, moved.build(placed).cells().size());
    }

    @Test void aRepeatedTurnedGroupFitsTheAreaItWorksOut() {
        var spec = BuildSpec.parse("""
            {"ops":[{"op":"group","rotate":90,"repeat":{"count":3,"step":[4,0,0]},"ops":[
              {"op":"box","from":[0,0,0],"to":[2,0,0],"block":"stone"}]}]}""");
        var result = spec.build(spec.place());
        assertEquals(9, result.cells().size());
        assertEquals(0, result.clipped());
    }

    @Test void groupsAreCheckedForDepthSelectionOpsAndTheOperationCount() {
        String deep = "{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"}";
        for (int n = 0; n < 4; n++) deep = "{\"op\":\"group\",\"ops\":[" + deep + "]}";
        var e = refused("{\"ops\":[" + deep + "]}");
        assertTrue(e.getMessage().contains("deep"), e.getMessage());
        e = refused("{\"ops\":[{\"op\":\"group\",\"ops\":[{\"op\":\"replace_selection\",\"block\":\"stone\"}]}]}");
        assertTrue(e.getMessage().contains("cannot be in a group"), e.getMessage());
        // Two groups of 200 are within the limit at the top level and over it counted through.
        var group = new StringBuilder("{\"op\":\"group\",\"ops\":[");
        for (int n = 0; n < 200; n++) group.append(n == 0 ? "" : ",").append("{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"stone\"}");
        group.append("]}");
        e = refused("{\"ops\":[" + group + "," + group + "]}");
        assertTrue(e.getMessage().contains("inside groups"), e.getMessage());
        e = refused("{\"ops\":[{\"op\":\"group\",\"ops\":[{\"op\":\"set\",\"at\":[0,0,0]}]}]}");
        assertTrue(e.getMessage().startsWith("ops[0] (group)"), e.getMessage());
    }
}
