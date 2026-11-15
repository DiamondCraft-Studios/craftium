package dev.craftium.client;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;

import dev.craftium.core.Point;
import dev.craftium.test.GoldenToolGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Changes of settings in the Tool options window are in the history with the server's edits, in one order: a block edit (A), a radius change (B), another block edit (C)
 * and a change of the hollow setting (D) are undone by Ctrl+Z as D, C, B, A, and Ctrl+Y brings them back as A, B, C, D, with a note on screen for each setting.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=optionhistory}.
 */
public final class OptionHistoryGameTest {
    private static ClientGameTestContext c;
    private static TestSingleplayerContext w;

    public static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        c = context;
        w = world;
        var ground = GoldenToolGameTest.surface(0, 0);
        var a = new Point(ground.x(), ground.y() + 1, ground.z());
        var cPoint = new Point(ground.x() + 2, ground.y() + 1, ground.z());
        w.getServer().runCommand("gamerule minecraft:random_tick_speed 0");
        GoldenToolGameTest.reset(w, null);
        w.getConnection().waitForClientboundPackets();
        c.runOnClient(mc -> {
            CraftiumClient.open(false);
            ClientState.options.confirmEdits = false;
            ClientState.options.block = "minecraft:andesite";
            ClientState.options.hollow = false;
            ClientState.options.radius = 5;
        });
        step("open");
        c.waitTicks(10);
        int radius = 5;

        pause();
        c.runOnClient(mc -> {
            ClientState.options.position1 = a;
            ClientState.options.position2 = cPoint;
            CraftiumClient.send("select_box", null, null);
        });
        settle();
        step("selected");
        fill("minecraft:andesite"); // A
        step("A");
        change(() -> ClientState.options.radius = 9); // B
        step("B");
        fill("minecraft:stone"); // C
        step("C");
        change(() -> ClientState.options.hollow = true); // D
        require(
                c.computeOnClient(mc -> ClientOptionHistory.history.undoCount() == 2),
                "the two changes of settings should be in the history");
        require(isStone(a) && isStone(cPoint), "the second fill should have replaced the first");

        ctrl(GLFW_KEY_Z); // undoes D
        require(
                !hollow() && radius() == 9 && isStone(a) && isStone(cPoint),
                "the first Ctrl+Z should undo the hollow setting alone");
        require(
                note().startsWith("Undid: Hollow off") || note().startsWith("Undid: Hollow"),
                "a note should say which setting was undone: " + note());
        ctrl(GLFW_KEY_Z); // undoes C
        require(
                isAndesite(cPoint) && isAndesite(a) && radius() == 9,
                "the second Ctrl+Z should undo the second block edit");
        ctrl(GLFW_KEY_Z); // undoes B
        require(
                radius() == radius && isAndesite(a) && isAndesite(cPoint),
                "the third Ctrl+Z should put the radius back: " + radius() + " a=" + isAndesite(a) + " undoCount="
                        + c.computeOnClient(mc -> ClientOptionHistory.history.undoCount()) + " redoCount="
                        + c.computeOnClient(mc -> ClientOptionHistory.history.redoCount()) + " serverUndo="
                        + c.computeOnClient(mc -> ClientState.status.undo() + " age " + ClientState.status.undoAge())
                        + " note=" + note());
        require(note().startsWith("Undid: Radius 5"), "the note should name the radius: " + note());
        ctrl(GLFW_KEY_Z); // undoes A
        require(
                !isAndesite(a) && !isAndesite(cPoint) && radius() == radius,
                "the fourth Ctrl+Z should undo the first block edit");

        ctrl(GLFW_KEY_Y); // redoes A
        require(
                isAndesite(a) && isAndesite(cPoint) && radius() == radius,
                "the first Ctrl+Y should redo the first block edit");
        ctrl(GLFW_KEY_Y); // redoes B
        require(radius() == 9 && isAndesite(a) && isAndesite(cPoint), "the second Ctrl+Y should redo the radius");
        require(note().startsWith("Redid: Radius"), "the note should say the radius was redone: " + note());
        ctrl(GLFW_KEY_Y); // redoes C
        require(isStone(cPoint) && !hollow(), "the third Ctrl+Y should redo the second block edit");
        ctrl(GLFW_KEY_Y); // redoes D
        require(hollow() && radius() == 9, "the fourth Ctrl+Y should redo the hollow setting");

        // A new change ends what was undone: undo the hollow setting, change the radius, and the hollow setting cannot
        // come back.
        ctrl(GLFW_KEY_Z);
        change(() -> ClientState.options.radius = 12);
        ctrl(GLFW_KEY_Y);
        require(!hollow() && radius() == 12, "a new change should end what was undone");

        System.out.println("CRAFTIUM_OPTION_HISTORY_PASS strict_order notes redo_ends");
    }

    /** A block edit on the server: a fill of the selection with this block. */
    private static void fill(String block) {
        pause();
        c.runOnClient(mc -> {
            ClientState.options.block = block;
            CraftiumClient.send("fill", null, null);
        });
        settle();
    }

    /** A gesture in the Tool options window: a press in it, the change, the pointer up, and a moment of stillness. */
    private static void change(Runnable set) {
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            var window = WorkspaceLayout.windows.get("options");
            double x = window.x + window.w / 2.0;
            double y = window.y + 30;
            screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
            set.run();
            screen.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
        });
        c.waitTicks(1);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        c.waitTicks(3);
    }

    private static void ctrl(int key) {
        pause();
        c.runOnClient(mc -> {
            var screen = mc.gui.screen();
            var event = new KeyEvent(key, 0, 2);
            screen.keyPressed(event);
            screen.keyReleased(event);
        });
        settle();
    }

    private static boolean hollow() {
        return c.computeOnClient(mc -> ClientState.options.hollow);
    }

    private static int radius() {
        return c.computeOnClient(mc -> ClientState.options.radius);
    }

    private static String note() {
        return c.computeOnClient(mc -> ClientState.notice);
    }

    private static boolean isStone(Point at) {
        return w.getServer().computeOnServer(server -> server.overworld()
                .getBlockState(new BlockPos(at.x(), at.y(), at.z()))
                .is(Blocks.STONE));
    }

    private static boolean isAndesite(Point at) {
        return w.getServer().computeOnServer(server -> server.overworld()
                .getBlockState(new BlockPos(at.x(), at.y(), at.z()))
                .is(Blocks.ANDESITE));
    }

    private static void settle() {
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(5);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 400);
        w.getConnection().waitForClientboundPackets();
    }

    private static void step(String s) {
        System.out.println("CRAFTIUM_OH_STEP " + s);
    }

    private static void pause() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
