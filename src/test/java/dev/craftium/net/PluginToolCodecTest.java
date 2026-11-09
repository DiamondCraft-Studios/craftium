package dev.craftium.net;

import dev.craftium.core.IconCatalog;
import dev.craftium.core.InteractionKind;
import dev.craftium.core.PropertySpec;
import dev.craftium.core.Tool;
import dev.craftium.core.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginToolCodecTest {
    private static ToolSpec spec(String id, InteractionKind kind, PropertySpec... properties) {
        return new ToolSpec(id, "Wall", "WL", IconCatalog.tool(Tool.SHAPE), "Walls", "Builds a wall", Tool.BrushUse.NONE, kind, 'W', "Build a wall", true, true, true, true, false, false, List.of(properties));
    }

    private static Packets.PluginTool wire(String id) { return PluginToolCodec.toWire(spec(id, InteractionKind.CLICK)); }

    private static Packets.PluginTool with(Packets.PluginTool t, String interaction, List<Packets.PluginProperty> properties) {
        return new Packets.PluginTool(t.id(), t.label(), t.icon(), t.iconId(), t.section(), t.description(), t.useHint(), interaction, t.confirms(), properties);
    }

    @Test void aToolSurvivesTheWireWithWhatAClientNeeds() {
        var original = spec("wall:build", InteractionKind.POINTS, PropertySpec.integer("height", "Height", 1, 8, 3), PropertySpec.toggle("hollow", "Hollow", true),
            PropertySpec.choice("axis", "Axis", List.of("X", "Y", "Z"), 2), PropertySpec.number("scale", "Scale", 0.5, 4, 1.5));
        var back = PluginToolCodec.fromWire(PluginToolCodec.toWire(original)).orElseThrow();
        assertEquals("wall:build", back.id());
        assertEquals("Wall", back.label());
        assertEquals("WL", back.icon());
        assertEquals(IconCatalog.tool(Tool.SHAPE), back.iconId());
        assertEquals("Walls", back.section());
        assertEquals("Builds a wall", back.description());
        assertEquals("Build a wall", back.useHint());
        assertEquals(InteractionKind.POINTS, back.interaction());
        assertTrue(back.confirms());
        assertEquals(original.properties(), back.properties());
    }

    @Test void aToolThatIsNotTheEditorsOwnHasNoBrushOrSelectionOfItsOwn() {
        var back = PluginToolCodec.fromWire(wire("wall:build")).orElseThrow();
        assertEquals(Tool.BrushUse.NONE, back.brushUse());
        assertFalse(back.selection());
        assertFalse(back.transform());
        assertEquals(-1, back.defaultKey(), "a plugin cannot take a key by naming it");
        assertTrue(back.placesBlocks());
    }

    @Test void theToolsOfTheEditorsOwnNamespaceAreNotTaken() {
        assertTrue(PluginToolCodec.fromWire(wire("craftium:painter")).isEmpty());
    }

    @Test void anIdThatIsNotPluginIdColonToolIsNotTaken() {
        for (String bad : new String[]{"", "wall", "Wall:build", "wall:Build", "wall:", ":build", "wall:build:again", "1wall:build"})
            assertTrue(PluginToolCodec.fromWire(wire(bad)).isEmpty(), bad);
        var noId = wire("wall:build");
        assertTrue(PluginToolCodec.fromWire(new Packets.PluginTool(null, noId.label(), noId.icon(), noId.iconId(), noId.section(), noId.description(), noId.useHint(), "CLICK", false, List.of())).isEmpty());
        assertTrue(PluginToolCodec.fromWire((Packets.PluginTool) null).isEmpty());
    }

    @Test void aToolWithoutALabelIsNotTaken() {
        var t = wire("wall:build");
        assertTrue(PluginToolCodec.fromWire(new Packets.PluginTool(t.id(), " ", t.icon(), t.iconId(), t.section(), t.description(), t.useHint(), "CLICK", false, List.of())).isEmpty());
        assertTrue(PluginToolCodec.fromWire(new Packets.PluginTool(t.id(), null, t.icon(), t.iconId(), t.section(), t.description(), t.useHint(), "CLICK", false, List.of())).isEmpty());
    }

    @Test void onlyTheInteractionsForPluginsAreTaken() {
        for (InteractionKind kind : InteractionKind.values())
            assertEquals(kind.forPlugins, PluginToolCodec.fromWire(with(wire("wall:build"), kind.name(), List.of())).isPresent(), kind.name());
    }

    @Test void anInteractionThisBuildDoesNotKnowIsLeftOutNotAnError() {
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "FROM_THE_FUTURE", List.of())).isEmpty());
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), null, List.of())).isEmpty());
    }

    @Test void aSettingThatIsNotValidLeavesTheToolOutAndSoDoesARepeatedOne() {
        var ok = new Packets.PluginProperty("height", "Height", "INTEGER", 1, 8, 3, List.of());
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", List.of(ok))).isPresent());
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", List.of(new Packets.PluginProperty("height", "Height", "INTEGER", 8, 1, 3, List.of())))).isEmpty(), "a range backwards");
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", List.of(new Packets.PluginProperty("height", "Height", "FROM_THE_FUTURE", 1, 8, 3, List.of())))).isEmpty(), "a kind not known");
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", List.of(new Packets.PluginProperty("Height", "Height", "INTEGER", 1, 8, 3, List.of())))).isEmpty(), "an id not in the form");
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", List.of(ok, ok))).isEmpty(), "the same setting twice");
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", java.util.Arrays.asList(ok, null))).isEmpty(), "a setting that is nothing");
    }

    @Test void aToolWithTooManySettingsIsNotTaken() {
        var many = new ArrayList<Packets.PluginProperty>();
        for (int i = 0; i < PluginToolCodec.MAX_PROPERTIES; i++) many.add(new Packets.PluginProperty("s" + i, "S", "TOGGLE", 0, 1, 0, List.of()));
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", many)).isPresent());
        many.add(new Packets.PluginProperty("one_more", "S", "TOGGLE", 0, 1, 0, List.of()));
        assertTrue(PluginToolCodec.fromWire(with(wire("wall:build"), "CLICK", many)).isEmpty());
    }

    @Test void anIconTheClientDoesNotHaveIsReplacedByAPlainOneAndAMissingSectionAndIconTextAreFilledIn() {
        var t = wire("wall:build");
        var odd = new Packets.PluginTool(t.id(), "Brick wall", null, "mod:not/an_icon", " ", null, null, "CLICK", false, null);
        var back = PluginToolCodec.fromWire(odd).orElseThrow();
        assertTrue(IconCatalog.exists(back.iconId()), "the fallback is an icon the client has");
        assertNotEquals("mod:not/an_icon", back.iconId());
        assertEquals("Br", back.icon(), "the first two letters of the label stand in");
        assertEquals(PluginToolCodec.DEFAULT_SECTION, back.section());
        assertEquals("", back.description());
        assertEquals("", back.useHint());
        assertTrue(back.properties().isEmpty());
    }

    @Test void aBlockIconIsTaken() {
        var t = wire("wall:build");
        var withBlock = new Packets.PluginTool(t.id(), t.label(), t.icon(), IconCatalog.block("minecraft:stone"), t.section(), t.description(), t.useHint(), "CLICK", false, List.of());
        assertEquals(IconCatalog.block("minecraft:stone"), PluginToolCodec.fromWire(withBlock).orElseThrow().iconId());
    }

    @Test void oneBadToolDoesNotCostThePlayerTheRestAndARepeatedIdIsTakenOnce() {
        var list = new ArrayList<Packets.PluginTool>(List.of(wire("a:one"), wire("craftium:painter"), with(wire("b:two"), "TRANSFORM", List.of()), wire("c:three"), wire("a:one")));
        var tools = PluginToolCodec.fromWire(list);
        assertEquals(List.of("a:one", "c:three"), tools.stream().map(ToolSpec::id).toList());
    }

    @Test void aListIsCutAtTheMostAClientTakesAndNoListIsNoTools() {
        var list = new ArrayList<Packets.PluginTool>();
        for (int i = 0; i < PluginToolCodec.MAX_TOOLS + 10; i++) list.add(wire("p:t" + i));
        assertEquals(PluginToolCodec.MAX_TOOLS, PluginToolCodec.fromWire(list).size());
        assertTrue(PluginToolCodec.fromWire((List<Packets.PluginTool>) null).isEmpty());
        assertTrue(PluginToolCodec.fromWire(List.<Packets.PluginTool>of()).isEmpty());
    }

    @Test void theListTravelsInAStatusOfItsOwnThatLeavesTheGhostAlone() {
        var s = Packets.toolsOnly(List.of(wire("wall:build")));
        assertTrue(s.background(), "it is not a reply to anything the player asked");
        assertEquals(1, s.pluginTools().size());
        assertNull(s.pluginPreviewData(), "the ghost is left as it was");
        assertEquals("", s.message());
        var back = Packets.JSON.fromJson(Packets.JSON.toJson(s), Packets.Status.class);
        assertEquals("wall:build", back.pluginTools().get(0).id());
        assertNull(back.pluginPreviewData());
    }

    @Test void theOtherBackgroundStatusesLeaveTheListAloneAndAnEmptyListMeansNone() {
        assertNull(Packets.pluginOnly("ghost").pluginTools(), "null means unchanged");
        assertNull(Packets.sharedOnly(List.of()).pluginTools());
        assertTrue(Packets.toolsOnly(List.of()).pluginTools().isEmpty());
        var old = Packets.JSON.fromJson("{\"message\":\"Ready\",\"error\":false,\"busy\":false,\"selectionSize\":0,\"undo\":0,\"redo\":0}", Packets.Status.class);
        assertNull(old.pluginTools(), "a server that knows no plugins sends none");
    }
}
