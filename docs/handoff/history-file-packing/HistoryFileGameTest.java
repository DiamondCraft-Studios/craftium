package dev.craftium.server;

import dev.craftium.client.ClientState;
import dev.craftium.client.CraftiumClient;
import dev.craftium.core.Point;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures, and checks nothing: the live heap while a paste of two million blocks is planned and applied, and then undone.
 * A sampler forces a collection every 150 ms so that what it reads is what is still referenced, not garbage waiting to be freed.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=historyfile}.
 */
public final class HistoryFileGameTest {
    private static final int SX = 300, SZ = 300, TX = 440, TZ = 300, Y = 60, W = 125, H = 125, D = 128;

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) {
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        w.getServer().runCommand("forceload add " + SX + " " + SZ + " " + (TX + W) + " " + (SZ + D));
        w.getServer().runCommand("tp @a " + (SX + W) + " 180 " + (SZ + D / 2) + " 180 0");
        c.waitTicks(100);
        w.getConnection().waitForChunksDownload();
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);
        w.getServer().runOnServer(server -> {
            var level = server.overworld();
            var pos = new BlockPos.MutableBlockPos();
            for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) {
                int k = Math.floorMod(x * 3 + y * 5 + z * 7, 3);
                level.setBlock(pos.set(SX + x, Y + y, SZ + z), (k == 0 ? Blocks.STONE : k == 1 ? Blocks.GLASS : Blocks.COBBLESTONE).defaultBlockState(), 2 | 16);
            }
        });
        c.runOnClient(mc -> {
            ClientState.options.confirmEdits = false;
            ClientState.options.largeSelectionWarnings = false;
            ClientState.options.position1 = new Point(SX, Y, SZ);
            ClientState.options.position2 = new Point(SX + W - 1, Y + H - 1, SZ + D - 1);
        });
        var rt = Runtime.getRuntime();
        System.out.println("CRAFTIUM_MEM max heap " + rt.maxMemory() / (1 << 20) + " MiB, " + (long) W * H * D + " blocks");
        send(c, w, "select_box", null);
        send(c, w, "copy", null);
        System.out.println("CRAFTIUM_MEM after copy (selection and clipboard held): " + live() + " MiB");
        send(c, w, "paste_at", new Point(TX, Y, TZ));
        var holder = new Object[2];
        w.getServer().runOnServer(server -> { var player = server.getPlayerList().getPlayers().get(0); holder[0] = player; holder[1] = EditorServer.session(player); });
        var player = (net.minecraft.server.level.ServerPlayer) holder[0];
        var session = (Session) holder[1];
        int edits = session.undo.size();
        var original = session.undo.peekFirst().changes();
        int changes = original.size();
        System.out.println("CRAFTIUM_FILE history before: " + edits + " edits, newest has " + changes + " changes, heap " + live() + " MiB");
        long t0 = System.nanoTime();
        String saved;
        try {
            w.getServer().runOnServer(server -> { session.historyDirty = true; HistoryStore.save(session); });
            HistoryStore.flush();
            long size = java.nio.file.Files.exists(session.historyPath) ? java.nio.file.Files.size(session.historyPath) : -1;
            saved = "saved in " + (System.nanoTime() - t0) / 1_000_000 + " ms, file " + size / 1024 + " KiB";
        } catch (Throwable e) { saved = "SAVE FAILED: " + e; }
        System.out.println("CRAFTIUM_FILE " + saved + ", heap " + live() + " MiB");
        long t1 = System.nanoTime();
        w.getServer().runOnServer(server -> { session.undo.clear(); session.redo.clear(); session.recalculateBytes(); HistoryStore.load(player, session); });
        int loaded = w.getServer().computeOnServer(server -> session.undo.isEmpty() ? 0 : session.undo.peekFirst().changes().size());
        boolean same = w.getServer().computeOnServer(server -> session.undo.peekFirst().changes().equals(original));
        System.out.println("CRAFTIUM_FILE every change equal: " + same);
        if (!same) throw new AssertionError("the loaded history should hold exactly the changes that were saved");
        System.out.println("CRAFTIUM_FILE loaded in " + (System.nanoTime() - t1) / 1_000_000 + " ms: " + loaded + " changes of " + changes + (loaded == changes ? " (whole)" : " (NOT whole)") + ", heap " + live() + " MiB");
        System.out.println("CRAFTIUM_FILE_DONE");
        w.getServer().runCommand("forceload remove " + SX + " " + SZ + " " + (TX + W) + " " + (SZ + D));
        System.out.println("CRAFTIUM_MEM_DONE");
    }

    private static long live() {
        System.gc();
        var rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    private static void measure(ClientGameTestContext c, TestSingleplayerContext w, String label, String action, Point target) {
        long base = live();
        var peak = new long[]{base};
        var samples = new ArrayList<String>();
        var running = new boolean[]{true};
        long start = System.nanoTime();
        var sampler = new Thread(() -> {
            while (running[0]) {
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                long now = live();
                peak[0] = Math.max(peak[0], now);
                samples.add((System.nanoTime() - start) / 1_000_000 + "ms:" + now);
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        send(c, w, action, target);
        running[0] = false;
        try { sampler.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
        long settled = live();
        System.out.println("CRAFTIUM_MEM " + label + ": before " + base + " MiB, peak " + peak[0] + " MiB, after " + settled + " MiB, " + (System.nanoTime() - start) / 1_000_000 + " ms, samples " + samples);
    }

    private static void send(ClientGameTestContext c, TestSingleplayerContext w, String action, Point target) {
        try { Thread.sleep(120); } catch (InterruptedException e) { throw new AssertionError(e); }
        c.runOnClient(mc -> CraftiumClient.send(action, target, new Point(0, 1, 0)));
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(5);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 6000);
        w.getConnection().waitForClientboundPackets();
        if (c.computeOnClient(mc -> ClientState.status.error())) throw new AssertionError(action + " failed: " + c.computeOnClient(mc -> ClientState.status.message()));
    }
}
