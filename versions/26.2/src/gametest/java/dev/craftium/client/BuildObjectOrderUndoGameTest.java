package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Dragging a BuildObject to another place in the Hierarchy is in the history: Ctrl+Z puts the order back, and when the new order changed which BuildObject holds a cell (the
 * blocks change places) that is part of the same step, so one Ctrl+Z puts back both. Run it with {@code CRAFTIUM_TEST_SCOPE=buildobjectorder}.
 */
public final class BuildObjectOrderUndoGameTest {
    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;
    private int baseX, baseY, baseZ;

    private BuildObjectOrderUndoGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new BuildObjectOrderUndoGameTest(c, w).all(); }

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

        // two cubes of 2 x 2 x 2, far apart: the stone one is made first, so it is first in the list, and the planks one below it outranks it
        cmd("fill " + (baseX + 6) + " " + (baseY + 5) + " " + baseZ + " " + (baseX + 7) + " " + (baseY + 6) + " " + (baseZ + 1) + " stone");
        cmd("fill " + baseX + " " + (baseY + 5) + " " + baseZ + " " + (baseX + 1) + " " + (baseY + 6) + " " + (baseZ + 1) + " oak_planks");
        c.waitTicks(10);
        select(cube(baseX + 6));
        action("saved_selection:save");
        int stone = savedAt(0).id();
        action("saved_selection:release"); // while one is shown, the next one made would go under it
        select(cube(baseX));
        action("saved_selection:save");
        int planks = savedAt(1).id();
        require(order().equals(List.of(stone, planks)), "the one made first is first in the list: " + order());

        // 1. a plain reorder, nothing overlapping: Ctrl+Z puts the order back
        action("saved_selection:place:" + stone + ":0:1");
        require(order().equals(List.of(planks, stone)), "dragging the first one below the second puts it last: " + order());
        undo();
        require(order().equals(List.of(stone, planks)), "Ctrl+Z puts the order back, but it is " + order());
        redo();
        require(order().equals(List.of(planks, stone)), "Ctrl+Y does it again, but it is " + order());
        undo();
        require(order().equals(List.of(stone, planks)), "and back: " + order());

        // 2. the stone cube, which is lower in the list, put down onto the planks one: it is above it, so its blocks are kept hidden under the planks
        action("saved_selection:view:" + stone);
        move(-6);
        require(saved(planks).size() == 8 && saved(stone).size() == 0 && saved(stone).hidden() == 8, "the planks cube holds the cells and the stone one keeps its blocks hidden, got " + saved(planks).size() + ", " + saved(stone).size() + " and " + saved(stone).hidden());
        require(blockIs(baseX, baseY + 5, baseZ, Blocks.OAK_PLANKS), "the world is planks there");

        // dragging the stone one below the planks one: it now outranks it, and the blocks change places
        action("saved_selection:place:" + stone + ":0:1");
        c.waitTicks(20);
        require(order().equals(List.of(planks, stone)), "the stone one is last: " + order());
        require(blockIs(baseX, baseY + 5, baseZ, Blocks.STONE), "and it takes the cells: the world is stone there");
        require(saved(stone).size() == 8 && saved(planks).size() == 0 && saved(planks).hidden() == 8, "and the planks cube keeps its blocks hidden, got " + saved(stone).size() + ", " + saved(planks).size() + " and " + saved(planks).hidden());

        undo(); // one step: the order and the blocks that changed places
        require(order().equals(List.of(stone, planks)), "one Ctrl+Z puts the order back, but it is " + order());
        require(blockIs(baseX, baseY + 5, baseZ, Blocks.OAK_PLANKS), "and the blocks: planks");
        require(saved(planks).size() == 8 && saved(stone).size() == 0 && saved(stone).hidden() == 8, "and who holds what, got " + saved(planks).size() + ", " + saved(stone).size() + " and " + saved(stone).hidden());
        redo();
        require(order().equals(List.of(planks, stone)) && blockIs(baseX, baseY + 5, baseZ, Blocks.STONE), "Ctrl+Y does both again: " + order());
        require(saved(stone).size() == 8 && saved(planks).hidden() == 8, "and who holds what, got " + saved(stone).size() + " and " + saved(planks).hidden());
        undo();
        require(order().equals(List.of(stone, planks)) && blockIs(baseX, baseY + 5, baseZ, Blocks.OAK_PLANKS), "and Ctrl+Z puts both back again: " + order());
    }

    private List<Integer> order() { return c.computeOnClient(mc -> ClientState.savedSelections().stream().map(dev.craftium.net.Packets.SavedSelection::id).toList()); }
    private dev.craftium.net.Packets.SavedSelection savedAt(int index) { return c.computeOnClient(mc -> ClientState.savedSelections().get(index)); }
    private dev.craftium.net.Packets.SavedSelection saved(int id) {
        return c.computeOnClient(mc -> { for (var e : ClientState.savedSelections()) if (e.id() == id) return e; throw new AssertionError("no BuildObject " + id); });
    }
    private boolean blockIs(int x, int y, int z, net.minecraft.world.level.block.Block block) {
        return w.getServer().computeOnServer(server -> server.overworld().getBlockState(new BlockPos(x, y, z)).is(block));
    }
    private List<Point> cube(int x) {
        var cells = new ArrayList<Point>();
        for (int i = 0; i < 2; i++) for (int j = 5; j < 7; j++) for (int k = 0; k < 2; k++) cells.add(new Point(x + i, baseY + j, baseZ + k));
        return cells;
    }
    private void cmd(String command) { w.getServer().runCommand(command); }
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
    private void undo() { action("undo"); c.waitTicks(10); }
    private void redo() { action("redo"); c.waitTicks(10); }
    private void move(int x) {
        c.waitTicks(3);
        c.runOnClient(mc -> {
            var o = ClientState.options;
            o.offsetX = x; o.offsetY = 0; o.offsetZ = 0; o.yaw = 0; o.pitch = 0; o.roll = 0; o.sizeX = 1; o.sizeY = 1; o.sizeZ = 1;
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
