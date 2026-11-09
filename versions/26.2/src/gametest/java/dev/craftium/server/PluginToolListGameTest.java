package dev.craftium.server;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.IconCatalog;
import dev.craftium.core.InteractionKind;
import dev.craftium.core.Point;
import dev.craftium.core.PropertySpec;
import dev.craftium.core.Tool;
import dev.craftium.core.ToolSpec;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.List;

/**
 * Checks that the tools plugins add reach a real client: in the first status it gets, as a push when the set of tools changes (with no request from the
 * client), and without a tool it cannot use costing it the others. A client that has the list holds it until the server says otherwise, and an empty
 * list means there are none.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=toollist}.
 */
public final class PluginToolListGameTest {
    private static final int PAUSE_MS = 170;

    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;

    private PluginToolListGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new PluginToolListGameTest(c, w).runAll(); }

    private static ToolSpec spec(String id, String label, InteractionKind kind, PropertySpec... properties) {
        return new ToolSpec(id, label, label.substring(0, 2), IconCatalog.tool(Tool.SHAPE), "Test plugins", "A tool for the test", Tool.BrushUse.NONE, kind, -1, "Build", false, false, false, true, false, false, List.of(properties));
    }

    private void runAll() {
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);
        ToolBehaviour nothing = use -> {};
        try {
            // The server has no plugin tools to start with, and the client knows none.
            require(ids().isEmpty(), "the client should know no plugin tools to start with, not " + ids());

            // 1. A tool registered before the client asks anything is in the first status it gets.
            PluginTools.register(spec("craftium_test:wall", "Wall", InteractionKind.POINTS, PropertySpec.integer("height", "Height", 1, 8, 3), PropertySpec.toggle("hollow", "Hollow", true)), nothing);
            send("status");
            require(ids().equals(List.of("craftium_test:wall")), "the first status should bring the tool: " + ids());
            var wall = c.computeOnClient(mc -> ClientState.pluginTools().get(0));
            require(wall.label().equals("Wall") && wall.interaction() == InteractionKind.POINTS && wall.section().equals("Test plugins"), "the client should have what the tool declared");
            require(wall.properties().size() == 2 && wall.properties().get(0).id().equals("height") && wall.properties().get(0).max() == 8, "the client should have the tool's settings");

            // 2. A tool registered later reaches the client with no request from it.
            PluginTools.register(spec("craftium_test:arch", "Arch", InteractionKind.BOX), nothing);
            c.waitFor(mc -> ClientState.pluginTools().size() == 2, 200);
            require(ids().equals(List.of("craftium_test:arch", "craftium_test:wall")), "the new tool should have been pushed: " + ids());

            // 3. Asking for a status does not lose the list: a status with none in it leaves it as it was.
            send("status");
            require(ids().size() == 2, "a status that does not bring the list should leave it alone: " + ids());

            // 4. A tool the client cannot use costs it only that tool: an interaction that is not for plugins is left out.
            PluginTools.register(spec("craftium_test:odd", "Odd", InteractionKind.TRANSFORM), nothing);
            PluginTools.register(spec("craftium_test:line", "Line", InteractionKind.LINE), nothing);
            c.waitFor(mc -> ClientState.pluginTools().size() == 3, 200);
            require(ids().equals(List.of("craftium_test:arch", "craftium_test:line", "craftium_test:wall")), "the tool that cannot be used should be left out and the rest kept: " + ids());

            // 5. Taking the tools back empties the list, which the client takes as none and not as unchanged.
            require(PluginTools.unregister("craftium_test") == 4, "all four tools should have been registered");
            c.waitFor(mc -> ClientState.pluginTools().isEmpty(), 200);
            require(ids().isEmpty(), "the client should be told there are none: " + ids());
        } finally {
            PluginTools.clear();
        }
        System.out.println("CRAFTIUM_TOOLLIST_PASS");
    }

    private List<String> ids() { return c.computeOnClient(mc -> ClientState.pluginTools().stream().map(ToolSpec::id).toList()); }

    private void send(String action) {
        try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) { throw new AssertionError(e); }
        c.runOnClient(mc -> CraftiumClient.send(action, new Point(0, 1, 0), new Point(0, 1, 0)));
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(5);
        w.getConnection().waitForClientboundPackets();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
