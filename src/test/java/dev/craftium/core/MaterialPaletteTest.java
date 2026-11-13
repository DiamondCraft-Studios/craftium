package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaterialPaletteTest {
    private static Options options(String materials,Double... weights){var o=new Options();o.materials=materials;o.materialWeights=new ArrayList<>(List.of(weights));return o;}

    @Test void addKeepsWeightsInStep() {
        var o=options("minecraft:stone",3d);
        assertTrue(MaterialPalette.add(o,"minecraft:dirt"));
        assertEquals("minecraft:stone,minecraft:dirt",o.materials);assertEquals(List.of(3d,1d),o.materialWeights);
    }
    @Test void shortWeightListCountsAsOnes() {
        var o=options("a,b,c",2d);
        assertEquals(.5,MaterialPalette.share(o,0),1e-9);assertEquals(.25,MaterialPalette.share(o,2),1e-9);
    }
    @Test void removeNeverEmptiesThePalette() {
        var o=options("a,b",1d,5d);
        assertTrue(MaterialPalette.remove(o,0));assertEquals("b",o.materials);assertEquals(List.of(5d),o.materialWeights);
        assertFalse(MaterialPalette.remove(o,0));assertEquals("b",o.materials);
    }
    @Test void moveCarriesTheWeight() {
        var o=options("a,b,c",1d,2d,3d);
        assertTrue(MaterialPalette.move(o,2,-1));assertEquals("a,c,b",o.materials);assertEquals(List.of(1d,3d,2d),o.materialWeights);
        assertFalse(MaterialPalette.move(o,0,-1));assertFalse(MaterialPalette.move(o,2,1));
    }
    @Test void weightIsClamped() {
        var o=options("a,b");
        MaterialPalette.setWeight(o,1,0);assertEquals(MaterialPalette.MIN_WEIGHT,o.materialWeights.get(1));
        MaterialPalette.setWeight(o,0,1e9);assertEquals(MaterialPalette.MAX_WEIGHT,o.materialWeights.get(0));
    }
    @Test void blockStatesWithCommasStayOneBlock() {
        var o=options("minecraft:oak_stairs[facing=north,half=top],minecraft:dirt",1d,2d);
        assertEquals(2,MaterialPalette.blocks(o).size());
        assertTrue(MaterialPalette.move(o,1,-1));assertEquals("minecraft:dirt,minecraft:oak_stairs[facing=north,half=top]",o.materials);
    }
    @Test void reorderFollowsTheGivenOrder() {
        var o=options("a,b,c",1d,2d,3d);
        MaterialPalette.reorder(o,List.of(2,0,1));assertEquals("c,a,b",o.materials);assertEquals(List.of(3d,1d,2d),o.materialWeights);
    }
}
