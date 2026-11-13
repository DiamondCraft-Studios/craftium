package dev.craftium.server;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Copies, pastes, undoes and redoes a selection of 300,000 blocks with the player's block limit set to 100, which a paste must
 * not answer to, and checks every block each time, that the history holds the edit packed, and what it cost.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=history}.
 */
public final class HistoryGameTest {
    private static final int SX = 300, SZ = 300, TX = 420, TZ = 300, Y = 60, W = 100, H = 30, D = 100;

    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;

    private HistoryGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new HistoryGameTest(c, w).runAll(); }

    /** The block that stands at a place in the copied area: a mix of stone, glass and air, so there are runs and breaks. */
    private static BlockState pattern(int x, int y, int z) {
        int k = Math.floorMod(x * 3 + y * 5 + z * 7, 11);
        return (k < 4 ? Blocks.STONE : k < 6 ? Blocks.GLASS : Blocks.AIR).defaultBlockState();
    }

    private void runAll() {
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        w.getServer().runCommand("forceload add " + SX + " " + SZ + " " + (TX + W) + " " + (SZ + D));
        w.getServer().runCommand("tp @a " + (SX + W) + " 100 " + (SZ + D / 2) + " 180 0");
        c.waitTicks(80);
        w.getConnection().waitForChunksDownload();
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);
        w.getServer().runOnServer(server -> {
            var level = server.overworld();
            var pos = new BlockPos.MutableBlockPos();
            for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) {
                level.setBlock(pos.set(SX + x, Y + y, SZ + z), pattern(x, y, z), 2 | 16);
                level.setBlock(pos.set(TX + x, Y + y, TZ + z), Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        });
        c.runOnClient(mc -> {
            ClientState.options.confirmEdits = false;
            ClientState.options.largeSelectionWarnings = false;
            ClientState.options.blockLimit = 100;
            ClientState.options.position1 = new Point(SX, Y, SZ);
            ClientState.options.position2 = new Point(SX + W - 1, Y + H - 1, SZ + D - 1);
        });
        send("select_box", null);
        send("copy", null);

        send("paste_at", new Point(TX, Y, TZ));
        require(mismatches(true) == 0, "every block of the paste should be in place, with the block limit at 100");
        var edit = w.getServer().computeOnServer(server -> EditorServer.session(server.getPlayerList().getPlayers().get(0)).undo.peekFirst());
        require(edit != null && edit.changes() instanceof PackedChanges, "the paste should be in the history packed");
        long packed = edit.estimatedBytes(), loose = (long) edit.changes().size() * 128;
        System.out.println("CRAFTIUM_HISTORY " + edit.changes().size() + " changes cost " + packed / 1024 + " KiB packed, " + loose / 1024 + " KiB as objects");
        require(packed * 6 < loose, "the packed history should cost under a sixth of the objects");

        send("undo", null);
        require(mismatches(false) == 0, "undo should leave every block of the target as it was");
        send("redo", null);
        require(mismatches(true) == 0, "redo should put every block of the paste back");
        send("undo", null);
        require(mismatches(false) == 0, "a second undo should leave every block of the target as it was");
        require(mismatchesSource() == 0, "the copied area should be untouched");
        w.getServer().runCommand("forceload remove " + SX + " " + SZ + " " + (TX + W) + " " + (SZ + D));
        System.out.println("CRAFTIUM_HISTORY_PASS paste_over_block_limit undo redo undo packed");
    }

    private int mismatches(boolean pasted) {
        return w.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var pos = new BlockPos.MutableBlockPos();
            int bad = 0;
            for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) {
                var expected = pasted ? pattern(x, y, z) : Blocks.AIR.defaultBlockState();
                if (level.getBlockState(pos.set(TX + x, Y + y, TZ + z)) != expected) bad++;
            }
            return bad;
        });
    }

    private int mismatchesSource() {
        return w.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var pos = new BlockPos.MutableBlockPos();
            int bad = 0;
            for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) for (int z = 0; z < D; z++)
                if (level.getBlockState(pos.set(SX + x, Y + y, SZ + z)) != pattern(x, y, z)) bad++;
            return bad;
        });
    }

    private void send(String action, Point target) {
        try { Thread.sleep(120); } catch (InterruptedException e) { throw new AssertionError(e); }
        c.runOnClient(mc -> CraftiumClient.send(action, target, new Point(0, 1, 0)));
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(5);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 2000);
        w.getConnection().waitForClientboundPackets();
        require(c.computeOnClient(mc -> !ClientState.status.error()), action + " failed: " + c.computeOnClient(mc -> ClientState.status.message()));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
