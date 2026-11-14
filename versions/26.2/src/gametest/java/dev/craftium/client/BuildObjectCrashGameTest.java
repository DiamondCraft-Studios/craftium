package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What is on disk for a BuildObject while an edit that moved it is still waiting for Enter, which is what a crash would leave: a house with its lower layer buried in the ground is
 * selected and moved up without confirming, and the saved file has to be the house as it still is in the world, with the buried blocks still kept hidden. Then the move is confirmed,
 * and the file has to be the house as it is now.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=buildobjectcrash}.
 */
public final class BuildObjectCrashGameTest {
    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;
    private int baseX, baseY, baseZ;

    private BuildObjectCrashGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new BuildObjectCrashGameTest(c, w).all(); }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private void all() {
        c.runOnClient(mc -> { mc.getWindow().setWindowed(1200, 800); mc.options.guiScale().set(1); mc.resizeGui(); mc.player.getAbilities().flying = true; });
        c.waitFor(mc -> mc.player.onGround() || mc.player.getAbilities().flying, 600);
        c.runOnClient(mc -> {
            ClientState.options = new Options();
            ClientState.options.confirmEdits = false;
            CraftiumClient.resetEditor();
            WorkspaceLayout.reset();
            mc.player.setYRot(0f); mc.player.setXRot(20f); mc.player.setOldPosAndRot();
            CraftiumClient.open(false);
        });
        c.waitTicks(10);
        var base = c.computeOnClient(mc -> mc.player.blockPosition().offset(-4, 0, 7));
        baseX = base.getX(); baseY = base.getY(); baseZ = base.getZ();

        // ground two blocks thick, and a house of 3 x 3 x 3 planks high above it, in the air
        cmd("fill " + (baseX - 1) + " " + baseY + " " + (baseZ - 1) + " " + (baseX + 5) + " " + (baseY + 1) + " " + (baseZ + 5) + " stone");
        cmd("fill " + baseX + " " + (baseY + 6) + " " + baseZ + " " + (baseX + 2) + " " + (baseY + 8) + " " + (baseZ + 2) + " oak_planks");
        c.waitTicks(10);
        var house = new ArrayList<Point>();
        for (int x = 0; x < 3; x++) for (int y = 6; y < 9; y++) for (int z = 0; z < 3; z++) house.add(new Point(baseX + x, baseY + y, baseZ + z));
        select(house);
        action("saved_selection:save");
        int id = saved().id();
        require(saved().size() == 27, "the house is a BuildObject of 27 blocks, got " + saved().size());

        // down into the ground, with the lower layer inside the stone: 18 blocks are placed and 9 are kept hidden
        move(-5);
        require(saved().size() == 18 && saved().hidden() == 9, "after being put down into the ground the house holds 18 blocks in the world and keeps 9 hidden, got " + saved().size() + " and " + saved().hidden());
        System.out.println("CRASHTEST buried: disk " + disk());

        // select it and move it up out of the ground, without pressing Enter
        stageUp();
        System.out.println("CRASHTEST staged: the preview says " + saved().size() + " blocks and " + saved().hidden() + " hidden");

        // longer than the five seconds between two writes of the BuildObjects
        c.waitTicks(180);
        String waiting = disk();
        System.out.println("CRASHTEST waiting for Enter: disk " + waiting);
        require(waiting.startsWith("own=18 ") && waiting.contains("dormant=9"),
            "a crash now must leave the house as the world still has it (18 blocks, 9 kept hidden under the ground), but the file says: " + waiting);
        require(minY(waiting) >= baseY + 2 && maxY(waiting) <= baseY + 3, "and where the world still has it, at y " + (baseY + 2) + " to " + (baseY + 3) + ": " + waiting);

        // the ways a waiting edit is dropped without being applied: each must give the house back as the world still has it
        c.runOnClient(mc -> ClientState.options.confirmEdits = true);
        serverCall("cancelPreview"); // Esc in the game
        expectBuried("Esc in the game");

        stageUp();
        action("pending_cancel"); // Esc in the editor
        expectBuried("Esc in the editor");

        stageUp();
        action("editor_reset"); // the way out of a stuck editor
        action("gamemode_restore");
        expectBuried("the editor reset");

        stageUp();
        w.getServer().computeOnServer(server -> { dev.craftium.server.EditorServer.disconnect(server.getPlayerList().getPlayers().getFirst().getUUID()); return true; }); // leaving the world
        expectBuried("leaving the world");

        // Enter: the file follows once the edit is applied
        stageUp();
        action("pending_confirm");
        c.waitTicks(60);
        String applied = disk();
        System.out.println("CRASHTEST applied: disk " + applied);
        require(applied.startsWith("own=27 ") && applied.contains("dormant=0"), "once applied the file is the house as it is now (27 blocks, none hidden), but it says: " + applied);
        require(minY(applied) >= baseY + 7, "up out of the ground: " + applied);

        // Enter in the game, as a second house would be staged next: nothing stale is left that the next edit would start from
        action("saved_selection:view:" + id);
        c.runOnClient(mc -> ClientState.options.confirmEdits = true);
        move(-6);
        serverCall("applyPreview");
        c.waitTicks(40);
        action("saved_selection:view:" + id);
        require(saved().size() == 18 && saved().hidden() == 9, "Enter in the game puts the house back down into the ground, got " + saved().size() + " and " + saved().hidden());
        c.runOnClient(mc -> ClientState.options.confirmEdits = false);
        move(6);
        require(saved().size() == 27 && saved().hidden() == 0, "and the next move starts from the house as it is, not from before the last one, got " + saved().size() + " and " + saved().hidden());
    }

    private int id() { return saved().id(); }

    /** Selects the house and moves it up out of the ground, without confirming. */
    private void stageUp() {
        action("saved_selection:view:" + id());
        c.runOnClient(mc -> ClientState.options.confirmEdits = true);
        move(6);
        require(c.computeOnClient(mc -> ClientState.hasPending()), "the move is waiting for Enter");
    }

    /** The house is as the world has it, both on disk and in the game. */
    private void expectBuried(String how) {
        c.waitTicks(40);
        String d = disk();
        System.out.println("CRASHTEST after " + how + ": disk " + d);
        require(d.startsWith("own=18 ") && d.contains("dormant=9"), how + ": the file must be the house as the world has it (18 blocks, 9 hidden), but it says: " + d);
        action("saved_selection:view:" + id());
        require(saved().size() == 18 && saved().hidden() == 9, how + ": the BuildObject must be the house as the world has it, got " + saved().size() + " blocks and " + saved().hidden() + " hidden");
        require(!c.computeOnClient(mc -> ClientState.hasPending()), how + ": nothing is waiting any more");
    }

    /** Calls a method of the server's editor that the Esc and Enter keys use in the game. */
    private void serverCall(String method) {
        w.getServer().computeOnServer(server -> {
            try {
                var m = dev.craftium.server.EditorServer.class.getDeclaredMethod(method, net.minecraft.server.level.ServerPlayer.class);
                m.setAccessible(true);
                m.invoke(null, server.getPlayerList().getPlayers().getFirst());
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            return true;
        });
        c.waitTicks(10);
    }

    private static int minY(String state) { return Integer.parseInt(state.substring(state.indexOf("y=") + 2, state.indexOf(".."))); }
    private static int maxY(String state) { String rest = state.substring(state.indexOf("..") + 2); return Integer.parseInt(rest.substring(0, rest.indexOf(' '))); }

    /** The BuildObject file of the world as it is on disk right now: the number of blocks, the lowest and highest y, and how many are kept hidden. */
    private String disk() {
        return w.getServer().computeOnServer(server -> {
            try {
                Path dir = server.getWorldPath(LevelResource.ROOT).resolve("craftium/buildobjects");
                if (!Files.isDirectory(dir)) return "no folder";
                try (var files = Files.list(dir)) {
                    var file = files.filter(p -> p.toString().endsWith(".nbt")).findFirst().orElse(null);
                    if (file == null) return "no file";
                    var root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                    var list = root.getListOrEmpty("entries");
                    if (list.isEmpty()) return "no BuildObjects";
                    var tag = list.getCompoundOrEmpty(0);
                    int[] flat = tag.getIntArray("points").orElse(new int[0]);
                    int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                    for (int i = 1; i < flat.length; i += 3) { low = Math.min(low, flat[i]); high = Math.max(high, flat[i]); }
                    long[] hidden = tag.getCompoundOrEmpty("dormant").getLongArray("cells").orElse(new long[0]);
                    return "own=" + flat.length / 3 + " y=" + low + ".." + high + " dormant=" + hidden.length;
                }
            } catch (Exception e) { return "unreadable: " + e; }
        });
    }

    private void cmd(String command) { w.getServer().runCommand(command); }

    private dev.craftium.net.Packets.SavedSelection saved() { return c.computeOnClient(mc -> ClientState.savedSelections().get(0)); }

    private void select(List<Point> points) {
        c.waitTicks(3);
        c.runOnClient(mc -> {
            ClientState.options.selectionPoints.clear(); ClientState.options.selectionPoints.addAll(points);
            ClientState.options.mode = Selection.Mode.REPLACE;
            CraftiumClient.send("lasso_select", null, new Point(0, 1, 0));
        });
        settle();
    }

    private void action(String name) { c.waitTicks(3); c.runOnClient(mc -> CraftiumClient.send(name, null, null)); settle(); }

    /** Moves what is selected up or down: what the gizmo sends when it is let go. */
    private void move(int up) {
        c.waitTicks(3);
        c.runOnClient(mc -> {
            var o = ClientState.options;
            o.offsetX = 0; o.offsetY = up; o.offsetZ = 0; o.yaw = 0; o.pitch = 0; o.roll = 0; o.sizeX = 1; o.sizeY = 1; o.sizeZ = 1;
            CraftiumClient.send("selection_transform", null, null);
        });
        settle();
    }

    private void settle() {
        w.getConnection().waitForServerboundPackets(); c.waitTicks(4);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 400);
        w.getConnection().waitForClientboundPackets();
        c.waitTicks(2);
        require(c.computeOnClient(mc -> !ClientState.status.error()), "a request failed: " + c.computeOnClient(mc -> ClientState.status.message()));
    }
}
