package dev.craftium.client;

import dev.craftium.core.BrushMath;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A waiting Shape keeps its own shape through the transform tools, is built turned, and the next shape starts with that turn. */
public final class ShapeRotateGameTest {
    private ShapeRotateGameTest() {}

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) {
        c.runOnClient(mc -> {
            ClientState.options.confirmEdits = false;
            ClientState.options.tool = Tool.SHAPE;
            ClientState.options.shape = BrushMath.Shape.CUBE;
            ClientState.options.radius = 3;
            ClientState.options.hollow = false;
            ClientState.options.block = "minecraft:gold_block";
            CraftiumClient.open(false);
        });
        c.waitTicks(10);
        Point at = new Point(55, 30, 50);
        c.runOnClient(mc -> {
            ShapeObject.place(at);
            ShapeObject.turnAbout(new Vec3(0, 1, 0), 45);
        });
        for (Tool tool : new Tool[] {Tool.ROTATE, Tool.MOVE, Tool.SCALE}) {
            c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).select(tool));
            c.waitTicks(3);
            require(
                    c.computeOnClient(
                            mc -> ClientState.options.shape == BrushMath.Shape.CUBE && ClientState.options.radius == 3),
                    tool + " replaced the waiting shape's options");
        }
        String turned = c.computeOnClient(
                mc -> java.util.Arrays.deepToString(ShapeObject.pose().rotation()));
        c.runOnClient(mc -> ShapeObject.confirm());
        w.getConnection().waitForServerboundPackets();
        w.getConnection().waitForClientboundPackets();
        c.waitTicks(10);
        // A cube turned 45 degrees about Y reaches out to 4 along X, and no longer fills the corner at (3, 3).
        require(gold(w, at.x() + 4, at.y(), at.z()), "The built shape was not turned (no block at the rotated tip)");
        require(!gold(w, at.x() + 3, at.y(), at.z() + 3), "The built shape was not turned (corner still filled)");
        // The next shape starts with the turn the last one was built with.
        c.runOnClient(mc -> {
            ShapeObject.place(new Point(55, 30, 60));
            ((EditorScreen) mc.gui.screen()).select(Tool.ROTATE);
        });
        c.waitTicks(3);
        require(
                c.computeOnClient(mc ->
                                java.util.Arrays.deepToString(ShapeObject.pose().rotation()))
                        .equals(turned),
                "The next shape did not start with the last turn");
        c.runOnClient(mc -> ShapeObject.cancel());
        System.out.println("SHAPE_ROTATE_TEST done");
    }

    private static boolean gold(TestSingleplayerContext w, int x, int y, int z) {
        return w.getServer()
                .computeOnServer(server ->
                        server.overworld().getBlockState(new BlockPos(x, y, z)).is(Blocks.GOLD_BLOCK));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
