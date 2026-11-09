package dev.craftium.server;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;
import dev.craftium.test.GoldenToolGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends real requests for namespaced actions to the integrated server and checks what the registry and the gate do with them: an action that is
 * registered plans a block and the block appears; one that plans more than the player's block limit is refused and nothing is built; a namespaced
 * name nobody registered is an error and is never taken for one of the editor's own; a namespace that is taken back answers no more.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=actions}.
 */
public final class ActionGameTest {
    private static final int PAUSE_MS = 170;

    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;

    private ActionGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new ActionGameTest(c, w).runAll(); }

    private void runAll() {
        Point ground = GoldenToolGameTest.surface(0, 0), above = new Point(ground.x(), ground.y() + 1, ground.z());
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        GoldenToolGameTest.reset(w, null);
        w.getConnection().waitForClientboundPackets();
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);

        ActionRegistry.register("craftium_test:place", use -> {
            var plan = use.plan();
            plan.put(use.target().add(0, 1, 0), plan.material);
            use.submit(plan.finish("Test place"));
        });
        // A plan that does not come from the planner, which would stop it at the limit itself: only the gate can.
        ActionRegistry.register("craftium_test:too_big", use -> {
            var changes = new ArrayList<Session.Change>();
            for (int i = 0; i < 4; i++)
                changes.add(new Session.Change(use.target().add(i + 1, 1, 0), new BlockSnapshot(Blocks.AIR.defaultBlockState()), new BlockSnapshot(Blocks.GOLD_BLOCK.defaultBlockState())));
            use.submit(new Session.Edit("Test too big", changes, List.of(), 0));
        });
        try {
            c.runOnClient(mc -> { ClientState.options.block = "minecraft:andesite"; ClientState.options.confirmEdits = false; ClientState.options.blockLimit = 3; });
            require(isAir(above), "the block above the ground should be empty to start with");

            // 1. A registered action plans a block and the block is built.
            var reply = send("craftium_test:place", ground);
            require(!reply.error, "a registered action should not be refused: " + reply.message);
            c.waitFor(mc -> !ClientState.status.busy(), 200);
            require(is(above, Blocks.ANDESITE), "the registered action's block should have been built");

            // 2. A plan past the block limit is refused by the gate, with the reason, and nothing is built.
            reply = send("craftium_test:too_big", ground);
            require(reply.error && reply.message.equals("The plan changes 4 blocks, past the block limit of 3"), "an over-limit plan should be refused by the gate: " + reply.message);
            // The terrain is not flat, so the blocks beside the target are not necessarily air; the plan used gold, which it does not contain.
            for (int i = 1; i <= 4; i++) require(!is(new Point(above.x() + i, above.y(), above.z()), Blocks.GOLD_BLOCK), "a refused plan should have built nothing");

            // 3. A namespaced name nobody registered is an error of its own.
            reply = send("craftium_test:missing", ground);
            require(reply.error && reply.message.equals("Unknown action craftium_test:missing"), "an unregistered namespaced action should be unknown: " + reply.message);

            // 4. Taking the namespace back leaves its actions unknown.
            require(ActionRegistry.unregister("craftium_test") == 2, "both actions should have been registered");
            reply = send("craftium_test:place", ground);
            require(reply.error && reply.message.equals("Unknown action craftium_test:place"), "an action that was taken back should be unknown: " + reply.message);

            // 5. The editor's own names stay the editor's.
            try { ActionRegistry.register("use", use -> {}); throw new AssertionError("an action of the editor's must not be registrable"); }
            catch (IllegalArgumentException expected) { /* refused */ }
        } finally {
            ActionRegistry.clear();
        }
        System.out.println("CRAFTIUM_ACTIONS_PASS");
    }

    private record Reply(String message, boolean error) {}

    /** Sends the action for the block and returns the status line the player is shown for it. */
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

    private boolean isAir(Point at) { return is(at, Blocks.AIR); }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
