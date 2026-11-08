package dev.craftium.server;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.IconCatalog;
import dev.craftium.core.InteractionKind;
import dev.craftium.core.Point;
import dev.craftium.core.PropertySpec;
import dev.craftium.core.Tool;
import dev.craftium.core.ToolSpec;
import dev.craftium.test.GoldenToolGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Drives a plugin's tool through a real client and the integrated server: the request names the tool and carries its settings; the server finds the
 * tool, holds the settings to what it declared, and the tool plans. Checked here: the ghost a tool shows (it arrives without touching the status line
 * and goes when the tool shows nothing), the click that builds what the ghost showed, a setting past its range held to it, a tool nobody registered, and
 * a plan from outside the planner held to the block limit, for the click and for the ghost.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=plugintools}.
 */
public final class PluginToolGameTest {
    private static final int PAUSE_MS = 170;

    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;

    private PluginToolGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new PluginToolGameTest(c, w).runAll(); }

    private static ToolSpec spec(String id, PropertySpec... properties) {
        return new ToolSpec(id, "Test tool", "TT", IconCatalog.tool(Tool.SHAPE), "Plugins", "A tool for the test", Tool.BrushUse.NONE, InteractionKind.CLICK, -1, "Build", false, false, false, true, false, false, List.of(properties));
    }

    private void runAll() {
        Point ground = GoldenToolGameTest.surface(0, 0), above = new Point(ground.x(), ground.y() + 1, ground.z());
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        GoldenToolGameTest.reset(w, null);
        w.getConnection().waitForClientboundPackets();
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);

        // A column of gold as high as the "height" setting, from the block above the one that was clicked. Gold, because the terrain has none.
        var column = new ToolBehaviour() {
            private Session.Edit plan(ToolUse use) {
                var plan = use.plan();
                for (int i = 0; i < use.settings().integer("height"); i++) plan.put(use.point().add(0, 1 + i, 0), Blocks.GOLD_BLOCK.defaultBlockState());
                return plan.finish("Test column");
            }
            @Override public void use(ToolUse use) { use.submit(plan(use)); }
            @Override public Session.Edit preview(ToolUse use) { return plan(use); }
        };
        PluginTools.register(spec("craftium_test:column", PropertySpec.integer("height", "Height", 1, 5, 2)), column);
        // Plans that do not come from the planner, which would stop them at the limit itself: only the gate can. Four blocks against a limit of three.
        var oversized = new ToolBehaviour() {
            private Session.Edit plan(ToolUse use) {
                var changes = new ArrayList<Session.Change>();
                for (int i = 0; i < 4; i++)
                    changes.add(new Session.Change(use.point().add(i + 1, 1, 0), new BlockSnapshot(Blocks.AIR.defaultBlockState()), new BlockSnapshot(Blocks.GOLD_BLOCK.defaultBlockState())));
                return new Session.Edit("Test oversized", changes, List.of(), 0);
            }
            @Override public void use(ToolUse use) { use.submit(plan(use)); }
            @Override public Session.Edit preview(ToolUse use) { return plan(use); }
        };
        PluginTools.register(spec("craftium_test:oversized"), oversized);
        try {
            c.runOnClient(mc -> { ClientState.options.confirmEdits = false; ClientState.options.blockLimit = 100; });

            // 1. The ghost: asking for a preview brings it to the client, and the status line is left as it was.
            c.runOnClient(mc -> select("craftium_test:column", 3));
            String lineBefore = c.computeOnClient(mc -> ClientState.status.message());
            send("tool_preview", ground);
            require(c.computeOnClient(mc -> ClientState.pluginPreviewBlocks()) == 3, "the ghost should be the three blocks of the column");
            require(c.computeOnClient(mc -> ClientState.status.message()).equals(lineBefore), "a preview should not change the status line");
            require(!is(above, Blocks.GOLD_BLOCK), "a ghost must not build anything");

            // 2. A setting past its range is held to it: 99 asked, 5 declared as the most.
            c.runOnClient(mc -> select("craftium_test:column", 99));
            send("tool_preview", ground);
            require(c.computeOnClient(mc -> ClientState.pluginPreviewBlocks()) == 5, "a height of 99 should be held to the declared 5");

            // 3. The click builds what the ghost showed, and the ghost has had its say.
            var reply = send("use", ground);
            require(!reply.error, "the tool's click should not be refused: " + reply.message);
            c.waitFor(mc -> !ClientState.status.busy(), 200);
            for (int i = 0; i < 5; i++) require(is(new Point(above.x(), above.y() + i, above.z()), Blocks.GOLD_BLOCK), "the column should have been built, block " + i);
            require(!is(new Point(above.x(), above.y() + 5, above.z()), Blocks.GOLD_BLOCK), "the column should stop at the declared 5");
            require(c.computeOnClient(mc -> ClientState.pluginPreviewBlocks()) == 0, "the ghost should be gone once the click has built it");

            // 4. A tool nobody registered is an error of its own.
            c.runOnClient(mc -> select("craftium_test:missing", 1));
            reply = send("use", ground);
            require(reply.error && reply.message.equals("Unknown tool craftium_test:missing"), "an unregistered tool should be unknown: " + reply.message);

            // 5. A plan past the block limit is refused by the gate and builds nothing.
            c.runOnClient(mc -> { ClientState.options.blockLimit = 3; select("craftium_test:oversized", 0); });
            reply = send("use", ground);
            require(reply.error && reply.message.equals("The plan changes 4 blocks, past the block limit of 3"), "an over-limit plan should be refused by the gate: " + reply.message);
            for (int i = 1; i <= 4; i++) require(!is(new Point(above.x() + i, above.y(), above.z()), Blocks.GOLD_BLOCK), "a refused plan should have built nothing");

            // 6. A ghost past the block limit is not shown, and is no error either.
            // The status line still says what step 5 was refused for: a ghost answers on its own and neither adds an error nor takes that one away.
            String lineBeforeGhost = c.computeOnClient(mc -> ClientState.status.message());
            send("tool_preview", ground);
            require(c.computeOnClient(mc -> ClientState.pluginPreviewBlocks()) == 0, "an over-limit ghost should not be shown");
            require(c.computeOnClient(mc -> ClientState.status.message()).equals(lineBeforeGhost), "an over-limit ghost should say nothing");

            // 7. Choosing one of the editor's own tools takes the ghost away.
            // Somewhere else: the first column is built where the ground was clicked, and a plan for blocks that are already there changes nothing, so shows nothing.
            Point elsewhere = GoldenToolGameTest.surface(5, 5);
            c.runOnClient(mc -> select("craftium_test:column", 2));
            send("tool_preview", elsewhere);
            int back = c.computeOnClient(mc -> ClientState.pluginPreviewBlocks());
            require(back == 2, "the column's ghost should be back, with 2 blocks, but it has " + back);
            c.runOnClient(mc -> { ClientState.options.pluginTool = null; ClientState.options.toolSettings = new HashMap<>(); });
            send("tool_preview", elsewhere);
            require(c.computeOnClient(mc -> ClientState.pluginPreviewBlocks()) == 0, "a request with no plugin tool should clear the ghost");

            // 8. Taking the namespace back leaves its tools unknown.
            require(PluginTools.unregister("craftium_test") == 2, "both tools should have been registered");
            c.runOnClient(mc -> select("craftium_test:column", 2));
            reply = send("use", elsewhere);
            require(reply.error && reply.message.equals("Unknown tool craftium_test:column"), "a tool that was taken back should be unknown: " + reply.message);
        } finally {
            PluginTools.clear();
            c.runOnClient(mc -> { ClientState.options.pluginTool = null; ClientState.options.toolSettings = new HashMap<>(); });
        }
        System.out.println("CRAFTIUM_PLUGINTOOLS_PASS");
    }

    private static void select(String tool, int height) {
        ClientState.options.pluginTool = tool;
        ClientState.options.toolSettings = new HashMap<>();
        ClientState.options.toolSettings.put("height", (double) height);
    }

    private record Reply(String message, boolean error) {}

    /** Sends the action for the block and returns the status line the player is shown after it. */
    private Reply send(String action, Point target) {
        try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) { throw new AssertionError(e); }
        c.runOnClient(mc -> CraftiumClient.send(action, target, new Point(0, 1, 0)));
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(5);
        w.getConnection().waitForClientboundPackets();
        return c.computeOnClient(mc -> new Reply(ClientState.status.message(), ClientState.status.error()));
    }

    private boolean is(Point at, Block block) {
        return w.getServer().computeOnServer(server -> server.overworld().getBlockState(new BlockPos(at.x(), at.y(), at.z())).is(block));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
