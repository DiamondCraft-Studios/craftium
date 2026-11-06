package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlockQueryTest {
    private record Block(String id, String name, String mod, String modName, String tooltip, List<String> tags, List<String> tabs, String colour) implements BlockQuery.Info {}
    private static final Block STONE = new Block("minecraft:stone", "Stone", "minecraft", "Minecraft", "Stone", List.of("minecraft:base_stone_overworld", "minecraft:mineable/pickaxe"), List.of("Building Blocks", "Natural Blocks"), "gray grey");
    private static final Block OAK = new Block("minecraft:oak_planks", "Oak Planks", "minecraft", "Minecraft", "Oak Planks", List.of("minecraft:planks"), List.of("Building Blocks"), "brown");
    private static final Block CASING = new Block("create:andesite_casing", "Andesite Casing", "create", "Create", "Andesite Casing\nA machine part", List.of(), List.of("Create"), "gray grey");

    private static boolean hit(String query, Block b) { return BlockQuery.parse(query).matches(b); }

    @Test void plainWordsMatchTheNameOrTheIdAndAllMustMatch() {
        assertTrue(hit("oak", OAK));
        assertTrue(hit("planks oak", OAK));
        assertTrue(hit("minecraft:oak", OAK));
        assertFalse(hit("oak stone", OAK));
        assertTrue(hit("", OAK));
        assertTrue(hit("  STONE ", STONE));
    }

    @Test void aWildcardStandsForARunOrOneCharacter() {
        assertTrue(hit("o*planks", OAK));
        assertTrue(hit("st?ne", STONE));
        assertFalse(hit("st?ne", OAK));
        assertTrue(hit("*", STONE));
        assertFalse(hit("a.b", new Block("x:axb", "Axb", "x", "X", "", List.of(), List.of(), ""))); // a dot means a dot
    }

    @Test void prefixesLookInTheirOwnPlace() {
        assertTrue(hit("@create", CASING));
        assertFalse(hit("@create", STONE));
        assertTrue(hit("@minec*", STONE));
        assertTrue(hit("#machine", CASING));
        assertFalse(hit("#machine", STONE));
        assertTrue(hit("$planks", OAK));
        assertTrue(hit("$mineable/pick", STONE));
        assertTrue(hit("%natural", STONE));
        assertFalse(hit("%natural", OAK));
        assertTrue(hit("&minecraft:stone", STONE));
        assertTrue(hit("^brown", OAK));
        assertTrue(hit("^grey", STONE));
    }

    @Test void aBarePrefixDoesNotEmptyTheList() {
        assertTrue(hit("@", STONE));
        assertTrue(hit("#", STONE));
        assertTrue(hit("$", STONE));
    }

    @Test void aBarMeansAnyOfThem() {
        assertTrue(hit("oak|stone", OAK));
        assertTrue(hit("oak|stone", STONE));
        assertFalse(hit("oak|stone", CASING));
        assertTrue(hit("@create|oak", CASING));
        assertTrue(hit("@create|oak", OAK));
    }

    @Test void aLeadingDashTurnsAWordAround() {
        assertTrue(hit("-oak", STONE));
        assertFalse(hit("-oak", OAK));
        assertTrue(hit("@minecraft -oak", STONE));
        assertFalse(hit("@minecraft -oak", OAK));
        assertTrue(hit("-@create", STONE));
        assertFalse(hit("-oak|stone", STONE)); // not either of them
    }

    @Test void quotesKeepSpacesInAWord() {
        assertTrue(hit("\"oak planks\"", OAK));
        assertFalse(hit("\"planks oak\"", OAK));
        assertTrue(hit("#\"machine part\"", CASING));
    }

    @Test void anOpenQuoteIsFlaggedButStillSearched() {
        var q = BlockQuery.parse("\"oak pl");
        assertFalse(q.valid());
        assertTrue(q.matches(OAK));
        assertTrue(BlockQuery.parse("oak").valid());
    }

    @Test void itKnowsWhichPrefixesASearchUses() {
        assertTrue(BlockQuery.parse("oak #wood").uses('#'));
        assertFalse(BlockQuery.parse("oak @mc").uses('#'));
        assertFalse(BlockQuery.parse("#").uses('#'));
    }

    @Test void coloursGetNames() {
        assertEquals("black", BlockQuery.colourName(0x101010));
        assertEquals("white", BlockQuery.colourName(0xf0f0f0));
        assertTrue(BlockQuery.colourName(0x808080).contains("gray"));
        assertEquals("red", BlockQuery.colourName(0xcc2020));
        assertEquals("green", BlockQuery.colourName(0x30aa30));
        assertEquals("blue", BlockQuery.colourName(0x3050dd));
        assertEquals("yellow", BlockQuery.colourName(0xdddd30));
        assertEquals("brown", BlockQuery.colourName(0x8a5a2a));
    }
}
