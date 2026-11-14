package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Duplicating a BuildObject copies all of it, the blocks it keeps hidden under the ground as well as the ones that can be seen: a house with its lower floors buried gives a whole
 * house wherever the copy is put down, and so does one that is buried completely. Run it with {@code CRAFTIUM_TEST_SCOPE=buildobjectduplicate}.
 */
public final class BuildObjectDuplicateGameTest {
    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;
    private int baseX, baseY, baseZ;

    private BuildObjectDuplicateGameTest(ClientGameTestContext c, TestSingleplayerContext w) {
        this.c = c;
        this.w = w;
    }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) {
        new BuildObjectDuplicateGameTest(c, w).all();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private void all() {
        c.runOnClient(mc -> {
            mc.getWindow().setWindowed(1200, 800);
            mc.options.guiScale().set(1);
            mc.resizeGui();
            mc.player.getAbilities().flying = true;
        });
        c.waitFor(mc -> mc.player.onGround() || mc.player.getAbilities().flying, 600);
        c.runOnClient(mc -> {
            ClientState.options = new Options();
            ClientState.options.confirmEdits = false;
            CraftiumClient.resetEditor();
            WorkspaceLayout.reset();
            mc.player.setYRot(0f);
            mc.player.setXRot(20f);
            mc.player.setOldPosAndRot();
            CraftiumClient.open(false);
        });
        c.waitTicks(10);
        var base = c.computeOnClient(mc -> mc.player.blockPosition().offset(-4, 0, 7));
        baseX = base.getX();
        baseY = base.getY();
        baseZ = base.getZ();

        // ground three blocks thick, and a house of 3 x 3 x 3 planks above it
        cmd("fill " + (baseX - 1) + " " + baseY + " " + (baseZ - 1) + " " + (baseX + 5) + " " + (baseY + 2) + " "
                + (baseZ + 5) + " stone");
        cmd("fill " + baseX + " " + (baseY + 6) + " " + baseZ + " " + (baseX + 2) + " " + (baseY + 8) + " "
                + (baseZ + 2) + " oak_planks");
        c.waitTicks(10);
        var house = new ArrayList<Point>();
        for (int x = 0; x < 3; x++)
            for (int y = 6; y < 9; y++)
                for (int z = 0; z < 3; z++) house.add(new Point(baseX + x, baseY + y, baseZ + z));
        select(house);
        action("saved_selection:save");
        int id = row(0).id();

        // down into the ground so that its two lower floors are inside the stone: 9 blocks are seen, 18 are hidden
        move(-5);
        require(
                row(0).size() == 9 && row(0).hidden() == 18,
                "the house is half buried, got " + row(0).size() + " blocks and " + row(0).hidden() + " hidden");

        // duplicate it and put the copy down on open ground to the side: it is a whole house
        action("saved_selection:view:" + id);
        action("saved_selection:duplicate");
        require(
                c.computeOnClient(mc -> ClientState.status.clipboardSize()) == 27,
                "the copy holds all 27 blocks, got " + c.computeOnClient(mc -> ClientState.status.clipboardSize()));
        pasteAsObject(id, 20);
        var copy = other(id, 1);
        require(
                copy.size() == 27 && copy.hidden() == 0,
                "the copy is a whole house, got " + copy.size() + " blocks and " + copy.hidden() + " hidden");
        require(planksAt(20), "and 27 planks stand where it was put down");

        // buried completely: nothing of it is in the world, and it can still be duplicated
        action("saved_selection:view:" + id);
        move(-1);
        require(
                row(0).size() == 0 && row(0).hidden() == 27,
                "the house is buried completely, got " + row(0).size() + " blocks and " + row(0).hidden() + " hidden");
        action("saved_selection:view:" + id);
        require(c.computeOnClient(mc -> ClientState.status.selectionSize()) == 0, "nothing is selected");
        action("saved_selection:duplicate");
        require(
                c.computeOnClient(mc -> ClientState.status.clipboardSize()) == 27,
                "a house that is buried completely is copied whole too, got "
                        + c.computeOnClient(mc -> ClientState.status.clipboardSize()));
        pasteAsObject(id, 30);
        var second = other(id, 2);
        require(
                second.size() == 27 && second.hidden() == 0,
                "and the copy is a whole house, got " + second.size() + " blocks and " + second.hidden() + " hidden");
        require(planksAtLevel(30, baseY), "with 27 planks where it was put down");
    }

    /** The BuildObject that is not the house nor any of those already counted: the newest of the copies. */
    private dev.craftium.net.Packets.SavedSelection other(int house, int copiesSoFar) {
        return c.computeOnClient(mc -> {
            var all = ClientState.savedSelections();
            require(all.size() == copiesSoFar + 1, "the copy is a BuildObject of its own, there are " + all.size());
            return all.stream()
                    .filter(e -> e.id() != house)
                    .max(java.util.Comparator.comparingInt(dev.craftium.net.Packets.SavedSelection::id))
                    .orElseThrow();
        });
    }

    /** Puts the copy down {@code dx} blocks to the east of where it was copied from, as confirming the placement does, and makes a BuildObject of it. */
    private void pasteAsObject(int house, int dx) {
        c.waitTicks(3);
        c.runOnClient(mc -> {
            var o = ClientState.options;
            var from = ClientState.status.clipboardOrigin();
            o.pasteAsObject = "House (copy)";
            o.pasteAsObjectAfter = house;
            try {
                CraftiumClient.send("paste_select", from.add(dx, 0, 0), new Point(0, 1, 0));
            } finally {
                o.pasteAsObject = null;
                o.pasteAsObjectAfter = 0;
            }
        });
        settle();
    }

    private boolean planksAt(int dx) {
        return planksAtLevel(dx, baseY + 1);
    }
    /** 27 planks in a cube of 3 x 3 x 3 whose lowest layer is at {@code lowest}, {@code dx} blocks east of the house's column. */
    private boolean planksAtLevel(int dx, int lowest) {
        return w.getServer().computeOnServer(server -> {
            for (int x = 0; x < 3; x++)
                for (int y = 0; y < 3; y++)
                    for (int z = 0; z < 3; z++)
                        if (!server.overworld()
                                .getBlockState(new BlockPos(baseX + dx + x, lowest + y, baseZ + z))
                                .is(Blocks.OAK_PLANKS)) return false;
            return true;
        });
    }

    private dev.craftium.net.Packets.SavedSelection row(int index) {
        return c.computeOnClient(mc -> ClientState.savedSelections().get(index));
    }

    private void cmd(String command) {
        w.getServer().runCommand(command);
    }

    private void select(List<Point> points) {
        c.waitTicks(3);
        c.runOnClient(mc -> {
            ClientState.options.selectionPoints.clear();
            ClientState.options.selectionPoints.addAll(points);
            ClientState.options.mode = Selection.Mode.REPLACE;
            CraftiumClient.send("lasso_select", null, new Point(0, 1, 0));
        });
        settle();
    }

    private void action(String name) {
        c.waitTicks(3);
        c.runOnClient(mc -> CraftiumClient.send(name, null, null));
        settle();
    }

    private void move(int up) {
        c.waitTicks(3);
        c.runOnClient(mc -> {
            var o = ClientState.options;
            o.offsetX = 0;
            o.offsetY = up;
            o.offsetZ = 0;
            o.yaw = 0;
            o.pitch = 0;
            o.roll = 0;
            o.sizeX = 1;
            o.sizeY = 1;
            o.sizeZ = 1;
            CraftiumClient.send("selection_transform", null, null);
        });
        settle();
    }

    private void settle() {
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(4);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 400);
        w.getConnection().waitForClientboundPackets();
        c.waitTicks(2);
        require(
                c.computeOnClient(mc -> !ClientState.status.error()),
                "a request failed: " + c.computeOnClient(mc -> ClientState.status.message()));
    }
}
